package it.gov.pagopa.fdr.to.eventhub;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
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

public class BlobProcessingFunction {

  private final String fdr1Container =
      System.getenv().getOrDefault("BLOB_STORAGE_FDR1_CONTAINER", "fdr1-flows");
  private final String fdr3Container =
      System.getenv().getOrDefault("BLOB_STORAGE_FDR3_CONTAINER", "fdr3-flows");
  @Getter private final EventHubProducerClient eventHubClientFlowTx;
  @Getter private final EventHubProducerClient eventHubClientReportedIUV;

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
        .fine(
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

      FlussoRendicontazione flusso = new FDR1XmlStAXParser().parseXmlStream(decompressedStream);
      flusso.setMetadata(blobMetadata);

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

      // Waits for confirmation of sending the entire flow to the Event Hub
      boolean eventBatchSent =
          CommonUtil.processXmlBlobAndSendToEventHub(
              eventHubClientFlowTx, eventHubClientReportedIUV, flusso, context, true, true);
      if (!eventBatchSent) {
        throw new EventHubException(
            String.format(
                "EventHub has not confirmed sending the entire batch of events for flow ID: %s",
                flusso.getIdentificativoFlusso()));
      }

      context
          .getLogger()
          .fine(
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
                      "[%s][FDR1] Error processing Blob '%s/%s': %s",
                      ErrorCodes.FDR1_E1, fdr1Container, blobName, e.getMessage()));
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

    // checks for the presence of the necessary metadata
    if (!CommonUtil.validateBlobMetadata(blobMetadata)) {
      context
          .getLogger()
          .warning(
              () ->
                  String.format(
                      "[FDR3] Skipping processing for Blob container: %s, name: %s, size in bytes:"
                          + " %d",
                      fdr3Container, blobName, content.length));
      return; // Skip execution
    }

    // verify that the file is present and that it is a compressed file
    boolean isValidGzipFile = CommonUtil.isGzip(content);

    context
        .getLogger()
        .fine(
            () ->
                String.format(
                    "[FDR3] Triggered at: %s for Blob container: %s, name: %s, size in bytes: %d",
                    LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                    fdr3Container,
                    blobName,
                    content.length));

    try (InputStream decompressedStream =
        isValidGzipFile ? CommonUtil.decompressGzip(content) : new ByteArrayInputStream(content)) {

      Flow flow = CommonUtil.parseJSON(decompressedStream);

      context
          .getLogger()
          .fine(
              () ->
                  String.format(
                      "[FDR3] Parsed Finished at: %s for Blob container: %s, name: %s, size in"
                          + " bytes: %d",
                      LocalDateTime.now()
                          .format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                      fdr3Container,
                      blobName,
                      content.length));

      flow.setMetadata(blobMetadata);

      // Waits for confirmation of sending the entire flow to the Event Hub
      boolean eventBatchSent =
          CommonUtil.processJsonBlobAndSendToEventHub(
              eventHubClientFlowTx, eventHubClientReportedIUV, flow, context, true, true);
      if (!eventBatchSent) {
        throw new EventHubException(
            String.format(
                "EventHub has not confirmed sending the entire batch of events for flow ID: %s",
                flow.getFdr()));
      }

      context
          .getLogger()
          .fine(
              () ->
                  String.format(
                      "[FDR3] Execution Finished at: %s for Blob container: %s, name: %s, size in"
                          + " bytes: %d",
                      LocalDateTime.now()
                          .format(DateTimeFormatter.ofPattern(CommonUtil.LOG_DATETIME_PATTERN)),
                      fdr3Container,
                      blobName,
                      content.length));

    } catch (Exception e) {
      context
          .getLogger()
          .severe(
              () ->
                  String.format(
                      "[%s][FDR3] Error processing Blob '%s/%s': %s",
                      ErrorCodes.FDR3_E1, fdr3Container, blobName, e.getMessage()));
    }
  }
}
