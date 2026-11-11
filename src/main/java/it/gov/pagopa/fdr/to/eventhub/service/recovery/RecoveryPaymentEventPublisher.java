package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import java.util.Objects;
import java.util.stream.Stream;

public final class RecoveryPaymentEventPublisher {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final EventHubProducerClient producer;

  public record PublishResult(
      long acknowledgedEvents,
      long acknowledgedBatches) {
  }

  public RecoveryPaymentEventPublisher(
      EventHubProducerClient producer) {

    this.producer =
        Objects.requireNonNull(producer, "producer is required");
  }

  /**
   * Consumes and closes the supplied event stream.
   *
   * Acknowledgement refers to Event Hub send completion, not ADX ingestion.
   * The producer is externally owned and is not closed here.
   *
   * Exceptions propagate. Earlier batches may already have been accepted.
   */
  public PublishResult publish(
      Stream<ReportedIUVEventModel> events,
      Runnable requireOwnership) throws JsonProcessingException {

    Objects.requireNonNull(events, "events is required");

    try (events) {
      Objects.requireNonNull(
          requireOwnership, "requireOwnership is required");

      var writer = new BatchWriter(requireOwnership);
      var iterator = events.iterator();

      while (true) {
        requireOwnership.run();

        if (!iterator.hasNext()) {
          break;
        }

        var event = iterator.next();

        if (event == null) {
          throw new IllegalArgumentException(
              "Payment event must not be null");
        }

        String json = MAPPER.writeValueAsString(event);

        writer.add(new EventData(json));
      }

      writer.flush();

      return writer.result();
    }
  }

  private final class BatchWriter {

    private final Runnable requireOwnership;

    private EventDataBatch batch;
    private long acknowledgedEvents;
    private long acknowledgedBatches;

    private BatchWriter(Runnable requireOwnership) {
      this.requireOwnership = requireOwnership;
    }

    private void add(EventData event) {
      if (batch == null) {
        batch = createBatch();
      }

      if (batch.tryAdd(event)) {
        return;
      }

      if (batch.getCount() == 0) {
        throw oversizedEvent();
      }

      flush();

      batch = createBatch();

      if (!batch.tryAdd(event)) {
        throw oversizedEvent();
      }
    }

    private EventDataBatch createBatch() {
      requireOwnership.run();

      EventDataBatch created = producer.createBatch();

      requireOwnership.run();

      return created;
    }

    private void flush() {
      if (batch == null || batch.getCount() == 0) {
        return;
      }

      int count = batch.getCount();

      requireOwnership.run();

      producer.send(batch);

      acknowledgedEvents =
          Math.addExact(acknowledgedEvents, count);

      acknowledgedBatches =
          Math.addExact(acknowledgedBatches, 1L);

      batch = null;

      requireOwnership.run();
    }

    private PublishResult result() {
      return new PublishResult(
          acknowledgedEvents, acknowledgedBatches);
    }

    private IllegalArgumentException oversizedEvent() {
      return new IllegalArgumentException(
          "Payment event exceeds the maximum Event Hub batch size");
    }
  }
}