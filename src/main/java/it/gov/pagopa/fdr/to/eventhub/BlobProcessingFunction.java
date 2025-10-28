package it.gov.pagopa.fdr.to.eventhub;

import static it.gov.pagopa.fdr.to.eventhub.exception.AlertAppException.getExceptionDetails;
import static it.gov.pagopa.fdr.to.eventhub.util.CommonUtil.*;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import lombok.Getter;
import org.slf4j.LoggerFactory;

public class BlobProcessingFunction {

  private final org.slf4j.Logger logger = LoggerFactory.getLogger(BlobProcessingFunction.class);

  private final String fdr1Container = System.getenv().getOrDefault("BLOB_STORAGE_FDR1_CONTAINER", "fdr1-flows");

  private final String fdr3Container = System.getenv().getOrDefault("BLOB_STORAGE_FDR3_CONTAINER", "fdr3-flows");

  private static final String SESSION_ID_METADATA_KEY = "sessionId";

  @Getter private final EventHubProducerClient eventHubClientFlowTx;
  @Getter private final EventHubProducerClient eventHubClientReportedIUV;
  @Getter private final BlobServiceClient blobServiceClient;
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

    this.blobServiceClient = CommonUtil.createBlobServiceClient(System.getenv("FDR_SA_CONNECTION_STRING"));

