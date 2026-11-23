package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record RecoveryPublicationExecution(
    UUID jobId,
    LocalDate day,
    RecoveryPublicationSource source,
    Status status,
    UUID attemptId,
    SendReceipt receipt,
    Instant createdAt,
    Instant updatedAt) {

  public enum Status {
    PENDING,
    SENDING,
    SENT,
    SKIPPED_PRESENT,
    UNCERTAIN
  }

  /**
   * Counts confirmed by successful Event Hub send calls.
   * This is not an ADX ingestion receipt.
   */
  public record SendReceipt(
      long acknowledgedEvents,
      long acknowledgedBatches) {

    public SendReceipt {
      if (acknowledgedEvents <= 0
          || acknowledgedBatches <= 0
          || acknowledgedBatches > acknowledgedEvents) {

        throw new IllegalArgumentException(
            "A send receipt requires positive, consistent counters");
      }
    }
  }

  public RecoveryPublicationExecution {
    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(source, "source is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");

    if (source.candidate().flowId() <= 0) {
      throw new IllegalArgumentException("flowId must be positive");
    }

    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException(
          "updatedAt must not precede createdAt");
    }

    boolean attempted =
        status == Status.SENDING
            || status == Status.SENT
            || status == Status.UNCERTAIN;

    if (attempted != (attemptId != null)) {
      throw new IllegalArgumentException(
          "Attempt identifier does not match execution status");
    }

    if ((status == Status.SENT) != (receipt != null)) {
      throw new IllegalArgumentException(
          "Only SENT executions must contain a send receipt");
    }
  }

  public static RecoveryPublicationExecution pending(
      RecoveryPublicationDiscoveryItem item,
      Instant now) {

    Objects.requireNonNull(item, "item is required");

    if (!(item.outcome()
        instanceof RecoveryPublicationDiscoveryItem.Compared compared)
        || compared.status()
            != RecoveryPublicationDiscoveryItem.Status.ABSENT) {

      throw new IllegalArgumentException(
          "Only an ABSENT publication can enter execution");
    }

    return new RecoveryPublicationExecution(
        item.jobId(),
        item.day(),
        compared.source(),
        Status.PENDING,
        null,
        null,
        now,
        now);
  }

  public RecoveryPublicationExecution startSending(
      UUID newAttemptId,
      Instant now) {

    requireStatus(Status.PENDING);

    Objects.requireNonNull(
        newAttemptId, "newAttemptId is required");

    return transition(
        Status.SENDING,
        newAttemptId,
        null,
        now);
  }

  public RecoveryPublicationExecution skipAlreadyPresent(
      Instant now) {

    requireStatus(Status.PENDING);

    return transition(
        Status.SKIPPED_PRESENT,
        null,
        null,
        now);
  }

  public RecoveryPublicationExecution markSent(
      long acknowledgedEvents,
      long acknowledgedBatches,
      Instant now) {

    requireStatus(Status.SENDING);

    return transition(
        Status.SENT,
        attemptId,
        new SendReceipt(
            acknowledgedEvents, acknowledgedBatches),
        now);
  }

  public RecoveryPublicationExecution markUncertain(
      Instant now) {

    requireStatus(Status.SENDING);

    return transition(
        Status.UNCERTAIN,
        attemptId,
        null,
        now);
  }

  public String partitionKey() {
    return jobId + "_" + day;
  }

  public String rowKey() {
    return String.format(
        Locale.ROOT,
        "EXECUTION_FLOW_%020d",
        source.candidate().flowId());
  }

  private void requireStatus(Status expected) {
    if (status != expected) {
      throw new IllegalStateException(
          "Execution status must be " + expected
              + " but was " + status);
    }
  }

  private RecoveryPublicationExecution transition(
      Status target,
      UUID targetAttemptId,
      SendReceipt targetReceipt,
      Instant now) {

    Objects.requireNonNull(now, "now is required");

    if (now.isBefore(updatedAt)) {
      throw new IllegalArgumentException(
          "Execution transition time must not move backwards");
    }

    return new RecoveryPublicationExecution(
        jobId,
        day,
        source,
        target,
        targetAttemptId,
        targetReceipt,
        createdAt,
        now);
  }
}