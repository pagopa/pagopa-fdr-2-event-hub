package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryExecutionJobWorkerStateServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 5);

  private static final Instant NOW =
      Instant.parse("2026-11-11T12:00:00Z");

  private static final String ETAG = "etag-1";

  private static final Runnable OWNERSHIP_HELD = () -> {
    // No-op guard: this test retains ownership.
  };

  private final RecoveryJobRepository repository =
      mock(RecoveryJobRepository.class);

  private final RecoveryExecutionJobWorkerStateService service =
      new RecoveryExecutionJobWorkerStateService(
          repository,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void shouldAdvanceCompletedDayWithoutCompletingWholeJob() {
    var current =
        job(RecoveryJobStatus.RUNNING, false, DAY.plusDays(1));

    stubJob(current);

    var updated =
        service.apply(
                completed(),
                OWNERSHIP_HELD)
            .orElseThrow();

    assertEquals(RecoveryJobStatus.RUNNING, updated.status());
    assertEquals(DAY.plusDays(1), updated.nextDate());
    assertEquals(current.createdAt(), updated.createdAt());
    assertEquals(NOW, updated.updatedAt());

    verify(repository).update(updated, ETAG);
  }

  @Test
  void shouldCompleteJobAfterLastSuccessfulDay() {
    stubJob(job(RecoveryJobStatus.RUNNING, false, DAY));

    var updated =
        service.apply(completed(), OWNERSHIP_HELD)
            .orElseThrow();

    assertEquals(RecoveryJobStatus.COMPLETED, updated.status());
    assertEquals(DAY.plusDays(1), updated.nextDate());

    verify(repository).update(updated, ETAG);
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryPublicationExecutionDayService.Status.class,
      names = {"BLOCKED_UNRESOLVED", "BLOCKED_UNCERTAIN"})
  void shouldFailBlockedJobWithoutAdvancingDay(
      RecoveryPublicationExecutionDayService.Status status) {

    stubJob(job(RecoveryJobStatus.RUNNING, false, DAY));

    var updated =
        service.apply(result(status), OWNERSHIP_HELD)
            .orElseThrow();

    assertEquals(RecoveryJobStatus.FAILED, updated.status());
    assertEquals(DAY, updated.nextDate());

    verify(repository).update(updated, ETAG);
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryPublicationExecutionDayService.Status.class,
      names = {"COMPLETED", "PAUSED"})
  void shouldAcknowledgeRequestedPauseWithoutAdvancingDay(
      RecoveryPublicationExecutionDayService.Status status) {

    stubJob(job(RecoveryJobStatus.PAUSING, false, DAY));

    var updated =
        service.apply(result(status), OWNERSHIP_HELD)
            .orElseThrow();

    assertEquals(RecoveryJobStatus.PAUSED, updated.status());
    assertEquals(DAY, updated.nextDate());
  }

  @Test
  void shouldPrioritizeUncertainOutcomeOverPauseRequest() {
    stubJob(job(RecoveryJobStatus.PAUSING, false, DAY));

    var updated =
        service.apply(
                result(
                    RecoveryPublicationExecutionDayService.Status
                        .BLOCKED_UNCERTAIN),
                OWNERSHIP_HELD)
            .orElseThrow();

    assertEquals(RecoveryJobStatus.FAILED, updated.status());
    assertEquals(DAY, updated.nextDate());
  }

  @Test
  void shouldNotAdvancePausedResultWhenJobIsStillRunning() {
    stubJob(job(RecoveryJobStatus.RUNNING, false, DAY));

    var updated =
        service.apply(
            result(RecoveryPublicationExecutionDayService.Status.PAUSED),
            OWNERSHIP_HELD);

    assertTrue(updated.isEmpty());
    verify(repository, never()).update(any(), anyString());
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = {"PENDING", "PAUSED", "COMPLETED", "FAILED"})
  void shouldNotChangeInactiveJob(RecoveryJobStatus status) {
    stubJob(job(status, false, DAY));

    assertTrue(
        service.apply(completed(), OWNERSHIP_HELD).isEmpty());

    verify(repository, never()).update(any(), anyString());
  }

  @Test
  void shouldReturnEmptyForMissingJob() {
    when(repository.find(JOB_ID)).thenReturn(Optional.empty());

    assertTrue(
        service.apply(completed(), OWNERSHIP_HELD).isEmpty());

    verify(repository, never()).update(any(), anyString());
  }

  @Test
  void shouldRejectDryRunJob() {
    stubJob(job(RecoveryJobStatus.RUNNING, true, DAY));

    var result = completed();

    assertThrows(
        IllegalArgumentException.class,
        () -> service.apply(result, OWNERSHIP_HELD));

    verify(repository, never()).update(any(), anyString());
  }

  @Test
  void shouldRejectResultForAnotherDay() {
    stubJob(job(RecoveryJobStatus.RUNNING, false, DAY.plusDays(1)));

    var result =
        new RecoveryPublicationExecutionDayService.DayResult(
            JOB_ID,
            DAY.plusDays(1),
            RecoveryPublicationExecutionDayService.Status.COMPLETED,
            1L,
            1L,
            0L,
            0L,
            0L,
            10L);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.apply(result, OWNERSHIP_HELD));

    verify(repository, never()).update(any(), anyString());
  }

  @Test
  void shouldRejectCompletedResultContainingUnresolvedPublications() {
    var invalid =
        new RecoveryPublicationExecutionDayService.DayResult(
            JOB_ID,
            DAY,
            RecoveryPublicationExecutionDayService.Status.COMPLETED,
            1L,
            0L,
            0L,
            1L,
            0L,
            10L);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.apply(invalid, OWNERSHIP_HELD));

    verifyNoInteractions(repository);
  }

  @Test
  void shouldPropagateConditionalUpdateFailure() {
    stubJob(job(RecoveryJobStatus.RUNNING, false, DAY));

    var failure =
        new IllegalStateException("Conditional update failed");

    doThrow(failure)
        .when(repository)
        .update(any(), anyString());

    var result = completed();

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.apply(result, OWNERSHIP_HELD));

    assertSame(failure, actual);
    verify(repository).update(any(), anyString());
  }

  @Test
  void shouldStopWhenOwnershipIsLostAfterReadingJob() {
    var current = job(RecoveryJobStatus.RUNNING, false, DAY);
    var held = new AtomicBoolean(true);
    var failure = new IllegalStateException("Ownership lost");

    when(repository.find(JOB_ID))
        .thenAnswer(invocation -> {
          held.set(false);

          return Optional.of(
              new StoredRecoveryJob(current, ETAG));
        });

    Runnable ownership = () -> {
      if (!held.get()) {
        throw failure;
      }
    };

    var result = completed();

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.apply(result, ownership));

    assertSame(failure, actual);
    verify(repository, never()).update(any(), anyString());
  }

  private void stubJob(RecoveryJob job) {
    when(repository.find(JOB_ID))
        .thenReturn(
            Optional.of(new StoredRecoveryJob(job, ETAG)));
  }

  private static RecoveryJob job(
      RecoveryJobStatus status,
      boolean dryRun,
      LocalDate dateTo) {

    LocalDate nextDate =
        status == RecoveryJobStatus.COMPLETED
            ? dateTo.plusDays(1)
            : DAY;

    return new RecoveryJob(
        JOB_ID,
        DAY,
        dateTo,
        dryRun,
        status,
        nextDate,
        NOW.minusSeconds(60),
        NOW.minusSeconds(30));
  }

  private static RecoveryPublicationExecutionDayService.DayResult completed() {
    return result(
        RecoveryPublicationExecutionDayService.Status.COMPLETED);
  }

  private static RecoveryPublicationExecutionDayService.DayResult result(
      RecoveryPublicationExecutionDayService.Status status) {

    boolean unresolved =
        status
            == RecoveryPublicationExecutionDayService.Status
                .BLOCKED_UNRESOLVED;

    boolean uncertain =
        status
            == RecoveryPublicationExecutionDayService.Status
                .BLOCKED_UNCERTAIN;

    return new RecoveryPublicationExecutionDayService.DayResult(
        JOB_ID,
        DAY,
        status,
        1L,
        unresolved || uncertain ? 0L : 1L,
        0L,
        unresolved ? 1L : 0L,
        uncertain ? 1L : 0L,
        10L);
  }
}