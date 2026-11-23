package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryBlobDecompressor;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3BlobMetadataInspector;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3IdentityMatcher;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3IdentityReader;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3SourceInspector;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3SourceVerifier;

import java.io.IOException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_FDR3_BLOB_INTEGRATION_TESTS",
    matches = "true")
class RecoveryFdr3BlobContentRepositoryIT {

  private static final String EXPECTED_ACCOUNT =
      "pagopadweufdrsa";

  private static final String CONTAINER_NAME =
      "fdr3-flows";

  // Limit for small DEV fixture
  private static final int MAX_COMPRESSED_BYTES = 1024 * 1024;
  private static final int MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024;

  private static final RecoveryFlowCandidate CANDIDATE =
      new RecoveryFlowCandidate(
          299L,
          "2026-10-0588888888888-125026001309",
          1L,
          "88888888888",
          "15376371009",
          LocalDateTime.of(2026, 10, 5, 12, 50, 26, 1_454_000));



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

  private RecoveryBlobSnapshot readSnapshot(
      BlobContainerClient container) {

    return new RecoveryFdr3BlobRepository(container)
        .find(CANDIDATE)
        .orElseThrow(
            () -> new IllegalStateException(
                "The verified DEV fixture blob is missing"));
  }

  @Test
  void shouldDownloadContentMatchingSnapshotFromDev() throws RecoveryContentSizeException {
    BlobContainerClient container = devContainer();
    RecoveryBlobSnapshot snapshot = readSnapshot(container);

    RecoveryFdr3BlobContentRepository repository =
        new RecoveryFdr3BlobContentRepository(
            container, MAX_COMPRESSED_BYTES);

    byte[] content = repository.download(snapshot);

    assertTrue(content.length > 0);
    assertEquals(
        snapshot.contentLength(),
        (long) content.length);

    System.out.printf(
        "DEV conditional download verified: blob=%s, "
            + "bytes=%d, etag=%s%n",
        snapshot.blobName(),
        content.length,
        snapshot.etag());
  }

  @Test
  void shouldRejectMismatchedETagWithoutChangingBlob() {
    BlobContainerClient container = devContainer();
    RecoveryBlobSnapshot current = readSnapshot(container);

    String mismatchedETag = "\"0x0000000000000000\"";

    assertNotEquals(
        current.etag(),
        mismatchedETag,
        "The test ETag must differ from the current ETag");

    RecoveryBlobSnapshot mismatched =
        new RecoveryBlobSnapshot(
            current.containerName(),
            current.blobName(),
            mismatchedETag,
            current.contentLength(),
            current.metadata());

    RecoveryFdr3BlobContentRepository repository =
        new RecoveryFdr3BlobContentRepository(
            container, MAX_COMPRESSED_BYTES);

    BlobStorageException failure =
        assertThrows(
            BlobStorageException.class,
            () -> repository.download(mismatched));

    assertEquals(412, failure.getStatusCode());
    assertEquals(
        BlobErrorCode.CONDITION_NOT_MET,
        failure.getErrorCode());

    System.out.println(
        "DEV mismatched ETag verified: "
            + "status=412, code=ConditionNotMet, writes=none");
  }

  @Test
  void shouldDecompressDownloadedContentWithinLimit()
      throws IOException {

    BlobContainerClient container = devContainer();
    RecoveryBlobSnapshot snapshot = readSnapshot(container);

    RecoveryFdr3BlobContentRepository repository =
        new RecoveryFdr3BlobContentRepository(
            container, MAX_COMPRESSED_BYTES);

    byte[] compressed = repository.download(snapshot);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES);

    byte[] decompressed = decompressor.decompress(compressed);

    assertEquals(
        snapshot.contentLength(),
        (long) compressed.length);

    assertTrue(
        decompressed.length > 1,
        "The verified DEV fixture must contain a non-empty payload");

    assertTrue(
        decompressed.length <= MAX_DECOMPRESSED_BYTES,
        "The decompressed fixture must respect the configured limit");

