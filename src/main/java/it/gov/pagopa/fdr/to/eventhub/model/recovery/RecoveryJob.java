package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record RecoveryJob(
    UUID jobId,
    LocalDate dateFrom,
    LocalDate dateTo,
    boolean dryRun,
    RecoveryJobStatus status,
    LocalDate nextDate,
    Instant createdAt,
    Instant updatedAt) {

  public RecoveryJob {
    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(dateFrom, "dateFrom is required");
    Objects.requireNonNull(dateTo, "dateTo is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(nextDate, "nextDate is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");

    if (dateFrom.isAfter(dateTo)) {
      throw new IllegalArgumentException(
          "dateFrom must be before or equal to dateTo");
    }

    // The day after dateTo represents a fully completed date range.
    if (nextDate.isBefore(dateFrom)
        || nextDate.isAfter(dateTo.plusDays(1))) {
      throw new IllegalArgumentException(
          "nextDate must be within the job range or the day after dateTo");
    }

    if (status == RecoveryJobStatus.COMPLETED
        && !nextDate.equals(dateTo.plusDays(1))) {
      throw new IllegalArgumentException(
          "A completed job must have nextDate equal to dateTo plus one day");
    }

    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException(
          "updatedAt must not be before createdAt");
    }
  }

  public static RecoveryJob create(RecoveryJobRequest request, Clock clock) {
    Objects.requireNonNull(request, "request is required");
    Objects.requireNonNull(clock, "clock is required");

    Instant now = clock.instant();

    return new RecoveryJob(
        UUID.randomUUID(),
        request.getDateFrom(),
        request.getDateTo(),
        request.isDryRun(),
        RecoveryJobStatus.PENDING,
        request.getDateFrom(),
        now,
        now);
  }
}