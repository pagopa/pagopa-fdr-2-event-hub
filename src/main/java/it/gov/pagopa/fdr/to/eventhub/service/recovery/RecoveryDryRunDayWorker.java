package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class RecoveryDryRunDayWorker {

  private static final String STATES_REQUIRED = "states is required";
  private static final String DISCOVERY_REQUIRED = "discovery is required";

  public enum Outcome {
    LEASE_BUSY,
    NOT_RUN,
    STATE_CHANGED,
    DAY_COMPLETED,
    JOB_COMPLETED,
    PAUSED,
    FAILED
  }

  @FunctionalInterface
  private interface DayDiscovery {
    DiscoveredDay discover(
        UUID jobId,
        LocalDate day,
        RecoveryWorkerLeaseSession lease)
        throws SQLException, IOException,
            DataClientException, DataServiceException;
  }

  private record DiscoveredDay(
      UUID jobId,
      LocalDate day,
      boolean completed,
      Supplier<Optional<RecoveryJob>> advance) {
  }

  private final RecoveryJobRepository jobs;
  private final RecoveryJobWorkerStateService states;
  private final DayDiscovery discovery;
  private final RecoveryPublicationExecutionJobService execution;
  private final Supplier<Optional<RecoveryWorkerLeaseSession>> leaseAcquirer;

  public RecoveryDryRunDayWorker(
      RecoveryJobRepository jobs,
      RecoveryJobWorkerStateService states,
      RecoveryDiscoveryJobService discovery,
      Supplier<Optional<RecoveryWorkerLeaseSession>> leaseAcquirer) {

    this(
        jobs,
        states,
        legacyDiscovery(discovery, states),
        null,
        leaseAcquirer);
  }

  public RecoveryDryRunDayWorker(
      RecoveryJobRepository jobs,
      RecoveryJobWorkerStateService states,
      RecoveryPublicationDiscoveryJobService discovery,
      Supplier<Optional<RecoveryWorkerLeaseSession>> leaseAcquirer) {

    this(
        jobs,
        states,
        publicationDiscovery(discovery, states),
        null,
        leaseAcquirer);
  }

  public RecoveryDryRunDayWorker(
      RecoveryJobRepository jobs,
      RecoveryJobWorkerStateService states,
      RecoveryPublicationDiscoveryJobService discovery,
      RecoveryPublicationExecutionJobService execution,
      Supplier<Optional<RecoveryWorkerLeaseSession>> leaseAcquirer) {

    this(
        jobs,
        states,
        publicationDiscovery(discovery, states),
        Objects.requireNonNull(execution, "execution is required"),
        leaseAcquirer);
  }

  private RecoveryDryRunDayWorker(
      RecoveryJobRepository jobs,
      RecoveryJobWorkerStateService states,
      DayDiscovery discovery,
      RecoveryPublicationExecutionJobService execution,
      Supplier<Optional<RecoveryWorkerLeaseSession>> leaseAcquirer) {

    this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    this.states = Objects.requireNonNull(states, STATES_REQUIRED);

    this.discovery =
        Objects.requireNonNull(discovery, DISCOVERY_REQUIRED);

    this.execution = execution;

    this.leaseAcquirer =
        Objects.requireNonNull(
            leaseAcquirer, "leaseAcquirer is required");
  }

  public Outcome runOneDay(UUID jobId)
      throws SQLException, IOException,
          DataClientException, DataServiceException {

    Objects.requireNonNull(jobId, "jobId is required");

    var acquired = leaseAcquirer.get();

    if (acquired.isEmpty()) {
      return Outcome.LEASE_BUSY;
    }

    try (var lease = acquired.orElseThrow()) {
      return runWithLease(jobId, lease);
    }
  }

  private Outcome runWithLease(
      UUID jobId,
      RecoveryWorkerLeaseSession lease)
      throws SQLException, IOException,
          DataClientException, DataServiceException {

    lease.requireHeld();

    Optional<StoredRecoveryJob> stored = jobs.find(jobId);

    if (stored.isEmpty()) {
      return Outcome.NOT_RUN;
    }

    RecoveryJob job = stored.orElseThrow().job();

    if (!job.dryRun()) {
      return runExecution(jobId, lease);
    }

    if (job.status() == RecoveryJobStatus.PAUSING) {
      return acknowledgePause(jobId, lease);
    }

    if (job.status() == RecoveryJobStatus.PENDING) {
      var started = states.tryStart(jobId, lease::requireHeld);

      if (started.isEmpty()) {
        return Outcome.STATE_CHANGED;
      }

      job = started.orElseThrow();

    } else if (job.status() != RecoveryJobStatus.RUNNING) {
      return Outcome.NOT_RUN;
    }

    lease.requireHeld();

    var result =
        discovery.discover(jobId, job.nextDate(), lease);

    lease.requireHeld();

    if (!jobId.equals(result.jobId())
        || !job.nextDate().equals(result.day())) {

      throw new IllegalStateException(
          "Discovery returned a checkpoint for a different job or day");
    }

    if (!result.completed()) {
      return acknowledgePause(jobId, lease);
    }

    var advanced = result.advance().get();

    lease.requireHeld();

    if (advanced.isEmpty()) {
      return acknowledgePause(jobId, lease);
    }

    return advanced.orElseThrow().status() == RecoveryJobStatus.COMPLETED
        ? Outcome.JOB_COMPLETED
        : Outcome.DAY_COMPLETED;
  }

  private Outcome runExecution(
      UUID jobId,
      RecoveryWorkerLeaseSession lease)
      throws SQLException, IOException,
          DataClientException, DataServiceException {

    if (execution == null) {
      throw new IllegalArgumentException(
          "This worker supports only dryRun jobs");
    }

    lease.requireHeld();

    var result = execution.runOneDay(jobId, lease);

    lease.requireHeld();

    return switch (result.outcome()) {
      case DAY_COMPLETED -> Outcome.DAY_COMPLETED;
      case JOB_COMPLETED -> Outcome.JOB_COMPLETED;
      case PAUSED -> Outcome.PAUSED;
      case FAILED -> Outcome.FAILED;
      case NOT_RUN -> Outcome.NOT_RUN;
    };
  }

  private Outcome acknowledgePause(
      UUID jobId,
      RecoveryWorkerLeaseSession lease) {

    var paused =
        states.acknowledgePause(jobId, lease::requireHeld);

    lease.requireHeld();

    return paused.isPresent()
        ? Outcome.PAUSED
        : Outcome.STATE_CHANGED;
  }

  private static DayDiscovery legacyDiscovery(
      RecoveryDiscoveryJobService discovery,
      RecoveryJobWorkerStateService states) {

    Objects.requireNonNull(discovery, DISCOVERY_REQUIRED);
    Objects.requireNonNull(states, STATES_REQUIRED);

    return (jobId, day, lease) -> {
      var checkpoint = discovery.discover(jobId, day, lease);

      return new DiscoveredDay(
          checkpoint.jobId(),
          checkpoint.day(),
          checkpoint.status()
              == RecoveryDiscoveryCheckpoint.Status.DISCOVERED,
          () -> states.completeDryRunDay(
              checkpoint, lease::requireHeld));
    };
  }

  private static DayDiscovery publicationDiscovery(
      RecoveryPublicationDiscoveryJobService discovery,
      RecoveryJobWorkerStateService states) {

    Objects.requireNonNull(discovery, DISCOVERY_REQUIRED);
    Objects.requireNonNull(states, STATES_REQUIRED);

    return (jobId, day, lease) -> {
      var checkpoint = discovery.discover(jobId, day, lease);

      return new DiscoveredDay(
          checkpoint.jobId(),
          checkpoint.day(),
          checkpoint.status()
              == RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED,
          () -> states.completeDryRunDay(
              checkpoint, lease::requireHeld));
    };
  }
}