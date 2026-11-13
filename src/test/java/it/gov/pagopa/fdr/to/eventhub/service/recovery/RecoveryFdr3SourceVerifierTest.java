package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.models.BlobStorageException;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFdr3BlobContentRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFdr3BlobRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr3SourceVerifierTest {

  private static final String SESSION_ID =
      "a92570e7-d5f5-49da-bb8f-0226e2d95ac7";

  private static final RecoveryFlowCandidate CANDIDATE =
      new RecoveryFlowCandidate(
          299L,
          "flow-1",
          1L,
          "PSP-A",
          "ORG-A",
          LocalDateTime.parse("2026-10-05T12:50:26.001454"));

  private static final String JSON = """
      {
        "fdr": "flow-1",
        "revision": 1,
        "fdrDate": "2026-10-05T12:50:26.001454Z",
        "sender": {"pspId": "PSP-A"},
        "receiver": {"organizationId": "ORG-A"},
        "payments": []
      }
      """;

  private final RecoveryFdr3BlobRepository propertiesRepository =
      mock(RecoveryFdr3BlobRepository.class);

  private final RecoveryFdr3BlobContentRepository contentRepository =
      mock(RecoveryFdr3BlobContentRepository.class);

  private final RecoveryFdr3SourceVerifier verifier =
      new RecoveryFdr3SourceVerifier(
          new RecoveryFdr3SourceInspector(
              propertiesRepository,
              new RecoveryFdr3BlobMetadataInspector()),
          contentRepository,
          new RecoveryBlobDecompressor(1024),
          new RecoveryFdr3IdentityReader(),
          new RecoveryFdr3IdentityMatcher());

  private Map<String, String> metadata(boolean elaborate) {
    Map<String, String> values = new HashMap<>();
    values.put("sessionId", SESSION_ID);
    values.put("serviceIdentifier", "FDR03");
    values.put("elaborate", Boolean.toString(elaborate));
    return values;
  }

  private RecoveryBlobSnapshot givenBlob(
      byte[] compressed,
      Map<String, String> metadata) throws RecoveryContentSizeException {

    RecoveryBlobSnapshot snapshot =
        new RecoveryBlobSnapshot(
            "fdr3-flows",
            CANDIDATE.blobName(),
            "\"source-etag\"",
            compressed.length,
            metadata);

    when(propertiesRepository.find(CANDIDATE))
        .thenReturn(Optional.of(snapshot));

    when(contentRepository.download(snapshot))
        .thenReturn(compressed);

    return snapshot;
  }

  private byte[] gzip(String json) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(json.getBytes(StandardCharsets.UTF_8));
    }

    return output.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldVerifyMatchingJsonAndPreserveElaborate(
      boolean elaborate) throws IOException {

    RecoveryBlobSnapshot snapshot =
        givenBlob(gzip(JSON), metadata(elaborate));

    RecoveryFdr3SourceVerifier.Verified result =
        assertInstanceOf(
            RecoveryFdr3SourceVerifier.Verified.class,
            verifier.verify(CANDIDATE));

    assertEquals(CANDIDATE, result.candidate());
    assertEquals(snapshot, result.blob());
    assertEquals(SESSION_ID, result.metadata().sessionId());
    assertEquals(elaborate, result.metadata().elaborate());
    assertEquals("flow-1", result.identity().name());

    verify(propertiesRepository).find(CANDIDATE);
    verify(contentRepository).download(snapshot);
    verifyNoMoreInteractions(
        propertiesRepository, contentRepository);
  }

  @Test
  void shouldStopWhenBlobIsMissing() throws IOException {
    when(propertiesRepository.find(CANDIDATE))
        .thenReturn(Optional.empty());

    assertEquals(
        new RecoveryFdr3SourceVerifier.BlobMissing(CANDIDATE),
        verifier.verify(CANDIDATE));

    verifyNoInteractions(contentRepository);
  }

  @Test
  void shouldStopWhenMetadataCannotBeResolved()
      throws IOException {

    Map<String, String> incomplete = metadata(true);
    incomplete.remove("sessionId");

    RecoveryBlobSnapshot snapshot =
        givenBlob(gzip(JSON), incomplete);

    assertEquals(
        new RecoveryFdr3SourceVerifier.MetadataUnresolved(
            CANDIDATE,
            snapshot,
            new RecoveryFdr3BlobMetadataInspector.Unresolved(
                RecoveryFdr3BlobMetadataInspector.Reason.MISSING_METADATA,
                "sessionId")),
        verifier.verify(CANDIDATE));

    verifyNoInteractions(contentRepository);
  }

  @Test
  void shouldReportIdentityMismatch() throws IOException {
    String different =
        JSON.replace(
            "\"fdr\": \"flow-1\"",
            "\"fdr\": \"other-flow\"");

    givenBlob(gzip(different), metadata(true));

    RecoveryFdr3SourceVerifier.IdentityMismatch result =
        assertInstanceOf(
            RecoveryFdr3SourceVerifier.IdentityMismatch.class,
            verifier.verify(CANDIDATE));

    assertEquals(
        Set.of(RecoveryFdr3IdentityMatcher.Field.FLOW_NAME),
        result.comparison().differences());

    assertEquals("other-flow", result.identity().name());
  }

  @Test
  void shouldPropagateDownloadFailure() throws IOException {
    RecoveryBlobSnapshot snapshot =
        givenBlob(gzip(JSON), metadata(true));

    BlobStorageException failure =
        mock(BlobStorageException.class);

    when(contentRepository.download(snapshot))
        .thenThrow(failure);

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            () -> verifier.verify(CANDIDATE));

    assertSame(failure, thrown);
  }

  @Test
  void shouldPropagateDecompressionFailure() throws RecoveryContentSizeException {
    byte[] invalid = {1, 2, 3};
    givenBlob(invalid, metadata(true));

    assertThrows(
        IOException.class,
        () -> verifier.verify(CANDIDATE));
  }

  @Test
  void shouldPropagateJsonParsingFailure() throws IOException {
    givenBlob(gzip("{invalid-json"), metadata(true));

    assertThrows(
        IOException.class,
        () -> verifier.verify(CANDIDATE));
  }

  @Test
  void shouldRejectNullCandidateBeforeStorageAccess() {
    assertThrows(
        NullPointerException.class,
        () -> verifier.verify(null));

    verifyNoInteractions(
        propertiesRepository, contentRepository);
  }

  @Test
  void shouldReturnDiagnosticWhenJsonExceedsDecompressedLimit()
      throws IOException {

    var snapshot =
        givenBlob(gzip(" ".repeat(1025)), metadata(true));

    var result =
        assertInstanceOf(
            RecoveryFdr3SourceVerifier.SizeLimitExceeded.class,
            verifier.verify(CANDIDATE));

    assertEquals(CANDIDATE, result.candidate());
    assertEquals(snapshot, result.blob());

    assertEquals(
        RecoveryContentSizeException.Stage.DECOMPRESSED_BLOB,
        result.failure().stage());

    assertEquals(1024, result.failure().limitBytes());
    assertEquals(1025, result.failure().observedBytesAtLeast());
  }

  @Test
  void shouldReturnDiagnosticWhenJsonBlobExceedsCompressedLimit()
      throws IOException {

    var snapshot =
        givenBlob(gzip(JSON), metadata(true));

    var failure =
        new RecoveryContentSizeException(
            RecoveryContentSizeException.Stage.COMPRESSED_BLOB,
            1L,
            snapshot.contentLength());

    when(contentRepository.download(snapshot))
        .thenThrow(failure);

    var result =
        assertInstanceOf(
            RecoveryFdr3SourceVerifier.SizeLimitExceeded.class,
            verifier.verify(CANDIDATE));

    assertEquals(CANDIDATE, result.candidate());
    assertEquals(snapshot, result.blob());
    assertSame(failure, result.failure());
  }
}