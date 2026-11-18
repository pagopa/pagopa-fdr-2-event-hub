package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import java.util.Map;
import java.util.Objects;

public record RecoveryBlobSnapshot(
    String containerName,
    String blobName,
    String etag,
    long contentLength,
    Map<String, String> metadata) {

  public RecoveryBlobSnapshot {
    requireText(containerName, "containerName");
    requireText(blobName, "blobName");
    requireText(etag, "etag");

    if (contentLength < 0) {
      throw new IllegalArgumentException(
          "contentLength must not be negative");
    }

    metadata =
        Map.copyOf(
            Objects.requireNonNull(
                metadata, "metadata is required"));
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}