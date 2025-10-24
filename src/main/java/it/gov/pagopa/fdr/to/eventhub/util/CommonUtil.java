package it.gov.pagopa.fdr.to.eventhub.util;

import com.azure.core.amqp.AmqpRetryMode;
import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import it.gov.pagopa.fdr.to.eventhub.exception.EventHubException;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlowMapper;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlStAXParser;
import it.gov.pagopa.fdr.to.eventhub.wrapper.BlobServiceClientWrapper;
import it.gov.pagopa.fdr.to.eventhub.wrapper.BlobServiceClientWrapperImpl;

import java.io.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import lombok.Setter;
import lombok.experimental.UtilityClass;
import org.slf4j.Logger;
import org.xml.sax.SAXException;

import javax.xml.stream.XMLStreamException;

import static it.gov.pagopa.fdr.to.eventhub.exception.AlertAppException.getExceptionDetails;

@UtilityClass
public class CommonUtil {

  public static final String LOG_DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
  private static final String SERVICE_IDENTIFIER = "serviceIdentifier";
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String APPLICATION_JSON = "application/json";

  @Setter
  private BlobServiceClientWrapper blobServiceClientWrapper = new BlobServiceClientWrapperImpl();

  public static boolean getBooleanQueryParam(
      HttpRequestMessage<Optional<String>> request, String paramName, boolean defaultValue) {
    return Boolean.parseBoolean(
        request.getQueryParameters().getOrDefault(paramName, String.valueOf(defaultValue)));
  }

