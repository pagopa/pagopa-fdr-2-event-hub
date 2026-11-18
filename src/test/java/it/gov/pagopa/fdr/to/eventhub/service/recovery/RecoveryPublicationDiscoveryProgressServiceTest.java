package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.UnsupportedRecoveryJobFormatException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryPublicationDiscoveryCheckpoint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryPublicationDiscoveryProgressServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryJobRepository jobs =
      mock(RecoveryJobRepository.class);

  private final RecoveryPublicationDiscoveryCheckpointRepository checkpoints =
      mock(RecoveryPublicationDiscoveryCheckpointRepository.class);

  private final RecoveryDiscoveryProgressService service =
      new RecoveryDiscoveryProgressService(jobs, checkpoints);

  @ParameterizedTest
  @EnumSource(RecoveryPublicationDiscoveryCheckpoint.Status.class)
  void shouldExposeAllOutcomeCounters(
      RecoveryPublicationDiscoveryCheckpoint.Status status) {

    givenJob();

    var checkpoint =
        new RecoveryPublicationDiscoveryCheckpoint(
            JOB_ID, DAY, 30L, 3L, 1L, 1L, 1L, 1L, status);

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(
            Optional.of(
                new StoredRecoveryPublicationDiscoveryCheckpoint(
                    checkpoint, "checkpoint-etag")));

    var result = service.find(JOB_ID, DAY).orElseThrow();

    assertEquals(status.name(), result.status().name());
    assertEquals(3L, result.candidateCount());
    assertEquals(1L, result.absentCandidateCount());
    assertEquals(1L, result.presentCandidateCount());
    assertEquals(1L, result.unresolvedCandidateCount());
    assertEquals(1L, result.pageCount());
    assertEquals(30L, result.lastFlowId());
  }

  @Test
  void shouldReportNotStartedWithZeroUnresolvedCount() {
    givenJob();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.empty());

    var result = service.find(JOB_ID, DAY).orElseThrow();

    assertEquals(
        RecoveryDiscoveryProgressService.DiscoveryStatus.NOT_STARTED,
        result.status());

    assertEquals(0L, result.candidateCount());
    assertEquals(0L, result.unresolvedCandidateCount());
  }

  @Test
  void shouldReturnEmptyForMissingJob() {
    when(jobs.find(JOB_ID)).thenReturn(Optional.empty());

    assertTrue(service.find(JOB_ID, DAY).isEmpty());

    verifyNoInteractions(checkpoints);
  }

  @Test
  void shouldPropagateUnsupportedFormatWithoutReadingCheckpoint() {
    var failure =
        new UnsupportedRecoveryJobFormatException("Old schema");

    when(jobs.find(JOB_ID)).thenThrow(failure);

    var actual =
        assertThrows(
            UnsupportedRecoveryJobFormatException.class,
            () -> service.find(JOB_ID, DAY));

    assertSame(failure, actual);
    verifyNoInteractions(checkpoints);
  }

  private void givenJob() {
    Instant now = Instant.parse("2026-11-10T15:00:00Z");

    var job =
        new RecoveryJob(
            JOB_ID,
            DAY,
            DAY,
            true,
            RecoveryJobStatus.RUNNING,
            DAY,
            now,
            now);

    when(jobs.find(JOB_ID))
        .thenReturn(
            Optional.of(new StoredRecoveryJob(job, "job-etag")));
  }
}