package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RecoveryDryRunRuntimeProvider
    implements AutoCloseable {

  private final Supplier<RecoveryDryRunRuntime> factory;
  private final Consumer<Runnable> shutdownHookRegistrar;

  private RecoveryDryRunRuntime runtime;
  private boolean closed;

  public RecoveryDryRunRuntimeProvider() {
    this(
        RecoveryDryRunRuntime::fromEnvironment,
        action ->
            Runtime.getRuntime().addShutdownHook(
                new Thread(action, "fdr-recovery-shutdown")));
  }

  RecoveryDryRunRuntimeProvider(
      Supplier<RecoveryDryRunRuntime> factory,
      Consumer<Runnable> shutdownHookRegistrar) {

    this.factory =
        Objects.requireNonNull(factory, "factory is required");

    this.shutdownHookRegistrar =
        Objects.requireNonNull(
            shutdownHookRegistrar,
            "shutdownHookRegistrar is required");
  }

  public synchronized RecoveryDryRunRuntime get() {
    if (closed) {
      throw new IllegalStateException(
          "Recovery runtime provider has been closed");
    }

    if (runtime != null) {
      return runtime;
    }

    RecoveryDryRunRuntime created =
        Objects.requireNonNull(
            factory.get(),
            "factory must return a runtime");

    try {
      shutdownHookRegistrar.accept(this::close);

    } catch (RuntimeException failure) {
      try {
        created.close();
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }

      throw failure;
    }

    runtime = created;
    return runtime;
  }

  @Override
  public void close() {
    RecoveryDryRunRuntime existing;

    synchronized (this) {
      if (closed) {
        return;
      }

      closed = true;
      existing = runtime;
      runtime = null;
    }

    if (existing != null) {
      existing.close();
    }
  }
}