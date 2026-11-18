package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import java.util.Objects;

public record StoredRecoveryPublicationDiscoveryCheckpoint(
    RecoveryPublicationDiscoveryCheckpoint checkpoint,
    String etag) {

  public StoredRecoveryPublicationDiscoveryCheckpoint {
    Objects.requireNonNull(checkpoint, "checkpoint is required");

    if (etag == null || etag.isBlank() || "*".equals(etag.strip())) {
      throw new IllegalArgumentException(
          "A specific checkpoint ETag is required");
    }
  }
}