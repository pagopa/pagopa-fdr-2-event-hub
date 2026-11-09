package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.models.BlobStorageException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFdr3BlobRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr3SourceInspectorTest {

  private static final String SESSION_ID =
      "a92570e7-d5f5-49da-bb8f-0226e2d95ac7";

  private static final RecoveryFlowCandidate CANDIDATE =
      new RecoveryFlowCandidate(
          299L,
          "2026-10-0588888888888-125026001309",
          1L,
          "88888888888",
          "15376371009",
          LocalDateTime.of(
              2026, 10, 5, 12, 50, 26, 1_000_000));

  private final RecoveryFdr3BlobRepository repository =
      mock(RecoveryFdr3BlobRepository.class);

  private final RecoveryFdr3SourceInspector inspector =
      new RecoveryFdr3SourceInspector(
          repository,
          new RecoveryFdr3BlobMetadataInspector());

  private static Map<String, String> validMetadata() {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", SESSION_ID);
    metadata.put("serviceIdentifier", "FDR03");
    metadata.put("elaborate", "true");
    return metadata;
  }

  private RecoveryBlobSnapshot snapshot(
      Map<String, String> metadata) {

    return new RecoveryBlobSnapshot(
        "fdr3-flows",
        CANDIDATE.blobName(),
        "\"source-etag\"",
        4291L,
        metadata);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldInspectBothElaborateValues(boolean elaborate) {
    Map<String, String> metadata = validMetadata();
    metadata.put("elaborate", Boolean.toString(elaborate));

    RecoveryBlobSnapshot blob = snapshot(metadata);

    when(repository.find(CANDIDATE))
        .thenReturn(Optional.of(blob));

    assertEquals(
        new RecoveryFdr3SourceInspector.Inspected(
            CANDIDATE,
            blob,
            new RecoveryFdr3BlobMetadataInspector.Valid(
                SESSION_ID, elaborate)),
        inspector.inspect(CANDIDATE));

    verify(repository).find(CANDIDATE);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldReportMissingBlob() {
    when(repository.find(CANDIDATE))
        .thenReturn(Optional.empty());

    assertEquals(
        new RecoveryFdr3SourceInspector.BlobMissing(CANDIDATE),
        inspector.inspect(CANDIDATE));

    verify(repository).find(CANDIDATE);
    verifyNoMoreInteractions(repository);
  }

  @ParameterizedTest
  @MethodSource("unresolvedMetadata")
  void shouldPreserveMetadataIssuesAndSourceSnapshot(
      Map<String, String> metadata,
      RecoveryFdr3BlobMetadataInspector.Reason reason,
      String field) {

    RecoveryBlobSnapshot blob = snapshot(metadata);

    when(repository.find(CANDIDATE))
        .thenReturn(Optional.of(blob));

    assertEquals(
        new RecoveryFdr3SourceInspector.MetadataUnresolved(
            CANDIDATE,
            blob,
            new RecoveryFdr3BlobMetadataInspector.Unresolved(
                reason, field)),
        inspector.inspect(CANDIDATE));

    verify(repository).find(CANDIDATE);
    verifyNoMoreInteractions(repository);
  }

  private static Stream<Arguments> unresolvedMetadata() {
    Map<String, String> missingSession = validMetadata();
    missingSession.remove("sessionId");

    Map<String, String> invalidSession = validMetadata();
    invalidSession.put("sessionId", "not-a-uuid");

    Map<String, String> invalidElaborate = validMetadata();
    invalidElaborate.put("elaborate", "yes");

    Map<String, String> unsupportedService = validMetadata();
    unsupportedService.put("serviceIdentifier", "FDR001");

    Map<String, String> ambiguousSession = validMetadata();
    ambiguousSession.put("SESSIONID", SESSION_ID);

    return Stream.of(
        Arguments.of(
            missingSession,
            RecoveryFdr3BlobMetadataInspector.Reason.MISSING_METADATA,
            "sessionId"),
        Arguments.of(
            invalidSession,
            RecoveryFdr3BlobMetadataInspector.Reason.INVALID_METADATA,
            "sessionId"),
        Arguments.of(
            invalidElaborate,
            RecoveryFdr3BlobMetadataInspector.Reason.INVALID_METADATA,
            "elaborate"),
        Arguments.of(
            unsupportedService,
            RecoveryFdr3BlobMetadataInspector.Reason.UNSUPPORTED_SERVICE,
            "serviceIdentifier"),
        Arguments.of(
            ambiguousSession,
            RecoveryFdr3BlobMetadataInspector.Reason.AMBIGUOUS_METADATA,
            "sessionId"));
  }

  @Test
  void shouldPropagateStorageFailures() {
    BlobStorageException failure =
        mock(BlobStorageException.class);

    when(repository.find(CANDIDATE)).thenThrow(failure);

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            () -> inspector.inspect(CANDIDATE));

    assertSame(failure, thrown);
  }

  @Test
  void shouldRejectNullCandidateBeforeStorageAccess() {
    assertThrows(
        NullPointerException.class,
        () -> inspector.inspect(null));

    verifyNoInteractions(repository);
  }
}