package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobRequest;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

public final class RecoveryJobService {

  private final RecoveryJobRepository repository;
  private final Clock clock;

  public RecoveryJobService(RecoveryJobRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  public RecoveryJob create(RecoveryJobRequest request) {
    Objects.requireNonNull(request, "request is required");

    RecoveryJob job = RecoveryJob.create(request, clock);
    repository.create(job);

    return job;
  }

  public Optional<RecoveryJob> find(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId is required");

    return repository.find(jobId).map(StoredRecoveryJob::job);
  }

  public Optional<RecoveryJob> pause(UUID jobId) {
    return changeStatus(jobId, RecoveryJobService::pauseTarget);
  }

  public Optional<RecoveryJob> resume(UUID jobId) {
    return changeStatus(jobId, RecoveryJobService::resumeTarget);
  }

  private Optional<RecoveryJob> changeStatus(
      UUID jobId,
      UnaryOperator<RecoveryJobStatus> targetResolver) {

    Objects.requireNonNull(jobId, "jobId is required");

    Optional<StoredRecoveryJob> result = repository.find(jobId);

    if (result.isEmpty()) {
      return Optional.empty();
    }

    StoredRecoveryJob stored = result.orElseThrow();
    RecoveryJob current = stored.job();
    RecoveryJobStatus target = targetResolver.apply(current.status());

    if (current.status() == target) {
      return Optional.of(current);
    }

    RecoveryJob updated =
        new RecoveryJob(
            current.jobId(),
            current.dateFrom(),
            current.dateTo(),
            current.dryRun(),
            current.status().transitionTo(target),
            current.nextDate(),
            current.createdAt(),
            clock.instant());

    repository.update(updated, stored.etag());

    return Optional.of(updated);
  }

  private static RecoveryJobStatus pauseTarget(RecoveryJobStatus status) {
    return switch (status) {
      case PENDING -> RecoveryJobStatus.PAUSED;
      case RUNNING -> RecoveryJobStatus.PAUSING;
      case PAUSING, PAUSED -> status;
      case COMPLETED, FAILED ->
           throw new RecoveryJobStateConflictException(
              "Cannot pause recovery job in status " + status);
    };
  }

  private static RecoveryJobStatus resumeTarget(RecoveryJobStatus status) {
    return switch (status) {
      case PAUSED -> RecoveryJobStatus.PENDING;
      case PENDING, RUNNING -> status;
      case PAUSING, COMPLETED, FAILED ->
           throw new RecoveryJobStateConflictException(
              "Cannot resume recovery job in status " + status);
    };
  }
}