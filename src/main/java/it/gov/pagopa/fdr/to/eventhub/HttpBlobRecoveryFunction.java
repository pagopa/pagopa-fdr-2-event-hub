package it.gov.pagopa.fdr.to.eventhub;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import it.gov.pagopa.fdr.to.eventhub.model.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import lombok.Getter;

/** Azure Functions with Azure Http trigger. */
public class HttpBlobRecoveryFunction {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String APPLICATION_JSON = "application/json";
  private static final String JSON_FILENAME = "fileName";
  private static final String JSON_CONTAINER = "container";

  @Getter private final EventHubProducerClient eventHubClientFlowTx;
  @Getter private final EventHubProducerClient eventHubClientReportedIUV;

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
      EventHubProducerClient eventHubClientReportedIUV) {
    this.eventHubClientFlowTx = eventHubClientFlowTx;
    this.eventHubClientReportedIUV = eventHubClientReportedIUV;
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
    Optional<String> requestBody = request.getBody();
    if (!requestBody.isPresent()) {
      return badRequest(request, "Missing request body");
    }

    try {
      JsonNode jsonNode = objectMapper.readTree(requestBody.get());
      String fileName =
          Optional.ofNullable(jsonNode.get(JSON_FILENAME)).map(JsonNode::asText).orElse(null);
      String container =
          Optional.ofNullable(jsonNode.get(JSON_CONTAINER)).map(JsonNode::asText).orElse(null);

      if (fileName == null || container == null) {
        return badRequest(request, "Missing required fields: fileName, container");
      }

      context
          .getLogger()
          .fine(
              () ->
                  String.format(
                      "[HTTP FDR] Triggered at: %s for Blob container: %s, name: %s",
                      LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                      container,
                      fileName));

      BlobFileData fileData =
          CommonUtil.getBlobFile("FDR_SA_CONNECTION_STRING", container, fileName, context);

      if (Objects.isNull(fileData)) {
        return notFound(
            request, String.format("File %s not found in container %s", fileName, container));
      }

      if (!CommonUtil.validateBlobMetadata(fileData.getMetadata())) {
        return unprocessableEntity(
            request,
            String.format(
                "The file %s in container %s is missing required metadata", fileName, container));
      }

      boolean isValidGzipFile = CommonUtil.isGzip(fileData.getFileContent());

      try (InputStream decompressedStream =
          isValidGzipFile
              ? CommonUtil.decompressGzip(fileData.getFileContent())
              : new ByteArrayInputStream(fileData.getFileContent())) {

        FlussoRendicontazione flusso = CommonUtil.parseXml(decompressedStream);
        flusso.setMetadata(fileData.getMetadata());

        boolean eventBatchSent =
            CommonUtil.processXmlBlobAndSendToEventHub(
                eventHubClientFlowTx, eventHubClientReportedIUV, flusso, context);

        if (!eventBatchSent) {
          return serviceUnavailable(
              request,
              String.format(
                  "EventHub failed to confirm batch processing for flow ID %s [file %s, container"
                      + " %s]",
                  flusso.getIdentificativoFlusso(), fileName, container));
        }
      }

      return ok(
          request,
          String.format(
              "Processed recovery request for file: %s in container: %s", fileName, container));

    } catch (IOException e) {
      return badRequest(request, "Invalid JSON format");
    } catch (Exception e) {
      context.getLogger().severe("[HTTP FDR] Unexpected error: " + e.getMessage());
      return serverError(request, "Internal Server Error");
    }
  }

  private HttpResponseMessage ok(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.OK, message);
  }

  private HttpResponseMessage badRequest(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.BAD_REQUEST, message);
  }

  private HttpResponseMessage notFound(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.NOT_FOUND, message);
  }

  private HttpResponseMessage unprocessableEntity(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private HttpResponseMessage serviceUnavailable(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.SERVICE_UNAVAILABLE, message);
  }

  private HttpResponseMessage serverError(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.INTERNAL_SERVER_ERROR, message);
  }

  private HttpResponseMessage response(
      HttpRequestMessage<?> request, HttpStatus status, String message) {
    return request
        .createResponseBuilder(status)
        .header(CONTENT_TYPE, APPLICATION_JSON)
        .body("{\"message\": \"" + message + "\"}")
        .build();
  }
}
