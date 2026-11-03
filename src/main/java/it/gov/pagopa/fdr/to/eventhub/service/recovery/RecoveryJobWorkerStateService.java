package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RecoveryJobWorkerStateService {

  private final RecoveryJobRepository repository;
  private final Clock clock;

  public RecoveryJobWorkerStateService(
      RecoveryJobRepository repository,
      Clock clock) {

    this.repository =
        Objects.requireNonNull(repository, "repository is required");

    this.clock =
        Objects.requireNonNull(clock, "clock is required");
  }

  public Optional<RecoveryJob> tryStart(UUID jobId) {
    return transitionIfExpected(
        jobId,
        RecoveryJobStatus.PENDING,
        RecoveryJobStatus.RUNNING);
  }

  // Call only after the current execution has stopped at a safe boundary.
  public Optional<RecoveryJob> acknowledgePause(UUID jobId) {
    return transitionIfExpected(
        jobId,
        RecoveryJobStatus.PAUSING,
        RecoveryJobStatus.PAUSED);
  }

  public Optional<RecoveryJob> completeDryRunDay(RecoveryDiscoveryCheckpoint checkpoint) {

      Objects.requireNonNull(checkpoint, "checkpoint is required");

      if (checkpoint.status() != RecoveryDiscoveryCheckpoint.Status.DISCOVERED) {
          throw new IllegalArgumentException("Discovery must be completed before advancing the job");
      }

      Optional<StoredRecoveryJob> result = repository.find(checkpoint.jobId());

      if (result.isEmpty()) {
          return Optional.empty();
      }

      StoredRecoveryJob stored = result.orElseThrow();
      RecoveryJob current = stored.job();

      if (current.status() != RecoveryJobStatus.RUNNING) {
          return Optional.empty();
      }

      if (!current.dryRun()) {
          throw new IllegalArgumentException("Discovery alone cannot complete a recovery day when dryRun is false");
      }

      if (!current.nextDate().equals(checkpoint.day())) {
          throw new IllegalArgumentException("Completed discovery day must match the job nextDate");
      }

      var nextDate = checkpoint.day().plusDays(1);

      RecoveryJobStatus targetStatus = nextDate.isAfter(current.dateTo())
              ? current.status().transitionTo(RecoveryJobStatus.COMPLETED)
              : current.status();

      RecoveryJob updated = new RecoveryJob(current.jobId(), current.dateFrom(), current.dateTo(), current.dryRun(),
              targetStatus, nextDate, current.createdAt(), clock.instant());

      repository.update(updated, stored.etag());

      return Optional.of(updated);
  }

  private Optional<RecoveryJob> transitionIfExpected(
      UUID jobId,
      RecoveryJobStatus expectedStatus,
      RecoveryJobStatus targetStatus) {

    Objects.requireNonNull(jobId, "jobId is required");

    Optional<StoredRecoveryJob> result = repository.find(jobId);

    if (result.isEmpty()) {
      return Optional.empty();
    }

    StoredRecoveryJob stored = result.orElseThrow();
    RecoveryJob current = stored.job();

    if (current.status() != expectedStatus) {
      return Optional.empty();
    }

    RecoveryJob updated =
        new RecoveryJob(
            current.jobId(),
            current.dateFrom(),
            current.dateTo(),
            current.dryRun(),
            current.status().transitionTo(targetStatus),
            current.nextDate(),
            current.createdAt(),
            clock.instant());

    repository.update(updated, stored.etag());

    return Optional.of(updated);
  }
}