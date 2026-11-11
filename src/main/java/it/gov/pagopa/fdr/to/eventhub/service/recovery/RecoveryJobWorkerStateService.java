package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.time.Clock;
import java.time.LocalDate;
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
    return tryStart(jobId, () -> {});
  }

  public Optional<RecoveryJob> tryStart(
      UUID jobId,
      Runnable requireOwnership) {

    return transitionIfExpected(
        jobId,
        RecoveryJobStatus.PENDING,
        RecoveryJobStatus.RUNNING,
        requireOwnership);
  }

  public Optional<RecoveryJob> acknowledgePause(UUID jobId) {
    return acknowledgePause(jobId, () -> {});
  }

  public Optional<RecoveryJob> acknowledgePause(
      UUID jobId,
      Runnable requireOwnership) {

    return transitionIfExpected(
        jobId,
        RecoveryJobStatus.PAUSING,
        RecoveryJobStatus.PAUSED,
        requireOwnership);
  }

  public Optional<RecoveryJob> completeDryRunDay(
      RecoveryDiscoveryCheckpoint checkpoint) {

    return completeDryRunDay(checkpoint, () -> {});
  }

  public Optional<RecoveryJob> completeDryRunDay(
      RecoveryDiscoveryCheckpoint checkpoint,
      Runnable requireOwnership) {

    Objects.requireNonNull(checkpoint, "checkpoint is required");
    Objects.requireNonNull(requireOwnership, "requireOwnership is required");

    if (checkpoint.status()
        != RecoveryDiscoveryCheckpoint.Status.DISCOVERED) {
      throw new IllegalArgumentException(
          "Discovery must be completed before advancing the job");
    }

    return advanceDryRunDay(
        checkpoint.jobId(),
        checkpoint.day(),
        requireOwnership);
  }

  public Optional<RecoveryJob> completeDryRunDay(
      RecoveryPublicationDiscoveryCheckpoint checkpoint,
      Runnable requireOwnership) {

    Objects.requireNonNull(checkpoint, "checkpoint is required");
    Objects.requireNonNull(requireOwnership, "requireOwnership is required");

    if (checkpoint.status()
        != RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED) {
      throw new IllegalArgumentException(
          "Discovery must be completed before advancing the job");
    }

    return advanceDryRunDay(
        checkpoint.jobId(),
        checkpoint.day(),
        requireOwnership);
  }

  private Optional<RecoveryJob> advanceDryRunDay(
      UUID jobId,
      LocalDate completedDay,
      Runnable requireOwnership) {

    Objects.requireNonNull(requireOwnership, "requireOwnership is required");

    requireOwnership.run();

    Optional<StoredRecoveryJob> result = repository.find(jobId);

    if (result.isEmpty()) {
      return Optional.empty();
    }

    StoredRecoveryJob stored = result.orElseThrow();
    RecoveryJob current = stored.job();

    if (current.status() != RecoveryJobStatus.RUNNING) {
      return Optional.empty();
    }

    if (!current.dryRun()) {
      throw new IllegalArgumentException(
          "Discovery alone cannot complete a recovery day when dryRun is false");
    }

    if (!current.nextDate().equals(completedDay)) {
      throw new IllegalArgumentException(
          "Completed discovery day must match the job nextDate");
    }

    var nextDate = completedDay.plusDays(1);

    RecoveryJobStatus targetStatus =
        nextDate.isAfter(current.dateTo())
            ? current.status().transitionTo(RecoveryJobStatus.COMPLETED)
            : current.status();

    RecoveryJob updated =
        new RecoveryJob(
            current.jobId(),
            current.dateFrom(),
            current.dateTo(),
            current.dryRun(),
            targetStatus,
            nextDate,
            current.createdAt(),
            clock.instant());

    requireOwnership.run();
    repository.update(updated, stored.etag());

    return Optional.of(updated);
  }

  private Optional<RecoveryJob> transitionIfExpected(
      UUID jobId,
      RecoveryJobStatus expectedStatus,
      RecoveryJobStatus targetStatus,
      Runnable requireOwnership) {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(requireOwnership, "requireOwnership is required");

    requireOwnership.run();

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

    requireOwnership.run();
    repository.update(updated, stored.etag());

    return Optional.of(updated);
  }
}