package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RecoveryBlobDecompressor {

  private final int maxDecompressedBytes;

  public RecoveryBlobDecompressor(int maxDecompressedBytes) {
    if (maxDecompressedBytes <= 0) {
      throw new IllegalArgumentException(
          "maxDecompressedBytes must be positive");
    }

    this.maxDecompressedBytes = maxDecompressedBytes;
  }

  public byte[] decompress(byte[] compressed) throws IOException {
    Objects.requireNonNull(compressed, "compressed is required");

    if (hasSignature(compressed, 0x1f, 0x8b)) {
      try (InputStream input =
          new GZIPInputStream(
              new ByteArrayInputStream(compressed))) {

        return readLimited(input);
      }
    }

    if (hasSignature(compressed, 0x50, 0x4b, 0x03, 0x04)
        || hasSignature(compressed, 0x50, 0x4b, 0x05, 0x06)) {

      return decompressZip(compressed);
    }

    throw new IOException(
        "Unsupported compression format: expected GZIP or ZIP");
  }

  private byte[] decompressZip(byte[] compressed)
      throws IOException {

    try (ZipInputStream input =
        new ZipInputStream(
            new ByteArrayInputStream(compressed))) {

      ZipEntry entry = input.getNextEntry();

      if (entry == null || entry.isDirectory()) {
        throw new IOException(
            "ZIP must contain exactly one file entry");
      }

      byte[] content = readLimited(input);

      if (input.getNextEntry() != null) {
        throw new IOException(
            "ZIP must contain exactly one file entry");
      }

      return content;
    }
  }

  private byte[] readLimited(InputStream input)
      throws IOException {

    ByteArrayOutputStream output =
        new ByteArrayOutputStream(
            Math.min(8192, maxDecompressedBytes));

    byte[] buffer = new byte[8192];

    while (true) {
      int remaining = maxDecompressedBytes - output.size();

      int requested =
          (int) Math.min(buffer.length, (long) remaining + 1);

      int read = input.read(buffer, 0, requested);

      if (read == -1) {
        return output.toByteArray();
      }

      if (read > remaining) {
        throw new IOException(
            "Decompressed content exceeds maxDecompressedBytes");
      }

      output.write(buffer, 0, read);
    }
  }

  private static boolean hasSignature(
      byte[] content,
      int... signature) {

    if (content.length < signature.length) {
      return false;
    }

    for (int index = 0; index < signature.length; index++) {
      if ((content[index] & 0xff) != signature[index]) {
        return false;
      }
    }

    return true;
  }
}