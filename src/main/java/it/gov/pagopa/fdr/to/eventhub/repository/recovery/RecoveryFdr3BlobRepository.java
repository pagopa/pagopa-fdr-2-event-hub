package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RecoveryFdr3BlobRepository {

  private static final String CONTAINER_NAME = "fdr3-flows";
  private static final Duration OPERATION_TIMEOUT =
      Duration.ofSeconds(30);

  private final BlobContainerClient container;

  public RecoveryFdr3BlobRepository(BlobContainerClient container) {
    this.container =
        Objects.requireNonNull(container, "container is required");

    if (!CONTAINER_NAME.equals(container.getBlobContainerName())) {
      throw new IllegalArgumentException(
          "The FDR3 source container must be " + CONTAINER_NAME);
    }
  }

  public Optional<RecoveryBlobSnapshot> find(
      RecoveryFlowCandidate candidate) {

    Objects.requireNonNull(candidate, "candidate is required");

    String blobName = candidate.blobName();
    BlobClient blob = container.getBlobClient(blobName);

    try {
      BlobProperties properties =
          blob.getPropertiesWithResponse(
                  null,
                  OPERATION_TIMEOUT,
                  Context.NONE)
              .getValue();

      Map<String, String> metadata = properties.getMetadata();

      return Optional.of(
          new RecoveryBlobSnapshot(
              CONTAINER_NAME,
              blobName,
              properties.getETag(),
              properties.getBlobSize(),
              metadata == null ? Map.of() : metadata));

    } catch (BlobStorageException exception) {
      if (exception.getStatusCode() == 404
          && BlobErrorCode.BLOB_NOT_FOUND.equals(
              exception.getErrorCode())) {

        return Optional.empty();
      }

      throw exception;
    }
  }
}