package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecoveryPaymentEventPublisherTest {

  private static final String SESSION_ID =
      "a92570e7-d5f5-49da-bb8f-0226e2d95ac7";

  private static final Runnable OWNERSHIP_HELD = () -> {
    // No-op guard: this test retains ownership.
  };

  private final EventHubProducerClient producer =
      mock(EventHubProducerClient.class);

  private final RecoveryPaymentEventPublisher publisher =
      new RecoveryPaymentEventPublisher(producer);

  @Test
  void shouldSendOriginalEventJsonAndCloseStream() throws Exception {
    var batch = batch(10);
    var closed = new AtomicBoolean();

    when(producer.createBatch()).thenReturn(batch);

    var result =
        publisher.publish(
            Stream.of(event()).onClose(() -> closed.set(true)),
            OWNERSHIP_HELD);

    assertEquals(
        new RecoveryPaymentEventPublisher.PublishResult(1L, 1L),
        result);

    var captured = ArgumentCaptor.forClass(EventData.class);
    verify(batch).tryAdd(captured.capture());
    verify(producer).send(batch);

    JsonNode json =
        new ObjectMapper().readTree(
            captured.getValue().getBodyAsString());

    assertEquals("IUV-1", json.path("IUV").asText());
    assertEquals("IUR-1", json.path("IUR").asText());
    assertEquals("recovery-flow", json.path("ID_FLUSSO").asText());
    assertEquals(SESSION_ID, json.path("UNIQUE_ID").asText());
    assertEquals("88888888888", json.path("PSP").asText());
    assertEquals("15376371009", json.path("ID_DOMINIO").asText());

    assertEquals(
        "2026-10-05T00:00:00",
        json.path("DATA_ESITO_SINGOLO_PAGAMENTO").asText());

    assertEquals(
        "2026-10-05T12:50:26.001454",
        json.path("DATA_ORA_FLUSSO").asText());

    assertEquals(
        "2026-10-05T12:51:00",
        json.path("INSERTED_TIMESTAMP").asText());

    assertTrue(closed.get());
    verify(producer, never()).close();
  }

  @Test
  void shouldSplitEventsAcrossBatches() throws Exception {
    var first = batch(2);
    var second = batch(2);
    var third = batch(2);

    when(producer.createBatch())
        .thenReturn(first, second, third);

    var result =
        publisher.publish(
            Stream.generate(
                RecoveryPaymentEventPublisherTest::event).limit(5),
            OWNERSHIP_HELD);

    assertEquals(
        new RecoveryPaymentEventPublisher.PublishResult(5L, 3L),
        result);

    var order = inOrder(producer);
    order.verify(producer).send(first);
    order.verify(producer).send(second);
    order.verify(producer).send(third);
  }

  @Test
  void shouldNotCreateOrSendBatchForEmptyStream() throws Exception {
    var result =
        publisher.publish(Stream.empty(), OWNERSHIP_HELD);

    assertEquals(
        new RecoveryPaymentEventPublisher.PublishResult(0L, 0L),
        result);

    verifyNoInteractions(producer);
  }

  @Test
  void shouldRejectEventTooLargeForEmptyBatch() {
    var batch = batch(0);
    var closed = new AtomicBoolean();

    when(producer.createBatch()).thenReturn(batch);

    Stream<ReportedIUVEventModel> events =
        Stream.of(event()).onClose(() -> closed.set(true));

    assertThrows(
        IllegalArgumentException.class,
        () -> publisher.publish(events, OWNERSHIP_HELD));

    verify(producer, never()).send(any(EventDataBatch.class));
    assertTrue(closed.get());
  }

  @Test
  void shouldPropagateFailureAfterEarlierBatchWasSent() {
    var first = batch(1);
    var second = batch(1);
    var failure = new IllegalStateException("Send failed");
    var closed = new AtomicBoolean();

    when(producer.createBatch()).thenReturn(first, second);
    doThrow(failure).when(producer).send(second);

    Stream<ReportedIUVEventModel> events =
        Stream.of(event(), event())
            .onClose(() -> closed.set(true));

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> publisher.publish(events, OWNERSHIP_HELD));

    assertSame(failure, actual);

    var order = inOrder(producer);
    order.verify(producer).send(first);
    order.verify(producer).send(second);

    assertTrue(closed.get());
  }

  @Test
  void shouldStopBeforeAccessingProducerWhenOwnershipIsLost() {
    var failure = new IllegalStateException("Ownership lost");
    var closed = new AtomicBoolean();

    Runnable ownership = () -> {
      throw failure;
    };

    Stream<ReportedIUVEventModel> events =
        Stream.of(event()).onClose(() -> closed.set(true));

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> publisher.publish(events, ownership));

    assertSame(failure, actual);
    verifyNoInteractions(producer);
    assertTrue(closed.get());
  }

  @Test
  void shouldPropagateOwnershipLossAfterSend() {
    var batch = batch(10);
    var held = new AtomicBoolean(true);
    var closed = new AtomicBoolean();

    when(producer.createBatch()).thenReturn(batch);

    doAnswer(invocation -> {
      held.set(false);
      return null;
    }).when(producer).send(batch);

    Runnable ownership = () -> {
      if (!held.get()) {
        throw new IllegalStateException("Ownership lost");
      }
    };

    Stream<ReportedIUVEventModel> events =
        Stream.of(event()).onClose(() -> closed.set(true));

    assertThrows(
        IllegalStateException.class,
        () -> publisher.publish(events, ownership));

    verify(producer).send(batch);
    assertTrue(closed.get());
  }

  @Test
  void shouldCloseStreamWhenBatchCreationFails() {
    var failure = new IllegalStateException("Cannot create batch");
    var closed = new AtomicBoolean();

    when(producer.createBatch()).thenThrow(failure);

    Stream<ReportedIUVEventModel> events =
        Stream.of(event()).onClose(() -> closed.set(true));

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> publisher.publish(events, OWNERSHIP_HELD));

    assertSame(failure, actual);
    assertTrue(closed.get());
  }

  @Test
  void shouldRejectNullEventWithoutSending() {
    Stream<ReportedIUVEventModel> events =
        Stream.of((ReportedIUVEventModel) null);

    assertThrows(
        IllegalArgumentException.class,
        () -> publisher.publish(events, OWNERSHIP_HELD));

    verifyNoInteractions(producer);
  }

  private static EventDataBatch batch(int capacity) {
    var batch = mock(EventDataBatch.class);
    var count = new AtomicInteger();

    when(batch.tryAdd(any(EventData.class)))
        .thenAnswer(invocation -> {
          if (count.get() >= capacity) {
            return false;
          }

          count.incrementAndGet();
          return true;
        });

    when(batch.getCount())
        .thenAnswer(invocation -> count.get());

    return batch;
  }

  private static ReportedIUVEventModel event() {
    return ReportedIUVEventModel.builder()
        .iuv("IUV-1")
        .iur("IUR-1")
        .amount(new BigDecimal("12.34"))
        .outcomeCode(0)
        .idsp("2")
        .singlePaymentOutcomeDate(
            LocalDateTime.parse("2026-10-05T00:00:00"))
        .flowId("recovery-flow")
        .flowDateTime(
            LocalDateTime.parse("2026-10-05T12:50:26.001454"))
        .domainId("15376371009")
        .psp("88888888888")
        .intPsp("broker")
        .uniqueId(SESSION_ID)
        .insertedTimestamp(
            LocalDateTime.parse("2026-10-05T12:51:00"))
        .build();
  }
}