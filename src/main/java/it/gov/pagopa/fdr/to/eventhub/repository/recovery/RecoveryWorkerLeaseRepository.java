package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobAcquireLeaseOptions;
import com.azure.storage.blob.options.BlobReleaseLeaseOptions;
import com.azure.storage.blob.options.BlobRenewLeaseOptions;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import com.azure.storage.blob.specialized.BlobLeaseClientBuilder;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class RecoveryWorkerLeaseRepository {

  private static final int LEASE_DURATION_SECONDS = 60;
  private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(5);

  private final Function<String, BlobLeaseClient> clientFactory;

  public RecoveryWorkerLeaseRepository(BlobClient lockBlob) {
    this(defaultClientFactory(lockBlob));
  }

  RecoveryWorkerLeaseRepository(
      Function<String, BlobLeaseClient> clientFactory) {

    this.clientFactory =
        Objects.requireNonNull(
            clientFactory,
            "clientFactory is required");
  }

  public Optional<String> tryAcquire() {
    String leaseId = UUID.randomUUID().toString();

    BlobLeaseClient client = clientFactory.apply(leaseId);

    try {
      client.acquireLeaseWithResponse(
          new BlobAcquireLeaseOptions(LEASE_DURATION_SECONDS),
          OPERATION_TIMEOUT,
          Context.NONE);

      return Optional.of(leaseId);

    } catch (BlobStorageException exception) {
      if (exception.getStatusCode() == 409
          && BlobErrorCode.LEASE_ALREADY_PRESENT.equals(
              exception.getErrorCode())) {

        return Optional.empty();
      }

      throw exception;
    }
  }

  public void renew(String leaseId) {
    validateLeaseId(leaseId);

    clientFactory.apply(leaseId)
        .renewLeaseWithResponse(
            new BlobRenewLeaseOptions(),
            OPERATION_TIMEOUT,
            Context.NONE);
  }

  public void release(String leaseId) {
    validateLeaseId(leaseId);

    clientFactory.apply(leaseId)
        .releaseLeaseWithResponse(
            new BlobReleaseLeaseOptions(),
            OPERATION_TIMEOUT,
            Context.NONE);
  }

  private static void validateLeaseId(String leaseId) {
    if (leaseId == null || leaseId.isBlank()) {
      throw new IllegalArgumentException(
          "leaseId is required");
    }
  }

  private static Function<String, BlobLeaseClient> defaultClientFactory(
      BlobClient lockBlob) {

    Objects.requireNonNull(lockBlob, "lockBlob is required");

    return leaseId ->
        new BlobLeaseClientBuilder()
            .blobClient(lockBlob)
            .leaseId(leaseId)
            .buildClient();
  }
}