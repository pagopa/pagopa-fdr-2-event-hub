package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryPublicationJobWorkerStateServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");
  private static final String ETAG = "job-etag-1";

  private final RecoveryJobRepository repository =
      mock(RecoveryJobRepository.class);

  private final Runnable ownership = mock(Runnable.class);

  private final RecoveryJobWorkerStateService service =
      new RecoveryJobWorkerStateService(
          repository, Clock.fixed(NOW, ZoneOffset.UTC));

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldAdvanceDryRunWithUnresolvedItems(boolean lastDay) {
    var current =
        job(
            true,
            RecoveryJobStatus.RUNNING,
            lastDay ? DAY : DAY.plusDays(1));

    stubJob(current);

    var completed =
        checkpoint(
            DAY,
            RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED);

    var result =
        service.completeDryRunDay(completed, ownership).orElseThrow();

    assertEquals(DAY.plusDays(1), result.nextDate());

    assertEquals(
        lastDay
            ? RecoveryJobStatus.COMPLETED
            : RecoveryJobStatus.RUNNING,
        result.status());

    assertEquals(current.createdAt(), result.createdAt());
    assertEquals(NOW, result.updatedAt());

    var captor = ArgumentCaptor.forClass(RecoveryJob.class);
    verify(repository).update(captor.capture(), org.mockito.ArgumentMatchers.eq(ETAG));

    assertEquals(result, captor.getValue());
  }

  @Test
  void shouldRejectIncompleteDiscoveryBeforeReadingJob() {
    var checkpoint =
        checkpoint(
            DAY,
            RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERING);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.completeDryRunDay(checkpoint, ownership));

    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectNonDryRunJob() {
    stubJob(job(false, RecoveryJobStatus.RUNNING, DAY));

    var checkpoint =
        checkpoint(
            DAY,
            RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.completeDryRunDay(checkpoint, ownership));

    verify(repository).find(JOB_ID);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldNotAdvanceWhenPauseWasRequested() {
    stubJob(job(true, RecoveryJobStatus.PAUSING, DAY));

    var checkpoint =
        checkpoint(
            DAY,
            RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED);

    assertTrue(
        service.completeDryRunDay(checkpoint, ownership).isEmpty());

    verify(repository).find(JOB_ID);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldRejectDayDifferentFromNextDate() {
    stubJob(job(true, RecoveryJobStatus.RUNNING, DAY.plusDays(1)));

    var checkpoint =
        checkpoint(
            DAY.plusDays(1),
            RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.completeDryRunDay(checkpoint, ownership));

    verify(repository).find(JOB_ID);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldReturnEmptyForMissingJob() {
    when(repository.find(JOB_ID)).thenReturn(Optional.empty());

    var checkpoint =
        checkpoint(
            DAY,
            RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED);

    assertTrue(
        service.completeDryRunDay(checkpoint, ownership).isEmpty());
  }

  @Test
  void shouldPropagateJobUpdateFailure() {
    stubJob(job(true, RecoveryJobStatus.RUNNING, DAY));

    var failure = new IllegalStateException("Simulated job update failure");

    doThrow(failure)
        .when(repository)
        .update(
            org.mockito.ArgumentMatchers.any(RecoveryJob.class),
            org.mockito.ArgumentMatchers.eq(ETAG));

    var checkpoint =
        checkpoint(
            DAY,
            RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED);

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.completeDryRunDay(checkpoint, ownership));

    assertSame(failure, actual);
  }

  @Test
  void shouldStopBeforeUpdateWhenOwnershipIsLost() {
    stubJob(job(true, RecoveryJobStatus.RUNNING, DAY));

    var failure = new IllegalStateException("Lease ownership lost");

    doNothing()
        .doThrow(failure)
        .when(ownership)
        .run();

    var checkpoint =
        checkpoint(
            DAY,
            RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED);

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.completeDryRunDay(checkpoint, ownership));

    assertSame(failure, actual);

    verify(repository).find(JOB_ID);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldRejectNullPublicationCheckpoint() {
    RecoveryPublicationDiscoveryCheckpoint checkpoint = null;

    assertThrows(
        NullPointerException.class,
        () -> service.completeDryRunDay(checkpoint, ownership));

    verifyNoInteractions(repository);
  }

  private void stubJob(RecoveryJob job) {
    when(repository.find(JOB_ID))
        .thenReturn(Optional.of(new StoredRecoveryJob(job, ETAG)));
  }

  private static RecoveryJob job(
      boolean dryRun,
      RecoveryJobStatus status,
      LocalDate dateTo) {

    return new RecoveryJob(
        JOB_ID,
        DAY,
        dateTo,
        dryRun,
        status,
        DAY,
        NOW.minusSeconds(3600),
        NOW.minusSeconds(60));
  }

  private static RecoveryPublicationDiscoveryCheckpoint checkpoint(
      LocalDate day,
      RecoveryPublicationDiscoveryCheckpoint.Status status) {

    return new RecoveryPublicationDiscoveryCheckpoint(
        JOB_ID,
        day,
        10L,
        1L,
        0L,
        0L,
        1L,
        1L,
        status);
  }
}