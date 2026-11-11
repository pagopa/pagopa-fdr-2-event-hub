package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import java.util.Map;
import java.util.Objects;

public record RecoveryPublicationConfiguration(
    int maxCompressedBytes,
    int maxDecompressedBytes,
    int maxInnerXmlBytes,
    int maxFdr1Candidates) {

  private static final String COMPRESSED =
      "RECOVERY_MAX_COMPRESSED_BLOB_BYTES";

  private static final String DECOMPRESSED =
      "RECOVERY_MAX_DECOMPRESSED_BLOB_BYTES";

  private static final String INNER_XML =
      "RECOVERY_MAX_INNER_XML_BYTES";

  private static final String XML_CANDIDATES =
      "RECOVERY_MAX_FDR1_CANDIDATES";

  public RecoveryPublicationConfiguration {
    requirePositive(maxCompressedBytes, COMPRESSED);
    requirePositive(maxDecompressedBytes, DECOMPRESSED);
    requirePositive(maxInnerXmlBytes, INNER_XML);
    requirePositive(maxFdr1Candidates, XML_CANDIDATES);

    if (maxFdr1Candidates > 1000) {
      throw new IllegalArgumentException(
          XML_CANDIDATES + " must not exceed 1000");
    }
  }

  public static RecoveryPublicationConfiguration fromEnvironment(
      Map<String, String> environment) {

    Objects.requireNonNull(environment, "environment is required");

    return new RecoveryPublicationConfiguration(
        requiredInteger(environment, COMPRESSED),
        requiredInteger(environment, DECOMPRESSED),
        requiredInteger(environment, INNER_XML),
        requiredInteger(environment, XML_CANDIDATES));
  }

  private static int requiredInteger(
      Map<String, String> environment,
      String name) {

    String value = environment.get(name);

    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }

    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          name + " must be an integer", exception);
    }
  }

  private static void requirePositive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}