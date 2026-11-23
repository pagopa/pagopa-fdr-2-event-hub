package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import java.time.Duration;
import java.util.Objects;

public final class RecoveryWorkerLockBlobInitializer {

  private static final Duration OPERATION_TIMEOUT =
      Duration.ofSeconds(5);

  private final BlobClient lockBlob;

  public RecoveryWorkerLockBlobInitializer(BlobClient lockBlob) {
    this.lockBlob =
        Objects.requireNonNull(lockBlob, "lockBlob is required");
  }

  public void ensureExists() {
    if (exists()) {
      return;
    }

    BlobParallelUploadOptions options =
        new BlobParallelUploadOptions(
            BinaryData.fromString("recovery-worker-lock"))
            .setRequestConditions(
                new BlobRequestConditions().setIfNoneMatch("*"));

    try {
      lockBlob.uploadWithResponse(
          options,
          OPERATION_TIMEOUT,
          Context.NONE);

    } catch (BlobStorageException exception) {
      if (!isConcurrentCreation(exception) || !exists()) {
        throw exception;
      }
    }
  }

  private boolean exists() {
    return lockBlob.existsWithResponse(
            OPERATION_TIMEOUT,
            Context.NONE)
        .getValue();
  }

  private static boolean isConcurrentCreation(
      BlobStorageException exception) {

    BlobErrorCode code = exception.getErrorCode();

    return (exception.getStatusCode() == 409
            && BlobErrorCode.BLOB_ALREADY_EXISTS.equals(code))
        || (exception.getStatusCode() == 412
            && (BlobErrorCode.CONDITION_NOT_MET.equals(code)
                || BlobErrorCode.LEASE_ID_MISSING.equals(code)));
  }
}