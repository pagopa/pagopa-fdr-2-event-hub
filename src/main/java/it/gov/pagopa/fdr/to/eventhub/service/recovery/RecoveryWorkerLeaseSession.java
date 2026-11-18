package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryWorkerLeaseRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class RecoveryWorkerLeaseSession implements AutoCloseable {

  private static final long RENEW_INTERVAL_SECONDS = 15;
  private static final long CONFIRMATION_WINDOW_NANOS =
      TimeUnit.SECONDS.toNanos(45);

  private final RecoveryWorkerLeaseRepository repository;
  private final ScheduledExecutorService scheduler;
  private final LongSupplier ticker;
  private final String leaseId;

  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean lost = new AtomicBoolean();

  private volatile long lastConfirmedRequestStartedAt;
  private final AtomicReference<RuntimeException> renewalFailure =
          new AtomicReference<>();

  private ScheduledFuture<?> renewalTask;

  private RecoveryWorkerLeaseSession(
      RecoveryWorkerLeaseRepository repository,
      ScheduledExecutorService scheduler,
      LongSupplier ticker,
      String leaseId,
      long acquisitionStartedAt) {

    this.repository = repository;
    this.scheduler = scheduler;
    this.ticker = ticker;
    this.leaseId = leaseId;
    this.lastConfirmedRequestStartedAt = acquisitionStartedAt;
  }

  public static Optional<RecoveryWorkerLeaseSession> tryAcquire(
      RecoveryWorkerLeaseRepository repository,
      ScheduledExecutorService scheduler) {

    return tryAcquire(repository, scheduler, System::nanoTime);
  }

  static Optional<RecoveryWorkerLeaseSession> tryAcquire(
      RecoveryWorkerLeaseRepository repository,
      ScheduledExecutorService scheduler,
      LongSupplier ticker) {

    Objects.requireNonNull(repository, "repository is required");
    Objects.requireNonNull(scheduler, "scheduler is required");
    Objects.requireNonNull(ticker, "ticker is required");

    long acquisitionStartedAt = ticker.getAsLong();

    Optional<String> acquired = repository.tryAcquire();

    if (acquired.isEmpty()) {
      return Optional.empty();
    }

    String leaseId = acquired.orElseThrow();

    RecoveryWorkerLeaseSession session =
        new RecoveryWorkerLeaseSession(
            repository,
            scheduler,
            ticker,
            leaseId,
            acquisitionStartedAt);

    try {
      session.startRenewal();
      return Optional.of(session);

    } catch (RuntimeException failure) {
      try {
        repository.release(leaseId);
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }

      throw failure;
    }
  }

  public boolean isHeld() {
    if (closed.get() || lost.get()) {
      return false;
    }

    long elapsed =
        ticker.getAsLong() - lastConfirmedRequestStartedAt;

    if (elapsed >= CONFIRMATION_WINDOW_NANOS) {
      lost.set(true);
      return false;
    }

    return true;
  }

  public void requireHeld() {
    if (!isHeld()) {
      throw new IllegalStateException(
          "Recovery worker lease is no longer confirmed",
          renewalFailure.get());
    }
  }

  private void startRenewal() {
    requireHeld();

    renewalTask =
        scheduler.scheduleWithFixedDelay(
            this::renewLease,
            RENEW_INTERVAL_SECONDS,
            RENEW_INTERVAL_SECONDS,
            TimeUnit.SECONDS);
  }

  private synchronized void renewLease() {
    if (!isHeld()) {
      return;
    }

    long renewalStartedAt = ticker.getAsLong();

    try {
      repository.renew(leaseId);

    } catch (RuntimeException failure) {
      renewalFailure.set(failure);
      lost.set(true);
      return;
    }

    // A late response must not reactivate an uncertain session.
    if (!isHeld()) {
      return;
    }

    // Count from request start, not from response arrival.
    lastConfirmedRequestStartedAt = renewalStartedAt;
  }

  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    renewalTask.cancel(false);
    repository.release(leaseId);
  }
}