package it.gov.pagopa.fdr.to.eventhub;

import com.azure.core.amqp.AmqpRetryMode;
import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.eventhubs.EventData;
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
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

public class BlobProcessingFunction {

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
                    .setMaxRetries(3) // Maximum number of attempts
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
  public void processFDR1BlobFiles(
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
          .info(
              () ->
                  String.format(
                      "Skipping processing for Blob container: %s, name: %s, size: %d bytes",
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
                    "Triggered for Blob container: %s, name: %s, size: %d bytes",
                    fdr1Container, blobName, content.length));

    try {
      String decompressedContent =
          isValidGzipFile ? decompressGzip(content) : new String(content, StandardCharsets.UTF_8);
      FlussoRendicontazione flusso = parseXml(decompressedContent);
      flusso.setMetadata(blobMetadata);
      processXmlBlobAndSendToEventHub(flusso, context);

    } catch (Exception e) {
      context
          .getLogger()
          .severe(
              () ->
                  String.format(
                      "Error processing Blob '%s/%s': %s",
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

    // checks for the presence of the necessary metadata
    if (!validateBlobMetadata(blobMetadata)) {
      context
          .getLogger()
          .info(
              () ->
                  String.format(
                      "Skipping processing for Blob container: %s, name: %s, size: %d bytes",
                      fdr3Container, blobName, content.length));
      return; // Skip execution
    }

    // verify that the file is present and that it is a compressed file
    boolean isValidGzipFile = isGzip(content);

    context
        .getLogger()
        .info(
            () ->
                String.format(
                    "Triggered for Blob container: %s, name: %s, size: %d bytes",
                    fdr3Container, blobName, content.length));

    try {
      String decompressedContent =
          isValidGzipFile ? decompressGzip(content) : new String(content, StandardCharsets.UTF_8);
      // TODO future needs
    } catch (Exception e) {
      context
          .getLogger()
          .severe(
              () ->
                  String.format(
                      "Error processing Blob '%s/%s': %s",
                      fdr3Container, blobName, e.getMessage()));
    }
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

  private String decompressGzip(byte[] compressedContent) throws IOException {
    try (GZIPInputStream gzipInputStream =
            new GZIPInputStream(new ByteArrayInputStream(compressedContent));
        InputStreamReader reader = new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8);
        BufferedReader bufferedReader = new BufferedReader(reader)) {

      StringBuilder decompressedData = new StringBuilder();
      String line;
      while ((line = bufferedReader.readLine()) != null) {
        decompressedData.append(line).append("\n");
      }
      return decompressedData.toString();
    }
  }

  private FlussoRendicontazione parseXml(String xmlContent)
      throws ParserConfigurationException, SAXException, IOException {
    // using a SAX parser for performance reason
    return FDR1XmlSAXParser.parseXmlContent(xmlContent);
  }

  private void processXmlBlobAndSendToEventHub(
      FlussoRendicontazione flussoRendicontazione, ExecutionContext context)
      throws EventHubException {
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

      sendEventToHub(flowEventJson, eventHubClientFlowTx, flussoRendicontazione);

      for (String chunk : reportedIUVEventJsonChunks) {
        context
            .getLogger()
            .info(
                () ->
                    "Event size: "
                        + (chunk.getBytes(StandardCharsets.UTF_8).length / 1024)
                        + " KB");
        sendEventToHub(chunk, eventHubClientReportedIUV, flussoRendicontazione);
      }

    } catch (Exception e) {
      // Log the exception with context
      String errorMessage =
          String.format(
              "Error processing or sending data to event hub: %s. Details: %s",
              flussoRendicontazione.getIdentificativoFlusso(), e.getMessage());
      context.getLogger().severe(() -> errorMessage);

      // Rethrow custom exception with context
      throw new EventHubException(errorMessage, e);
    }
  }

  /** Divides the event list into smaller JSON blocks (to avoid exceeding 1MB) */
  private List<String> splitIntoChunks(
      List<ReportedIUVEventModel> eventList, JsonMapper objectMapper)
      throws JsonProcessingException {
    List<String> chunks = new ArrayList<>();
    List<ReportedIUVEventModel> tempBatch = new ArrayList<>();
    final int MAX_CHUNK_SIZE_BYTES = 900 * 1024; // 900 KB for security

    for (ReportedIUVEventModel event : eventList) {
      tempBatch.add(event);
      String jsonChunk = objectMapper.writeValueAsString(tempBatch);
      int jsonChunkSize = jsonChunk.getBytes(StandardCharsets.UTF_8).length;

      if (jsonChunkSize > MAX_CHUNK_SIZE_BYTES) {
        // Remove the last element to stay within the limit
        tempBatch.remove(tempBatch.size() - 1);
        chunks.add(objectMapper.writeValueAsString(tempBatch));
        tempBatch.clear();
        tempBatch.add(event); // Resume with the item removed
      }
    }

    // Add any remaining items
    if (!tempBatch.isEmpty()) {
      chunks.add(objectMapper.writeValueAsString(tempBatch));
    }

    return chunks;
  }

  /** Send a message to the Event Hub */
  private void sendEventToHub(
      String jsonPayload, EventHubProducerClient eventHubClient, FlussoRendicontazione flusso) {
    EventData eventData = new EventData(jsonPayload);
    eventData
        .getProperties()
        .put(
            SERVICE_IDENTIFIER,
            flusso.getMetadata().get(SERVICE_IDENTIFIER) != null
                ? flusso.getMetadata().get(SERVICE_IDENTIFIER)
                : "NA");

    eventHubClient.send(new ArrayList<>(Arrays.asList(eventData)));
  }
}
