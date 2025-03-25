package it.gov.pagopa.fdr.to.eventhub.util;

import com.azure.core.amqp.AmqpRetryMode;
import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microsoft.azure.functions.ExecutionContext;
import it.gov.pagopa.fdr.to.eventhub.exception.EventHubException;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlSAXParser;
import it.gov.pagopa.fdr.to.eventhub.wrapper.BlobServiceClientWrapper;
import it.gov.pagopa.fdr.to.eventhub.wrapper.BlobServiceClientWrapperImpl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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

  public static boolean processXmlBlobAndSendToEventHub(
      final EventHubProducerClient eventHubClientFlowTx,
      final EventHubProducerClient eventHubClientReportedIUV,
      FlussoRendicontazione flussoRendicontazione,
      ExecutionContext context,
      boolean sendFlowEvent,
      boolean sendPaymentEvents) {
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
      List<String> reportedIUVEventJsonChunks = new LinkedList<>();
      for (ReportedIUVEventModel eventModel : reportedIUVEventList) {
        reportedIUVEventJsonChunks.add(objectMapper.writeValueAsString(eventModel));
      }

      boolean flowEventSent = true;
      if (sendFlowEvent) {
        flowEventSent =
            sendEventToHub(flowEventJson, eventHubClientFlowTx, flussoRendicontazione, context);
      } else {
        context.getLogger().info(() -> "Skipping sending flow event to EventHub");
      }

      boolean allEventChunksSent = true;
      if (sendPaymentEvents) {
        allEventChunksSent = sendEventBatchToHub(reportedIUVEventJsonChunks,
            eventHubClientReportedIUV, flussoRendicontazione, context);
      } else {
        context.getLogger().info(() -> "Skipping sending payments events to EventHub");
      }

      return flowEventSent && allEventChunksSent;

    } catch (Exception e) {
      // Log the exception with context
      String errorMessage =
          String.format(
              "[%s] Error processing or sending data to event hub: %s. Details: %s",
              ErrorCodes.COMMON_E2,
              flussoRendicontazione.getIdentificativoFlusso(),
              e.getMessage());
      context.getLogger().severe(() -> errorMessage);

      return false;
    }
  }

  /**
   * Send a message to the Event Hub
   */
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
          .severe(
              () ->
                  String.format(
                      "[%s] Failed to add event to batch for flow ID: %s. Details: %s",
                      ErrorCodes.COMMON_E1, flusso.getIdentificativoFlusso(), e.getMessage()));
      return false;
    }
  }


  /**
   * Send a batch of messages to the Event Hub
   */
  private boolean sendEventBatchToHub(
      List<String> jsonPayloads,
      EventHubProducerClient eventHubClient,
      FlussoRendicontazione flusso,
      ExecutionContext context) {

    try {

      // Creating an empty event batch
      EventDataBatch evhEventBatch = eventHubClient.createBatch();
      int batchMaxSize = evhEventBatch.getMaxSizeInBytes();
      context.getLogger()
          .fine(() ->
              String.format("Defining batches with maximum dimension of [%s] bytes.",
                  batchMaxSize));

      for (String jsonPayload : jsonPayloads) {

        // Generating event data from single payload
        EventData eventData = new EventData(jsonPayload);
        eventData
            .getProperties()
            .put(SERVICE_IDENTIFIER, flusso.getMetadata().getOrDefault(SERVICE_IDENTIFIER, "NA"));

        // Try to add the event from the array to the batch
        if (!evhEventBatch.tryAdd(eventData)) {

          // If the batch is full, send it and then create a new batch
          eventHubClient.send(evhEventBatch);
          evhEventBatch = eventHubClient.createBatch();

          // Try to add that event that couldn't fit before.
          if (!evhEventBatch.tryAdd(eventData)) {
            throw new EventHubException(
                "Event is too large for an empty batch. Max size: [" + batchMaxSize + "].");
          }
        }
      }
      // send the last batch of remaining events
      if (evhEventBatch.getCount() > 0) {
        eventHubClient.send(evhEventBatch);
      }

      return true;

    } catch (Exception e) {
      context
          .getLogger()
          .severe(
              () ->
                  String.format(
                      "[%s] Failed to add event to batch for flow ID: %s. Details: %s",
                      ErrorCodes.COMMON_E1, flusso.getIdentificativoFlusso(), e.getMessage()));
      return false;
    }
  }
}
