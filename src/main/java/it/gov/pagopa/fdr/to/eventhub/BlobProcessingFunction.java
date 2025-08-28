package it.gov.pagopa.fdr.to.eventhub;

import static it.gov.pagopa.fdr.to.eventhub.exception.AlertAppException.getExceptionDetails;

import com.azure.messaging.eventhubs.EventHubProducerClient;
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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.Getter;
import org.slf4j.LoggerFactory;

public class BlobProcessingFunction {

  private final org.slf4j.Logger logger = LoggerFactory.getLogger(BlobProcessingFunction.class);

  private final String fdr1Container =
      System.getenv().getOrDefault("BLOB_STORAGE_FDR1_CONTAINER", "fdr1-flows");
  private final String fdr3Container =
      System.getenv().getOrDefault("BLOB_STORAGE_FDR3_CONTAINER", "fdr3-flows");
  private static final String SESSION_ID_METADATA_KEY = "sessionId";

  @Getter private final EventHubProducerClient eventHubClientFlowTx;
  @Getter private final EventHubProducerClient eventHubClientReportedIUV;
  private final AppInsightTelemetryClient aiTelemetryClient;

  private FDR1XmlStAXParser fdr1XmlParser = new FDR1XmlStAXParser();

  public BlobProcessingFunction() {
    this.eventHubClientFlowTx =
        CommonUtil.createEventHubClient(
            System.getenv("EVENT_HUB_FLOWTX_CONNECTION_STRING"),
            System.getenv("EVENT_HUB_FLOWTX_NAME"));

    this.eventHubClientReportedIUV =
        CommonUtil.createEventHubClient(
            System.getenv("EVENT_HUB_REPORTEDIUV_CONNECTION_STRING"),
            System.getenv("EVENT_HUB_REPORTEDIUV_NAME"));

    this.aiTelemetryClient = new AppInsightTelemetryClient();
  }

  // Constructor to inject the Event Hub clients
  public BlobProcessingFunction(
          EventHubProducerClient eventHubClientFlowTx,
          EventHubProducerClient eventHubClientReportedIUV,
          AppInsightTelemetryClient aiTelemetryClient,
          FDR1XmlStAXParser fdr1XmlParser
  ) {
    this.eventHubClientFlowTx = eventHubClientFlowTx;
    this.eventHubClientReportedIUV = eventHubClientReportedIUV;
    this.aiTelemetryClient = aiTelemetryClient;
    this.fdr1XmlParser = fdr1XmlParser;
  }

  @FunctionName("ProcessFDR1BlobFiles")
  public synchronized void processFDR1BlobFiles(
      @BlobTrigger(
              name = "Fdr1BlobTrigger",
              dataType = "binary",
              path = "%BLOB_STORAGE_FDR1_CONTAINER%/{blobName}",
              connection = "FDR_SA_CONNECTION_STRING")
          byte[] content,
      @BindingName("blobName") String blobName,
      @BindingName("Metadata") Map<String, String> blobMetadata,
      final ExecutionContext context) {

    int retryIndex =
        context.getRetryContext() == null ? -1 : context.getRetryContext().getRetrycount();

    // checks for the presence of the necessary metadata
    if (!CommonUtil.validateBlobMetadata(blobMetadata)) {
      logger.warn("[FDR1] Skipping processing for Blob container: {}, name: {}, size in bytes: {}",
                  fdr1Container, blobName, content.length);
      return; // Skip execution
    }

    // verify that the file is present and that it is a compressed file
    boolean isValidGzipFile = CommonUtil.isGzip(content);

    logger.info("[FDR1] Triggered at: {} for Blob container: {}, name: {}, size in bytes: {}",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                fdr1Container,
                blobName,
                content.length);

    try (InputStream decompressedStream =
        isValidGzipFile ? CommonUtil.decompressGzip(content) : new ByteArrayInputStream(content)) {

      FlussoRendicontazione flusso = fdr1XmlParser.parseXmlStream(decompressedStream);
      flusso.setMetadata(blobMetadata);

      logger.info("[FDR1] Parsed Finished at: {} for Blob container: {}, name: {}, size in bytes: {}",
                  LocalDateTime.now().format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                  fdr1Container,
                  blobName,
                  content.length);

      // Waits for confirmation of sending the entire flow to the Event Hub
      boolean eventBatchSent =
          CommonUtil.processXmlBlobAndSendToEventHub(
              eventHubClientFlowTx, eventHubClientReportedIUV, flusso, logger, true, true);
      if (!eventBatchSent) {
        throw new EventHubException(
            String.format(
                "EventHub has not confirmed sending the entire batch of events for flow ID: %s",
                flusso.getIdentificativoFlusso()));
      }

      logger.info("[FDR1] Execution Finished at: {} for Blob container: {}, name: {}, size in bytes: {}",
                  LocalDateTime.now().format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                  fdr1Container,
                  blobName,
                  content.length);
    } catch (Exception e) {
      String exceptionDetails =
          getExceptionDetails(
              ErrorCodes.FDR1_E1.getCode(),
              fdr1Container,
              blobName,
              blobMetadata.get(SESSION_ID_METADATA_KEY),
              retryIndex);
      this.aiTelemetryClient.createCustomEvent(ErrorCodes.FDR1_E1, exceptionDetails, e);
      throw new AlertAppException(e.getMessage(), e.getCause(), exceptionDetails);
    }
  }

