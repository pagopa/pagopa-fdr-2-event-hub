package it.gov.pagopa.fdr.to.eventhub;

import static it.gov.pagopa.fdr.to.eventhub.exception.AlertAppException.getExceptionDetails;
import static it.gov.pagopa.fdr.to.eventhub.util.CommonUtil.*;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.storage.blob.BlobClient;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import it.gov.pagopa.fdr.to.eventhub.client.AppInsightTelemetryClient;
import it.gov.pagopa.fdr.to.eventhub.exception.AlertAppException;
import it.gov.pagopa.fdr.to.eventhub.exception.EventHubException;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlStAXParser;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import it.gov.pagopa.fdr.to.eventhub.util.ErrorCodes;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;

public class BlobProcessingFunction {

  private final org.slf4j.Logger logger = LoggerFactory.getLogger(BlobProcessingFunction.class);

  private final String fdr1Container = System.getenv().getOrDefault("BLOB_STORAGE_FDR1_CONTAINER", "fdr1-flows");

  private final String fdr3Container = System.getenv().getOrDefault("BLOB_STORAGE_FDR3_CONTAINER", "fdr3-flows");

  private static final String SESSION_ID_METADATA_KEY = "sessionId";

  @Getter private final EventHubProducerClient eventHubClientFlowTx;
  @Getter private final EventHubProducerClient eventHubClientReportedIUV;

  @Getter private final AppInsightTelemetryClient aiTelemetryClient;

  private FDR1XmlStAXParser fdr1XmlParser = new FDR1XmlStAXParser();

  private static class ClientsHolder {

    private static final EventHubProducerClient EVENT_HUB_CLIENT_FLOW_TX =
        CommonUtil.createEventHubClient(
            System.getenv("EVENT_HUB_FLOWTX_CONNECTION_STRING"),
            System.getenv("EVENT_HUB_FLOWTX_NAME"));

    private static final EventHubProducerClient EVENT_HUB_CLIENT_REPORTED_IUV =
        CommonUtil.createEventHubClient(
            System.getenv("EVENT_HUB_REPORTEDIUV_CONNECTION_STRING"),
            System.getenv("EVENT_HUB_REPORTEDIUV_NAME"));



    private static final AppInsightTelemetryClient AI_TELEMETRY_CLIENT =
        new AppInsightTelemetryClient();

    private ClientsHolder() {
      // Utility holder
    }
  }

  private enum BlobProcessingStatus {
    IN_ELABORAZIONE,
    ERRORE
  }

  @Getter
  @RequiredArgsConstructor
  public enum BlobTagName {

    PROCESSING_STATUS("processingStatus"),
    PROCESSING_UPDATED_AT("processingUpdatedAt"),
    PROCESSING_ATTEMPT("processingAttempt");

    private final String value;
  }

  public BlobProcessingFunction() {
    this.eventHubClientFlowTx =
        ClientsHolder.EVENT_HUB_CLIENT_FLOW_TX;

    this.eventHubClientReportedIUV =
        ClientsHolder.EVENT_HUB_CLIENT_REPORTED_IUV;

    this.aiTelemetryClient = ClientsHolder.AI_TELEMETRY_CLIENT;
  }


  // Constructor to inject the Event Hub clients
  public BlobProcessingFunction(
      EventHubProducerClient eventHubClientFlowTx,
      EventHubProducerClient eventHubClientReportedIUV,
      AppInsightTelemetryClient aiTelemetryClient,
      FDR1XmlStAXParser fdr1XmlParser) {
    this.eventHubClientFlowTx = eventHubClientFlowTx;
    this.eventHubClientReportedIUV = eventHubClientReportedIUV;
    this.aiTelemetryClient = aiTelemetryClient;
    this.fdr1XmlParser = fdr1XmlParser;
  }