    this.aiTelemetryClient = new AppInsightTelemetryClient();
  }

  // Constructor to inject the Event Hub clients
  public BlobProcessingFunction(
      EventHubProducerClient eventHubClientFlowTx,
      EventHubProducerClient eventHubClientReportedIUV,
      BlobServiceClient blobServiceClient,
      AppInsightTelemetryClient aiTelemetryClient,
      FDR1XmlStAXParser fdr1XmlParser) {
    this.eventHubClientFlowTx = eventHubClientFlowTx;
    this.eventHubClientReportedIUV = eventHubClientReportedIUV;
    this.blobServiceClient = blobServiceClient;
    this.aiTelemetryClient = aiTelemetryClient;
    this.fdr1XmlParser = fdr1XmlParser;
  }

  @FunctionName("ProcessFDR1BlobFiles")
  public void processFDR1BlobFiles(
          @BlobTrigger(
                  name = "Fdr1BlobTrigger",
                  path = "%BLOB_STORAGE_FDR1_CONTAINER%/{blobName}",
                  connection = "FDR_SA_CONNECTION_STRING")
          byte[] dontUse,
          @BindingName("blobName") String blobName,
          @BindingName("Metadata") Map<String, String> blobMetadata,
          final ExecutionContext context) {

    int contentLength = dontUse.length;
    dontUse = null; // help GC

    int retryIndex = context.getRetryContext() == null ? -1 : context.getRetryContext().getRetrycount();

    // checks for the presence of the necessary metadata
    if (!CommonUtil.validateBlobMetadata(blobMetadata)) {
      logger.warn(
              "[FDR1] Skipping processing for Blob container: {}, name: {}, size in bytes: {}",
              fdr1Container,
              blobName,
              contentLength);
      return; // Skip execution
    }

    logger.info(
            "[FDR1] Triggered at: {} for Blob container: {}, name: {}, size in bytes: {}",
            LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
            fdr1Container,
            blobName,
            contentLength
    );

    String containerName = System.getenv("BLOB_STORAGE_FDR1_CONTAINER");
    BlobClient blobClient = getBlobClient(containerName, blobName);

    try (InputStream originalInputStream = blobClient.openInputStream()) {
      CommonUtil.Pair<InputStream, Boolean> resultGzip = isGzipStream(originalInputStream);
      GZIPInputStream gzipStream = new GZIPInputStream(resultGzip.key);
      boolean isValidGzipFile = resultGzip.value;
      if (isValidGzipFile) {
        FlussoRendicontazione flusso = fdr1XmlParser.parseXmlStream(gzipStream);
        if (flusso.getIdentificativoFlusso().isBlank()) {
            throw new IllegalArgumentException("Flow is empty");
        }
        flusso.setMetadata(blobMetadata);

        String flowId = flusso.getIdentificativoFlusso();

        logger.info(
              "[FDR1] Parsed Finished at: {} for Blob container: {}, name: {}, size in bytes: {}",
              LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
              fdr1Container,
              blobName,
              contentLength
        );

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
        // help GC for large files
        flusso.releaseResources();
        flusso = null;
        gzipStream.close();

        logger.info(
                "[FDR1] Execution Finished at: {} for Blob container: {}, name: {}, size in bytes: {}",
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                fdr1Container,
                blobName,
                contentLength
        );
      }
      else {
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
      String exceptionDetails =
              getExceptionDetails(
                      ErrorCodes.FDR1_E1.getCode(),
                      fdr1Container,
                      blobName,
                      blobMetadata.get(SESSION_ID_METADATA_KEY),
                      retryIndex);
      if (retryIndex >= (context.getRetryContext().getMaxretrycount() - 1)) {
        this.aiTelemetryClient.createCustomEventForAlert(ErrorCodes.FDR1_E1, exceptionDetails, e);
      }
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
      byte[] dontUse,
      @BindingName("blobName") String blobName,
      @BindingName("Metadata") Map<String, String> blobMetadata,
      final ExecutionContext context) {

    int contentLength = dontUse.length;
    dontUse = null; // help GC

    int retryIndex = context.getRetryContext() == null ? -1 : context.getRetryContext().getRetrycount();

    // checks for the presence of the necessary metadata
    if (!CommonUtil.validateBlobMetadata(blobMetadata)) {
      logger.warn(
          "[FDR3] Skipping processing for Blob container: {}, name: {}, size in bytes: {}",
          fdr3Container,
          blobName,
          contentLength);
      return; // Skip execution
    }

    logger.info(
        "[FDR3] Triggered at: {} for Blob container: {}, name: {}, size in bytes: {}",
        LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
        fdr3Container,
        blobName,
        contentLength);

    String containerName = System.getenv("BLOB_STORAGE_FDR3_CONTAINER");
    BlobClient blobClient = getBlobClient(containerName, blobName);

    try (InputStream originalInputStream = blobClient.openInputStream()) {
      CommonUtil.Pair<InputStream, Boolean> resultGzip = isGzipStream(originalInputStream);
      GZIPInputStream gzipStream = new GZIPInputStream(resultGzip.key);
      boolean isValidGzipFile = resultGzip.value;
      if (isValidGzipFile) {
        Flow flow = CommonUtil.parseJSON(gzipStream);
        flow.setMetadata(blobMetadata);

        String flowId = flow.getFdr();

        logger.info(
                "[FDR3] Parsed Finished at: {} for Blob container: {}, name: {}, size in bytes: {}",
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                fdr3Container,
                blobName,
                contentLength);

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

        // help GC for large files
        flow.releaseResources();
        flow = null;
        gzipStream.close();

        logger.info(
                "[FDR3] Execution Finished at: {} for Blob container: {}, name: {}, size in bytes: {}",
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                fdr3Container,
                blobName,
                contentLength);
      }
      else {
        String exceptionDetails =
                getExceptionDetails(
                        ErrorCodes.FDR3_E1_1.getCode(),
                        fdr3Container,
                        blobName,
                        blobMetadata.get(SESSION_ID_METADATA_KEY),
                        retryIndex);
        this.aiTelemetryClient.createCustomEventForAlert(ErrorCodes.FDR3_E1_1, exceptionDetails, null);
      }
    } catch (Exception e) {
      String exceptionDetails =
          getExceptionDetails(
              ErrorCodes.FDR3_E1.getCode(),
              fdr3Container,
              blobName,
              blobMetadata.get(SESSION_ID_METADATA_KEY),
              retryIndex);
      if (retryIndex >= (context.getRetryContext().getMaxretrycount() - 1)) {
        this.aiTelemetryClient.createCustomEventForAlert(ErrorCodes.FDR3_E1, exceptionDetails, e);
      }
      throw new AlertAppException(e.getMessage(), e.getCause(), exceptionDetails);
    }
  }

//  @FunctionName("ProcessFDR3BlobFiles")
//  public void processFDR3BlobFiles(
//          @BlobTrigger(
//                  name = "Fdr3BlobTrigger",
//                  dataType = "binary",
//                  path = "%BLOB_STORAGE_FDR3_CONTAINER%/{blobName}",
//                  connection = "FDR_SA_CONNECTION_STRING")
//          byte[] content,
//          @BindingName("blobName") String blobName,
//          @BindingName("Metadata") Map<String, String> blobMetadata,
//          final ExecutionContext context) {
//
//    int retryIndex =
//            context.getRetryContext() == null ? -1 : context.getRetryContext().getRetrycount();
//
//    // checks for the presence of the necessary metadata
//    if (!CommonUtil.validateBlobMetadata(blobMetadata)) {
//      logger.warn(
//              "[FDR3] Skipping processing for Blob container: {}, name: {}, size in bytes: {}",
//              fdr3Container,
//              blobName,
//              content.length);
//      return; // Skip execution
//    }
//
//    // verify that the file is present and that it is a compressed file
//    boolean isValidGzipFile = CommonUtil.isGzip(content);
//
//    logger.info(
//            "[FDR3] Triggered at: {} for Blob container: {}, name: {}, size in bytes: {}",
//            LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
//            fdr3Container,
//            blobName,
//            content.length);
//
//    try (InputStream decompressedStream =
//                 isValidGzipFile ? CommonUtil.decompressGzip(content) : new ByteArrayInputStream(content)) {
//
//      Flow flow = CommonUtil.parseJSON(decompressedStream);
//
//      logger.info(
//              "[FDR3] Parsed Finished at: {} for Blob container: {}, name: {}, size in bytes: {}",
//              LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
//              fdr3Container,
//              blobName,
//              content.length);
//
//      flow.setMetadata(blobMetadata);
//
//      // Waits for confirmation of sending the entire flow to the Event Hub
//      boolean eventBatchSent =
//              CommonUtil.processJsonBlobAndSendToEventHub(
//                      eventHubClientFlowTx, eventHubClientReportedIUV, flow, logger, true, true);
//      if (!eventBatchSent) {
//        throw new EventHubException(
//                String.format(
//                        "EventHub has not confirmed sending the entire batch of events for flow ID: %s",
//                        flow.getFdr()));
//      }
//
//      logger.info(
//              "[FDR3] Execution Finished at: {} for Blob container: {}, name: {}, size in bytes: {}",
//              LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
//              fdr3Container,
//              blobName,
//              content.length);
//
//    } catch (Exception e) {
//      String exceptionDetails =
//              getExceptionDetails(
//                      ErrorCodes.FDR3_E1.getCode(),
//                      fdr3Container,
//                      blobName,
//                      blobMetadata.get(SESSION_ID_METADATA_KEY),
//                      retryIndex);
//      if (retryIndex >= (context.getRetryContext().getMaxretrycount() - 1)) {
//        this.aiTelemetryClient.createCustomEventForAlert(ErrorCodes.FDR3_E1, exceptionDetails, e);
//      }
//      throw new AlertAppException(e.getMessage(), e.getCause(), exceptionDetails);
//    }
//  }

  public BlobClient getBlobClient(String containerName, String blobName) {
    BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
    return containerClient.getBlobClient(blobName);
  }
}
