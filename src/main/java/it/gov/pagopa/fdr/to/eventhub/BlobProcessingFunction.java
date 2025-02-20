package it.gov.pagopa.fdr.to.eventhub;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import it.gov.pagopa.fdr.to.eventhub.exception.EventHubException;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class BlobProcessingFunction {

  private final String fdr1Container =
      System.getenv().getOrDefault("BLOB_STORAGE_FDR1_CONTAINER", "fdr1-flows");
  private final String fdr3Container =
      System.getenv().getOrDefault("BLOB_STORAGE_FDR3_CONTAINER", "fdr3-flows");
  private final EventHubProducerClient eventHubClientFlowTx;
  private final EventHubProducerClient eventHubClientReportedIUV;

  public BlobProcessingFunction() {
    this.eventHubClientFlowTx =
        CommonUtil.createEventHubClient(
            System.getenv("EVENT_HUB_FLOWTX_CONNECTION_STRING"),
            System.getenv("EVENT_HUB_FLOWTX_NAME"));

    this.eventHubClientReportedIUV =
        CommonUtil.createEventHubClient(
            System.getenv("EVENT_HUB_REPORTEDIUV_CONNECTION_STRING"),
            System.getenv("EVENT_HUB_REPORTEDIUV_NAME"));
  }

  // Constructor to inject the Event Hub clients
  public BlobProcessingFunction(
      EventHubProducerClient eventHubClientFlowTx,
      EventHubProducerClient eventHubClientReportedIUV) {
    this.eventHubClientFlowTx = eventHubClientFlowTx;
    this.eventHubClientReportedIUV = eventHubClientReportedIUV;
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

    // checks for the presence of the necessary metadata
    if (!CommonUtil.validateBlobMetadata(blobMetadata)) {
      context
          .getLogger()
          .warning(
              () ->
                  String.format(
                      "[FDR1] Skipping processing for Blob container: %s, name: %s, size in bytes:"
                          + " %d",
                      fdr1Container, blobName, content.length));
      return; // Skip execution
    }

    // verify that the file is present and that it is a compressed file
    boolean isValidGzipFile = CommonUtil.isGzip(content);

    context
        .getLogger()
        .info(
            () ->
                String.format(
                    "[FDR1] Triggered at: %s for Blob container: %s, name: %s, size in bytes: %d",
                    LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                    fdr1Container,
                    blobName,
                    content.length));

    try (InputStream decompressedStream =
        isValidGzipFile ? CommonUtil.decompressGzip(content) : new ByteArrayInputStream(content)) {

      FlussoRendicontazione flusso = CommonUtil.parseXml(decompressedStream);

      context
          .getLogger()
          .fine(
              () ->
                  String.format(
                      "[FDR1] Parsed Finished at: %s for Blob container: %s, name: %s, size in"
                          + " bytes: %d",
                      LocalDateTime.now()
                          .format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                      fdr1Container,
                      blobName,
                      content.length));

      flusso.setMetadata(blobMetadata);

      // Waits for confirmation of sending the entire flow to the Event Hub
      boolean eventBatchSent =
          CommonUtil.processXmlBlobAndSendToEventHub(
              eventHubClientFlowTx, eventHubClientReportedIUV, flusso, context);
      if (!eventBatchSent) {
        throw new EventHubException(
            String.format(
                "EventHub has not confirmed sending the entire batch of events for flow ID: %s",
                flusso.getIdentificativoFlusso()));
      }

      context
          .getLogger()
          .info(
              () ->
                  String.format(
                      "[FDR1] Execution Finished at: %s for Blob container: %s, name: %s, size in"
                          + " bytes: %d",
                      LocalDateTime.now()
                          .format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                      fdr1Container,
                      blobName,
                      content.length));

    } catch (Exception e) {
      context
          .getLogger()
          .severe(
              () ->
                  String.format(
                      "[FDR1] Error processing Blob '%s/%s': %s",
                      fdr1Container, blobName, e.getMessage()));
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

    context
        .getLogger()
        .info(
            () ->
                String.format(
                    "[FDR3] Triggered for Blob container: %s, name: %s, size: %d bytes",
                    fdr3Container, blobName, content.length));

    context
        .getLogger()
        .info(
            () ->
                String.format(
                    "[FDR3] Execution Finished at: %s for Blob container: %s, name: %s, size: %d"
                        + " bytes",
                    LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                    fdr1Container,
                    blobName,
                    content.length));
  }
}