  @FunctionName("ProcessFDR3BlobFiles")
  public void processFDR3BlobFiles(
      @BlobTrigger(
              name = "Fdr3BlobTrigger",
              dataType = "binary",
              path = "%BLOB_STORAGE_FDR3_CONTAINER%/{blobName}",
              connection = "FDR_SA_CONNECTION_STRING")
          byte[] content,
      @BindingName("blobName") String blobName,
      @BindingName("Metadata") Map<String, String> blobMetadata,
      final ExecutionContext context) {

    int retryIndex =
        context.getRetryContext() == null ? -1 : context.getRetryContext().getRetrycount();

    // checks for the presence of the necessary metadata
    if (!CommonUtil.validateBlobMetadata(blobMetadata)) {
      logger.warn("[FDR3] Skipping processing for Blob container: {}, name: {}, size in bytes: {}",
                  fdr3Container, blobName, content.length);
      return; // Skip execution
    }

    // verify that the file is present and that it is a compressed file
    boolean isValidGzipFile = CommonUtil.isGzip(content);

    logger.info("[FDR3] Triggered at: {} for Blob container: {}, name: {}, size in bytes: {}",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                fdr3Container,
                blobName,
                content.length);

    try (InputStream decompressedStream =
        isValidGzipFile ? CommonUtil.decompressGzip(content) : new ByteArrayInputStream(content)) {

      Flow flow = CommonUtil.parseJSON(decompressedStream);

      logger.info("[FDR3] Parsed Finished at: {} for Blob container: {}, name: {}, size in bytes: {}",
                  LocalDateTime.now().format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                  fdr3Container,
                  blobName,
                  content.length);

      flow.setMetadata(blobMetadata);

      // Waits for confirmation of sending the entire flow to the Event Hub
      boolean eventBatchSent =
          CommonUtil.processJsonBlobAndSendToEventHub(
              eventHubClientFlowTx, eventHubClientReportedIUV, flow, logger, true, true);
      if (!eventBatchSent) {
        throw new EventHubException(
            String.format(
                "EventHub has not confirmed sending the entire batch of events for flow ID: %s",
                flow.getFdr()));
      }

      logger.info("[FDR3] Execution Finished at: {} for Blob container: {}, name: {}, size in bytes: {}",
                  LocalDateTime.now().format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                  fdr3Container,
                  blobName,
                  content.length);

    } catch (Exception e) {
      String exceptionDetails =
          getExceptionDetails(
              ErrorCodes.FDR3_E1.getCode(),
              fdr3Container,
              blobName,
              blobMetadata.get(SESSION_ID_METADATA_KEY),
              retryIndex);
      this.aiTelemetryClient.createCustomEvent(ErrorCodes.FDR3_E1, exceptionDetails, e);
      throw new AlertAppException(e.getMessage(), e.getCause(), exceptionDetails);
    }
  }
}