  public static String getJsonField(JsonNode node, String fieldName) {
    return Optional.ofNullable(node.get(fieldName)).map(JsonNode::asText).orElse(null);
  }

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
    return blobMetadata != null
        && !blobMetadata.isEmpty()
        && blobMetadata.containsKey("sessionId")
        && blobMetadata.containsKey("insertedTimestamp")
        && (blobMetadata.get("elaborate") == null
            || !"false".equalsIgnoreCase(blobMetadata.get("elaborate")));
  }

  public static Pair<InputStream, Boolean> isGzipStream(InputStream input) throws IOException {
    // wrappa stream in a PushbackInputStream with a buffer of 2 bytes
    PushbackInputStream pbStream = new PushbackInputStream(input, 2);
    byte[] header = new byte[2];
    int bytesRead = 0;
    boolean isGzip = false;
    try {
      // read the first two bytes
      bytesRead = pbStream.read(header);
      if (bytesRead == 2) {
        // check for GZIP magic numbers
        final byte GZIP_MAGIC_1 = (byte) 0x1f;
        final byte GZIP_MAGIC_2 = (byte) 0x8b;
        isGzip = (header[0] == GZIP_MAGIC_1 && header[1] == GZIP_MAGIC_2);
      }
    } finally {
      // reset the stream to include the bytes read
      if (bytesRead > 0) {
        pbStream.unread(header, 0, bytesRead);
      }
    }

    // return the stream and the result
    return new Pair<>(pbStream, isGzip);
  }

  public static class Pair<K, V> {
    public final K key;
    public final V value;
    public Pair(K key, V value) {
      this.key = key;
      this.value = value;
    }
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

  public static Flow parseJSON(InputStream jsonStream) throws IOException {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .readValue(jsonStream, Flow.class);
  }

  public static BlobFileData getBlobFile(
      String storageEnvVar, String containerName, String blobName, Logger logger) {
    try {
      BlobContainerClient containerClient =
          blobServiceClientWrapper.getBlobContainerClient(storageEnvVar, containerName);
      BlobClient blobClient = containerClient.getBlobClient(blobName);

      if (Boolean.FALSE.equals(blobClient.exists())) {
        logger.error("Blob not found: {}", blobName);
        return null;
      }

      Map<String, String> metadata = blobClient.getProperties().getMetadata();
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      blobClient.downloadStream(outputStream);

      return BlobFileData.builder()
          .fileName(blobName)
          .fileContent(outputStream.toByteArray())
          .metadata(metadata)
          .build();

    } catch (Exception e) {
      logger.error("Error accessing blob", e);
      return null;
    }
  }

  public static List<BlobFileData> getBlobFilesInDateRange(
      String storageEnvVar,
      String containerName,
      String prefixFormat, // Prefix format e.g.: "yyyy-MM-dd"
      LocalDate from,
      LocalDate to,
      Logger logger) {
    try {
      BlobContainerClient containerClient =
          blobServiceClientWrapper.getBlobContainerClient(storageEnvVar, containerName);

      List<BlobFileData> blobFiles = new ArrayList<>();

      // Iterates over the dates in the range and searches for blobs for each generated prefix
      LocalDate currentDate = from;
      while (currentDate.isBefore(to) || currentDate.isEqual(to)) {
        String datePrefix = currentDate.format(DateTimeFormatter.ofPattern(prefixFormat));
        ListBlobsOptions options = new ListBlobsOptions().setPrefix(datePrefix);

        for (BlobItem blobItem : containerClient.listBlobs(options, null)) {
          BlobClient blobClient = containerClient.getBlobClient(blobItem.getName());

          try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            blobClient.downloadStream(outputStream);
            Map<String, String> metadata =
                Optional.ofNullable(blobClient.getProperties().getMetadata())
                    .orElse(new HashMap<>());

            List<String> unprocessableFileDetail = new ArrayList<>();
            if (!validateBlobMetadata(metadata)) {
              unprocessableFileDetail.add(
                  String.format(
                      "Skipped file %s in container %s due to missing required metadata or because"
                          + " it is in an unprocessable state",
                      blobItem.getName(), containerName));
            }

            BlobFileData blobFileData =
                BlobFileData.builder()
                    .fileName(blobItem.getName())
                    .fileContent(outputStream.toByteArray())
                    .metadata(metadata)
                    .unprocessableFileDetail(unprocessableFileDetail)
                    .build();
            blobFiles.add(blobFileData);
          }
        }

        // Skip to next date in range
        currentDate = currentDate.plusDays(1);
      }
      return blobFiles;
    } catch (Exception e) {
      logger.error("Error accessing blob", e);
      return Collections.emptyList();
    }
  }

  public static boolean processXmlBlobAndSendToEventHub(
      final EventHubProducerClient eventHubClientFlowTx,
      final EventHubProducerClient eventHubClientReportedIUV,
      FlussoRendicontazione flussoRendicontazione,
      Logger logger,
      boolean sendFlowEvent,
      boolean sendPaymentEvents) {

    try {
      // Convert FlussoRendicontazione to event models
      FlowTxEventModel flowEvent =
          FlussoRendicontazioneMapper.toFlowTxEventList(flussoRendicontazione);
//      List<ReportedIUVEventModel> reportedIUVEventList =
//          FlussoRendicontazioneMapper.toReportedIUVEventList(flussoRendicontazione);
//      return prepareAndSendEventsToEventHub(
//          eventHubClientFlowTx,
//          eventHubClientReportedIUV,
//          flowEvent,
//          reportedIUVEventList,
//          flussoRendicontazione.getIdentificativoFlusso(),
//          flussoRendicontazione.getMetadata(),
//          logger,
//          sendFlowEvent,
//          sendPaymentEvents);
      Stream<ReportedIUVEventModel> reportedIUVEventStream =
              FlussoRendicontazioneMapper.toReportedIUVEventStream(flussoRendicontazione);

      return prepareAndSendEventsToEventHubFC(
              eventHubClientFlowTx,
              eventHubClientReportedIUV,
              flowEvent,
              reportedIUVEventStream,
              flussoRendicontazione.getIdentificativoFlusso(),
              flussoRendicontazione.getMetadata(),
              logger,
              sendFlowEvent,
              sendPaymentEvents);

    } catch (Exception e) {
      logger.error(
          "[{}] Error processing or sending data to event hub: {}",
          ErrorCodes.COMMON_E2,
          flussoRendicontazione.getIdentificativoFlusso(),
          e);
      return false;
    }
  }

  public static boolean processJsonBlobAndSendToEventHub(
      EventHubProducerClient eventHubClientFlowTx,
      EventHubProducerClient eventHubClientReportedIUV,
      Flow flow,
      Logger logger,
      boolean sendFlowEvent,
      boolean sendPaymentEvents) {

    try {
      // Convert FlussoRendicontazione to event models
      FlowTxEventModel flowEvent = FlowMapper.toFlowTxEventList(flow);
      List<ReportedIUVEventModel> reportedIUVEventList = FlowMapper.toReportedIUVEventList(flow);

      return prepareAndSendEventsToEventHub(
          eventHubClientFlowTx,
          eventHubClientReportedIUV,
          flowEvent,
          reportedIUVEventList,
          flow.getFdr(),
          flow.getMetadata(),
          logger,
          sendFlowEvent,
          sendPaymentEvents);

    } catch (Exception e) {
      logger.error(
          "[{}] Error processing or sending data to event hub: {}.",
          ErrorCodes.COMMON_E2,
          flow.getFdr(),
          e);
      return false;
    }
  }

  public static HttpResponseMessage ok(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.OK, message);
  }

  public static HttpResponseMessage multiStatus(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.MULTI_STATUS, message);
  }

  public static HttpResponseMessage badRequest(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.BAD_REQUEST, message);
  }

  public static HttpResponseMessage notFound(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.NOT_FOUND, message);
  }

  public static HttpResponseMessage unprocessableEntity(
      HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  public static HttpResponseMessage serviceUnavailable(
      HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.SERVICE_UNAVAILABLE, message);
  }

  public static HttpResponseMessage serverError(HttpRequestMessage<?> request, String message) {
    return response(request, HttpStatus.INTERNAL_SERVER_ERROR, message);
  }

  private HttpResponseMessage response(
      HttpRequestMessage<?> request, HttpStatus status, String message) {
    String formattedMessage = "";
    if (message != null) {
      formattedMessage = message.endsWith("\"") || message.endsWith("]") ? message : message + "\"";
    }
    return request
        .createResponseBuilder(status)
        .header(CONTENT_TYPE, APPLICATION_JSON)
        .body("{\"message\": \"" + formattedMessage + "}")
        .build();
  }

  private static boolean prepareAndSendEventsToEventHubFC(
          EventHubProducerClient eventHubClientFlowTx,
          EventHubProducerClient eventHubClientReportedIUV,
          FlowTxEventModel flowEvent,
          Stream<ReportedIUVEventModel> reportedIUVEventStream,
          String flowName,
          Map<String, String> metadata,
          Logger logger,
          boolean sendFlowEvent,
          boolean sendPaymentEvents)
          throws JsonProcessingException {

    // TODO objectMapper can be shared and reused ? (in a function?)
    JsonMapper objectMapper =
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    .build();

    // flow event
    String flowEventJson = objectMapper.writeValueAsString(flowEvent);
    String serviceIdentifier = metadata.getOrDefault(SERVICE_IDENTIFIER, "NA");

    boolean flowEventSent = true;
    if (sendFlowEvent) {
      flowEventSent = sendEventToHub(flowEventJson, eventHubClientFlowTx, flowName, serviceIdentifier, logger);
    } else {
      logger.info("Skipping sending flow event to EventHub");
    }

    // payment events
    boolean allPaymentEventsSent = true;
    if (sendPaymentEvents) {
      logger.info("Starting to send payment events in batches...");

      // evaluate stream instead of list to avoid OOM for large flows

      try (Stream<ReportedIUVEventModel> stream = reportedIUVEventStream) {
        // create batch using EventDataBatch: a class for aggregating EventData into a single, size-limited, batch.
        // It is treated as a single message when sent to the Azure Event Hubs service.
        // EventDataBatch is recommended in scenarios requiring high throughput for publishing events.

        EventDataBatch currentBatch = eventHubClientReportedIUV.createBatch();
        Iterator<ReportedIUVEventModel> iterator = stream.iterator();

        while (iterator.hasNext()) {
          ReportedIUVEventModel eventModel = iterator.next();

          // serialize only one event at a time
          String eventJson = objectMapper.writeValueAsString(eventModel);
          EventData eventData = new EventData(eventJson);

          // try to add the event to the current batch
          // if it returns false, the batch is full (or the event is too large),
          // so send the current batch and create a new one
          if (!currentBatch.tryAdd(eventData)) {
            if (currentBatch.getCount() > 0) {
              eventHubClientReportedIUV.send(currentBatch);
              logger.debug("Sent a batch of " + currentBatch.getCount() + " payment events.");
            }

            // create a new batch
            currentBatch = eventHubClientReportedIUV.createBatch();

            // try to add the new event to the new batch
            if (!currentBatch.tryAdd(eventData)) {
              // if the event doesn't fit even in an empty batch, it's too large
              logger.error("Payment event is too large for a single batch. Skipping. IUV: " + eventModel.getIuv());
              allPaymentEventsSent = false;
            }
          }
        }

        // send last batch if it has events
        if (currentBatch.getCount() > 0) {
          eventHubClientReportedIUV.send(currentBatch);
          logger.debug("Sent final batch of " + currentBatch.getCount() + " payment events.");
        }

        logger.debug("Finished sending all payment events.");

      } catch (Exception e) {
        // catch errors during sending or serialization
        logger.error("Error while processing or sending payment events stream: " + e.getMessage());
        allPaymentEventsSent = false;
      }

    } else {
      logger.info("Skipping sending payments events to EventHub");
    }

    return flowEventSent && allPaymentEventsSent;
  }

  private static boolean prepareAndSendEventsToEventHub(
      EventHubProducerClient eventHubClientFlowTx,
      EventHubProducerClient eventHubClientReportedIUV,
      FlowTxEventModel flowEvent,
      List<ReportedIUVEventModel> reportedIUVEventList,
      String flowName,
      Map<String, String> metadata,
      Logger logger,
      boolean sendFlowEvent,
      boolean sendPaymentEvents)
      throws JsonProcessingException {

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

    String serviceIdentifier = metadata.getOrDefault(SERVICE_IDENTIFIER, "NA");

    boolean flowEventSent = true;
    if (sendFlowEvent) {
      flowEventSent =
          sendEventToHub(flowEventJson, eventHubClientFlowTx, flowName, serviceIdentifier, logger);
    } else {
      logger.info("Skipping sending flow event to EventHub");
    }

    boolean allEventChunksSent = true;
    if (sendPaymentEvents) {
      allEventChunksSent =
          sendEventBatchToHub(
              reportedIUVEventJsonChunks,
              eventHubClientReportedIUV,
              flowName,
              serviceIdentifier,
              logger);
    } else {
      logger.info("Skipping sending payments events to EventHub");
    }

    return flowEventSent && allEventChunksSent;
  }

  /** Send a message to the Event Hub */
  private boolean sendEventToHub(
      String jsonPayload,
      EventHubProducerClient eventHubClient,
      String flowName,
      String serviceIdentifier,
      Logger logger) {

    EventData eventData = new EventData(jsonPayload);
    eventData.getProperties().put(SERVICE_IDENTIFIER, serviceIdentifier);

    EventDataBatch eventBatch = eventHubClient.createBatch();
    if (!eventBatch.tryAdd(eventData)) {
      logger.warn("Failed to add event to batch for flow ID: {}", flowName);
      return false;
    }

    try {
      eventHubClient.send(eventBatch);
      return true;
    } catch (Exception e) {
      logger.error(
          "[{}] Failed to add event to batch for flow ID: {}.", ErrorCodes.COMMON_E1, flowName, e);
      return false;
    }
  }

  /** Send a batch of messages to the Event Hub */
  private boolean sendEventBatchToHub(
      List<String> jsonPayloads,
      EventHubProducerClient eventHubClient,
      String flowName,
      String serviceIdentifier,
      Logger logger) {

    try {

      // Creating an empty event batch
      EventDataBatch evhEventBatch = eventHubClient.createBatch();
      int batchMaxSize = evhEventBatch.getMaxSizeInBytes();
      logger.info("Defining batches with maximum dimension of [{}] bytes.", batchMaxSize);

      for (String jsonPayload : jsonPayloads) {

        // Generating event data from single payload
        EventData eventData = new EventData(jsonPayload);
        eventData.getProperties().put(SERVICE_IDENTIFIER, serviceIdentifier);

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
      logger.error(
          "[{}] Failed to add event to batch for flow ID: {}.", ErrorCodes.COMMON_E1, flowName, e);
      return false;
    }
  }
}
