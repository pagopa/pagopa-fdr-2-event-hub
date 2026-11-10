package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RecoveryPublicationDiscoveryCheckpoint(
    UUID jobId,
    LocalDate day,
    long lastFlowId,
    long candidateCount,
    long absentCandidateCount,
    long presentCandidateCount,
    long unresolvedCandidateCount,
    long pageCount,
    Status status) {

  public enum Status {
    DISCOVERING,
    DISCOVERED
  }

  public RecoveryPublicationDiscoveryCheckpoint {
    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(status, "status is required");

    validateCounters(
        lastFlowId,
        candidateCount,
        absentCandidateCount,
        presentCandidateCount,
        unresolvedCandidateCount,
        pageCount);

    validateProgress(lastFlowId, candidateCount, pageCount);
  }

  public static RecoveryPublicationDiscoveryCheckpoint start(
      UUID jobId, LocalDate day) {

    return new RecoveryPublicationDiscoveryCheckpoint(
        jobId, day, 0L, 0L, 0L, 0L, 0L, 0L,
        Status.DISCOVERING);
  }

  /**
   * The caller must save the entire page before advancing the checkpoint.
   */
  public RecoveryPublicationDiscoveryCheckpoint afterSavedPage(
      List<RecoveryPublicationDiscoveryItem> savedItems) {

    if (status != Status.DISCOVERING) {
      throw new IllegalStateException(
          "Cannot advance a completed discovery");
    }

    var page =
        List.copyOf(
            Objects.requireNonNull(savedItems, "savedItems is required"));

    if (page.isEmpty()) {
      throw new IllegalArgumentException(
          "A saved page must contain at least one item");
    }

    long cursor = lastFlowId;
    long absent = 0L;
    long present = 0L;
    long unresolved = 0L;

    for (var item : page) {
      validateSavedItem(item, cursor);
      cursor = item.candidate().flowId();

      switch (item.outcome().status()) {
        case ABSENT -> absent++;
        case PRESENT_UNVERIFIED -> present++;
        case UNRESOLVED -> unresolved++;
      }
    }

    return new RecoveryPublicationDiscoveryCheckpoint(
        jobId,
        day,
        cursor,
        Math.addExact(candidateCount, page.size()),
        Math.addExact(absentCandidateCount, absent),
        Math.addExact(presentCandidateCount, present),
        Math.addExact(unresolvedCandidateCount, unresolved),
        Math.addExact(pageCount, 1L),
        Status.DISCOVERING);
  }

  /**
   * The caller must first verify that PostgreSQL has no further candidates.
   */
  public RecoveryPublicationDiscoveryCheckpoint completeDiscovery() {
    if (status == Status.DISCOVERED) {
      return this;
    }

    return new RecoveryPublicationDiscoveryCheckpoint(
        jobId,
        day,
        lastFlowId,
        candidateCount,
        absentCandidateCount,
        presentCandidateCount,
        unresolvedCandidateCount,
        pageCount,
        Status.DISCOVERED);
  }

  public String partitionKey() {
    return jobId + "_" + day;
  }

  public String rowKey() {
    return "DISCOVERY";
  }

  private void validateSavedItem(
      RecoveryPublicationDiscoveryItem item,
      long cursor) {

    if (!jobId.equals(item.jobId()) || !day.equals(item.day())) {
      throw new IllegalArgumentException(
          "Saved items must belong to the checkpoint job and day");
    }

    if (item.candidate().flowId() <= cursor) {
      throw new IllegalArgumentException(
          "Saved flow IDs must be strictly increasing after lastFlowId");
    }
  }

  private static void validateCounters(
      long cursor,
      long candidates,
      long absent,
      long present,
      long unresolved,
      long pages) {

    if (cursor < 0 || candidates < 0 || absent < 0
        || present < 0 || unresolved < 0 || pages < 0) {
      throw new IllegalArgumentException(
          "Checkpoint cursor and counters must not be negative");
    }

    // Subtraction avoids overflow while validating the total.
    if (absent > candidates
        || present > candidates - absent
        || candidates - absent - present != unresolved) {
      throw new IllegalArgumentException(
          "Outcome counters do not match candidateCount");
    }

    if (pages > candidates) {
      throw new IllegalArgumentException(
          "pageCount must not exceed candidateCount");
    }
  }

  private static void validateProgress(
      long cursor, long candidates, long pages) {

    if (candidates == 0) {
      if (cursor != 0 || pages != 0) {
        throw new IllegalArgumentException(
            "An empty checkpoint must have zero cursor and pageCount");
      }
    } else if (cursor == 0 || pages == 0) {
      throw new IllegalArgumentException(
          "A non-empty checkpoint must have positive cursor and pageCount");
    }
  }
}