  @FunctionName("ProcessFDR1BlobFiles")
  public void processFDR1BlobFiles(
          @BlobTrigger(
                  name = "Fdr1BlobTrigger",
                  path = "%BLOB_STORAGE_FDR1_CONTAINER%/{blobName}",
                  connection = "FDR_SA_CONNECTION_STRING")
          BlobClient blobClient,
          @BindingName("blobName") String blobName,
          @BindingName("Metadata") Map<String, String> blobMetadata,
          final ExecutionContext context) {

    int retryIndex = context.getRetryContext() == null ? -1 : context.getRetryContext().getRetrycount();

    // checks for the presence of the necessary metadata
    if (!CommonUtil.validateBlobMetadata(blobMetadata)) {
      logBlobMetadata(blobName, blobMetadata, "FDR1");
      return; // Skip execution
    }
    updateTags(blobClient, BlobProcessingStatus.IN_ELABORAZIONE, "FDR1");
    logger.info(
            "[FDR1] Triggered at: {} for Blob container: {}, name: {}",
            LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
            fdr1Container,
            blobName);

    FlussoRendicontazione flusso = null;
    try (InputStream originalInputStream = blobClient.openInputStream()) {
      CommonUtil.Pair<InputStream, Boolean> resultGzip = isGzipStream(originalInputStream);
      boolean isValidGzipFile = resultGzip.value;
      if (isValidGzipFile) {
        try (GZIPInputStream gzipStream = new GZIPInputStream(resultGzip.key)) {
          flusso = fdr1XmlParser.parseXmlStream(gzipStream);
          String flowId = flusso.getIdentificativoFlusso();
          if (flowId == null || flowId.isBlank()) {
              throw new IllegalArgumentException("Flow is empty");
          }
          flusso.setMetadata(blobMetadata);

          logger.info(
                "[FDR1] Parsed Finished at: {} for Blob container: {}, name: {}",
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                fdr1Container,
                blobName);

          // waits for confirmation of sending the entire flow to the Event Hub
          boolean eventBatchSent = CommonUtil.processXmlBlobAndSendToEventHub(
                  eventHubClientFlowTx, eventHubClientReportedIUV, flusso, logger,
                  true, true
          );

          if (!eventBatchSent) {
            throw new EventHubException(
                    String.format("EventHub has not confirmed sending the entire batch of events for flow ID: %s", flowId)
            );
          }

          logger.info(
                  "[FDR1] Execution Finished at: {} for Blob container: {}, name: {}",
                  LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                  fdr1Container,
                  blobName);
          removeBlobProcessingTags(blobClient,  "FDR1");
        }
      }
      else {
        updateTags(blobClient, BlobProcessingStatus.ERRORE, "FDR1");
        String exceptionDetails =
                getExceptionDetails(
                        ErrorCodes.FDR1_E1_1.getCode(),
                        fdr1Container,
                        blobName,
                        blobMetadata.get(SESSION_ID_METADATA_KEY),
                        retryIndex);
          this.aiTelemetryClient.createCustomEventForAlert(ErrorCodes.FDR1_E1_1, exceptionDetails, null);
      }

    } catch (Exception e) {
      updateTags(blobClient, BlobProcessingStatus.ERRORE, "FDR1");
      String exceptionDetails =
              getExceptionDetails(
                      ErrorCodes.FDR1_E1.getCode(),
                      fdr1Container,
                      blobName,
                      blobMetadata.get(SESSION_ID_METADATA_KEY),
                      retryIndex);

      boolean lastAttempt = context.getRetryContext() == null || retryIndex >= (context.getRetryContext().getMaxretrycount() - 1);
      if (lastAttempt) {
        this.aiTelemetryClient.createCustomEventForAlert(ErrorCodes.FDR1_E1, exceptionDetails, e);
      }
      throw new AlertAppException(e.getMessage(), e, exceptionDetails);
    }
    finally{
      if (flusso != null) {
        flusso.releaseResources();
      }
    }
  }


