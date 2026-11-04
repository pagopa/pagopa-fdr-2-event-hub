package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryRunnableJobRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryDryRunDispatcherTest {

  private static final UUID FIRST =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final UUID SECOND =
      UUID.fromString("22222222-2222-2222-2222-222222222222");

  private final RecoveryRunnableJobRepository jobs =
      mock(RecoveryRunnableJobRepository.class);

  private final RecoveryDryRunDayWorker worker =
      mock(RecoveryDryRunDayWorker.class);

  private final RecoveryDryRunDispatcher dispatcher =
      new RecoveryDryRunDispatcher(jobs, worker, 2);

  private RecoveryRunnableJobRepository.JobPage page(
      List<UUID> jobIds,
      String token) {

    return new RecoveryRunnableJobRepository.JobPage(jobIds, token);
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryDryRunDayWorker.Outcome.class,
      names = "LEASE_BUSY",
      mode = EnumSource.Mode.EXCLUDE)
  void shouldConsumeCandidatesOneAtATimeWithoutReloadingPage(
      RecoveryDryRunDayWorker.Outcome outcome)
      throws Exception {

    when(jobs.findPage(null, 2))
        .thenReturn(page(List.of(FIRST, SECOND), null));

    when(worker.runOneDay(FIRST)).thenReturn(outcome);
    when(worker.runOneDay(SECOND))
        .thenReturn(RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED);

    RecoveryDryRunDispatcher.Attempt first =
        dispatcher.runNext().orElseThrow();

    assertEquals(FIRST, first.jobId());
    assertEquals(outcome, first.outcome());

    RecoveryDryRunDispatcher.Attempt second =
        dispatcher.runNext().orElseThrow();

    assertEquals(SECOND, second.jobId());

    verify(jobs).findPage(null, 2);
    verify(worker).runOneDay(FIRST);
    verify(worker).runOneDay(SECOND);
  }

  @Test
  void shouldReadNextPageAfterConsumingCurrentPage()
      throws Exception {

    when(jobs.findPage(null, 2))
        .thenReturn(page(List.of(FIRST), "next-page"));

    when(jobs.findPage("next-page", 2))
        .thenReturn(page(List.of(SECOND), null));

    when(worker.runOneDay(FIRST))
        .thenReturn(RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED);

    when(worker.runOneDay(SECOND))
        .thenReturn(RecoveryDryRunDayWorker.Outcome.JOB_COMPLETED);

    dispatcher.runNext();

    RecoveryDryRunDispatcher.Attempt second =
        dispatcher.runNext().orElseThrow();

    assertEquals(SECOND, second.jobId());

    verify(jobs).findPage(null, 2);
    verify(jobs).findPage("next-page", 2);
  }

  @Test
  void shouldContinueAfterEmptyPageOnNextInvocation()
      throws Exception {

    when(jobs.findPage(null, 2))
        .thenReturn(page(List.of(), "next-page"));

    when(jobs.findPage("next-page", 2))
        .thenReturn(page(List.of(FIRST), null));

    when(worker.runOneDay(FIRST))
        .thenReturn(RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED);

    Optional<RecoveryDryRunDispatcher.Attempt> first =
        dispatcher.runNext();

    assertTrue(first.isEmpty());
    verifyNoInteractions(worker);

    RecoveryDryRunDispatcher.Attempt second =
        dispatcher.runNext().orElseThrow();

    assertEquals(FIRST, second.jobId());
    verify(jobs).findPage("next-page", 2);
  }

  @Test
  void shouldRestartScanAfterTerminalPage() throws Exception {
    when(jobs.findPage(null, 2))
        .thenReturn(
            page(List.of(FIRST), null),
            page(List.of(SECOND), null));

    when(worker.runOneDay(FIRST))
        .thenReturn(RecoveryDryRunDayWorker.Outcome.JOB_COMPLETED);

    when(worker.runOneDay(SECOND))
        .thenReturn(RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED);

    dispatcher.runNext();

    RecoveryDryRunDispatcher.Attempt nextScan =
        dispatcher.runNext().orElseThrow();

    assertEquals(SECOND, nextScan.jobId());

    verify(jobs, times(2)).findPage(null, 2);
  }

  @Test
  void shouldKeepCandidateWhenLeaseIsOccupied()
      throws Exception {

    when(jobs.findPage(null, 2))
        .thenReturn(page(List.of(FIRST, SECOND), null));

    when(worker.runOneDay(FIRST))
        .thenReturn(
            RecoveryDryRunDayWorker.Outcome.LEASE_BUSY,
            RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED);

    RecoveryDryRunDispatcher.Attempt busy =
        dispatcher.runNext().orElseThrow();

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.LEASE_BUSY,
        busy.outcome());

    RecoveryDryRunDispatcher.Attempt retried =
        dispatcher.runNext().orElseThrow();

    assertEquals(FIRST, retried.jobId());
    assertEquals(
        RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED,
        retried.outcome());

    verify(jobs).findPage(null, 2);
    verify(worker, times(2)).runOneDay(FIRST);
  }

  @Test
  void shouldPropagateJobFailureAndContinueWithOtherCandidates()
      throws Exception {

    when(jobs.findPage(null, 2))
        .thenReturn(page(List.of(FIRST, SECOND), null));

    IllegalStateException failure =
        new IllegalStateException("Simulated job failure");

    when(worker.runOneDay(FIRST)).thenThrow(failure);

    when(worker.runOneDay(SECOND))
        .thenReturn(RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            dispatcher::runNext);

    assertSame(failure, thrown);

    RecoveryDryRunDispatcher.Attempt next =
        dispatcher.runNext().orElseThrow();

    assertEquals(SECOND, next.jobId());

    verify(jobs).findPage(null, 2);
    verify(worker).runOneDay(FIRST);
  }

  @Test
  void shouldRetryPageReadAfterRepositoryFailure()
      throws Exception {

    IllegalStateException failure =
        new IllegalStateException("Simulated page read failure");

    when(jobs.findPage(null, 2))
        .thenThrow(failure)
        .thenReturn(page(List.of(FIRST), null));

    when(worker.runOneDay(FIRST))
        .thenReturn(RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            dispatcher::runNext);

    assertSame(failure, thrown);

    RecoveryDryRunDispatcher.Attempt next =
        dispatcher.runNext().orElseThrow();

    assertEquals(FIRST, next.jobId());

    verify(jobs, times(2)).findPage(null, 2);
  }

  @Test
  void shouldRejectOverlappingInvocationWhileWorkerIsRunning()
      throws Exception {

    when(jobs.findPage(null, 2))
        .thenReturn(page(List.of(FIRST), null));

    when(worker.runOneDay(FIRST)).thenAnswer(invocation -> {
      // Simulate another invocation arriving before this one finishes.
      assertTrue(dispatcher.runNext().isEmpty());

      return RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED;
    });

    RecoveryDryRunDispatcher.Attempt result =
        dispatcher.runNext().orElseThrow();

    assertEquals(FIRST, result.jobId());

    verify(jobs).findPage(null, 2);
    verify(worker).runOneDay(FIRST);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 101})
  void shouldRejectInvalidPageSize(int pageSize) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryDryRunDispatcher(jobs, worker, pageSize));

    verifyNoInteractions(jobs, worker);
  }
}