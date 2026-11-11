package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

public final class RecoveryExecutionJobWorkerStateService {

  private final RecoveryJobRepository repository;
  private final Clock clock;

  public RecoveryExecutionJobWorkerStateService(
      RecoveryJobRepository repository,
      Clock clock) {

    this.repository =
        Objects.requireNonNull(repository, "repository is required");

    this.clock =
        Objects.requireNonNull(clock, "clock is required");
  }

  public Optional<RecoveryJob> apply(
      RecoveryPublicationExecutionDayService.DayResult result,
      Runnable requireOwnership) {

    Objects.requireNonNull(result, "result is required");
    Objects.requireNonNull(
        requireOwnership, "requireOwnership is required");

    validateResult(result);

    requireOwnership.run();

    var found = repository.find(result.jobId());

    requireOwnership.run();

    if (found.isEmpty()) {
      return Optional.empty();
    }

    var stored = found.orElseThrow();
    var current = stored.job();

    if (current.status() != RecoveryJobStatus.RUNNING
        && current.status() != RecoveryJobStatus.PAUSING) {

      return Optional.empty();
    }

    validateJob(current, result);

    RecoveryJobStatus targetStatus;
    LocalDate nextDate = current.nextDate();

    if (isBlocked(result.status())) {
      targetStatus =
          current.status().transitionTo(RecoveryJobStatus.FAILED);

    } else if (current.status() == RecoveryJobStatus.PAUSING) {
      targetStatus =
          current.status().transitionTo(RecoveryJobStatus.PAUSED);

    } else if (result.status()
        == RecoveryPublicationExecutionDayService.Status.PAUSED) {

      return Optional.empty();

    } else {
      nextDate = result.day().plusDays(1);

      targetStatus =
          nextDate.isAfter(current.dateTo())
              ? current.status().transitionTo(
                  RecoveryJobStatus.COMPLETED)
              : current.status();
    }

    var updated =
        new RecoveryJob(
            current.jobId(),
            current.dateFrom(),
            current.dateTo(),
            false,
            targetStatus,
            nextDate,
            current.createdAt(),
            clock.instant());

    requireOwnership.run();

    repository.update(updated, stored.etag());

    requireOwnership.run();

    return Optional.of(updated);
  }

  private static void validateJob(
      RecoveryJob job,
      RecoveryPublicationExecutionDayService.DayResult result) {

    if (job.dryRun()) {
      throw new IllegalArgumentException(
          "Execution results cannot advance a dry-run job");
    }

    if (!job.nextDate().equals(result.day())
        || result.day().isBefore(job.dateFrom())
        || result.day().isAfter(job.dateTo())) {

      throw new IllegalArgumentException(
          "Execution day must match the active job nextDate");
    }
  }

  private static boolean isBlocked(
      RecoveryPublicationExecutionDayService.Status status) {

    return status
            == RecoveryPublicationExecutionDayService.Status
                .BLOCKED_UNRESOLVED
        || status
            == RecoveryPublicationExecutionDayService.Status
                .BLOCKED_UNCERTAIN;
  }

  private static void validateResult(
      RecoveryPublicationExecutionDayService.DayResult result) {

    Objects.requireNonNull(result.jobId(), "result jobId is required");
    Objects.requireNonNull(result.day(), "result day is required");
    Objects.requireNonNull(result.status(), "result status is required");

    long remaining = result.examinedPublications();

    if (remaining < 0 || result.lastExaminedFlowId() < 0) {
      throw new IllegalArgumentException(
          "Execution progress must not be negative");
    }

    long[] counters = {
        result.sentPublications(),
        result.presentPublications(),
        result.unresolvedPublications(),
        result.uncertainPublications()
    };

    for (long count : counters) {
      if (count < 0 || count > remaining) {
        throw new IllegalArgumentException(
            "Execution result counters are inconsistent");
      }

      remaining -= count;
    }

    if (remaining != 0
        || (result.examinedPublications() == 0)
            != (result.lastExaminedFlowId() == 0)) {

      throw new IllegalArgumentException(
          "Execution result progress is inconsistent");
    }

    validateOutcome(result);
  }

  private static void validateOutcome(
      RecoveryPublicationExecutionDayService.DayResult result) {

    switch (result.status()) {
      case COMPLETED -> {
        if (result.unresolvedPublications() != 0
            || result.uncertainPublications() != 0) {

          throw new IllegalArgumentException(
              "A completed execution must not contain blocked publications");
        }
      }

      case BLOCKED_UNRESOLVED -> {
        if (result.unresolvedPublications() == 0) {
          throw new IllegalArgumentException(
              "An unresolved result must contain unresolved publications");
        }
      }

      case BLOCKED_UNCERTAIN -> {
        if (result.uncertainPublications() == 0) {
          throw new IllegalArgumentException(
              "An uncertain result must contain uncertain publications");
        }
      }

      case PAUSED -> {
        // The day remains unchanged; only an actual pause is acknowledged.
      }
    }
  }
}