package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class RecoveryJobControlServiceTest {

  private static final Instant NOW =
      Instant.parse("2026-10-30T08:30:00Z");

  private static final String ETAG = "W/\"version-1\"";

  private final RecoveryJobRepository repository =
      mock(RecoveryJobRepository.class);

  private final RecoveryJobService service =
      new RecoveryJobService(
          repository,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private StoredRecoveryJob stubJob(RecoveryJobStatus status) {
    LocalDate from = LocalDate.of(2026, 10, 1);
    LocalDate to = LocalDate.of(2026, 10, 31);

    LocalDate next =
        status == RecoveryJobStatus.COMPLETED
            ? to.plusDays(1)
            : LocalDate.of(2026, 10, 3);

    RecoveryJob job =
        new RecoveryJob(
            UUID.randomUUID(),
            from,
            to,
            true,
            status,
            next,
            NOW.minusSeconds(120),
            NOW.minusSeconds(60));

    StoredRecoveryJob stored = new StoredRecoveryJob(job, ETAG);
    when(repository.find(job.jobId())).thenReturn(Optional.of(stored));

    return stored;
  }

  private void assertSavedTransition(
      StoredRecoveryJob original,
      RecoveryJob result,
      RecoveryJobStatus expectedStatus) {

    ArgumentCaptor<RecoveryJob> captor =
        ArgumentCaptor.forClass(RecoveryJob.class);

    verify(repository).find(original.job().jobId());
    verify(repository).update(captor.capture(), eq(ETAG));

    RecoveryJob saved = captor.getValue();

    assertSame(result, saved);
    assertEquals(expectedStatus, saved.status());
    assertEquals(original.job().jobId(), saved.jobId());
    assertEquals(original.job().dateFrom(), saved.dateFrom());
    assertEquals(original.job().dateTo(), saved.dateTo());
    assertEquals(original.job().dryRun(), saved.dryRun());
    assertEquals(original.job().nextDate(), saved.nextDate());
    assertEquals(original.job().createdAt(), saved.createdAt());
    assertEquals(NOW, saved.updatedAt());

    verifyNoMoreInteractions(repository);
  }

  private void assertUnchanged(
      StoredRecoveryJob original, RecoveryJob result) {

    assertSame(original.job(), result);
    verify(repository).find(original.job().jobId());
    verifyNoMoreInteractions(repository);
  }

  @ParameterizedTest
  @CsvSource({
      "PENDING, PAUSED",
      "RUNNING, PAUSING"
  })
  void shouldRequestPause(
      RecoveryJobStatus source,
      RecoveryJobStatus expected) {

    StoredRecoveryJob original = stubJob(source);

    RecoveryJob result =
        service.pause(original.job().jobId()).orElseThrow();

    assertSavedTransition(original, result, expected);
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = {"PAUSING", "PAUSED"})
  void shouldNotWriteWhenPauseAlreadyRequested(RecoveryJobStatus status) {
    StoredRecoveryJob original = stubJob(status);

    RecoveryJob result =
        service.pause(original.job().jobId()).orElseThrow();

    assertUnchanged(original, result);
  }

  @Test
  void shouldResumePausedJobWithoutResettingProgress() {
    StoredRecoveryJob original = stubJob(RecoveryJobStatus.PAUSED);

    RecoveryJob result =
        service.resume(original.job().jobId()).orElseThrow();

    assertSavedTransition(original, result, RecoveryJobStatus.PENDING);
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = {"PENDING", "RUNNING"})
  void shouldNotWriteWhenJobAlreadyScheduledOrRunning(
      RecoveryJobStatus status) {

    StoredRecoveryJob original = stubJob(status);

    RecoveryJob result =
        service.resume(original.job().jobId()).orElseThrow();

    assertUnchanged(original, result);
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = {"COMPLETED", "FAILED"})
  void shouldRejectPauseForTerminalJob(RecoveryJobStatus status) {
    StoredRecoveryJob original = stubJob(status);
    UUID jobId = original.job().jobId();

    assertThrows(
        IllegalStateException.class,
        () -> service.pause(jobId));

    verify(repository).find(jobId);
    verifyNoMoreInteractions(repository);
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = {"PAUSING", "COMPLETED", "FAILED"})
  void shouldRejectResumeForUnsupportedStatus(RecoveryJobStatus status) {
    StoredRecoveryJob original = stubJob(status);
    UUID jobId = original.job().jobId();

    assertThrows(
        IllegalStateException.class,
        () -> service.resume(jobId));

    verify(repository).find(jobId);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldReturnEmptyWhenPausingMissingJob() {
    UUID jobId = UUID.randomUUID();
    when(repository.find(jobId)).thenReturn(Optional.empty());

    assertTrue(service.pause(jobId).isEmpty());

    verify(repository).find(jobId);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldReturnEmptyWhenResumingMissingJob() {
    UUID jobId = UUID.randomUUID();
    when(repository.find(jobId)).thenReturn(Optional.empty());

    assertTrue(service.resume(jobId).isEmpty());

    verify(repository).find(jobId);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldRejectNullIdForPause() {
    assertThrows(
        NullPointerException.class,
        () -> service.pause(null));

    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectNullIdForResume() {
    assertThrows(
        NullPointerException.class,
        () -> service.resume(null));

    verifyNoInteractions(repository);
  }

  @Test
  void shouldPropagateUpdateFailureWithoutApplicationRetry() {
    StoredRecoveryJob original = stubJob(RecoveryJobStatus.RUNNING);
    UUID jobId = original.job().jobId();

    IllegalStateException failure =
        new IllegalStateException("Simulated repository update failure");

    doThrow(failure)
        .when(repository)
        .update(any(RecoveryJob.class), anyString());

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> service.pause(jobId)));

    verify(repository).find(jobId);
    verify(repository).update(any(RecoveryJob.class), eq(ETAG));
    verifyNoMoreInteractions(repository);
  }
}