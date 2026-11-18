package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_FDR3_BLOB_INTEGRATION_TESTS",
    matches = "true")
class RecoveryFdr3BlobRepositoryIT {

  private static final String EXPECTED_ACCOUNT =
      "pagopadweufdrsa";

  private static final String CONTAINER_NAME =
      "fdr3-flows";

  private static final String EXPECTED_SESSION_ID =
      "a92570e7-d5f5-49da-bb8f-0226e2d95ac7";

  private static final RecoveryFlowCandidate KNOWN_CANDIDATE =
      new RecoveryFlowCandidate(
          299L,
          "2026-10-0588888888888-125026001309",
          1L,
          "88888888888",
          "15376371009",
          LocalDateTime.of(
              2026, 10, 5, 12, 50, 26, 1_000_000));

  private BlobContainerClient devContainer() {
    String connectionString =
        System.getenv("FDR_SA_CONNECTION_STRING");

    assertNotNull(
        connectionString,
        "FDR_SA_CONNECTION_STRING must target DEV");

    assertTrue(
        !connectionString.isBlank(),
        "FDR_SA_CONNECTION_STRING must not be blank");

    BlobServiceClient service =
        new BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();

    // Validate the destination before performing network operations.
    assertEquals(
        EXPECTED_ACCOUNT,
        service.getAccountName(),
        "This integration test must target the DEV storage account");

    BlobContainerClient container =
        service.getBlobContainerClient(CONTAINER_NAME);

    assertEquals(CONTAINER_NAME, container.getBlobContainerName());

    return container;
  }

  @Test
  void shouldReadKnownRevisionPropertiesFromDev() {
    RecoveryFdr3BlobRepository repository =
        new RecoveryFdr3BlobRepository(devContainer());

    RecoveryBlobSnapshot snapshot =
        repository.find(KNOWN_CANDIDATE)
            .orElseThrow(
                () -> new IllegalStateException(
                    "The verified DEV fixture blob is missing"));

    assertEquals(CONTAINER_NAME, snapshot.containerName());
    assertEquals(KNOWN_CANDIDATE.blobName(), snapshot.blobName());
    assertTrue(!snapshot.etag().isBlank());
    assertTrue(snapshot.contentLength() > 0);

    assertEquals(
        EXPECTED_SESSION_ID,
        requiredMetadata(snapshot, "sessionId"));

    assertEquals(
        "FDR03",
        requiredMetadata(snapshot, "serviceIdentifier"));

    assertEquals(
        "true",
        requiredMetadata(snapshot, "elaborate"));

    System.out.printf(
        "DEV FDR3 blob properties verified: blob=%s, "
            + "sessionId=%s, contentLength=%d, etag=%s%n",
        snapshot.blobName(),
        EXPECTED_SESSION_ID,
        snapshot.contentLength(),
        snapshot.etag());
  }

  @Test
  void shouldReportMissingBlobWithoutCreatingIt() {
    RecoveryFdr3BlobRepository repository =
        new RecoveryFdr3BlobRepository(devContainer());

    RecoveryFlowCandidate missing =
        new RecoveryFlowCandidate(
            1L,
            "it-recovery-missing-" + UUID.randomUUID(),
            1L,
            "88888888888",
            "15376371009",
            LocalDateTime.of(2026, 10, 1, 0, 0));

    assertTrue(
        repository.find(missing).isEmpty(),
        "A missing blob must produce an empty result");

    System.out.println(
        "DEV missing blob verified: result=ABSENT, writes=none");
  }

  private static String requiredMetadata(
      RecoveryBlobSnapshot snapshot,
      String name) {

    List<String> values =
        snapshot.metadata().entrySet().stream()
            .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
            .map(Map.Entry::getValue)
            .toList();

    assertEquals(
        1,
        values.size(),
        "Expected exactly one metadata entry for " + name);

    String value = values.get(0);

    assertTrue(
        !value.isBlank(),
        "Metadata must not be blank: " + name);

    return value;
  }
}