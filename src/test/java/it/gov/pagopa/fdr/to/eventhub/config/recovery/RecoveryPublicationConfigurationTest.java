package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryPublicationConfigurationTest {

  @Test
  void shouldReadExplicitLimits() {
    var configuration =
        RecoveryPublicationConfiguration.fromEnvironment(
            validEnvironment());

    assertEquals(1_048_576, configuration.maxCompressedBytes());
    assertEquals(8_388_608, configuration.maxDecompressedBytes());
    assertEquals(8_388_608, configuration.maxInnerXmlBytes());
    assertEquals(20, configuration.maxFdr1Candidates());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "RECOVERY_MAX_COMPRESSED_BLOB_BYTES",
      "RECOVERY_MAX_DECOMPRESSED_BLOB_BYTES",
      "RECOVERY_MAX_INNER_XML_BYTES",
      "RECOVERY_MAX_FDR1_CANDIDATES"
  })
  void shouldRejectMissingSetting(String name) {
    var environment = validEnvironment();
    environment.remove(name);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecoveryPublicationConfiguration.fromEnvironment(
                environment));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "RECOVERY_MAX_COMPRESSED_BLOB_BYTES",
      "RECOVERY_MAX_DECOMPRESSED_BLOB_BYTES",
      "RECOVERY_MAX_INNER_XML_BYTES",
      "RECOVERY_MAX_FDR1_CANDIDATES"
  })
  void shouldRejectNonNumericSetting(String name) {
    var environment = validEnvironment();
    environment.put(name, "invalid");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecoveryPublicationConfiguration.fromEnvironment(
                environment));
  }

  @ParameterizedTest
  @CsvSource({
      "RECOVERY_MAX_COMPRESSED_BLOB_BYTES,0",
      "RECOVERY_MAX_COMPRESSED_BLOB_BYTES,-1",
      "RECOVERY_MAX_DECOMPRESSED_BLOB_BYTES,0",
      "RECOVERY_MAX_DECOMPRESSED_BLOB_BYTES,-1",
      "RECOVERY_MAX_INNER_XML_BYTES,0",
      "RECOVERY_MAX_INNER_XML_BYTES,-1",
      "RECOVERY_MAX_FDR1_CANDIDATES,0",
      "RECOVERY_MAX_FDR1_CANDIDATES,-1"
  })
  void shouldRejectNonPositiveSetting(String name, String value) {
    var environment = validEnvironment();
    environment.put(name, value);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecoveryPublicationConfiguration.fromEnvironment(
                environment));
  }

  @Test
  void shouldRejectTooManyXmlCandidates() {
    var environment = validEnvironment();
    environment.put("RECOVERY_MAX_FDR1_CANDIDATES", "1001");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecoveryPublicationConfiguration.fromEnvironment(
                environment));
  }

  @Test
  void shouldRejectNullEnvironment() {
    assertThrows(
        NullPointerException.class,
        () -> RecoveryPublicationConfiguration.fromEnvironment(null));
  }

  private static Map<String, String> validEnvironment() {
    return new HashMap<>(
        Map.of(
            "RECOVERY_MAX_COMPRESSED_BLOB_BYTES", "1048576",
            "RECOVERY_MAX_DECOMPRESSED_BLOB_BYTES", "8388608",
            "RECOVERY_MAX_INNER_XML_BYTES", "8388608",
            "RECOVERY_MAX_FDR1_CANDIDATES", "20"));
  }
}