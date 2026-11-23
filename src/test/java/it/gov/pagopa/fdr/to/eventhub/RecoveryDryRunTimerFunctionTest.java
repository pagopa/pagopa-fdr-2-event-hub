package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import com.microsoft.azure.functions.ExecutionContext;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryDryRunRuntime;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDayWorker;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDispatcher;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecoveryDryRunTimerFunctionTest {

  private final RecoveryDryRunRuntime runtime =
      mock(RecoveryDryRunRuntime.class);

  @SuppressWarnings("unchecked")
  private final Supplier<RecoveryDryRunRuntime> factory =
      mock(Supplier.class);

  private final ExecutionContext context =
      mock(ExecutionContext.class);

  private final Logger logger = mock(Logger.class);

  @BeforeEach
  void setUp() {
    when(factory.get()).thenReturn(runtime);
    when(context.getLogger()).thenReturn(logger);
  }

  private RecoveryDryRunTimerFunction function(boolean enabled) {
    return new RecoveryDryRunTimerFunction(factory, () -> enabled);
  }

  @Test
  void shouldNotInitializeRuntimeWhenDisabled() throws Exception {
    RecoveryDryRunTimerFunction function = function(false);

    function.run("{}", context);

    verifyNoInteractions(factory, runtime, context, logger);
  }

  @Test
  void shouldDispatchAndLogJobOutcomeWhenEnabled()
      throws Exception {

    UUID jobId =
        UUID.fromString("11111111-1111-1111-1111-111111111111");

    RecoveryDryRunDispatcher.Attempt attempt =
        new RecoveryDryRunDispatcher.Attempt(
            jobId,
            RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED);

    when(runtime.runNext()).thenReturn(Optional.of(attempt));
    when(logger.isLoggable(Level.INFO)).thenReturn(true);

    RecoveryDryRunTimerFunction function = function(true);

    function.run("{}", context);

    verify(runtime).runNext();
    verify(runtime, never()).close();

    ArgumentCaptor<String> captor =
        ArgumentCaptor.forClass(String.class);

    verify(logger).info(captor.capture());

    assertTrue(captor.getValue().contains(jobId.toString()));
    assertTrue(captor.getValue().contains("DAY_COMPLETED"));
  }

  @Test
  void shouldHandleInvocationWithoutDispatchAttempt()
      throws Exception {

    when(runtime.runNext()).thenReturn(Optional.empty());

    RecoveryDryRunTimerFunction function = function(true);

    function.run("{}", context);

    verify(runtime).runNext();

    verify(logger).fine(
        "Recovery dry-run worker: no dispatch attempt");
  }

  @Test
  void shouldPropagateInitializationFailure() {
    IllegalStateException failure =
        new IllegalStateException("Simulated initialization failure");

    when(factory.get()).thenThrow(failure);

    RecoveryDryRunTimerFunction function = function(true);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> function.run("{}", context));

    assertSame(failure, thrown);
    verifyNoInteractions(runtime);
  }

  @Test
  void shouldPropagateDispatchFailureWithoutClosingSharedRuntime()
      throws Exception {

    IllegalStateException failure =
        new IllegalStateException("Simulated dispatch failure");

    when(runtime.runNext()).thenThrow(failure);

    RecoveryDryRunTimerFunction function = function(true);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> function.run("{}", context));

    assertSame(failure, thrown);
    verify(runtime, never()).close();
  }

  @Test
  void shouldPropagatePostgresFailure() throws Exception {
    SQLException failure =
        new SQLException("Simulated PostgreSQL failure");

    when(runtime.runNext()).thenThrow(failure);

    RecoveryDryRunTimerFunction function = function(true);

    SQLException thrown =
        assertThrows(
            SQLException.class,
            () -> function.run("{}", context));

    assertSame(failure, thrown);
    verify(runtime, never()).close();
  }

  @Test
  void shouldDispatchWithoutInfoLogWhenLevelIsDisabled()
      throws Exception {

    RecoveryDryRunDispatcher.Attempt attempt =
        new RecoveryDryRunDispatcher.Attempt(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED);

    when(runtime.runNext()).thenReturn(Optional.of(attempt));
    when(logger.isLoggable(Level.INFO)).thenReturn(false);

    RecoveryDryRunTimerFunction function = function(true);

    function.run("{}", context);

    verify(runtime).runNext();
    verify(logger, never()).info(anyString());
    verify(runtime, never()).close();
  }
}