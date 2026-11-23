package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.storage.blob.BlobClient;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryWorkerLockClientFactoryTest {

  private static final String CONNECTION_STRING_ENV =
      "FDR_SA_CONNECTION_STRING";

  private String testConnectionString() {
    // Synthetic key: no actual Azure credentials are used.
    String key =
        Base64.getEncoder().encodeToString(new byte[32]);

    return "DefaultEndpointsProtocol=https;"
        + "AccountName=recoverytestaccount;"
        + "AccountKey=" + key + ";"
        + "EndpointSuffix=core.windows.net";
  }

  @Test
  void shouldBuildClientForSharedWorkerLockWithoutConnecting() {
    Map<String, String> environment =
        Map.of(
            CONNECTION_STRING_ENV,
            testConnectionString());

    BlobClient client =
        RecoveryWorkerLockClientFactory.fromEnvironment(environment);

    assertEquals(
        "recoverytestaccount",
        client.getAccountName());

    assertEquals(
        "fdr-recovery-locks",
        client.getContainerName());

    assertEquals(
        "worker.lock",
        client.getBlobName());

    assertEquals(
        "https://recoverytestaccount.blob.core.windows.net/"
            + "fdr-recovery-locks/worker.lock",
        client.getBlobUrl());
  }

  @Test
  void shouldRejectNullEnvironment() {
    assertThrows(
        NullPointerException.class,
        () -> RecoveryWorkerLockClientFactory.fromEnvironment(null));
  }

  @Test
  void shouldRejectMissingConnectionString() {
    Map<String, String> environment = Map.of();

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RecoveryWorkerLockClientFactory.fromEnvironment(
                    environment));

    assertEquals(
        "FDR_SA_CONNECTION_STRING is required",
        thrown.getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void shouldRejectNullOrBlankConnectionString(String value) {
    Map<String, String> environment = new HashMap<>();
    environment.put(CONNECTION_STRING_ENV, value);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RecoveryWorkerLockClientFactory.fromEnvironment(
                    environment));

    assertEquals(
        "FDR_SA_CONNECTION_STRING is required",
        thrown.getMessage());
  }

  @Test
  void shouldRejectMalformedConnectionString() {
    Map<String, String> environment =
        Map.of(
            CONNECTION_STRING_ENV,
            "not-a-connection-string");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecoveryWorkerLockClientFactory.fromEnvironment(
                environment));
  }
}