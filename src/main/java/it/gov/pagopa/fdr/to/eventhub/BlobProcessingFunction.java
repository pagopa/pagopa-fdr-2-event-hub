package it.gov.pagopa.fdr.to.eventhub;

import com.azure.core.amqp.AmqpRetryMode;
import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import it.gov.pagopa.fdr.to.eventhub.exception.EventHubException;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlSAXParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

public class BlobProcessingFunction {

  private static final String LOG_DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
  private static final String SERVICE_IDENTIFIER = "serviceIdentifier";
  private final String fdr1Container =
      System.getenv().getOrDefault("BLOB_STORAGE_FDR1_CONTAINER", "fdr1-flows");
  private final String fdr3Container =
      System.getenv().getOrDefault("BLOB_STORAGE_FDR3_CONTAINER", "fdr3-flows");
  private final EventHubProducerClient eventHubClientFlowTx;
  private final EventHubProducerClient eventHubClientReportedIUV;

  public BlobProcessingFunction() {
    this.eventHubClientFlowTx =
        new EventHubClientBuilder()
            .connectionString(
                System.getenv("EVENT_HUB_FLOWTX_CONNECTION_STRING"),
                System.getenv("EVENT_HUB_FLOWTX_NAME"))
            .retryOptions(
                new AmqpRetryOptions()
                    .setMaxRetries(3) // Maximum number of
                    // attempts
                    .setDelay(Duration.ofSeconds(2)) // Delay between attempts
                    .setMode(AmqpRetryMode.EXPONENTIAL)) // Backoff strategy
            .buildProducerClient();

    this.eventHubClientReportedIUV =
        new EventHubClientBuilder()
            .connectionString(
                System.getenv("EVENT_HUB_REPORTEDIUV_CONNECTION_STRING"),
                System.getenv("EVENT_HUB_REPORTEDIUV_NAME"))
            .retryOptions(
                new AmqpRetryOptions()
                    .setMaxRetries(3)
                    .setDelay(Duration.ofSeconds(2))
                    .setMode(AmqpRetryMode.EXPONENTIAL))
            .buildProducerClient();
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
    if (!validateBlobMetadata(blobMetadata)) {
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
    boolean isValidGzipFile = isGzip(content);

    context
        .getLogger()
        .info(
            () ->
                String.format(
                    "[FDR1] Triggered at: %s for Blob container: %s, name: %s, size in bytes: %d",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern(LOG_DATETIME_PATTERN)),
                    fdr1Container,
                    blobName,
                    content.length));

    try (InputStream decompressedStream =
        isValidGzipFile ? decompressGzip(content) : new ByteArrayInputStream(content)) {

      FlussoRendicontazione flusso = parseXml(decompressedStream);

      context
          .getLogger()
          .fine(
              () ->
                  String.format(
                      "[FDR1] Parsed Finished at: %s for Blob container: %s, name: %s, size in"
                          + " bytes: %d",
                      LocalDateTime.now().format(DateTimeFormatter.ofPattern(LOG_DATETIME_PATTERN)),
                      fdr1Container,
                      blobName,
                      content.length));

      flusso.setMetadata(blobMetadata);

      // Waits for confirmation of sending the entire flow to the Event Hub
      boolean eventBatchSent = processXmlBlobAndSendToEventHub(flusso, context);
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
                      LocalDateTime.now().format(DateTimeFormatter.ofPattern(LOG_DATETIME_PATTERN)),
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
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern(LOG_DATETIME_PATTERN)),
                    fdr1Container,
                    blobName,
                    content.length));
  }

  private boolean isGzip(byte[] content) {
    if (content == null || content.length == 0) {
      throw new IllegalArgumentException("Invalid input data for decompression: empty file");
    }
    return content.length > 2 && content[0] == (byte) 0x1F && content[1] == (byte) 0x8B;
  }

  private boolean validateBlobMetadata(Map<String, String> blobMetadata) {
    if (blobMetadata == null
        || blobMetadata.isEmpty()
        || !blobMetadata.containsKey("sessionId")
        || !blobMetadata.containsKey("insertedTimestamp")) {
      throw new IllegalArgumentException(
          "Invalid blob metadata: sessionId or insertedTimestamp is missing.");
    }
    return !("false".equalsIgnoreCase(blobMetadata.get("elaborate")));
  }

  private InputStream decompressGzip(byte[] compressedContent) throws IOException {
    return new GZIPInputStream(new ByteArrayInputStream(compressedContent));
  }

  private FlussoRendicontazione parseXml(InputStream xmlStream)
      throws ParserConfigurationException, SAXException, IOException {
    return FDR1XmlSAXParser.parseXmlStream(xmlStream);
  }

  private boolean processXmlBlobAndSendToEventHub(
      FlussoRendicontazione flussoRendicontazione, ExecutionContext context) {
    try {
      // Convert FlussoRendicontazione to event models
      FlowTxEventModel flowEvent =
          FlussoRendicontazioneMapper.toFlowTxEventList(flussoRendicontazione);
      List<ReportedIUVEventModel> reportedIUVEventList =
          FlussoRendicontazioneMapper.toReportedIUVEventList(flussoRendicontazione);

      // Serialize the objects to JSON
      JsonMapper objectMapper =
          JsonMapper.builder()
              .addModule(new JavaTimeModule())
              .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
              .build();

      String flowEventJson = objectMapper.writeValueAsString(flowEvent);

      // Break the list into smaller batches to avoid overshooting limit
      List<String> reportedIUVEventJsonChunks = splitIntoChunks(reportedIUVEventList, objectMapper);

      context
          .getLogger()
          .fine(
              () ->
                  String.format(
                      "Chunk splitting process completed at: %s for flow ID: %s. Total number of"
                          + " chunks: %d",
                      LocalDateTime.now().format(DateTimeFormatter.ofPattern(LOG_DATETIME_PATTERN)),
                      flussoRendicontazione.getIdentificativoFlusso(),
                      reportedIUVEventJsonChunks.size()));

      boolean flowEventSent =
          sendEventToHub(flowEventJson, eventHubClientFlowTx, flussoRendicontazione, context);
      boolean allEventChunksSent = true;

      for (String chunk : reportedIUVEventJsonChunks) {
        if (!sendEventToHub(chunk, eventHubClientReportedIUV, flussoRendicontazione, context)) {
          allEventChunksSent = false;
          break;
        }
      }

      return flowEventSent && allEventChunksSent;

    } catch (Exception e) {
      // Log the exception with context
      String errorMessage =
          String.format(
              "Error processing or sending data to event hub: %s. Details: %s",
              flussoRendicontazione.getIdentificativoFlusso(), e.getMessage());
      context.getLogger().severe(() -> errorMessage);

      return false;
    }
  }

  /** Divides the event list into smaller JSON blocks (to avoid exceeding 1MB) */
  private List<String> splitIntoChunks(
      List<ReportedIUVEventModel> eventList, JsonMapper objectMapper)
      throws JsonProcessingException {

    List<String> chunks = new ArrayList<>();
    List<ReportedIUVEventModel> tempBatch = new ArrayList<>();
    final int MAX_CHUNK_SIZE_BYTES = 900 * 1024; // 900 KB for security

    StringBuilder currentJsonBatch = new StringBuilder();
    AtomicInteger currentBatchSize = new AtomicInteger(0);

    for (ReportedIUVEventModel event : eventList) {
      tempBatch.add(event);
      String eventJson = objectMapper.writeValueAsString(event);
      int eventSize = eventJson.getBytes(StandardCharsets.UTF_8).length;

      if (currentBatchSize.addAndGet(eventSize) > MAX_CHUNK_SIZE_BYTES) {
        // If the limit is exceed, add the current batch and reset it
        chunks.add(currentJsonBatch.toString());
        currentJsonBatch.setLength(0); // Reset the StringBuilder
        currentBatchSize.set(0); // Reset the batch size
        tempBatch.clear(); // Clear the current batch
        tempBatch.add(event); // Start with the current event
        currentJsonBatch.append(objectMapper.writeValueAsString(tempBatch));
        currentBatchSize.addAndGet(eventSize);
      } else {
        // Add the event to the current batch
        currentJsonBatch.append(eventJson);
      }
    }

    // Add remaining items
    if (currentBatchSize.get() > 0) {
      chunks.add(currentJsonBatch.toString());
    }

    return chunks;
  }

  /** Send a message to the Event Hub */
  private boolean sendEventToHub(
      String jsonPayload,
      EventHubProducerClient eventHubClient,
      FlussoRendicontazione flusso,
      ExecutionContext context) {
    EventData eventData = new EventData(jsonPayload);
    eventData
        .getProperties()
        .put(SERVICE_IDENTIFIER, flusso.getMetadata().getOrDefault(SERVICE_IDENTIFIER, "NA"));

    EventDataBatch eventBatch = eventHubClient.createBatch();
    if (!eventBatch.tryAdd(eventData)) {
      context
          .getLogger()
          .warning(
              () ->
                  String.format(
                      "Failed to add event to batch for flow ID: %s",
                      flusso.getIdentificativoFlusso()));
      return false;
    }

    try {
      eventHubClient.send(eventBatch);
      return true;
    } catch (Exception e) {
      context
          .getLogger()
          .warning(
              () ->
                  String.format(
                      "Failed to add event to batch for flow ID: %s. Details: %s",
                      flusso.getIdentificativoFlusso(), e.getMessage()));
      return false;
    }
  }
}
