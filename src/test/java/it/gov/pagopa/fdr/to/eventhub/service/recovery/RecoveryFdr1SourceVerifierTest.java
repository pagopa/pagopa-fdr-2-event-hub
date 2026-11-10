package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr1SourceVerifierTest {

  private static final String SESSION_ID =
      "6ecc100f-cd56-422d-815b-8243edc81a84";

  private final RecoveryFdr1BlobMetadataInspector metadataInspector =
      mock(RecoveryFdr1BlobMetadataInspector.class);

  private final RecoveryFdr1SourceInspector sourceInspector =
      mock(RecoveryFdr1SourceInspector.class);

  private final RecoveryFdr1SourceVerifier verifier =
      new RecoveryFdr1SourceVerifier(
          metadataInspector, sourceInspector);

  private final RecoveryFlowCandidate candidate =
      new RecoveryFlowCandidate(
          593L,
          "flow-1",
          2L,
          "psp-1",
          "org-1",
          LocalDateTime.parse("2026-10-29T16:39:37"));

  private final RecoveryBlobSnapshot blob =
      new RecoveryBlobSnapshot(
          "fdr1-flows",
          "flow-1_original.xml.zip",
          "\"etag-1\"",
          100L,
          Map.of());

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldInspectWithBothElaborateValues(boolean elaborate)
      throws IOException {

    var metadata =
        new RecoveryFdr1BlobMetadataInspector.Valid(
            SESSION_ID, elaborate);

    var source = inspection(Set.of());

    when(metadataInspector.inspect(blob)).thenReturn(metadata);
    when(sourceInspector.inspect(candidate, blob)).thenReturn(source);

    var result = verifier.verify(candidate, blob);

    var inspected =
        assertInstanceOf(
            RecoveryFdr1SourceVerifier.Inspected.class,
            result);

    assertSame(source, inspected.source());
    assertEquals(metadata, inspected.metadata());
  }

  @Test
  void shouldStopBeforeReadingContentWhenMetadataIsUnresolved()
      throws IOException {

    var issue =
        new RecoveryFdr1BlobMetadataInspector.Unresolved(
            RecoveryFdr1BlobMetadataInspector.UnresolvedReason
                .MISSING_METADATA,
            "sessionId");

    when(metadataInspector.inspect(blob)).thenReturn(issue);

    var result = verifier.verify(candidate, blob);

    assertEquals(
        new RecoveryFdr1SourceVerifier.MetadataUnresolved(
            candidate, blob, issue),
        result);

    verifyNoInteractions(sourceInspector);
  }

  @Test
  void shouldPreserveIdentityDifferences() throws IOException {
    var metadata =
        new RecoveryFdr1BlobMetadataInspector.Valid(
            SESSION_ID, false);

    var differences =
        Set.of(RecoveryFdr1IdentityMatcher.Field.FLOW_DATE);

    var source = inspection(differences);

    when(metadataInspector.inspect(blob)).thenReturn(metadata);
    when(sourceInspector.inspect(candidate, blob)).thenReturn(source);

    var result = verifier.verify(candidate, blob);

    var inspected =
        assertInstanceOf(
            RecoveryFdr1SourceVerifier.Inspected.class,
            result);

    assertEquals(
        differences,
        inspected.source().comparison().differences());

    assertEquals(SESSION_ID, inspected.metadata().sessionId());
  }

  @Test
  void shouldPropagateContentFailure() throws IOException {
    var metadata =
        new RecoveryFdr1BlobMetadataInspector.Valid(
            SESSION_ID, false);

    var failure = new IOException("Invalid XML");

    when(metadataInspector.inspect(blob)).thenReturn(metadata);
    when(sourceInspector.inspect(candidate, blob)).thenThrow(failure);

    var actual =
        assertThrows(
            IOException.class,
            () -> verifier.verify(candidate, blob));

    assertSame(failure, actual);
  }

  @Test
  void shouldRejectUnexpectedContainerBeforeInspection() {
    var unexpectedBlob =
        new RecoveryBlobSnapshot(
            "fdr3-flows",
            "flow-1.json.zip",
            "\"etag-1\"",
            100L,
            Map.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> verifier.verify(candidate, unexpectedBlob));

    verifyNoInteractions(metadataInspector, sourceInspector);
  }

  private RecoveryFdr1SourceInspector.Inspection inspection(
      Set<RecoveryFdr1IdentityMatcher.Field> differences) {

    var envelope =
        new RecoveryFdr1EnvelopeReader.RequestIdentity(
            "flow-1",
            "psp-1",
            "org-1",
            "2026-10-29T16:39:37Z");

    var report =
        new RecoveryFdr1ReportReader.ReportIdentity(
            "flow-1",
            "2026-10-29T16:39:37Z",
            "A",
            "inner-sender",
            "G",
            "inner-receiver",
            1L,
            1L);

    return new RecoveryFdr1SourceInspector.Inspection(
        candidate,
        blob,
        envelope,
        report,
        new RecoveryFdr1IdentityMatcher.Comparison(differences));
  }
}