    // Reuse the downloaded bytes: no additional storage request.
    RecoveryBlobDecompressor insufficientLimit =
        new RecoveryBlobDecompressor(decompressed.length - 1);

    IOException failure =
        assertThrows(
            IOException.class,
            () -> insufficientLimit.decompress(compressed));

    assertEquals(
        "Decompressed content exceeds maxDecompressedBytes",
        failure.getMessage());

    System.out.printf(
        "DEV decompression verified: blob=%s, "
            + "compressedBytes=%d, decompressedBytes=%d, "
            + "insufficientLimitRejected=true%n",
        snapshot.blobName(),
        compressed.length,
        decompressed.length);
  }

  @Test
  void shouldReadIdentityFromDevJson() throws IOException {
    BlobContainerClient container = devContainer();
    RecoveryBlobSnapshot snapshot = readSnapshot(container);

    RecoveryFdr3BlobContentRepository repository =
        new RecoveryFdr3BlobContentRepository(
            container, MAX_COMPRESSED_BYTES);

    byte[] compressed = repository.download(snapshot);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES);

    byte[] json = decompressor.decompress(compressed);

    RecoveryFdr3IdentityReader.Identity identity =
        new RecoveryFdr3IdentityReader().read(json);

    RecoveryFdr3IdentityMatcher.Comparison comparison = new RecoveryFdr3IdentityMatcher().compare(CANDIDATE, identity);

    assertTrue(comparison.matches(), () -> "DEV source identity differs in fields: " + comparison.differences());

    System.out.println("DEV identity comparison verified: " + "name, revision, PSP, organization and flowDate match");

    assertEquals(CANDIDATE.name(), identity.name());
    assertEquals(CANDIDATE.revision(), identity.revision());
    assertEquals(
        CANDIDATE.pspDomainId(),
        identity.pspDomainId());
    assertEquals(
        CANDIDATE.orgDomainId(),
        identity.orgDomainId());

    assertNotNull(identity.flowDate());

    System.out.printf(
        "DEV JSON identity verified: flow=%s, revision=%d, "
            + "psp=%s, organization=%s, flowDate=%s%n",
        identity.name(),
        identity.revision(),
        identity.pspDomainId(),
        identity.orgDomainId(),
        identity.flowDate());
  }

  @Test
  void shouldVerifyDevSourceWithComposedService()
      throws IOException {

    BlobContainerClient container = devContainer();

    RecoveryFdr3BlobRepository propertiesRepository =
        new RecoveryFdr3BlobRepository(container);

    RecoveryFdr3SourceInspector sourceInspector =
        new RecoveryFdr3SourceInspector(
            propertiesRepository,
            new RecoveryFdr3BlobMetadataInspector());

    RecoveryFdr3BlobContentRepository contentRepository =
        new RecoveryFdr3BlobContentRepository(
            container, MAX_COMPRESSED_BYTES);

    RecoveryFdr3SourceVerifier verifier =
        new RecoveryFdr3SourceVerifier(
            sourceInspector,
            contentRepository,
            new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES),
            new RecoveryFdr3IdentityReader(),
            new RecoveryFdr3IdentityMatcher());

    RecoveryFdr3SourceVerifier.Verified result =
        assertInstanceOf(
            RecoveryFdr3SourceVerifier.Verified.class,
            verifier.verify(CANDIDATE));

    assertEquals(CANDIDATE, result.candidate());
    assertEquals(
        CANDIDATE.blobName(),
        result.blob().blobName());

    assertEquals(
        "a92570e7-d5f5-49da-bb8f-0226e2d95ac7",
        result.metadata().sessionId());

    assertTrue(
        result.metadata().elaborate(),
        "The verified DEV fixture must retain its original flag");

    System.out.printf(
        "DEV composed source verification completed: "
            + "flow=%s, revision=%d, sessionId=%s, "
            + "flowDate=%s, compressedBytes=%d%n",
        result.identity().name(),
        result.identity().revision(),
        result.metadata().sessionId(),
        result.identity().flowDate(),
        result.blob().contentLength());
  }
}