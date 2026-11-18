package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryDiscoveryProgressServiceTest {

  private static final UUID JOB_ID = UUID.randomUUID();
  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryJobRepository jobs =
      mock(RecoveryJobRepository.class);

  private final RecoveryDiscoveryCheckpointRepository checkpoints =
      mock(RecoveryDiscoveryCheckpointRepository.class);

  private final RecoveryDiscoveryProgressService service =
      new RecoveryDiscoveryProgressService(jobs, checkpoints);

  @Test
  void shouldReturnEmptyForUnknownJob() {
    when(jobs.find(JOB_ID)).thenReturn(Optional.empty());

    assertTrue(service.find(JOB_ID, DAY).isEmpty());

    verifyNoInteractions(checkpoints);
  }

  @Test
  void shouldReportNotStartedWithoutCreatingCheckpoint() {
    givenJob();
    when(checkpoints.find(JOB_ID, DAY)).thenReturn(Optional.empty());

    assertEquals(
        new RecoveryDiscoveryProgressService.DailyProgress(
            JOB_ID,
            DAY,
            RecoveryDiscoveryProgressService.DiscoveryStatus.NOT_STARTED,
            0L, 0L, 0L, 0L, 0L),
        service.find(JOB_ID, DAY).orElseThrow());

    verify(checkpoints).find(JOB_ID, DAY);
    verifyNoMoreInteractions(checkpoints);
  }

  @Test
  void shouldReturnCommittedProgress() {
    givenJob();

    var checkpoint =
        new RecoveryDiscoveryCheckpoint(
            JOB_ID, DAY, 20L, 2L, 1L, 1L, 1L,
            RecoveryDiscoveryCheckpoint.Status.DISCOVERING);

    givenCheckpoint(checkpoint);

    assertEquals(
        new RecoveryDiscoveryProgressService.DailyProgress(
            JOB_ID,
            DAY,
            RecoveryDiscoveryProgressService.DiscoveryStatus.DISCOVERING,
            2L, 1L, 1L, 1L, 20L),
        service.find(JOB_ID, DAY).orElseThrow());
  }

  @Test
  void shouldReportCompletedEmptyDay() {
    givenJob();
    givenCheckpoint(
        RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY)
            .completeDiscovery());

    assertEquals(
        new RecoveryDiscoveryProgressService.DailyProgress(
            JOB_ID,
            DAY,
            RecoveryDiscoveryProgressService.DiscoveryStatus.DISCOVERED,
            0L, 0L, 0L, 0L, 0L),
        service.find(JOB_ID, DAY).orElseThrow());
  }

  @Test
  void shouldRejectDayBeforeJobRange() {
    givenJob();
    LocalDate beforeRange = DAY.minusDays(1);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.find(JOB_ID, beforeRange));

    verifyNoInteractions(checkpoints);
  }

  @Test
  void shouldRejectDayAfterJobRange() {
    givenJob();
    LocalDate afterRange = DAY.plusDays(2);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.find(JOB_ID, afterRange));

    verifyNoInteractions(checkpoints);
  }

  private void givenJob() {
    Instant now = Instant.parse("2026-11-04T16:00:00Z");

    RecoveryJob job =
        new RecoveryJob(
            JOB_ID,
            DAY,
            DAY.plusDays(1),
            true,
            RecoveryJobStatus.PENDING,
            DAY,
            now,
            now);

    when(jobs.find(JOB_ID))
        .thenReturn(Optional.of(new StoredRecoveryJob(job, "job-etag")));
  }

  private void givenCheckpoint(RecoveryDiscoveryCheckpoint checkpoint) {
    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(
            new StoredRecoveryDiscoveryCheckpoint(
                checkpoint,
                "checkpoint-etag")));
  }
}