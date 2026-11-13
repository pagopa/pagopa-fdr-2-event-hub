package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RecoveryPublicationDiscoveryItem(
    UUID jobId,
    LocalDate day,
    RecoveryFlowCandidate candidate,
    Outcome outcome) {

  public enum Status {
    ABSENT,
    PRESENT_UNVERIFIED,
    UNRESOLVED
  }

  public sealed interface Outcome permits Compared, Unresolved {
    Status status();
  }

  public record Compared(
      RecoveryPublicationSource source,
      Status status,
      long presentRows) implements Outcome {

    public Compared {
      Objects.requireNonNull(source, "source is required");
      Objects.requireNonNull(status, "status is required");

      if (status == Status.UNRESOLVED) {
        throw new IllegalArgumentException(
            "Compared outcome cannot have UNRESOLVED status");
      }

      if (presentRows < 0) {
        throw new IllegalArgumentException(
            "presentRows must not be negative");
      }

      if (status == Status.ABSENT && presentRows != 0) {
        throw new IllegalArgumentException(
            "An absent publication must have zero presentRows");
      }

      if (status == Status.PRESENT_UNVERIFIED && presentRows == 0) {
        throw new IllegalArgumentException(
            "A present publication must have positive presentRows");
      }
    }
  }

  public record Unresolved(
      String reason,
      Map<String, String> details) implements Outcome {

    public Unresolved {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason is required");
      }

      details = Map.copyOf(
          Objects.requireNonNull(details, "details is required"));
    }

    @Override
    public Status status() {
      return Status.UNRESOLVED;
    }
  }

  public RecoveryPublicationDiscoveryItem {
    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(candidate, "candidate is required");
    Objects.requireNonNull(outcome, "outcome is required");

    if (candidate.flowId() <= 0) {
      throw new IllegalArgumentException("flowId must be positive");
    }

    if (outcome instanceof Compared compared
        && !candidate.equals(compared.source().candidate())) {

      throw new IllegalArgumentException(
          "Selected source belongs to a different candidate");
    }
  }

  public String partitionKey() {
    return jobId + "_" + day;
  }

  public String rowKey() {
    return String.format(
        Locale.ROOT, "FLOW_%020d", candidate.flowId());
  }
}