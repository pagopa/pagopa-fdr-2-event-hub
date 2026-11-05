package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import java.util.Map;
import java.util.Objects;

public final class RecoveryWorkerLockClientFactory {

  private static final String CONNECTION_STRING_ENV =
      "FDR_SA_CONNECTION_STRING";

  private static final String CONTAINER_NAME =
      "fdr-recovery-locks";

  private static final String BLOB_NAME =
      "worker.lock";

  private RecoveryWorkerLockClientFactory() {
  }

  public static BlobClient fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  public static BlobClient fromEnvironment(
      Map<String, String> environment) {

    Objects.requireNonNull(
        environment,
        "environment is required");

    String connectionString =
        environment.get(CONNECTION_STRING_ENV);

    if (connectionString == null || connectionString.isBlank()) {
      throw new IllegalArgumentException(
          CONNECTION_STRING_ENV + " is required");
    }

    return new BlobServiceClientBuilder()
        .connectionString(connectionString.trim())
        .buildClient()
        .getBlobContainerClient(CONTAINER_NAME)
        .getBlobClient(BLOB_NAME);
  }
}