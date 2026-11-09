package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.rest.SimpleResponse;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr3BlobRepositoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static final String SESSION_ID =
      "6fd3e2eb-1d1d-4068-ba74-fa9866141524";

  private static final String ETAG = "\"source-etag\"";

  private static final RecoveryFlowCandidate CANDIDATE =
      new RecoveryFlowCandidate(
          155333L,
          "2026-10-05CIPBITMM-00002",
          1L,
          "CIPBITMM",
          "81000730077",
          LocalDateTime.of(2026, 10, 5, 10, 31, 35));

  private final BlobContainerClient container =
      mock(BlobContainerClient.class);

  private final BlobClient blob = mock(BlobClient.class);

  private final BlobProperties properties =
      mock(BlobProperties.class);

  private RecoveryFdr3BlobRepository repository;

  @BeforeEach
  void setUp() {
    when(container.getBlobContainerName())
        .thenReturn("fdr3-flows");

    when(container.getBlobClient(CANDIDATE.blobName()))
        .thenReturn(blob);

    repository = new RecoveryFdr3BlobRepository(container);
  }

  private void givenProperties(Map<String, String> metadata) {
    when(properties.getETag()).thenReturn(ETAG);
    when(properties.getBlobSize()).thenReturn(1234L);
    when(properties.getMetadata()).thenReturn(metadata);

    when(blob.getPropertiesWithResponse(
            isNull(), eq(TIMEOUT), eq(Context.NONE)))
        .thenReturn(
            new SimpleResponse<>(
                null,
                200,
                new HttpHeaders(),
                properties));
  }

  private BlobStorageException storageError(
      int status, String code) {

    BlobStorageException failure =
        mock(BlobStorageException.class);

    when(failure.getStatusCode()).thenReturn(status);
    when(failure.getErrorCode())
        .thenReturn(BlobErrorCode.fromString(code));

    return failure;
  }

  private void givenStorageFailure(
      BlobStorageException failure) {

    when(blob.getPropertiesWithResponse(
            isNull(), eq(TIMEOUT), eq(Context.NONE)))
        .thenThrow(failure);
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "false"})
  void shouldReadRevisionPropertiesWithoutInterpretingElaborate(
      String elaborate) {

    Map<String, String> metadata =
        Map.of(
            "sessionId", SESSION_ID,
            "serviceIdentifier", "FDR03",
            "elaborate", elaborate,
            "insertedTimestamp", "2026-10-05T15:37:10.498Z");

    givenProperties(metadata);

    RecoveryBlobSnapshot result =
        repository.find(CANDIDATE).orElseThrow();

    assertEquals("fdr3-flows", result.containerName());
    assertEquals(
        "2026-10-05CIPBITMM-00002_CIPBITMM_1.json.zip",
        result.blobName());
    assertEquals(ETAG, result.etag());
    assertEquals(1234L, result.contentLength());
    assertEquals(metadata, result.metadata());

    verify(container).getBlobClient(CANDIDATE.blobName());
    verify(blob).getPropertiesWithResponse(
        isNull(), eq(TIMEOUT), eq(Context.NONE));
    verifyNoMoreInteractions(blob);
  }

  @Test
  void shouldReturnEmptyForMissingBlob() {
    givenStorageFailure(
        storageError(404, "BlobNotFound"));

    assertTrue(repository.find(CANDIDATE).isEmpty());
  }

  @ParameterizedTest
  @CsvSource({
      "404, ContainerNotFound",
      "403, AuthorizationPermissionMismatch",
      "409, ContainerBeingDeleted",
      "500, InternalError"
  })
  void shouldPropagateOtherStorageFailures(
      int status, String code) {

    BlobStorageException failure =
        storageError(status, code);

    givenStorageFailure(failure);

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            () -> repository.find(CANDIDATE));

    assertSame(failure, thrown);
  }

  @Test
  void shouldPropagateTransportFailures() {
    IllegalStateException failure =
        new IllegalStateException("Simulated transport failure");

    when(blob.getPropertiesWithResponse(
            isNull(), eq(TIMEOUT), eq(Context.NONE)))
        .thenThrow(failure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> repository.find(CANDIDATE));

    assertSame(failure, thrown);
  }

  @Test
  void shouldKeepIncompleteMetadataForLaterResolution() {
    givenProperties(Map.of("elaborate", "false"));

    RecoveryBlobSnapshot result =
        repository.find(CANDIDATE).orElseThrow();

    assertEquals(
        Map.of("elaborate", "false"),
        result.metadata());
  }

  @Test
  void shouldCopyMetadataAndPreventLaterChanges() {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", SESSION_ID);

    givenProperties(metadata);

    RecoveryBlobSnapshot result =
        repository.find(CANDIDATE).orElseThrow();

    metadata.put("sessionId", "changed-after-read");

    assertEquals(SESSION_ID, result.metadata().get("sessionId"));

    assertThrows(
        UnsupportedOperationException.class,
        () -> result.metadata().put("sessionId", "another-value"));
  }

  @Test
  void shouldRejectWrongContainer() {
    BlobContainerClient wrongContainer =
        mock(BlobContainerClient.class);

    when(wrongContainer.getBlobContainerName())
        .thenReturn("fdr1-flows");

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryFdr3BlobRepository(wrongContainer));

    verifyNoInteractions(blob);
  }

  @Test
  void shouldRejectNullCandidateBeforeStorageAccess() {
    assertThrows(
        NullPointerException.class,
        () -> repository.find(null));

    verifyNoInteractions(blob);
  }
}