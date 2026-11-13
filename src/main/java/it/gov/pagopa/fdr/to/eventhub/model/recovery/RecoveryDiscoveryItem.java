package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFlowPresenceIndex;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record RecoveryDiscoveryItem(
    UUID jobId,
    LocalDate day,
    RecoveryFlowCandidate candidate,
    RecoveryFlowPresenceIndex.Presence presence,
    long presentRows) {

  public RecoveryDiscoveryItem {
    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(candidate, "candidate is required");
    Objects.requireNonNull(presence, "presence is required");

    if (candidate.flowId() <= 0) {
      throw new IllegalArgumentException("flowId must be positive");
    }

    if (presentRows < 0) {
      throw new IllegalArgumentException("presentRows must not be negative");
    }

    if (presence == RecoveryFlowPresenceIndex.Presence.ABSENT
        && presentRows != 0) {
      throw new IllegalArgumentException(
          "An absent candidate must have zero presentRows");
    }

    if (presence == RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED
        && presentRows == 0) {
      throw new IllegalArgumentException(
          "A present candidate must have positive presentRows");
    }
  }

  public static RecoveryDiscoveryItem fromComparison(
      UUID jobId,
      LocalDate day,
      RecoveryFlowPresenceIndex.Comparison comparison) {

    Objects.requireNonNull(comparison, "comparison is required");

    return new RecoveryDiscoveryItem(
        jobId,
        day,
        comparison.candidate(),
        comparison.presence(),
        comparison.presentRows());
  }

  public String partitionKey() {
    return jobId + "_" + day;
  }

  public String rowKey() {
    return String.format(
        Locale.ROOT,
        "FLOW_%020d",
        candidate.flowId());
  }
}