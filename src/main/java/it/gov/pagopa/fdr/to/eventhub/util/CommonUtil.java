package it.gov.pagopa.fdr.to.eventhub.util;

import com.azure.core.amqp.AmqpRetryMode;
import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microsoft.azure.functions.ExecutionContext;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlSAXParser;
import it.gov.pagopa.fdr.to.eventhub.wrapper.BlobServiceClientWrapper;
import it.gov.pagopa.fdr.to.eventhub.wrapper.BlobServiceClientWrapperImpl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import lombok.Setter;
import lombok.experimental.UtilityClass;
import org.xml.sax.SAXException;

@UtilityClass
public class CommonUtil {

  public static final String LOG_DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

  private static final String SERVICE_IDENTIFIER = "serviceIdentifier";

  @Setter
  private BlobServiceClientWrapper blobServiceClientWrapper = new BlobServiceClientWrapperImpl();

  public static EventHubProducerClient createEventHubClient(
      String connectionString, String eventHubName) {
    return new EventHubClientBuilder()
        .connectionString(connectionString, eventHubName)
        .retryOptions(
            new AmqpRetryOptions()
                .setMaxRetries(3)
                .setDelay(Duration.ofSeconds(2))
                .setMode(AmqpRetryMode.EXPONENTIAL))
        .buildProducerClient();
  }

  public static boolean validateBlobMetadata(Map<String, String> blobMetadata) {
    if (blobMetadata == null
        || blobMetadata.isEmpty()
        || !blobMetadata.containsKey("sessionId")
        || !blobMetadata.containsKey("insertedTimestamp")) {
      throw new IllegalArgumentException(
          "Invalid blob metadata: sessionId or insertedTimestamp is missing.");
    }
    return !("false".equalsIgnoreCase(blobMetadata.get("elaborate")));
  }

  public static boolean isGzip(byte[] content) {
    if (content == null || content.length == 0) {
      throw new IllegalArgumentException("Invalid input data for decompression: empty file");
    }
    return content.length > 2 && content[0] == (byte) 0x1F && content[1] == (byte) 0x8B;
  }

  public static InputStream decompressGzip(byte[] compressedContent) throws IOException {
    return new GZIPInputStream(new ByteArrayInputStream(compressedContent));
  }

  public static FlussoRendicontazione parseXml(InputStream xmlStream)
      throws ParserConfigurationException, SAXException, IOException {
    return FDR1XmlSAXParser.parseXmlStream(xmlStream);
  }

  public static BlobFileData getBlobFile(
      String storageEnvVar, String containerName, String blobName, ExecutionContext context) {
    try {
      BlobContainerClient containerClient =
          blobServiceClientWrapper.getBlobContainerClient(storageEnvVar, containerName);
      BlobClient blobClient = containerClient.getBlobClient(blobName);

      if (Boolean.FALSE.equals(blobClient.exists())) {
        context.getLogger().severe(() -> "Blob not found: " + blobName);
        return null;
      }

      Map<String, String> metadata = blobClient.getProperties().getMetadata();
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      blobClient.downloadStream(outputStream);

      return new BlobFileData(outputStream.toByteArray(), metadata);

    } catch (Exception e) {
      context.getLogger().severe("Error accessing blob: " + e.getMessage());
      return null;
    }
  }

  // public static BlobContainerClient getBlobContainerClient(String
  // storageEnvVar,
  // String container) {

  // return new BlobServiceClientWrapperImpl()
  // .getBlobContainerClient(storageEnvVar, container);
  // }

  public static boolean processXmlBlobAndSendToEventHub(
      final EventHubProducerClient eventHubClientFlowTx,
      final EventHubProducerClient eventHubClientReportedIUV,
      FlussoRendicontazione flussoRendicontazione,
      ExecutionContext context) {
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