  @FunctionName("ProcessFDR3BlobFiles")
  public void processFDR3BlobFiles(
      @BlobTrigger(
              name = "Fdr3BlobTrigger",
              path = "%BLOB_STORAGE_FDR3_CONTAINER%/{blobName}",
              connection = "FDR_SA_CONNECTION_STRING")
      BlobClient blobClient,
      @BindingName("blobName") String blobName,
      @BindingName("Metadata") Map<String, String> blobMetadata,
      final ExecutionContext context) {

    int retryIndex = context.getRetryContext() == null ? -1 : context.getRetryContext().getRetrycount();

    // checks for the presence of the necessary metadata
    if (!CommonUtil.validateBlobMetadata(blobMetadata)) {
      logBlobMetadata(blobName, blobMetadata, "FDR3");
      return; // Skip execution
    }

    updateTags(blobClient, BlobProcessingStatus.IN_ELABORAZIONE, "FDR3");

    logger.info(
        "[FDR3] Triggered at: {} for Blob container: {}, name: {}",
        LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
        fdr3Container,
        blobName);

    Flow flow = null;
    try (InputStream originalInputStream = blobClient.openInputStream()) {
      CommonUtil.Pair<InputStream, Boolean> resultGzip = isGzipStream(originalInputStream);
      boolean isValidGzipFile = resultGzip.value;
      if (isValidGzipFile) {
        try (GZIPInputStream gzipStream = new GZIPInputStream(resultGzip.key)) {
          flow = CommonUtil.parseJSON(gzipStream);
          flow.setMetadata(blobMetadata);

          String flowId = flow.getFdr();

          logger.info(
                  "[FDR3] Parsed Finished at: {} for Blob container: {}, name: {}",
                  LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                  fdr3Container,
                  blobName);

          // waits for confirmation of sending the entire flow to the Event Hub
          boolean eventBatchSent = CommonUtil.processJsonBlobAndSendToEventHub(
                  eventHubClientFlowTx, eventHubClientReportedIUV, flow, logger,
                  true, true
          );

          if (!eventBatchSent) {
            throw new EventHubException(
                    String.format(
                            "EventHub has not confirmed sending the entire batch of events for flow ID: %s",
                            flowId));
          }

          logger.info(
                  "[FDR3] Execution Finished at: {} for Blob container: {}, name: {}",
                  LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                  fdr3Container,
                  blobName);
          removeBlobProcessingTags(blobClient, "FDR3");
        }
      }
      else {
        updateTags(blobClient, BlobProcessingStatus.ERRORE,  "FDR3");
        String exceptionDetails =
                getExceptionDetails(
                        ErrorCodes.FDR3_E1_1.getCode(),
                        fdr3Container,
                        blobName,
                        blobMetadata.get(SESSION_ID_METADATA_KEY),
                        retryIndex);
        this.aiTelemetryClient.createCustomEventForAlert(ErrorCodes.FDR3_E1_1, exceptionDetails, null);
      }
    } catch (Exception  e) {
      updateTags(blobClient, BlobProcessingStatus.ERRORE,  "FDR3");
      String exceptionDetails =
          getExceptionDetails(
              ErrorCodes.FDR3_E1.getCode(),
              fdr3Container,
              blobName,
              blobMetadata.get(SESSION_ID_METADATA_KEY),
              retryIndex);
      boolean lastAttempt = context.getRetryContext() == null  || retryIndex >= (context.getRetryContext().getMaxretrycount() - 1);
      if (lastAttempt) {
        this.aiTelemetryClient.createCustomEventForAlert(ErrorCodes.FDR3_E1, exceptionDetails, e);
      }
      throw new AlertAppException(e.getMessage(), e, exceptionDetails);
    }
    finally{
      if (flow != null) {
        flow.releaseResources();
      }
    }
  }

  private int calculateAttempt(
      Map<String, String> tags,
      BlobProcessingStatus status) {

    int currentAttempt = Integer.parseInt(
        tags.getOrDefault(
            BlobTagName.PROCESSING_ATTEMPT.getValue(),
            "0"
        )
    );

    return status == BlobProcessingStatus.IN_ELABORAZIONE
        ? currentAttempt + 1
        : currentAttempt;
  }

