package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class RecoveryDryRunDayWorker {

  public enum Outcome {
    LEASE_BUSY,
    NOT_RUN,
    STATE_CHANGED,
    DAY_COMPLETED,
    JOB_COMPLETED,
    PAUSED
  }

  private final RecoveryJobRepository jobs;
  private final RecoveryJobWorkerStateService states;
  private final RecoveryDiscoveryJobService discovery;
  private final Supplier<Optional<RecoveryWorkerLeaseSession>> leaseAcquirer;

  public RecoveryDryRunDayWorker(
      RecoveryJobRepository jobs,
      RecoveryJobWorkerStateService states,
      RecoveryDiscoveryJobService discovery,
      Supplier<Optional<RecoveryWorkerLeaseSession>> leaseAcquirer) {

    this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    this.states = Objects.requireNonNull(states, "states is required");
    this.discovery =
        Objects.requireNonNull(discovery, "discovery is required");
    this.leaseAcquirer =
        Objects.requireNonNull(leaseAcquirer, "leaseAcquirer is required");
  }

  public Outcome runOneDay(UUID jobId)
      throws SQLException, DataClientException, DataServiceException {

    Objects.requireNonNull(jobId, "jobId is required");

    Optional<RecoveryWorkerLeaseSession> acquired = leaseAcquirer.get();

    if (acquired.isEmpty()) {
      return Outcome.LEASE_BUSY;
    }

    try (RecoveryWorkerLeaseSession lease = acquired.orElseThrow()) {
      return runWithLease(jobId, lease);
    }
  }

  private Outcome runWithLease(
      UUID jobId,
      RecoveryWorkerLeaseSession lease)
      throws SQLException, DataClientException, DataServiceException {

    lease.requireHeld();

    Optional<StoredRecoveryJob> stored = jobs.find(jobId);

    if (stored.isEmpty()) {
      return Outcome.NOT_RUN;
    }

    RecoveryJob job = stored.orElseThrow().job();

    if (!job.dryRun()) {
      throw new IllegalArgumentException(
          "This worker supports only dryRun jobs");
    }

    if (job.status() == RecoveryJobStatus.PAUSING) {
      return acknowledgePause(jobId, lease);
    }

    if (job.status() == RecoveryJobStatus.PENDING) {
      Optional<RecoveryJob> started =
          states.tryStart(jobId, lease::requireHeld);

      if (started.isEmpty()) {
        return Outcome.STATE_CHANGED;
      }

      job = started.orElseThrow();

    } else if (job.status() != RecoveryJobStatus.RUNNING) {
      return Outcome.NOT_RUN;
    }

    lease.requireHeld();

    RecoveryDiscoveryCheckpoint checkpoint =
        discovery.discover(jobId, job.nextDate(), lease);

    lease.requireHeld();

    if (!jobId.equals(checkpoint.jobId())
        || !job.nextDate().equals(checkpoint.day())) {
      throw new IllegalStateException(
          "Discovery returned a checkpoint for a different job or day");
    }

    if (checkpoint.status()
        != RecoveryDiscoveryCheckpoint.Status.DISCOVERED) {
      return acknowledgePause(jobId, lease);
    }

    Optional<RecoveryJob> advanced =
        states.completeDryRunDay(checkpoint, lease::requireHeld);

    lease.requireHeld();

    if (advanced.isEmpty()) {
      // A pause may have arrived after discovery completed.
      return acknowledgePause(jobId, lease);
    }

    return advanced.orElseThrow().status() == RecoveryJobStatus.COMPLETED
        ? Outcome.JOB_COMPLETED
        : Outcome.DAY_COMPLETED;
  }

  private Outcome acknowledgePause(
      UUID jobId,
      RecoveryWorkerLeaseSession lease) {

    Optional<RecoveryJob> paused =
        states.acknowledgePause(jobId, lease::requireHeld);

    lease.requireHeld();

    return paused.isPresent()
        ? Outcome.PAUSED
        : Outcome.STATE_CHANGED;
  }
}