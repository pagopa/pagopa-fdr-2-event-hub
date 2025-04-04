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
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.xml.parsers.ParserConfigurationException;
import lombok.Getter;
import org.xml.sax.SAXException;

/** Azure Functions with Azure Http trigger. */
public class HttpMassiveBlobRecoveryFunction {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final String JSON_FILENAME = "fileName";
  private static final String JSON_CONTAINER = "container";
  private static final String JSON_DATEFROM = "dateFrom";
  private static final String JSON_DATETO = "dateTo";
  private final String fdr1Container =
      System.getenv().getOrDefault("BLOB_STORAGE_FDR1_CONTAINER", "fdr1-flows");
  private final String blobFilterPrefix =
      System.getenv().getOrDefault("BLOB_FILTER_PREFIX", "yyyy-MM-dd");
  @Getter private final EventHubProducerClient eventHubClientFlowTx;
  @Getter private final EventHubProducerClient eventHubClientReportedIUV;

  public HttpMassiveBlobRecoveryFunction() {
    this.eventHubClientFlowTx =
        CommonUtil.createEventHubClient(
            System.getenv("EVENT_HUB_FLOWTX_CONNECTION_STRING"),
            System.getenv("EVENT_HUB_FLOWTX_NAME"));

    this.eventHubClientReportedIUV =
        CommonUtil.createEventHubClient(
            System.getenv("EVENT_HUB_REPORTEDIUV_CONNECTION_STRING"),
            System.getenv("EVENT_HUB_REPORTEDIUV_NAME"));
  }

  public HttpMassiveBlobRecoveryFunction(
      EventHubProducerClient eventHubClientFlowTx,
      EventHubProducerClient eventHubClientReportedIUV) {
    this.eventHubClientFlowTx = eventHubClientFlowTx;
    this.eventHubClientReportedIUV = eventHubClientReportedIUV;
  }

  @FunctionName("HTTPMassiveBlobRecovery")
  public HttpResponseMessage run(
      @HttpTrigger(
              name = "HTTPMassiveBlobRecovery",
              methods = {HttpMethod.POST},
              route = "notify/fdr/massive",
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
      String fromStr = CommonUtil.getJsonField(jsonNode, JSON_DATEFROM);
      String toStr = CommonUtil.getJsonField(jsonNode, JSON_DATETO);

      HttpResponseMessage checkBodyRes =
          this.checkBodyContentAccuracy(request, fileName, container, fromStr, toStr);
      if (checkBodyRes != null) return checkBodyRes;

      context
          .getLogger()
          .fine(
              () ->
                  String.format(
                      "[HTTP FDR] Triggered at: %s for Blob container: %s, name: %s",
                      LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                      container,
                      fileName));

      List<BlobFileData> filesToProcess = new ArrayList<>();

      if (fileName != null) {
        BlobFileData fileData =
            CommonUtil.getBlobFile("FDR_SA_CONNECTION_STRING", container, fileName, context);
        if (fileData == null) {
          return CommonUtil.notFound(
              request, String.format("File %s not found in container %s", fileName, container));
        }

        if (!CommonUtil.validateBlobMetadata(fileData.getMetadata())) {
          return CommonUtil.unprocessableEntity(
              request,
              String.format(
                  "The file %s in container %s is either missing required metadata or is in an"
                      + " unprocessable state",
                  fileName, container));
        }
        filesToProcess.add(fileData);
      } else {
        LocalDate fromDateTime = parseDate(fromStr);
        LocalDate toDateTime = parseDate(toStr);
        filesToProcess =
            CommonUtil.getBlobFilesInDateRange(
                "FDR_SA_CONNECTION_STRING",
                container,
                blobFilterPrefix,
                fromDateTime,
                toDateTime,
                context);
      }

      // initialize the list with any errors that occurred during blob file recovery
      List<String> errors =
          filesToProcess.stream()
              .flatMap(file -> file.getUnprocessableFileDetail().stream())
              .collect(Collectors.toList());
      for (BlobFileData fileData : filesToProcess) {
        if (fileData.getUnprocessableFileDetail().isEmpty()) {
          errors.add(
              processBlobFile(fileData, container, sendFlowEvent, sendPaymentEvents, context));
        }
      }

      // Filter the list by removing empty elements
      List<String> filteredErrors =
          errors.stream().filter(err -> err != null && !err.trim().isEmpty()).toList();

      return processedResultResponse(request, container, filesToProcess, filteredErrors);

    } catch (IOException e) {
      return CommonUtil.badRequest(request, "Invalid JSON format");
    } catch (IllegalArgumentException e) {
      return CommonUtil.badRequest(request, e.getMessage());
    } catch (Exception e) {
      context.getLogger().severe("[HTTP FDR] Unexpected error: " + e.getMessage());
      return CommonUtil.serverError(request, "Internal Server Error");
    }
  }

