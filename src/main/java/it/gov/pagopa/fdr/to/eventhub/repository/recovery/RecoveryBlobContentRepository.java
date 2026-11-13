package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobRequestConditions;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public final class RecoveryBlobContentRepository {

  private static final Set<String> SOURCE_CONTAINERS =
      Set.of("fdr1-flows", "fdr3-flows");

  private static final Duration OPERATION_TIMEOUT =
      Duration.ofSeconds(60);

  private final BlobContainerClient container;
  private final String containerName;
  private final int maxCompressedBytes;

  public RecoveryBlobContentRepository(
      BlobContainerClient container,
      String expectedContainerName,
      int maxCompressedBytes) {

    this.container =
        Objects.requireNonNull(container, "container is required");

    this.containerName =
        Objects.requireNonNull(
            expectedContainerName,
            "expectedContainerName is required");

    if (!SOURCE_CONTAINERS.contains(containerName)) {
      throw new IllegalArgumentException(
          "Unsupported recovery source container");
    }

    if (!containerName.equals(container.getBlobContainerName())) {
      throw new IllegalArgumentException(
          "Unexpected source container: expected " + containerName);
    }

    if (maxCompressedBytes <= 0) {
      throw new IllegalArgumentException(
          "maxCompressedBytes must be positive");
    }

    this.maxCompressedBytes = maxCompressedBytes;
  }

  public byte[] download(RecoveryBlobSnapshot snapshot) throws RecoveryContentSizeException {
    Objects.requireNonNull(snapshot, "snapshot is required");

    if (!containerName.equals(snapshot.containerName())) {
      throw new IllegalArgumentException(
          "The snapshot must refer to " + containerName);
    }

    if ("*".equals(snapshot.etag())) {
      throw new IllegalArgumentException(
          "A specific snapshot ETag is required");
    }

    if (snapshot.contentLength() > maxCompressedBytes) {
        throw new RecoveryContentSizeException(RecoveryContentSizeException.Stage.COMPRESSED_BLOB, maxCompressedBytes,
                snapshot.contentLength());
    }

    FixedLengthOutputStream output =
        new FixedLengthOutputStream((int) snapshot.contentLength());

    BlobRequestConditions conditions =
        new BlobRequestConditions()
            .setIfMatch(snapshot.etag());

    BlobClient blob = container.getBlobClient(snapshot.blobName());

    blob.downloadStreamWithResponse(
        output,
        null,
        null,
        conditions,
        false,
        OPERATION_TIMEOUT,
        Context.NONE);

    return output.completedContent();
  }

  private static final class FixedLengthOutputStream
      extends OutputStream {

    private final byte[] content;
    private int position;

    private FixedLengthOutputStream(int expectedLength) {
      content = new byte[expectedLength];
    }

    @Override
    public void write(int value) {
      requireCapacity(1);
      content[position++] = (byte) value;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      requireCapacity(length);

      System.arraycopy(bytes, offset, content, position, length);
      position += length;
    }

    private void requireCapacity(int length) {
      if (length > content.length - position) {
        throw new IllegalStateException(
            "Blob content exceeds snapshot length");
      }
    }

    private byte[] completedContent() {
      if (position != content.length) {
        throw new IllegalStateException(
            "Blob content length does not match snapshot");
      }

      return content;
    }
  }
}