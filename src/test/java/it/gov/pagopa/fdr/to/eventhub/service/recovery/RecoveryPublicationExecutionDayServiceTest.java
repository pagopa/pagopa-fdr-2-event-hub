package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationExecution;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryPageRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryPublicationDiscoveryCheckpoint;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RecoveryPublicationExecutionDayServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 5);

  private static final Instant NOW =
      Instant.parse("2026-11-11T12:00:00Z");

  private static final int PAGE_SIZE = 2;

  private static final Runnable OWNERSHIP_HELD = () -> {
    // No-op guard: this test retains ownership.
  };

  private final RecoveryPublicationDiscoveryCheckpointRepository checkpoints =
      mock(RecoveryPublicationDiscoveryCheckpointRepository.class);

  private final RecoveryPublicationDiscoveryPageRepository pages =
      mock(RecoveryPublicationDiscoveryPageRepository.class);

  private final RecoveryPublicationExecutionService executions =
      mock(RecoveryPublicationExecutionService.class);

  private final RecoveryPublicationExecutionDayService service =
      new RecoveryPublicationExecutionDayService(
          checkpoints, pages, executions, PAGE_SIZE);

  @Test
  void shouldExecuteAbsentPublicationsAcrossPages() throws Exception {
    var first = absent(1L);
    var present =
        item(2L, RecoveryPublicationDiscoveryItem.Status.PRESENT_UNVERIFIED);
    var third = absent(3L);

    stubDay(List.of(first, present, third));
    stubSuccessfulExecutions();

    var result =
        service.execute(JOB_ID, DAY, () -> true, OWNERSHIP_HELD);

    assertEquals(
        RecoveryPublicationExecutionDayService.Status.COMPLETED,
        result.status());

    assertEquals(3L, result.examinedPublications());
    assertEquals(2L, result.sentPublications());
    assertEquals(1L, result.presentPublications());
    assertEquals(3L, result.lastExaminedFlowId());

    verify(executions).execute(first, OWNERSHIP_HELD);
    verify(executions).execute(third, OWNERSHIP_HELD);
    verify(executions, never()).execute(eq(present), any());

    verify(pages).findPage(JOB_ID, DAY, 2L, PAGE_SIZE);
    verify(pages).findPage(JOB_ID, DAY, 3L, PAGE_SIZE);
  }

  @Test
  void shouldCountPublicationFoundPresentByExecutionRecheck()
      throws Exception {

    var item = absent(1L);
    stubDay(List.of(item));

    var skipped =
        RecoveryPublicationExecution.pending(item, NOW)
            .skipAlreadyPresent(NOW);

    when(executions.execute(item, OWNERSHIP_HELD))
        .thenReturn(skipped);

    var result =
        service.execute(JOB_ID, DAY, () -> true, OWNERSHIP_HELD);

    assertEquals(
        RecoveryPublicationExecutionDayService.Status.COMPLETED,
        result.status());

    assertEquals(0L, result.sentPublications());
    assertEquals(1L, result.presentPublications());
  }

  @Test
  void shouldProcessOtherItemsButBlockCompletionForUnresolved()
      throws Exception {

    var unresolved =
        item(1L, RecoveryPublicationDiscoveryItem.Status.UNRESOLVED);
    var absent = absent(2L);

    stubDay(List.of(unresolved, absent));
    stubSuccessfulExecutions();

    var result =
        service.execute(JOB_ID, DAY, () -> true, OWNERSHIP_HELD);

    assertEquals(
        RecoveryPublicationExecutionDayService.Status.BLOCKED_UNRESOLVED,
        result.status());

    assertEquals(1L, result.unresolvedPublications());
    assertEquals(1L, result.sentPublications());

    verify(executions, never()).execute(eq(unresolved), any());
    verify(executions).execute(absent, OWNERSHIP_HELD);
  }

  @Test
  void shouldStopImmediatelyForUncertainExecution() throws Exception {
    var first = absent(1L);
    var second = absent(2L);

    stubDay(List.of(first, second));

    var uncertain =
        RecoveryPublicationExecution.pending(first, NOW)
            .startSending(UUID.randomUUID(), NOW)
            .markUncertain(NOW);

    when(executions.execute(first, OWNERSHIP_HELD))
        .thenReturn(uncertain);

    var result =
        service.execute(JOB_ID, DAY, () -> true, OWNERSHIP_HELD);

    assertEquals(
        RecoveryPublicationExecutionDayService.Status.BLOCKED_UNCERTAIN,
        result.status());

    assertEquals(1L, result.uncertainPublications());
    assertEquals(1L, result.examinedPublications());
    assertEquals(1L, result.lastExaminedFlowId());

    verify(executions, never()).execute(eq(second), any());
  }

  @Test
  void shouldPauseBeforeReadingAnyPage() throws Exception {
    stubDay(List.of(absent(1L)));

    var result =
        service.execute(JOB_ID, DAY, () -> false, OWNERSHIP_HELD);

    assertEquals(
        RecoveryPublicationExecutionDayService.Status.PAUSED,
        result.status());

    assertEquals(0L, result.examinedPublications());

    verifyNoInteractions(pages, executions);
  }

  @Test
  void shouldPauseBetweenPublications() throws Exception {
    var first = absent(1L);
    var second = absent(2L);
    var allowed = new AtomicBoolean(true);

    stubDay(List.of(first, second));

    when(executions.execute(first, OWNERSHIP_HELD))
        .thenAnswer(invocation -> {
          allowed.set(false);
          return sent(first);
        });

    var result =
        service.execute(JOB_ID, DAY, allowed::get, OWNERSHIP_HELD);

    assertEquals(
        RecoveryPublicationExecutionDayService.Status.PAUSED,
        result.status());

    assertEquals(1L, result.examinedPublications());
    assertEquals(1L, result.lastExaminedFlowId());

    verify(executions, never()).execute(eq(second), any());
  }

  @Test
  void shouldCompleteAnEmptyDiscoveredDay() throws Exception {
    stubDay(List.of());

    var result =
        service.execute(JOB_ID, DAY, () -> true, OWNERSHIP_HELD);

    assertEquals(
        RecoveryPublicationExecutionDayService.Status.COMPLETED,
        result.status());

    assertEquals(0L, result.examinedPublications());
    verifyNoInteractions(executions);
  }

  @Test
  void shouldRejectMissingDiscoveryCheckpoint() {
    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.empty());

    assertThrows(
        IllegalStateException.class,
        () -> service.execute(
            JOB_ID, DAY, () -> true, OWNERSHIP_HELD));

    verifyNoInteractions(pages, executions);
  }

  @Test
  void shouldRejectDiscoveryStillInProgress() {
    var checkpoint =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(
            Optional.of(
                new StoredRecoveryPublicationDiscoveryCheckpoint(
                    checkpoint, "etag")));

    assertThrows(
        IllegalStateException.class,
        () -> service.execute(
            JOB_ID, DAY, () -> true, OWNERSHIP_HELD));

    verifyNoInteractions(pages, executions);
  }

  @Test
  void shouldValidateEntirePageBeforeSending() {
    var first = absent(1L);
    var second = absent(2L);

    stubDay(List.of(first, second));

    when(pages.findPage(JOB_ID, DAY, 0L, PAGE_SIZE))
        .thenReturn(List.of(second, first));

    assertThrows(
        IllegalStateException.class,
        () -> service.execute(
            JOB_ID, DAY, () -> true, OWNERSHIP_HELD));

    verifyNoInteractions(executions);
  }

  @Test
  void shouldRejectIncompleteStoredDiscovery() throws Exception {
    var first = absent(1L);
    var second = absent(2L);

    stubDay(List.of(first, second));
    stubSuccessfulExecutions();

    when(pages.findPage(JOB_ID, DAY, 0L, PAGE_SIZE))
        .thenReturn(List.of(first));

    when(pages.findPage(JOB_ID, DAY, 1L, PAGE_SIZE))
        .thenReturn(List.of());

    assertThrows(
        IllegalStateException.class,
        () -> service.execute(
            JOB_ID, DAY, () -> true, OWNERSHIP_HELD));
  }

  @Test
  void shouldPropagateExecutionFailureWithoutProcessingNextItem()
      throws Exception {

    var first = absent(1L);
    var second = absent(2L);
    var failure = new IllegalStateException("Execution failed");

    stubDay(List.of(first, second));

    when(executions.execute(first, OWNERSHIP_HELD))
        .thenThrow(failure);

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.execute(
                JOB_ID, DAY, () -> true, OWNERSHIP_HELD));

    assertSame(failure, actual);
    verify(executions, never()).execute(eq(second), any());
  }

  @Test
  void shouldStopWhenOwnershipIsLostAfterPageRead() {
    var first = absent(1L);
    var held = new AtomicBoolean(true);
    var failure = new IllegalStateException("Ownership lost");

    stubDay(List.of(first));

    when(pages.findPage(JOB_ID, DAY, 0L, PAGE_SIZE))
        .thenAnswer(invocation -> {
          held.set(false);
          return List.of(first);
        });

    Runnable ownership = () -> {
      if (!held.get()) {
        throw failure;
      }
    };

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.execute(
                JOB_ID, DAY, () -> true, ownership));

    assertSame(failure, actual);
    verifyNoInteractions(executions);
  }

  private void stubSuccessfulExecutions() throws Exception {
    when(executions.execute(any(), any()))
        .thenAnswer(invocation -> sent(invocation.getArgument(0)));
  }

  private void stubDay(
      List<RecoveryPublicationDiscoveryItem> items) {

    var checkpoint = checkpoint(items);

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(
            Optional.of(
                new StoredRecoveryPublicationDiscoveryCheckpoint(
                    checkpoint, "etag")));

    for (int offset = 0; offset < items.size(); offset += PAGE_SIZE) {
      long cursor =
          offset == 0
              ? 0L
              : items.get(offset - 1).candidate().flowId();

      var page =
          List.copyOf(
              items.subList(
                  offset,
                  Math.min(offset + PAGE_SIZE, items.size())));

      when(pages.findPage(JOB_ID, DAY, cursor, PAGE_SIZE))
          .thenReturn(page);
    }

    long finalCursor =
        items.isEmpty()
            ? 0L
            : items.get(items.size() - 1).candidate().flowId();

    when(pages.findPage(JOB_ID, DAY, finalCursor, PAGE_SIZE))
        .thenReturn(List.of());
  }

  private static RecoveryPublicationDiscoveryCheckpoint checkpoint(
      List<RecoveryPublicationDiscoveryItem> items) {

    long absent = count(items, RecoveryPublicationDiscoveryItem.Status.ABSENT);
    long present =
        count(items, RecoveryPublicationDiscoveryItem.Status.PRESENT_UNVERIFIED);
    long unresolved =
        count(items, RecoveryPublicationDiscoveryItem.Status.UNRESOLVED);

    long lastId =
        items.isEmpty()
            ? 0L
            : items.get(items.size() - 1).candidate().flowId();

    return new RecoveryPublicationDiscoveryCheckpoint(
        JOB_ID,
        DAY,
        lastId,
        items.size(),
        absent,
        present,
        unresolved,
        items.isEmpty() ? 0L : 1L,
        RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED);
  }

  private static long count(
      List<RecoveryPublicationDiscoveryItem> items,
      RecoveryPublicationDiscoveryItem.Status status) {

    return items.stream()
        .filter(item -> item.outcome().status() == status)
        .count();
  }

  private static RecoveryPublicationExecution sent(
      RecoveryPublicationDiscoveryItem item) {

    return RecoveryPublicationExecution.pending(item, NOW)
        .startSending(UUID.randomUUID(), NOW)
        .markSent(1L, 1L, NOW);
  }

  private static RecoveryPublicationDiscoveryItem absent(long id) {
    return item(id, RecoveryPublicationDiscoveryItem.Status.ABSENT);
  }

  private static RecoveryPublicationDiscoveryItem item(
      long id,
      RecoveryPublicationDiscoveryItem.Status status) {

    var candidate =
        new RecoveryFlowCandidate(
            id,
            "flow-" + id,
            1L,
            "88888888888",
            "15376371009",
            LocalDateTime.parse("2026-10-05T12:50:26.001454"));

    RecoveryPublicationDiscoveryItem.Outcome outcome;

    if (status == RecoveryPublicationDiscoveryItem.Status.UNRESOLVED) {
      outcome =
          new RecoveryPublicationDiscoveryItem.Unresolved(
              "ORIGIN_UNDETERMINED", Map.of());
    } else {
      String sessionId = new UUID(0L, id).toString();

      var source =
          new RecoveryPublicationSource(
              candidate,
              new RecoveryBlobSnapshot(
                  "fdr3-flows",
                  "original-" + id,
                  "\"etag\"",
                  100L,
                  Map.of("sessionId", sessionId)),
              RecoveryPublicationSource.SourceType.FDR3_JSON,
              sessionId,
              Instant.parse("2026-10-05T12:50:26.001454Z"));

      outcome =
          new RecoveryPublicationDiscoveryItem.Compared(
              source,
              status,
              status == RecoveryPublicationDiscoveryItem.Status.ABSENT
                  ? 0L
                  : 1L);
    }

    return new RecoveryPublicationDiscoveryItem(
        JOB_ID, DAY, candidate, outcome);
  }
}