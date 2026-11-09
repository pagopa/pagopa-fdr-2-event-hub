package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobRequestConditions;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;

public final class RecoveryFdr3BlobContentRepository {

  private static final String CONTAINER_NAME = "fdr3-flows";
  private static final Duration OPERATION_TIMEOUT =
      Duration.ofSeconds(60);

  private final BlobContainerClient container;
  private final int maxCompressedBytes;

  public RecoveryFdr3BlobContentRepository(
      BlobContainerClient container,
      int maxCompressedBytes) {

    this.container =
        Objects.requireNonNull(container, "container is required");

    if (!CONTAINER_NAME.equals(container.getBlobContainerName())) {
      throw new IllegalArgumentException(
          "The FDR3 source container must be " + CONTAINER_NAME);
    }

    if (maxCompressedBytes <= 0) {
      throw new IllegalArgumentException(
          "maxCompressedBytes must be positive");
    }

    this.maxCompressedBytes = maxCompressedBytes;
  }

  public byte[] download(RecoveryBlobSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot is required");

    if (!CONTAINER_NAME.equals(snapshot.containerName())) {
      throw new IllegalArgumentException(
          "The snapshot must refer to " + CONTAINER_NAME);
    }

    if ("*".equals(snapshot.etag())) {
      throw new IllegalArgumentException(
          "A specific snapshot ETag is required");
    }

    if (snapshot.contentLength() > maxCompressedBytes) {
      throw new IllegalArgumentException(
          "Compressed blob exceeds maxCompressedBytes");
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