  private void updateTags(BlobClient blobClient, BlobProcessingStatus status, String fdrType) {
    ErrorCodes errorCode = status == BlobProcessingStatus.IN_ELABORAZIONE
        ? ErrorCodes.FDR_TAG_E1 : ErrorCodes.FDR_TAG_E2;
    try {
      Map<String, String> tags = getBlobTags(blobClient);

      String previousStatus = tags.get(
          BlobTagName.PROCESSING_STATUS.getValue());

      String previousAttempt = tags.get(
          BlobTagName.PROCESSING_ATTEMPT.getValue());


      int attempt = calculateAttempt(tags, status);

      logger.info(
          "[{}] Blob {} previous status: {}, previous attempt: {}",
          fdrType,
          blobClient.getBlobName(),
          previousStatus == null ? "NOT_SET" : previousStatus,
          previousAttempt == null ? "NOT_SET" : previousAttempt
      );

      tags.put(BlobTagName.PROCESSING_STATUS.getValue(), status.name());
      tags.put(BlobTagName.PROCESSING_ATTEMPT.getValue(), String.valueOf(attempt));
      tags.put(BlobTagName.PROCESSING_UPDATED_AT.getValue(),
          Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());

      logger.info(
          "[{}] Blob {} status transition: {} -> {}, attempt: {} -> {}",
          fdrType,
          blobClient.getBlobName(),
          previousStatus == null ? "NOT_SET" : previousStatus,
          status.name(),
          previousAttempt == null ? "NOT_SET" : previousAttempt,
          attempt
      );
    } catch (Exception e) {
      logger.error("[{}] Tagging failed for blob {}", fdrType, blobClient.getBlobName(), e);
      sendTaggingAlert(errorCode, blobClient, fdrType, e);
      // processing continues
    }
  }

  private void removeBlobProcessingTags(BlobClient blobClient, String fdrType) {
    try {
      Map<String, String> tags = getBlobTags(blobClient);

      String previousStatus = tags.get(BlobTagName.PROCESSING_STATUS.getValue());
      String previousAttempt = tags.get(BlobTagName.PROCESSING_ATTEMPT.getValue());
      String previousUpdatedAt = tags.get(BlobTagName.PROCESSING_UPDATED_AT.getValue());

      boolean changed = tags.remove(BlobTagName.PROCESSING_STATUS.getValue()) != null;
      changed |= tags.remove(BlobTagName.PROCESSING_UPDATED_AT.getValue()) != null;
      changed |= tags.remove(BlobTagName.PROCESSING_ATTEMPT.getValue()) != null;

      if (changed) {
        blobClient.setTags(tags);

        logger.info(
            "[{}] Processing tags removed from blob {}. Previous status: {}, attempt: {}, updatedAt: {}",
            fdrType,
            blobClient.getBlobName(),
            previousStatus,
            previousAttempt,
            previousUpdatedAt
        );
      }

    } catch (Exception e) {
      logger.error(
          "[{}] Failed to remove processing tags from blob {}",
          fdrType,
          blobClient.getBlobName(),
          e
      );

      sendTaggingAlert(ErrorCodes.FDR_TAG_E3, blobClient, fdrType, e);
    }
  }

  private void sendTaggingAlert(
      ErrorCodes errorCode,
      BlobClient blobClient,
      String fdrType,
      Exception exception) {

    String exceptionDetails = String.format(
        "[%s] Tagging error [%s] - Blob: %s, Message: %s",
        fdrType,
        errorCode.getCode(),
        blobClient.getBlobName(),
        exception.getMessage()
    );

    try {
      aiTelemetryClient.createCustomEventForAlert(
          errorCode,
          exceptionDetails,
          exception
      );

    } catch (Exception telemetryException) {
      logger.error(
          "[{}] Failed to send tagging alert {} for blob {}",
          fdrType,
          errorCode.getCode(),
          blobClient.getBlobName(),
          telemetryException
      );
    }
  }

  private Map<String, String> getBlobTags(BlobClient blobClient) {
    return new HashMap<>(blobClient.getTags());
  }

  private void logBlobMetadata(
      String blobName,
      Map<String, String> blobMetadata, String type) {

    logger.warn(
        "[{}] Blob metadata check: blobName={}, metadataNull={}, "
            + "hasSessionId={}, hasInsertedTimestamp={}, elaborateFalse={}",
        type,
        blobName,
        blobMetadata == null,
        blobMetadata != null
            && blobMetadata.containsKey(SESSION_ID_METADATA_KEY),
        blobMetadata != null
            && blobMetadata.containsKey("insertedTimestamp"),
        blobMetadata != null
            && "false".equalsIgnoreCase(blobMetadata.get("elaborate")));
  }

}
