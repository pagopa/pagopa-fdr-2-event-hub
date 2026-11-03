package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import java.util.Objects;

public record StoredRecoveryDiscoveryCheckpoint(
    RecoveryDiscoveryCheckpoint checkpoint,
    String etag) {

  public StoredRecoveryDiscoveryCheckpoint {
    Objects.requireNonNull(checkpoint, "checkpoint is required");

    if (etag == null
        || etag.isBlank()
        || "*".equals(etag.trim())) {
      throw new IllegalArgumentException(
          "A non-blank, non-wildcard ETag is required");
    }
  }
}