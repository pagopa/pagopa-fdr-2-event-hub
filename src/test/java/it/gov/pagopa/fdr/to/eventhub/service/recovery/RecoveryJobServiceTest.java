package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobRequest;
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
import org.mockito.ArgumentCaptor;

class RecoveryJobServiceTest {

  private static final Instant NOW =
      Instant.parse("2026-10-30T08:30:00Z");

  private static final Clock CLOCK =
      Clock.fixed(NOW, ZoneOffset.UTC);

  private final RecoveryJobRepository repository =
      mock(RecoveryJobRepository.class);

  private final RecoveryJobService service =
      new RecoveryJobService(repository, CLOCK);

  @Test
  void shouldPersistAndReturnPendingJob() {
    RecoveryJobRequest request =
        new RecoveryJobRequest("2026-10-01", "2026-10-31", true);

    RecoveryJob result = service.create(request);

    ArgumentCaptor<RecoveryJob> captor =
        ArgumentCaptor.forClass(RecoveryJob.class);
    verify(repository).create(captor.capture());

    assertSame(result, captor.getValue());
    assertNotNull(result.jobId());
    assertEquals(RecoveryJobStatus.PENDING, result.status());
    assertEquals(LocalDate.of(2026, 10, 1), result.dateFrom());
    assertEquals(LocalDate.of(2026, 10, 31), result.dateTo());
    assertEquals(result.dateFrom(), result.nextDate());
    assertTrue(result.dryRun());
    assertEquals(NOW, result.createdAt());
    assertEquals(NOW, result.updatedAt());

    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldPreserveExecutionMode() {
    RecoveryJobRequest request =
        new RecoveryJobRequest("2026-10-01", "2026-10-01", false);

    RecoveryJob result = service.create(request);

    assertFalse(result.dryRun());
    verify(repository).create(result);
  }

  @Test
  void shouldPropagateCreationFailureWithoutApplicationRetry() {
    RecoveryJobRequest request =
        new RecoveryJobRequest("2026-10-01", "2026-10-31", true);
    IllegalStateException failure =
        new IllegalStateException("Simulated storage failure");

    doThrow(failure).when(repository).create(any(RecoveryJob.class));

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> service.create(request)));

    verify(repository, times(1)).create(any(RecoveryJob.class));
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldReturnExistingJobWithoutExposingStorageEtag() {
    RecoveryJobRequest request =
        new RecoveryJobRequest("2026-10-01", "2026-10-31", true);
    RecoveryJob job = RecoveryJob.create(request, CLOCK);
    UUID jobId = job.jobId();

    when(repository.find(jobId))
        .thenReturn(
            Optional.of(new StoredRecoveryJob(job, "W/\"version-1\"")));

    RecoveryJob result = service.find(jobId).orElseThrow();

    assertSame(job, result);
    verify(repository).find(jobId);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldReturnEmptyForMissingJob() {
    UUID jobId = UUID.randomUUID();
    when(repository.find(jobId)).thenReturn(Optional.empty());

    assertTrue(service.find(jobId).isEmpty());

    verify(repository).find(jobId);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldPropagateReadFailure() {
    UUID jobId = UUID.randomUUID();
    IllegalStateException failure =
        new IllegalStateException("Simulated storage failure");

    when(repository.find(jobId)).thenThrow(failure);

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> service.find(jobId)));

    verify(repository).find(jobId);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldRejectNullRequestBeforeAccessingStorage() {
    assertThrows(
        NullPointerException.class,
        () -> service.create(null));

    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectNullJobIdBeforeAccessingStorage() {
    assertThrows(
        NullPointerException.class,
        () -> service.find(null));

    verifyNoInteractions(repository);
  }
}