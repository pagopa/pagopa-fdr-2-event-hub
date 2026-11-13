package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryPublicationExecutionJobServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 5);

  private static final Instant NOW =
      Instant.parse("2026-11-11T12:00:00Z");

  private final RecoveryJobRepository jobs =
      mock(RecoveryJobRepository.class);

  private final RecoveryPersistedPublicationDiscoveryService discovery =
      mock(RecoveryPersistedPublicationDiscoveryService.class);

  private final RecoveryPublicationExecutionDayService execution =
      mock(RecoveryPublicationExecutionDayService.class);

  private final RecoveryJobWorkerStateService states =
      mock(RecoveryJobWorkerStateService.class);

  private final RecoveryExecutionJobWorkerStateService executionStates =
      mock(RecoveryExecutionJobWorkerStateService.class);

  private final RecoveryWorkerLeaseSession lease =
      mock(RecoveryWorkerLeaseSession.class);

  private final AtomicReference<RecoveryJob> current =
      new AtomicReference<>();

  private final RecoveryPublicationExecutionJobService service =
      new RecoveryPublicationExecutionJobService(
          jobs, discovery, execution, states, executionStates);

  @Test
  void shouldCompleteLastDayAfterDiscoveryAndExecution()
      throws Exception {

    stubJob(job(RecoveryJobStatus.RUNNING, false));
    var result = stubPipeline(
        RecoveryPublicationExecutionDayService.Status.COMPLETED);

    when(executionStates.apply(eq(result), any()))
        .thenReturn(
            Optional.of(job(RecoveryJobStatus.COMPLETED, false)));

    var actual = service.runOneDay(JOB_ID, lease);

    assertEquals(
        RecoveryPublicationExecutionJobService.Outcome.JOB_COMPLETED,
        actual.outcome());

    assertEquals(result, actual.execution().orElseThrow());

    verify(discovery).discover(
        eq(JOB_ID), eq(DAY), any(), any());

    verify(execution).execute(
        eq(JOB_ID), eq(DAY), any(), any());

    verify(executionStates).apply(eq(result), any());
  }

  @Test
  void shouldStartPendingJobBeforeProcessing() throws Exception {
    stubJob(job(RecoveryJobStatus.PENDING, false));

    when(states.tryStart(eq(JOB_ID), any()))
        .thenAnswer(invocation -> {
          var running = job(RecoveryJobStatus.RUNNING, false);
          current.set(running);
          return Optional.of(running);
        });

    var result = stubPipeline(
        RecoveryPublicationExecutionDayService.Status.COMPLETED);

    when(executionStates.apply(eq(result), any()))
        .thenReturn(
            Optional.of(job(RecoveryJobStatus.COMPLETED, false)));

    var actual = service.runOneDay(JOB_ID, lease);

    assertEquals(
        RecoveryPublicationExecutionJobService.Outcome.JOB_COMPLETED,
        actual.outcome());

    verify(states).tryStart(eq(JOB_ID), any());
  }

  @Test
  void shouldReportCompletedDayWhenMoreDaysRemain() throws Exception {
    var running =
        new RecoveryJob(
            JOB_ID,
            DAY,
            DAY.plusDays(1),
            false,
            RecoveryJobStatus.RUNNING,
            DAY,
            NOW,
            NOW);

    stubJob(running);

    var result = stubPipeline(
        RecoveryPublicationExecutionDayService.Status.COMPLETED);

    var advanced =
        new RecoveryJob(
            JOB_ID,
            DAY,
            DAY.plusDays(1),
            false,
            RecoveryJobStatus.RUNNING,
            DAY.plusDays(1),
            NOW,
            NOW);

    when(executionStates.apply(eq(result), any()))
        .thenReturn(Optional.of(advanced));

    assertEquals(
        RecoveryPublicationExecutionJobService.Outcome.DAY_COMPLETED,
        service.runOneDay(JOB_ID, lease).outcome());
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = {"PAUSED", "COMPLETED", "FAILED"})
  void shouldNotRunInactiveJob(RecoveryJobStatus status)
      throws Exception {

    stubJob(job(status, false));

    assertEquals(
        RecoveryPublicationExecutionJobService.Outcome.NOT_RUN,
        service.runOneDay(JOB_ID, lease).outcome());

    verifyNoInteractions(
        discovery, execution, states, executionStates);
  }

  @Test
  void shouldRejectDryRunJob() {
    stubJob(job(RecoveryJobStatus.RUNNING, true));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.runOneDay(JOB_ID, lease));

    verifyNoInteractions(
        discovery, execution, states, executionStates);
  }

  @Test
  void shouldAcknowledgePauseBeforeDiscovery() throws Exception {
    stubJob(job(RecoveryJobStatus.PAUSING, false));

    stubPauseAcknowledgement();

    assertEquals(
        RecoveryPublicationExecutionJobService.Outcome.PAUSED,
        service.runOneDay(JOB_ID, lease).outcome());

    verifyNoInteractions(discovery, execution, executionStates);
  }

  @Test
  void shouldNotExecuteWhenDiscoveryStopsForPause() throws Exception {
    stubJob(job(RecoveryJobStatus.RUNNING, false));
    stubPauseAcknowledgement();

    when(discovery.discover(eq(JOB_ID), eq(DAY), any(), any()))
        .thenAnswer(invocation -> {
          current.set(job(RecoveryJobStatus.PAUSING, false));

          return RecoveryPublicationDiscoveryCheckpoint.start(
              JOB_ID, DAY);
        });

    assertEquals(
        RecoveryPublicationExecutionJobService.Outcome.PAUSED,
        service.runOneDay(JOB_ID, lease).outcome());

    verifyNoInteractions(execution, executionStates);
  }

  @Test
  void shouldObservePauseArrivingAfterDiscoveryCompletes()
      throws Exception {

    stubJob(job(RecoveryJobStatus.RUNNING, false));
    stubPauseAcknowledgement();

    when(discovery.discover(eq(JOB_ID), eq(DAY), any(), any()))
        .thenAnswer(invocation -> {
          current.set(job(RecoveryJobStatus.PAUSING, false));
          return checkpoint(false);
        });

    assertEquals(
        RecoveryPublicationExecutionJobService.Outcome.PAUSED,
        service.runOneDay(JOB_ID, lease).outcome());

    verifyNoInteractions(execution, executionStates);
  }

  @Test
  void shouldUseLiveJobStateInExecutionPausePredicate()
      throws Exception {

    stubJob(job(RecoveryJobStatus.RUNNING, false));

    var result = stubPipeline(
        RecoveryPublicationExecutionDayService.Status.PAUSED);

    when(execution.execute(eq(JOB_ID), eq(DAY), any(), any()))
        .thenAnswer(invocation -> {
          BooleanSupplier shouldContinue =
              invocation.getArgument(2);

          assertEquals(true, shouldContinue.getAsBoolean());

          current.set(job(RecoveryJobStatus.PAUSING, false));

          assertEquals(false, shouldContinue.getAsBoolean());

          return result;
        });

    when(executionStates.apply(eq(result), any()))
        .thenReturn(
            Optional.of(job(RecoveryJobStatus.PAUSED, false)));

    assertEquals(
        RecoveryPublicationExecutionJobService.Outcome.PAUSED,
        service.runOneDay(JOB_ID, lease).outcome());
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryPublicationExecutionDayService.Status.class,
      names = {"BLOCKED_UNRESOLVED", "BLOCKED_UNCERTAIN"})
  void shouldApplyBlockedOutcomeAsFailed(
      RecoveryPublicationExecutionDayService.Status status)
      throws Exception {

    stubJob(job(RecoveryJobStatus.RUNNING, false));
    var result = stubPipeline(status);

    when(executionStates.apply(eq(result), any()))
        .thenReturn(
            Optional.of(job(RecoveryJobStatus.FAILED, false)));

    assertEquals(
        RecoveryPublicationExecutionJobService.Outcome.FAILED,
        service.runOneDay(JOB_ID, lease).outcome());

    verify(executionStates).apply(eq(result), any());
  }

  @Test
  void shouldPropagateDiscoveryFailureWithoutExecuting()
      throws Exception {

    stubJob(job(RecoveryJobStatus.RUNNING, false));
    var failure = new SQLException("Discovery failed");

    when(discovery.discover(eq(JOB_ID), eq(DAY), any(), any()))
        .thenThrow(failure);

    var actual =
        assertThrows(
            SQLException.class,
            () -> service.runOneDay(JOB_ID, lease));

    assertSame(failure, actual);
    verifyNoInteractions(execution, executionStates);
  }

  @Test
  void shouldStopWhenLeaseIsLostAfterDiscovery() throws Exception {
    stubJob(job(RecoveryJobStatus.RUNNING, false));

    var held = new AtomicBoolean(true);
    var failure = new IllegalStateException("Lease lost");

    doAnswer(invocation -> {
      if (!held.get()) {
        throw failure;
      }

      return null;
    }).when(lease).requireHeld();

    when(discovery.discover(eq(JOB_ID), eq(DAY), any(), any()))
        .thenAnswer(invocation -> {
          held.set(false);
          return checkpoint(false);
        });

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.runOneDay(JOB_ID, lease));

    assertSame(failure, actual);
    verifyNoInteractions(execution, executionStates);
  }

  @Test
  void shouldRejectExecutionResultForAnotherJob() throws Exception {
    stubJob(job(RecoveryJobStatus.RUNNING, false));

    when(discovery.discover(eq(JOB_ID), eq(DAY), any(), any()))
        .thenReturn(checkpoint(false));

    var invalid =
        new RecoveryPublicationExecutionDayService.DayResult(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            DAY,
            RecoveryPublicationExecutionDayService.Status.COMPLETED,
            1L, 1L, 0L, 0L, 0L, 1L);

    when(execution.execute(eq(JOB_ID), eq(DAY), any(), any()))
        .thenReturn(invalid);

    assertThrows(
        IllegalStateException.class,
        () -> service.runOneDay(JOB_ID, lease));

    verifyNoInteractions(executionStates);
  }

  private void stubJob(RecoveryJob job) {
    current.set(job);

    when(jobs.find(JOB_ID))
        .thenAnswer(invocation ->
            Optional.of(
                new StoredRecoveryJob(current.get(), "etag")));
  }

  private void stubPauseAcknowledgement() {
    when(states.acknowledgePause(eq(JOB_ID), any()))
        .thenReturn(
            Optional.of(job(RecoveryJobStatus.PAUSED, false)));
  }

  private RecoveryPublicationExecutionDayService.DayResult stubPipeline(
      RecoveryPublicationExecutionDayService.Status status)
      throws Exception {

    boolean unresolved =
        status
            == RecoveryPublicationExecutionDayService.Status
                .BLOCKED_UNRESOLVED;

    boolean uncertain =
        status
            == RecoveryPublicationExecutionDayService.Status
                .BLOCKED_UNCERTAIN;

    boolean paused =
        status == RecoveryPublicationExecutionDayService.Status.PAUSED;

    when(discovery.discover(eq(JOB_ID), eq(DAY), any(), any()))
        .thenReturn(checkpoint(unresolved));

    var result =
        new RecoveryPublicationExecutionDayService.DayResult(
            JOB_ID,
            DAY,
            status,
            paused ? 0L : 1L,
            paused || unresolved || uncertain ? 0L : 1L,
            0L,
            unresolved ? 1L : 0L,
            uncertain ? 1L : 0L,
            paused ? 0L : 1L);

    when(execution.execute(eq(JOB_ID), eq(DAY), any(), any()))
        .thenReturn(result);

    return result;
  }

  private static RecoveryPublicationDiscoveryCheckpoint checkpoint(
      boolean unresolved) {

    return new RecoveryPublicationDiscoveryCheckpoint(
        JOB_ID,
        DAY,
        1L,
        1L,
        unresolved ? 0L : 1L,
        0L,
        unresolved ? 1L : 0L,
        1L,
        RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED);
  }

  private static RecoveryJob job(
      RecoveryJobStatus status,
      boolean dryRun) {

    return new RecoveryJob(
        JOB_ID,
        DAY,
        DAY,
        dryRun,
        status,
        status == RecoveryJobStatus.COMPLETED
            ? DAY.plusDays(1)
            : DAY,
        NOW,
        NOW);
  }
}