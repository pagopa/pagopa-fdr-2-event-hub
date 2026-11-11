package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryExecutionRoutingWorkerTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private final RecoveryJobRepository jobs =
      mock(RecoveryJobRepository.class);

  private final RecoveryJobWorkerStateService states =
      mock(RecoveryJobWorkerStateService.class);

  private final RecoveryPublicationDiscoveryJobService discovery =
      mock(RecoveryPublicationDiscoveryJobService.class);

  private final RecoveryPublicationExecutionJobService execution =
      mock(RecoveryPublicationExecutionJobService.class);

  private final RecoveryWorkerLeaseSession lease =
      mock(RecoveryWorkerLeaseSession.class);

  @ParameterizedTest
  @EnumSource(RecoveryPublicationExecutionJobService.Outcome.class)
  void shouldRouteExecutionUsingAcquiredLease(
      RecoveryPublicationExecutionJobService.Outcome outcome)
      throws Exception {

    stubExecutionJob();

    when(execution.runOneDay(JOB_ID, lease))
        .thenReturn(
            new RecoveryPublicationExecutionJobService.Result(
                outcome, Optional.empty()));

    var actual = worker().runOneDay(JOB_ID);

    var expected = switch (outcome) {
      case DAY_COMPLETED -> RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED;
      case JOB_COMPLETED -> RecoveryDryRunDayWorker.Outcome.JOB_COMPLETED;
      case PAUSED -> RecoveryDryRunDayWorker.Outcome.PAUSED;
      case FAILED -> RecoveryDryRunDayWorker.Outcome.FAILED;
      case NOT_RUN -> RecoveryDryRunDayWorker.Outcome.NOT_RUN;
    };

    assertEquals(expected, actual);

    verify(execution).runOneDay(JOB_ID, lease);
    verify(lease).close();
    verifyNoInteractions(states, discovery);
  }

  @Test
  void shouldNotReadOrExecuteJobWhenLeaseIsBusy() throws Exception {
    var worker =
        new RecoveryDryRunDayWorker(
            jobs,
            states,
            discovery,
            execution,
            () -> Optional.empty());

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.LEASE_BUSY,
        worker.runOneDay(JOB_ID));

    verifyNoInteractions(jobs, states, discovery, execution, lease);
  }

  @Test
  void shouldCloseLeaseWhenExecutionFails() throws Exception {
    stubExecutionJob();

    var failure = new IOException("Execution failed");

    when(execution.runOneDay(JOB_ID, lease)).thenThrow(failure);

    var worker = worker();

    var actual =
        assertThrows(
            IOException.class,
            () -> worker.runOneDay(JOB_ID));

    assertSame(failure, actual);
    verify(lease).close();
  }

  @Test
  void shouldKeepExistingConstructorDryRunOnly() {
    stubExecutionJob();

    var worker =
        new RecoveryDryRunDayWorker(
            jobs,
            states,
            discovery,
            () -> Optional.of(lease));

    assertThrows(
        IllegalArgumentException.class,
        () -> worker.runOneDay(JOB_ID));

    verifyNoInteractions(execution, discovery, states);
    verify(lease).close();
  }

  private RecoveryDryRunDayWorker worker() {
    return new RecoveryDryRunDayWorker(
        jobs,
        states,
        discovery,
        execution,
        () -> Optional.of(lease));
  }

  private void stubExecutionJob() {
    var day = LocalDate.of(2026, 10, 5);
    var now = Instant.parse("2026-11-11T12:00:00Z");

    var job =
        new RecoveryJob(
            JOB_ID,
            day,
            day,
            false,
            RecoveryJobStatus.PENDING,
            day,
            now,
            now);

    when(jobs.find(JOB_ID))
        .thenReturn(
            Optional.of(new StoredRecoveryJob(job, "etag")));
  }
}