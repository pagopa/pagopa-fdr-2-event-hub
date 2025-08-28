package it.gov.pagopa.fdr.to.eventhub;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlStAXParser;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import lombok.Getter;
import org.slf4j.LoggerFactory;

/** Azure Functions with Azure Http trigger. */
public class HttpBlobRecoveryFunction {

  private final org.slf4j.Logger logger = LoggerFactory.getLogger(HttpBlobRecoveryFunction.class);

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final String JSON_FILENAME = "fileName";
  private static final String JSON_CONTAINER = "container";
  private final String fdr1Container =
      System.getenv().getOrDefault("BLOB_STORAGE_FDR1_CONTAINER", "fdr1-flows");
  @Getter private final EventHubProducerClient eventHubClientFlowTx;
  @Getter private final EventHubProducerClient eventHubClientReportedIUV;
  private FDR1XmlStAXParser fdr1XmlParser = new FDR1XmlStAXParser();

  public HttpBlobRecoveryFunction() {
    this.eventHubClientFlowTx =
        CommonUtil.createEventHubClient(
            System.getenv("EVENT_HUB_FLOWTX_CONNECTION_STRING"),
            System.getenv("EVENT_HUB_FLOWTX_NAME"));

    this.eventHubClientReportedIUV =
        CommonUtil.createEventHubClient(
            System.getenv("EVENT_HUB_REPORTEDIUV_CONNECTION_STRING"),
            System.getenv("EVENT_HUB_REPORTEDIUV_NAME"));
  }

  public HttpBlobRecoveryFunction(
      EventHubProducerClient eventHubClientFlowTx,
      EventHubProducerClient eventHubClientReportedIUV,
      FDR1XmlStAXParser fdr1XmlParser) {
    this.eventHubClientFlowTx = eventHubClientFlowTx;
    this.eventHubClientReportedIUV = eventHubClientReportedIUV;
    this.fdr1XmlParser = fdr1XmlParser;
  }

  @FunctionName("HTTPBlobRecovery")
  public HttpResponseMessage run(
      @HttpTrigger(
              name = "HTTPBlobRecoveryTrigger",
              methods = {HttpMethod.POST},
              route = "notify/fdr",
              authLevel = AuthorizationLevel.ANONYMOUS)
          HttpRequestMessage<Optional<String>> request,
      final ExecutionContext context) {

    // Check if body is present
    if (request.getBody().isEmpty()) {
      return CommonUtil.badRequest(request, "Missing request body");
    }

    // Get named parameter
    boolean sendFlowEvent = CommonUtil.getBooleanQueryParam(request, "sendFlowEvent", true);
    boolean sendPaymentEvents = CommonUtil.getBooleanQueryParam(request, "sendPaymentEvent", true);

    try {
      JsonNode jsonNode = objectMapper.readTree(request.getBody().get());

      String fileName = CommonUtil.getJsonField(jsonNode, JSON_FILENAME);
      String container = CommonUtil.getJsonField(jsonNode, JSON_CONTAINER);

      if (fileName == null || container == null) {
        return CommonUtil.badRequest(request, "Missing required fields: fileName, container");
      }

      logger.info(
          "[HTTP FDR] Triggered at: {} for Blob container: {}, name: {}",
          CommonUtil.getFormattedDateTimeNowIfLogLevelEnabled(logger),
          container,
          fileName);

      BlobFileData fileData =
          CommonUtil.getBlobFile("FDR_SA_CONNECTION_STRING", container, fileName, logger);

      if (Objects.isNull(fileData)) {
        return CommonUtil.notFound(
            request, String.format("File %s not found in container %s", fileName, container));
      }

      if (!CommonUtil.validateBlobMetadata(fileData.getMetadata())) {
        return CommonUtil.unprocessableEntity(
            request,
            String.format(
                "The file %s in container %s is missing required metadata", fileName, container));
      }

      boolean isValidGzipFile = CommonUtil.isGzip(fileData.getFileContent());

      try (InputStream decompressedStream =
          isValidGzipFile
              ? CommonUtil.decompressGzip(fileData.getFileContent())
              : new ByteArrayInputStream(fileData.getFileContent())) {

        boolean eventBatchSent;
        String flowName;
        if (fdr1Container.equals(container)) {

          context
              .getLogger()
              .info(() -> "Retrieving and sending data on EventHub from FdR1 container.");
          FlussoRendicontazione flusso = fdr1XmlParser.parseXmlStream(decompressedStream);
          flusso.setMetadata(fileData.getMetadata());
          flowName = flusso.getIdentificativoFlusso();
          eventBatchSent =
              CommonUtil.processXmlBlobAndSendToEventHub(
                  eventHubClientFlowTx,
                  eventHubClientReportedIUV,
                  flusso,
                  logger,
                  sendFlowEvent,
                  sendPaymentEvents);

        } else {

          context
              .getLogger()
              .info(() -> "Retrieving and sending data on EventHub from FdR3 container.");
          Flow flusso = CommonUtil.parseJSON(decompressedStream);
          flusso.setMetadata(fileData.getMetadata());
          flowName = flusso.getFdr();
          eventBatchSent =
              CommonUtil.processJsonBlobAndSendToEventHub(
                  eventHubClientFlowTx,
                  eventHubClientReportedIUV,
                  flusso,
                  logger,
                  sendFlowEvent,
                  sendPaymentEvents);
        }

        if (!eventBatchSent) {
          return CommonUtil.serviceUnavailable(
              request,
              String.format(
                  "EventHub failed to confirm batch processing for flow ID %s [file %s, container"
                      + " %s]",
                  flowName, fileName, container));
        }
      }

      return CommonUtil.ok(
          request,
          String.format(
              "Processed recovery request for file: %s in container: %s", fileName, container));

    } catch (IOException e) {
      return CommonUtil.badRequest(request, "Invalid JSON format");
    } catch (Exception e) {
      logger.error("[HTTP FDR] Unexpected error", e);
      return CommonUtil.serverError(request, "Internal Server Error");
    }
  }
}
