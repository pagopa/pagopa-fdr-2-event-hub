package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import java.util.Objects;

public record StoredRecoveryJob(RecoveryJob job, String etag) {

  public StoredRecoveryJob {
    Objects.requireNonNull(job, "job is required");

    if (etag == null || etag.isBlank() || "*".equals(etag.trim())) {
      throw new IllegalArgumentException("A specific ETag is required");
    }
  }
}