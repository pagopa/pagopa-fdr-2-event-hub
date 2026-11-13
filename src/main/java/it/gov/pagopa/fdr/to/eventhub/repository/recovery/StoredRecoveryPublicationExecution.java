package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationExecution;
import java.util.Objects;

public record StoredRecoveryPublicationExecution(
    RecoveryPublicationExecution execution,
    String etag) {

  public StoredRecoveryPublicationExecution {
    Objects.requireNonNull(execution, "execution is required");

    if (etag == null
        || etag.isBlank()
        || "*".equals(etag.strip())) {

      throw new IllegalArgumentException(
          "A specific execution ETag is required");
    }
  }
}