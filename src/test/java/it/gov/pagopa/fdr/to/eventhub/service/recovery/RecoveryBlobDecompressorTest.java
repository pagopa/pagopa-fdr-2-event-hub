package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryBlobDecompressorTest {

  private static final byte[] PAYLOAD =
      "{\"fdr\":\"flow-1\"}".getBytes(StandardCharsets.UTF_8);

  @ParameterizedTest
  @ValueSource(strings = {"gzip", "zip"})
  void shouldDecompressSupportedFormats(String format)
      throws IOException {

    byte[] compressed = compress(format, PAYLOAD);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(1024);

    assertArrayEquals(
        PAYLOAD,
        decompressor.decompress(compressed));
  }

  @ParameterizedTest
  @ValueSource(strings = {"gzip", "zip"})
  void shouldAcceptContentExactlyAtLimit(String format)
      throws IOException {

    byte[] compressed = compress(format, PAYLOAD);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(PAYLOAD.length);

    assertArrayEquals(
        PAYLOAD,
        decompressor.decompress(compressed));
  }

  @ParameterizedTest
  @ValueSource(strings = {"gzip", "zip"})
  void shouldRejectContentExceedingLimit(String format)
      throws IOException {

    byte[] compressed = compress(format, PAYLOAD);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(PAYLOAD.length - 1);

    assertThrows(
        IOException.class,
        () -> decompressor.decompress(compressed));
  }

  @Test
  void shouldRejectCorruptedGzipChecksum() throws IOException {
    byte[] compressed = gzip(PAYLOAD);

    // The GZIP trailer starts with the CRC32 of the original content.
    compressed[compressed.length - 8] ^= 1;

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(1024);

    assertThrows(
        IOException.class,
        () -> decompressor.decompress(compressed));
  }

  @Test
  void shouldRejectTruncatedGzip() throws IOException {
    byte[] compressed = gzip(PAYLOAD);

    byte[] truncated =
        Arrays.copyOf(compressed, compressed.length - 4);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(1024);

    assertThrows(
        IOException.class,
        () -> decompressor.decompress(truncated));
  }

  @Test
  void shouldRejectZipContainingMultipleFiles()
      throws IOException {

    byte[] compressed =
        zip(PAYLOAD, "first.json", "second.json");

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(1024);

    assertThrows(
        IOException.class,
        () -> decompressor.decompress(compressed));
  }

  @Test
  void shouldRejectZipContainingDirectoryEntry()
      throws IOException {

    byte[] compressed = zip(PAYLOAD, "folder/");

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(1024);

    assertThrows(
        IOException.class,
        () -> decompressor.decompress(compressed));
  }

  @Test
  void shouldRejectEmptyZipArchive() throws IOException {
    byte[] compressed = zip(PAYLOAD);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(1024);

    assertThrows(
        IOException.class,
        () -> decompressor.decompress(compressed));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "{}", "PKxx"})
  void shouldRejectUnsupportedOrEmptyContent(String value) {
    byte[] compressed = value.getBytes(StandardCharsets.UTF_8);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(1024);

    assertThrows(
        IOException.class,
        () -> decompressor.decompress(compressed));
  }

  @Test
  void shouldRejectNullContent() {
    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(1024);

    assertThrows(
        NullPointerException.class,
        () -> decompressor.decompress(null));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void shouldRejectInvalidLimit(int limit) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryBlobDecompressor(limit));
  }

  private static byte[] compress(String format, byte[] content)
      throws IOException {

    return "gzip".equals(format)
        ? gzip(content)
        : zip(content, "flow.json");
  }

  private static byte[] gzip(byte[] content) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(content);
    }

    return output.toByteArray();
  }

  private static byte[] zip(byte[] content, String... names)
      throws IOException {

    ByteArrayOutputStream output = new ByteArrayOutputStream();

    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (String name : names) {
        zip.putNextEntry(new ZipEntry(name));

        if (!name.endsWith("/")) {
          zip.write(content);
        }

        zip.closeEntry();
      }
    }

    return output.toByteArray();
  }
}