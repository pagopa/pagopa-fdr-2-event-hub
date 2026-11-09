package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryBlobDecompressor;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_FDR1_BLOB_INTEGRATION_TESTS",
    matches = "true")
class RecoveryFdr1BlobRepositoryIT {

    // Limits for DEV fixture
    private static final int MAX_COMPRESSED_BYTES = 1024 * 1024;
    private static final int MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024;

    private static final String EXPECTED_ACCOUNT =
      "pagopadweufdrsa";

  private static final String CONTAINER_NAME =
      "fdr1-flows";

  private static final int MAX_CANDIDATES = 20;

  private static final String KNOWN_BLOB_NAME =
      "2026-10-1660000000001-1741953872446"
          + "_377137b2-6193-455a-8ea5-b693e3bb21cd.xml.zip";

  private static final String KNOWN_SESSION_ID =
      "6ecc100f-cd56-422d-815b-8243edc81a84";

  private static final RecoveryFlowCandidate CANDIDATE =
      new RecoveryFlowCandidate(
          592L,
          "2026-10-1660000000001-1741953872446",
          1L,
          "60000000001",
          "15376371009",
          LocalDateTime.of(
              2026, 10, 29, 16, 39, 37, 810_000_000));

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
        "This integration test must target DEV");

    return service.getBlobContainerClient(CONTAINER_NAME);
  }

  @Test
  void shouldListKnownXmlCandidateWithoutFilteringElaborate() {
    RecoveryFdr1BlobRepository repository =
        new RecoveryFdr1BlobRepository(
            devContainer(), MAX_CANDIDATES);

    List<RecoveryBlobSnapshot> candidates =
        repository.listCandidates(CANDIDATE);

    assertTrue(!candidates.isEmpty());
    assertTrue(candidates.size() <= MAX_CANDIDATES);

    RecoveryBlobSnapshot known = requireKnownCandidate(candidates);

    assertEquals(CONTAINER_NAME, known.containerName());
    assertTrue(known.contentLength() > 0);
    assertTrue(!known.etag().isBlank());

    assertEquals(
        KNOWN_SESSION_ID,
        requiredMetadata(known, "sessionId"));

    assertEquals(
        "FDR001",
        requiredMetadata(known, "serviceIdentifier"));

    assertEquals(
        "false",
        requiredMetadata(known, "elaborate"));

    System.out.printf(
        "DEV FDR1 candidate listing verified: "
            + "flow=%s, candidates=%d, "
            + "knownSessionId=%s, elaborate=false, writes=none%n",
        CANDIDATE.name(),
        candidates.size(),
        KNOWN_SESSION_ID);
  }

  @Test
  void shouldReturnEmptyForUnknownFlowPrefix() {
    RecoveryFdr1BlobRepository repository =
        new RecoveryFdr1BlobRepository(
            devContainer(), MAX_CANDIDATES);

    RecoveryFlowCandidate missing =
        new RecoveryFlowCandidate(
            1L,
            "it-recovery-missing-" + UUID.randomUUID(),
            1L,
            "60000000001",
            "15376371009",
            LocalDateTime.of(2026, 10, 1, 0, 0));

    assertTrue(repository.listCandidates(missing).isEmpty());

    System.out.println(
        "DEV FDR1 unknown prefix verified: candidates=0, writes=none");
  }

  @Test
  void shouldDownloadAndDecompressKnownXmlCandidate()
      throws IOException {

    BlobContainerClient container = devContainer();

    RecoveryFdr1BlobRepository propertiesRepository =
        new RecoveryFdr1BlobRepository(
            container, MAX_CANDIDATES);

    RecoveryBlobSnapshot snapshot =
        requireKnownCandidate(
            propertiesRepository.listCandidates(CANDIDATE));

    RecoveryBlobContentRepository contentRepository =
        new RecoveryBlobContentRepository(
            container,
            CONTAINER_NAME,
            MAX_COMPRESSED_BYTES);

    byte[] compressed = contentRepository.download(snapshot);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES);

    byte[] decompressed = decompressor.decompress(compressed);

    assertEquals(
        snapshot.contentLength(),
        (long) compressed.length);

    assertTrue(
        decompressed.length > 0,
        "The XML fixture must contain a non-empty payload");

    assertEquals(
        KNOWN_SESSION_ID,
        requiredMetadata(snapshot, "sessionId"));

    System.out.printf(
        "DEV XML download and decompression verified: "
            + "blob=%s, sessionId=%s, "
            + "compressedBytes=%d, decompressedBytes=%d, "
            + "etag=%s, writes=none%n",
        snapshot.blobName(),
        KNOWN_SESSION_ID,
        compressed.length,
        decompressed.length,
        snapshot.etag());
  }

  @Test
  void shouldRejectMismatchedXmlETag() {
    BlobContainerClient container = devContainer();

    RecoveryFdr1BlobRepository propertiesRepository =
        new RecoveryFdr1BlobRepository(
            container, MAX_CANDIDATES);

    RecoveryBlobSnapshot current =
        requireKnownCandidate(
            propertiesRepository.listCandidates(CANDIDATE));

    String mismatchedETag = "\"0x0000000000000000\"";

    assertNotEquals(current.etag(), mismatchedETag);

    RecoveryBlobSnapshot mismatched =
        new RecoveryBlobSnapshot(
            current.containerName(),
            current.blobName(),
            mismatchedETag,
            current.contentLength(),
            current.metadata());

    RecoveryBlobContentRepository contentRepository =
        new RecoveryBlobContentRepository(
            container,
            CONTAINER_NAME,
            MAX_COMPRESSED_BYTES);

    BlobStorageException failure =
        assertThrows(
            BlobStorageException.class,
            () -> contentRepository.download(mismatched));

    assertEquals(412, failure.getStatusCode());
    assertEquals(
        BlobErrorCode.CONDITION_NOT_MET,
        failure.getErrorCode());

    System.out.println(
        "DEV XML mismatched ETag verified: "
            + "status=412, code=ConditionNotMet, writes=none");
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

  private static RecoveryBlobSnapshot requireKnownCandidate(List<RecoveryBlobSnapshot> candidates) {

      return candidates.stream().filter(blob -> KNOWN_BLOB_NAME.equals(blob.blobName())).findFirst()
              .orElseThrow(() -> new IllegalStateException("The verified DEV XML fixture is missing"));
  }
}