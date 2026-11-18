package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationExecution;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationExecutionRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryPublicationExecution;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryPublicationExecutionServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 5);

  private static final Instant NOW =
      Instant.parse("2026-11-11T12:00:00Z");

  private static final String INITIAL_ETAG = "etag-1";

  private static final Runnable OWNERSHIP_HELD = () -> {
    // No-op guard: this test retains ownership.
  };

  private final RecoveryPublicationExecutionRepository repository =
      mock(RecoveryPublicationExecutionRepository.class);

  private final RecoveryPublicationPresenceService presence =
      mock(RecoveryPublicationPresenceService.class);

  private final RecoveryFdr3PaymentEventReader jsonReader =
      mock(RecoveryFdr3PaymentEventReader.class);

  private final RecoveryFdr1PaymentEventReader xmlReader =
      mock(RecoveryFdr1PaymentEventReader.class);

  private final RecoveryPaymentEventPublisher publisher =
      mock(RecoveryPaymentEventPublisher.class);

  private final AtomicReference<StoredRecoveryPublicationExecution> stored =
      new AtomicReference<>();

  private final AtomicBoolean failSentUpdate = new AtomicBoolean();

  private final RecoveryPublicationExecutionService service =
      new RecoveryPublicationExecutionService(
          repository,
          presence,
          jsonReader,
          xmlReader,
          publisher,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @ParameterizedTest
  @EnumSource(RecoveryPublicationSource.SourceType.class)
  void shouldPersistSendingBeforePublishingAndThenMarkSent(
      RecoveryPublicationSource.SourceType type) throws Exception {

    var item = item(type);
    stubStorage(RecoveryPublicationExecution.pending(item, NOW));
    stubPresence(item, false);

    var closed = new AtomicBoolean();
    stubEvents(
        item,
        Stream.of(event()).onClose(() -> closed.set(true)));

    when(publisher.publish(any(), any()))
        .thenAnswer(invocation -> {
          assertEquals(
              RecoveryPublicationExecution.Status.SENDING,
              stored.get().execution().status());

          Stream<ReportedIUVEventModel> events =
              invocation.getArgument(0);

          try (events) {
            assertEquals(1, events.toList().size());
          }

          return new RecoveryPaymentEventPublisher.PublishResult(1L, 1L);
        });

    var result = service.execute(item, OWNERSHIP_HELD);

    assertEquals(RecoveryPublicationExecution.Status.SENT, result.status());
    assertEquals(result, stored.get().execution());
    assertTrue(closed.get());

    var order = inOrder(repository, publisher);

    order.verify(repository).update(
        argThat(execution ->
            execution.status()
                == RecoveryPublicationExecution.Status.SENDING),
        eq(INITIAL_ETAG));

    order.verify(publisher).publish(any(), any());

    order.verify(repository).update(
        argThat(execution ->
            execution.status()
                == RecoveryPublicationExecution.Status.SENT),
        eq("etag-2"));
  }

  @Test
  void shouldSkipPublicationFoundPresentInAdx() throws Exception {
    var item = item(RecoveryPublicationSource.SourceType.FDR3_JSON);
    stubStorage(RecoveryPublicationExecution.pending(item, NOW));
    stubPresence(item, true);

    var result = service.execute(item, OWNERSHIP_HELD);

    assertEquals(
        RecoveryPublicationExecution.Status.SKIPPED_PRESENT,
        result.status());

    verifyNoInteractions(jsonReader, xmlReader, publisher);
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryPublicationExecution.Status.class,
      names = {"SENT", "SKIPPED_PRESENT", "UNCERTAIN"})
  void shouldNotRepeatTerminalExecution(
      RecoveryPublicationExecution.Status status) throws Exception {

    var item = item(RecoveryPublicationSource.SourceType.FDR3_JSON);
    var pending = RecoveryPublicationExecution.pending(item, NOW);
    var attemptId = UUID.randomUUID();

    var existing = switch (status) {
      case SENT ->
          pending.startSending(attemptId, NOW)
              .markSent(1L, 1L, NOW);
      case SKIPPED_PRESENT ->
          pending.skipAlreadyPresent(NOW);
      case UNCERTAIN ->
          pending.startSending(attemptId, NOW)
              .markUncertain(NOW);
      default -> throw new IllegalArgumentException(
          "Unexpected test status");
    };

    stubStorage(existing);

    assertEquals(existing, service.execute(item, OWNERSHIP_HELD));

    verify(repository, never()).update(any(), anyString());
    verifyNoInteractions(presence, jsonReader, xmlReader, publisher);
  }

  @Test
  void shouldClassifyInterruptedSendingAsUncertain() throws Exception {
    var item = item(RecoveryPublicationSource.SourceType.FDR3_JSON);

    var sending =
        RecoveryPublicationExecution.pending(item, NOW)
            .startSending(UUID.randomUUID(), NOW);

    stubStorage(sending);

    var result = service.execute(item, OWNERSHIP_HELD);

    assertEquals(
        RecoveryPublicationExecution.Status.UNCERTAIN,
        result.status());

    assertEquals(sending.attemptId(), result.attemptId());

    verifyNoInteractions(presence, jsonReader, xmlReader, publisher);
  }

  @Test
  void shouldKeepPendingWhenDownloadFailsBeforeAttempt()
      throws Exception {

    var item = item(RecoveryPublicationSource.SourceType.FDR3_JSON);
    stubStorage(RecoveryPublicationExecution.pending(item, NOW));
    stubPresence(item, false);

    var failure = new IOException("Download failed");

    when(jsonReader.read(DAY, itemSource(item))).thenThrow(failure);

    var actual =
        assertThrows(
            IOException.class,
            () -> service.execute(item, OWNERSHIP_HELD));

    assertSame(failure, actual);

    assertEquals(
        RecoveryPublicationExecution.Status.PENDING,
        stored.get().execution().status());

    verifyNoInteractions(publisher);
  }

  @Test
  void shouldRejectEmptySelectedDayBeforeRecordingAttempt()
      throws Exception {

    var item = item(RecoveryPublicationSource.SourceType.FDR3_JSON);
    stubStorage(RecoveryPublicationExecution.pending(item, NOW));
    stubPresence(item, false);

    var closed = new AtomicBoolean();

    stubEvents(
        item,
        Stream.<ReportedIUVEventModel>empty()
            .onClose(() -> closed.set(true)));

    assertThrows(
        IOException.class,
        () -> service.execute(item, OWNERSHIP_HELD));

    assertEquals(
        RecoveryPublicationExecution.Status.PENDING,
        stored.get().execution().status());

    assertTrue(closed.get());
    verifyNoInteractions(publisher);
  }

  @Test
  void shouldMarkUncertainWhenPublisherFails() throws Exception {
    var item = item(RecoveryPublicationSource.SourceType.FDR3_JSON);
    stubStorage(RecoveryPublicationExecution.pending(item, NOW));
    stubPresence(item, false);
    stubEvents(item, Stream.of(event()));

    var failure = new IllegalStateException("Send failed");

    when(publisher.publish(any(), any())).thenThrow(failure);

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.execute(item, OWNERSHIP_HELD));

    assertSame(failure, actual);

    assertEquals(
        RecoveryPublicationExecution.Status.UNCERTAIN,
        stored.get().execution().status());
  }

  @Test
  void shouldKeepSendingWhenOwnershipIsLostDuringPublication()
      throws Exception {

    var item = item(RecoveryPublicationSource.SourceType.FDR3_JSON);
    stubStorage(RecoveryPublicationExecution.pending(item, NOW));
    stubPresence(item, false);
    stubEvents(item, Stream.of(event()));

    var held = new AtomicBoolean(true);
    var failure = new IllegalStateException("Ownership lost");

    Runnable ownership = () -> {
      if (!held.get()) {
        throw failure;
      }
    };

    when(publisher.publish(any(), any()))
        .thenAnswer(invocation -> {
          held.set(false);
          throw failure;
        });

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.execute(item, ownership));

    assertSame(failure, actual);

    assertEquals(
        RecoveryPublicationExecution.Status.SENDING,
        stored.get().execution().status());
  }

  @Test
  void shouldMarkUncertainWhenFinalStateCannotBeSaved()
      throws Exception {

    var item = item(RecoveryPublicationSource.SourceType.FDR3_JSON);
    stubStorage(RecoveryPublicationExecution.pending(item, NOW));
    stubPresence(item, false);
    stubEvents(item, Stream.of(event()));

    when(publisher.publish(any(), any()))
        .thenReturn(
            new RecoveryPaymentEventPublisher.PublishResult(1L, 1L));

    failSentUpdate.set(true);

    assertThrows(
        IllegalStateException.class,
        () -> service.execute(item, OWNERSHIP_HELD));

    assertEquals(
        RecoveryPublicationExecution.Status.UNCERTAIN,
        stored.get().execution().status());

    verify(publisher).publish(any(), any());
  }

  @Test
  void shouldNotPublishWhenSendingUpdateFails() throws Exception {
    var item = item(RecoveryPublicationSource.SourceType.FDR3_JSON);
    stubStorage(RecoveryPublicationExecution.pending(item, NOW));
    stubPresence(item, false);
    stubEvents(item, Stream.of(event()));

    var failure = new IllegalStateException("Conditional update failed");

    doThrow(failure)
        .when(repository)
        .update(
            argThat(execution ->
                execution.status()
                    == RecoveryPublicationExecution.Status.SENDING),
            eq(INITIAL_ETAG));

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.execute(item, OWNERSHIP_HELD));

    assertSame(failure, actual);
    verifyNoInteractions(publisher);
  }

  private void stubStorage(RecoveryPublicationExecution initial) {
    stored.set(
        new StoredRecoveryPublicationExecution(
            initial, INITIAL_ETAG));

    var version = new AtomicInteger(1);

    when(repository.createOrRead(any()))
        .thenAnswer(invocation -> stored.get());

    when(repository.find(JOB_ID, DAY, 1L))
        .thenAnswer(invocation -> Optional.of(stored.get()));

    doAnswer(invocation -> {
      RecoveryPublicationExecution updated =
          invocation.getArgument(0);

      String etag = invocation.getArgument(1);

      if (!stored.get().etag().equals(etag)) {
        throw new IllegalStateException("Stale ETag");
      }

      if (failSentUpdate.get()
          && updated.status()
              == RecoveryPublicationExecution.Status.SENT) {

        throw new IllegalStateException("Final state update failed");
      }

      stored.set(
          new StoredRecoveryPublicationExecution(
              updated,
              "etag-" + version.incrementAndGet()));

      return null;
    }).when(repository).update(any(), anyString());
  }

  private void stubPresence(
      RecoveryPublicationDiscoveryItem item,
      boolean present) throws Exception {

    var source = itemSource(item);

    var comparison =
        new RecoveryPublicationPresenceIndex.Comparison(
            DAY,
            source,
            present
                ? RecoveryPublicationPresenceIndex.Presence
                    .PRESENT_UNVERIFIED
                : RecoveryPublicationPresenceIndex.Presence.ABSENT,
            present ? 1L : 0L);

    when(presence.compare(DAY, List.of(source)))
        .thenReturn(List.of(comparison));
  }

  private void stubEvents(
      RecoveryPublicationDiscoveryItem item,
      Stream<ReportedIUVEventModel> events) throws IOException {

    var source = itemSource(item);

    switch (source.sourceType()) {
      case FDR3_JSON ->
          when(jsonReader.read(DAY, source)).thenReturn(events);
      case FDR1_XML ->
          when(xmlReader.read(DAY, source)).thenReturn(events);
    }
  }

  private static RecoveryPublicationSource itemSource(
      RecoveryPublicationDiscoveryItem item) {

    return ((RecoveryPublicationDiscoveryItem.Compared) item.outcome())
        .source();
  }

  private static ReportedIUVEventModel event() {
    return ReportedIUVEventModel.builder().iuv("IUV-1").build();
  }

  private static RecoveryPublicationDiscoveryItem item(
      RecoveryPublicationSource.SourceType type) {

    var candidate =
        new RecoveryFlowCandidate(
            1L,
            "recovery-flow",
            1L,
            "88888888888",
            "15376371009",
            LocalDateTime.parse("2026-10-05T12:50:26.001454"));

    var source =
        new RecoveryPublicationSource(
            candidate,
            new RecoveryBlobSnapshot(
                type.containerName(),
                "original-blob",
                "\"original-etag\"",
                100L,
                Map.of(
                    "sessionId",
                    "a92570e7-d5f5-49da-bb8f-0226e2d95ac7")),
            type,
            "a92570e7-d5f5-49da-bb8f-0226e2d95ac7",
            Instant.parse("2026-10-05T12:50:26.001454Z"));

    return new RecoveryPublicationDiscoveryItem(
        JOB_ID,
        DAY,
        candidate,
        new RecoveryPublicationDiscoveryItem.Compared(
            source,
            RecoveryPublicationDiscoveryItem.Status.ABSENT,
            0L));
  }
}