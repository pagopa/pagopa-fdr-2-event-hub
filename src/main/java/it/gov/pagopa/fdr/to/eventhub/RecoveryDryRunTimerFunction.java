package it.gov.pagopa.fdr.to.eventhub;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.TimerTrigger;
import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryDryRunRuntime;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryDryRunRuntimeProvider;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDispatcher;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RecoveryDryRunTimerFunction {

  private static final RecoveryDryRunRuntimeProvider SHARED_RUNTIME =
      new RecoveryDryRunRuntimeProvider();

  private final Supplier<RecoveryDryRunRuntime> runtimeFactory;
  private final BooleanSupplier enabled;

  public RecoveryDryRunTimerFunction() {
    this(
        SHARED_RUNTIME::get,
        () ->
            "true".equalsIgnoreCase(
                System.getenv("RECOVERY_WORKER_ENABLED")));
  }

  RecoveryDryRunTimerFunction(
      Supplier<RecoveryDryRunRuntime> runtimeFactory,
      BooleanSupplier enabled) {

    this.runtimeFactory =
        Objects.requireNonNull(
            runtimeFactory,
            "runtimeFactory is required");

    this.enabled =
        Objects.requireNonNull(enabled, "enabled is required");
  }

  @FunctionName("RecoveryDryRunWorker")
  public void run(
      @TimerTrigger(
          name = "timerInfo",
          schedule = "%RECOVERY_WORKER_SCHEDULE%")
      String timerInfo,
      ExecutionContext context)
      throws SQLException, DataClientException, DataServiceException {

    if (!enabled.getAsBoolean()) {
      return;
    }

    Logger logger = context.getLogger();

    Optional<RecoveryDryRunDispatcher.Attempt> result =
        runtimeFactory.get().runNext();

    if (result.isEmpty()) {
      logger.fine("Recovery dry-run worker: no dispatch attempt");
      return;
    }

    RecoveryDryRunDispatcher.Attempt attempt =
        result.orElseThrow();

    if (logger.isLoggable(Level.INFO)) {
        logger.info("Recovery dry-run worker: jobId=%s, outcome=%s".formatted(attempt.jobId(), attempt.outcome()));
    }
  }
}