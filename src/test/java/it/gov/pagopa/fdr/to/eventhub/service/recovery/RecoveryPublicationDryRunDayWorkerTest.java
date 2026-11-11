package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecoveryPublicationDryRunDayWorkerTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");

  private final RecoveryJobRepository jobs =
      mock(RecoveryJobRepository.class);

  private final RecoveryJobWorkerStateService states =
      mock(RecoveryJobWorkerStateService.class);

  private final RecoveryPublicationDiscoveryJobService discovery =
      mock(RecoveryPublicationDiscoveryJobService.class);

  private final RecoveryWorkerLeaseSession lease =
      mock(RecoveryWorkerLeaseSession.class);

  private final RecoveryDryRunDayWorker worker =
      new RecoveryDryRunDayWorker(
          jobs, states, discovery, () -> Optional.of(lease));

  @BeforeEach
  void setUp() throws Exception {
    stubJob(job(RecoveryJobStatus.RUNNING, DAY, DAY.plusDays(1)));

    when(discovery.discover(JOB_ID, DAY, lease))
        .thenReturn(completedCheckpoint());

    when(states.completeDryRunDay(
            any(RecoveryPublicationDiscoveryCheckpoint.class),
            any(Runnable.class)))
        .thenReturn(
            Optional.of(
                job(
                    RecoveryJobStatus.RUNNING,
                    DAY.plusDays(1),
                    DAY.plusDays(1))));
  }

  @Test
  void shouldAdvanceDayUsingPublicationCheckpoint() throws Exception {
    assertEquals(
        RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED,
        worker.runOneDay(JOB_ID));

    verify(discovery).discover(JOB_ID, DAY, lease);

    verify(states).completeDryRunDay(
        eq(completedCheckpoint()),
        any(Runnable.class));

    verify(lease).close();
  }

  @Test
  void shouldStartPendingJobBeforeDiscovery() throws Exception {
    stubJob(job(RecoveryJobStatus.PENDING, DAY, DAY.plusDays(1)));

    when(states.tryStart(eq(JOB_ID), any(Runnable.class)))
        .thenReturn(
            Optional.of(
                job(
                    RecoveryJobStatus.RUNNING,
                    DAY,
                    DAY.plusDays(1))));

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED,
        worker.runOneDay(JOB_ID));

    verify(states).tryStart(eq(JOB_ID), any(Runnable.class));
    verify(discovery).discover(JOB_ID, DAY, lease);
    verify(lease).close();
  }

  @Test
  void shouldCompleteJobAfterLastDay() throws Exception {
    stubJob(job(RecoveryJobStatus.RUNNING, DAY, DAY));

    when(states.completeDryRunDay(
            any(RecoveryPublicationDiscoveryCheckpoint.class),
            any(Runnable.class)))
        .thenReturn(
            Optional.of(
                job(
                    RecoveryJobStatus.COMPLETED,
                    DAY.plusDays(1),
                    DAY)));

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.JOB_COMPLETED,
        worker.runOneDay(JOB_ID));

    verify(lease).close();
  }

  @Test
  void shouldAcknowledgePauseAfterIncompleteDiscovery()
      throws Exception {

    when(discovery.discover(JOB_ID, DAY, lease))
        .thenReturn(
            RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY));

    when(states.acknowledgePause(eq(JOB_ID), any(Runnable.class)))
        .thenReturn(
            Optional.of(
                job(
                    RecoveryJobStatus.PAUSED,
                    DAY,
                    DAY.plusDays(1))));

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.PAUSED,
        worker.runOneDay(JOB_ID));

    verify(states, never()).completeDryRunDay(
        any(RecoveryPublicationDiscoveryCheckpoint.class),
        any(Runnable.class));

    verify(lease).close();
  }

  @Test
  void shouldRejectCheckpointOfAnotherJob() throws Exception {
    var wrong =
        RecoveryPublicationDiscoveryCheckpoint.start(
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                DAY)
            .completeDiscovery();

    when(discovery.discover(JOB_ID, DAY, lease)).thenReturn(wrong);

    assertThrows(
        IllegalStateException.class,
        () -> worker.runOneDay(JOB_ID));

    verifyNoInteractions(states);
    verify(lease).close();
  }

  @Test
  void shouldPropagateSourceIOExceptionAndReleaseLease()
      throws Exception {

    var failure = new IOException("Cannot read source blob");

    when(discovery.discover(JOB_ID, DAY, lease))
        .thenThrow(failure);

    var actual =
        assertThrows(
            IOException.class,
            () -> worker.runOneDay(JOB_ID));

    assertSame(failure, actual);

    verifyNoInteractions(states);
    verify(lease).close();
  }

  @Test
  void shouldNotAdvanceAfterLeaseLossDuringDiscovery()
      throws Exception {

    var failure = new IllegalStateException("Lease ownership lost");

    when(discovery.discover(JOB_ID, DAY, lease))
        .thenAnswer(invocation -> {
          doThrow(failure).when(lease).requireHeld();
          return completedCheckpoint();
        });

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> worker.runOneDay(JOB_ID));

    assertSame(failure, actual);

    verifyNoInteractions(states);
    verify(lease).close();
  }

  @Test
  void shouldSkipWhenLeaseIsBusy() throws Exception {
    var busyWorker =
        new RecoveryDryRunDayWorker(
            jobs, states, discovery, Optional::empty);

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.LEASE_BUSY,
        busyWorker.runOneDay(JOB_ID));

    verifyNoInteractions(jobs, states, discovery, lease);
  }

  private void stubJob(RecoveryJob job) {
    when(jobs.find(JOB_ID))
        .thenReturn(Optional.of(new StoredRecoveryJob(job, "job-etag-1")));
  }

  private static RecoveryPublicationDiscoveryCheckpoint completedCheckpoint() {
    return RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY)
        .completeDiscovery();
  }

  private static RecoveryJob job(
      RecoveryJobStatus status,
      LocalDate nextDate,
      LocalDate dateTo) {

    return new RecoveryJob(
        JOB_ID,
        DAY,
        dateTo,
        true,
        status,
        nextDate,
        NOW.minusSeconds(3600),
        NOW);
  }
}