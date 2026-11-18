package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RecoveryPublicationSource(
    RecoveryFlowCandidate candidate,
    RecoveryBlobSnapshot blob,
    SourceType sourceType,
    String sessionId,
    Instant eventFlowDate) {

  public enum SourceType {
    FDR1_XML("fdr1-flows"),
    FDR3_JSON("fdr3-flows");

    private final String containerName;

    SourceType(String containerName) {
      this.containerName = containerName;
    }

    public String containerName() {
      return containerName;
    }
  }

  public RecoveryPublicationSource {
    Objects.requireNonNull(candidate, "candidate is required");
    Objects.requireNonNull(blob, "blob is required");
    Objects.requireNonNull(sourceType, "sourceType is required");
    Objects.requireNonNull(sessionId, "sessionId is required");
    Objects.requireNonNull(eventFlowDate, "eventFlowDate is required");

    if (!sourceType.containerName().equals(blob.containerName())) {
      throw new IllegalArgumentException(
          "Source type and blob container do not match");
    }

    if (!UUID.fromString(sessionId)
        .toString()
        .equalsIgnoreCase(sessionId)) {
      throw new IllegalArgumentException(
          "sessionId must be a full UUID");
    }
  }
}