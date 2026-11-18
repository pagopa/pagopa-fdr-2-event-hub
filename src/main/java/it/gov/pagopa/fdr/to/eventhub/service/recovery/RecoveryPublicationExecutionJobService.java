package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RecoveryPublicationExecutionJobService {

  public enum Outcome {
    DAY_COMPLETED,
    JOB_COMPLETED,
    PAUSED,
    FAILED,
    NOT_RUN
  }

  public record Result(
      Outcome outcome,
      Optional<RecoveryPublicationExecutionDayService.DayResult> execution) {

    public Result {
      Objects.requireNonNull(outcome, "outcome is required");
      Objects.requireNonNull(execution, "execution is required");
    }
  }

  private final RecoveryJobRepository jobs;
  private final RecoveryPersistedPublicationDiscoveryService discovery;
  private final RecoveryPublicationExecutionDayService execution;
  private final RecoveryJobWorkerStateService states;
  private final RecoveryExecutionJobWorkerStateService executionStates;

  public RecoveryPublicationExecutionJobService(
      RecoveryJobRepository jobs,
      RecoveryPersistedPublicationDiscoveryService discovery,
      RecoveryPublicationExecutionDayService execution,
      RecoveryJobWorkerStateService states,
      RecoveryExecutionJobWorkerStateService executionStates) {

    this.jobs = Objects.requireNonNull(jobs, "jobs is required");

    this.discovery =
        Objects.requireNonNull(discovery, "discovery is required");

    this.execution =
        Objects.requireNonNull(execution, "execution is required");

    this.states =
        Objects.requireNonNull(states, "states is required");

    this.executionStates =
        Objects.requireNonNull(
            executionStates, "executionStates is required");
  }

  public Result runOneDay(
      UUID jobId,
      RecoveryWorkerLeaseSession lease)
      throws SQLException, IOException,
          DataClientException, DataServiceException {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(lease, "lease is required");

    var job = requireJob(jobId, lease);

    if (job.dryRun()) {
      throw new IllegalArgumentException(
          "Execution coordinator requires dryRun=false");
    }

    if (job.status() == RecoveryJobStatus.PAUSING) {
      return acknowledgePause(jobId, lease);
    }

    var started = startIfPending(job, lease);

    if (started.isEmpty()) {
      return withoutExecution(Outcome.NOT_RUN);
    }

    var active = started.orElseThrow();

    if (active.status() != RecoveryJobStatus.RUNNING) {
      return withoutExecution(Outcome.NOT_RUN);
    }

    validateActiveJob(jobId, active);

    return processActiveDay(active, lease);
  }

  private Optional<RecoveryJob> startIfPending(
      RecoveryJob job,
      RecoveryWorkerLeaseSession lease) {

    if (job.status() != RecoveryJobStatus.PENDING) {
      return Optional.of(job);
    }

    lease.requireHeld();

    var started =
        states.tryStart(job.jobId(), lease::requireHeld);

    lease.requireHeld();

    return started;
  }

  private Result processActiveDay(
      RecoveryJob job,
      RecoveryWorkerLeaseSession lease)
      throws SQLException, IOException,
          DataClientException, DataServiceException {

    UUID jobId = job.jobId();
    LocalDate day = job.nextDate();

    if (!shouldContinue(jobId, day, lease)) {
      return acknowledgePause(jobId, lease);
    }

    var checkpoint =
        discovery.discover(
            jobId,
            day,
            () -> shouldContinue(jobId, day, lease),
            lease::requireHeld);

    lease.requireHeld();

    if (!jobId.equals(checkpoint.jobId())
        || !day.equals(checkpoint.day())) {

      throw new IllegalStateException(
          "Discovery returned a checkpoint for another job or day");
    }

    if (checkpoint.status()
            != RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED
        || !shouldContinue(jobId, day, lease)) {

      return acknowledgePause(jobId, lease);
    }

    var result =
        execution.execute(
            jobId,
            day,
            () -> shouldContinue(jobId, day, lease),
            lease::requireHeld);

    lease.requireHeld();

    if (!jobId.equals(result.jobId())
        || !day.equals(result.day())) {

      throw new IllegalStateException(
          "Execution returned a result for another job or day");
    }

    var updated =
        executionStates.apply(result, lease::requireHeld);

    lease.requireHeld();

    Outcome outcome =
        updated.map(current -> outcomeOf(current.status()))
            .orElse(Outcome.NOT_RUN);

    return new Result(outcome, Optional.of(result));
  }

  private boolean shouldContinue(
      UUID jobId,
      LocalDate day,
      RecoveryWorkerLeaseSession lease) {

    var current = requireJob(jobId, lease);

    return current.status() == RecoveryJobStatus.RUNNING
        && !current.dryRun()
        && day.equals(current.nextDate());
  }

  private RecoveryJob requireJob(
      UUID jobId,
      RecoveryWorkerLeaseSession lease) {

    lease.requireHeld();

    var found = jobs.find(jobId);

    lease.requireHeld();

    return found.orElseThrow(
            () -> new IllegalStateException(
                "Recovery execution job was not found"))
        .job();
  }

  private Result acknowledgePause(
      UUID jobId,
      RecoveryWorkerLeaseSession lease) {

    lease.requireHeld();

    var paused =
        states.acknowledgePause(jobId, lease::requireHeld);

    lease.requireHeld();

    return withoutExecution(
        paused.isPresent() ? Outcome.PAUSED : Outcome.NOT_RUN);
  }

  private static void validateActiveJob(
      UUID expectedJobId,
      RecoveryJob job) {

    if (!expectedJobId.equals(job.jobId())
        || job.dryRun()
        || job.nextDate().isBefore(job.dateFrom())
        || job.nextDate().isAfter(job.dateTo())) {

      throw new IllegalStateException(
          "Active execution job has an inconsistent mode or date");
    }
  }

  private static Outcome outcomeOf(RecoveryJobStatus status) {
    return switch (status) {
      case RUNNING -> Outcome.DAY_COMPLETED;
      case COMPLETED -> Outcome.JOB_COMPLETED;
      case PAUSED -> Outcome.PAUSED;
      case FAILED -> Outcome.FAILED;
      default -> throw new IllegalStateException(
          "Unexpected job status after execution: " + status);
    };
  }

  private static Result withoutExecution(Outcome outcome) {
    return new Result(outcome, Optional.empty());
  }
}