  private HttpResponseMessage processedResultResponse(
      HttpRequestMessage<Optional<String>> request,
      String container,
      List<BlobFileData> filesToProcess,
      List<String> filteredErrors) {
    return filteredErrors.isEmpty()
        ? CommonUtil.ok(
            request,
            String.format(
                "Successfully processed %d file(s) in container %s",
                filesToProcess.size(), container))
        : CommonUtil.multiStatus(
            request,
            "Processed "
                + filesToProcess.size()
                + " file(s) in container "
                + container
                + " with "
                + filteredErrors.size()
                + " error(s)\", "
                + "\"errors\": ["
                + filteredErrors.stream()
                    .map(err -> "\"" + err + "\"")
                    .collect(Collectors.joining(", "))
                + "]");
  }

  private HttpResponseMessage checkBodyContentAccuracy(
      HttpRequestMessage<Optional<String>> request,
      String fileName,
      String container,
      String fromStr,
      String toStr) {
    if (container == null) {
      return CommonUtil.badRequest(request, "The 'container' field is mandatory.");
    }
    if (fileName == null && (fromStr == null || toStr == null)) {
      return CommonUtil.badRequest(
          request, "Either 'fileName' or both 'dateFrom' and 'dateTo' must be provided.");
    }
    if (fileName != null && (fromStr != null || toStr != null)) {
      return CommonUtil.badRequest(
          request, "'fileName' and 'dateFrom/dateTo' are mutually exclusive.");
    }
    return null;
  }

  private String processBlobFile(
      BlobFileData fileData,
      String container,
      boolean sendFlowEvent,
      boolean sendPaymentEvents,
      ExecutionContext context)
      throws IOException, ParserConfigurationException, SAXException {

    String error = "";
    boolean isValidGzipFile = CommonUtil.isGzip(fileData.getFileContent());
    try (InputStream decompressedStream =
        isValidGzipFile
            ? CommonUtil.decompressGzip(fileData.getFileContent())
            : new ByteArrayInputStream(fileData.getFileContent())) {

      boolean eventBatchSent;
      String flowName;

      if (fdr1Container.equals(container)) {
        context.getLogger().info("Processing data from FdR1 container.");
        FlussoRendicontazione flusso = CommonUtil.parseXml(decompressedStream);
        flusso.setMetadata(fileData.getMetadata());
        flowName = flusso.getIdentificativoFlusso();
        eventBatchSent =
            CommonUtil.processXmlBlobAndSendToEventHub(
                eventHubClientFlowTx,
                eventHubClientReportedIUV,
                flusso,
                context,
                sendFlowEvent,
                sendPaymentEvents);
      } else {
        context.getLogger().info("Processing data from FdR3 container.");
        Flow flusso = CommonUtil.parseJSON(decompressedStream);
        flusso.setMetadata(fileData.getMetadata());
        flowName = flusso.getFdr();
        eventBatchSent =
            CommonUtil.processJsonBlobAndSendToEventHub(
                eventHubClientFlowTx,
                eventHubClientReportedIUV,
                flusso,
                context,
                sendFlowEvent,
                sendPaymentEvents);
      }

      if (!eventBatchSent) {
        error =
            String.format(
                "EventHub failed to confirm batch processing for flow ID %s [file %s, container"
                    + " %s]",
                flowName, fileData.getFileName(), container);
      }
    }

    return error;
  }

  private LocalDate parseDate(String dateStr) {
    try {
      return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
    } catch (NullPointerException | DateTimeParseException e) {
      throw new IllegalArgumentException(
          "Invalid date format for value: " + dateStr + ". Expected yyyy-MM-dd");
    }
  }
}
