package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDayWorker;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDispatcher;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class RecoveryDryRunRuntimeTest {

  private final RecoveryDryRunDispatcher dispatcher =
      mock(RecoveryDryRunDispatcher.class);

  private final RecoveryPostgresDataSource postgres =
      mock(RecoveryPostgresDataSource.class);

  private final ScheduledExecutorService renewals =
      mock(ScheduledExecutorService.class);

  private final RecoveryDryRunRuntime runtime =
      new RecoveryDryRunRuntime(dispatcher, postgres, renewals);

  @Test
  void shouldDelegateDispatchWithoutClosingResources()
      throws Exception {

    RecoveryDryRunDispatcher.Attempt attempt =
        new RecoveryDryRunDispatcher.Attempt(
            UUID.randomUUID(),
            RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED);

    Optional<RecoveryDryRunDispatcher.Attempt> expected =
        Optional.of(attempt);

    when(dispatcher.runNext()).thenReturn(expected);

    assertSame(expected, runtime.runNext());

    verifyNoInteractions(postgres, renewals);
  }

  @Test
  void shouldReturnEmptyWhenDispatcherHasNoAttempt()
      throws Exception {

    when(dispatcher.runNext()).thenReturn(Optional.empty());

    assertTrue(runtime.runNext().isEmpty());
    verifyNoInteractions(postgres, renewals);
  }

  @Test
  void shouldPropagateDispatchFailureAndRemainUsable()
      throws Exception {

    IllegalStateException failure =
        new IllegalStateException("Simulated dispatch failure");

    when(dispatcher.runNext())
        .thenThrow(failure)
        .thenReturn(Optional.empty());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            runtime::runNext);

    assertSame(failure, thrown);
    assertTrue(runtime.runNext().isEmpty());

    runtime.close();

    verify(renewals).shutdownNow();
    verify(postgres).close();
  }

  @Test
  void shouldStopSchedulerBeforeClosingPool() {
    runtime.close();

    InOrder order = inOrder(renewals, postgres);

    order.verify(renewals).shutdownNow();
    order.verify(postgres).close();

    verifyNoInteractions(dispatcher);
  }

  @Test
  void shouldCloseResourcesOnlyOnce() {
    runtime.close();
    runtime.close();

    verify(renewals).shutdownNow();
    verify(postgres).close();
  }

  @Test
  void shouldRejectDispatchAfterClose() {
    runtime.close();

    assertThrows(
        IllegalStateException.class,
        runtime::runNext);

    verifyNoInteractions(dispatcher);
  }

  @Test
  void shouldClosePoolEvenWhenSchedulerShutdownFails() {
    IllegalStateException failure =
        new IllegalStateException("Simulated scheduler shutdown failure");

    when(renewals.shutdownNow()).thenThrow(failure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            runtime::close);

    assertSame(failure, thrown);
    verify(postgres).close();

    assertThrows(
        IllegalStateException.class,
        runtime::runNext);
  }

  @Test
  void shouldWaitForCurrentDispatchBeforeClosingResources()
      throws Exception {

    CountDownLatch dispatchStarted = new CountDownLatch(1);
    CountDownLatch allowCompletion = new CountDownLatch(1);
    CountDownLatch closeRequested = new CountDownLatch(1);

    when(dispatcher.runNext()).thenAnswer(invocation -> {
      dispatchStarted.countDown();

      if (!allowCompletion.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
            "Test did not allow dispatch to finish");
      }

      return Optional.empty();
    });

    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<Optional<RecoveryDryRunDispatcher.Attempt>> dispatch =
          executor.submit(runtime::runNext);

      assertTrue(dispatchStarted.await(5, TimeUnit.SECONDS));

      Future<?> closing =
          executor.submit(() -> {
            closeRequested.countDown();
            runtime.close();
          });

      assertTrue(closeRequested.await(5, TimeUnit.SECONDS));

      assertThrows(
          TimeoutException.class,
          () -> closing.get(100, TimeUnit.MILLISECONDS));

      verifyNoInteractions(postgres, renewals);

      allowCompletion.countDown();

      assertTrue(dispatch.get(5, TimeUnit.SECONDS).isEmpty());
      closing.get(5, TimeUnit.SECONDS);

      verify(renewals).shutdownNow();
      verify(postgres).close();

    } finally {
      allowCompletion.countDown();
      executor.shutdownNow();
      runtime.close();
    }
  }

  @ParameterizedTest
  @CsvSource({
      "RECOVERY_DISCOVERY_PAGE_SIZE, 0",
      "RECOVERY_DISCOVERY_PAGE_SIZE, 101",
      "RECOVERY_JOB_PAGE_SIZE, 0",
      "RECOVERY_JOB_PAGE_SIZE, 101",
      "RECOVERY_POSTGRES_QUERY_TIMEOUT_SECONDS, 0",
      "RECOVERY_POSTGRES_QUERY_TIMEOUT_SECONDS, 61"
  })
  void shouldRejectOutOfRangeSettingsBeforeCreatingClients(
      String setting,
      String value) {

    Map<String, String> environment = Map.of(setting, value);

    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryDryRunRuntime.fromEnvironment(environment));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "RECOVERY_DISCOVERY_PAGE_SIZE",
      "RECOVERY_JOB_PAGE_SIZE",
      "RECOVERY_POSTGRES_QUERY_TIMEOUT_SECONDS"
  })
  void shouldRejectNonNumericSettingsBeforeCreatingClients(
      String setting) {

    Map<String, String> environment = Map.of(setting, "invalid");

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> RecoveryDryRunRuntime.fromEnvironment(environment));

    assertTrue(thrown.getMessage().startsWith(setting));
  }

  @Test
  void shouldRejectNullEnvironment() {
    assertThrows(
        NullPointerException.class,
        () -> RecoveryDryRunRuntime.fromEnvironment(null));
  }
}