package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecoveryPublicationSourceInspectorTest {

  private final RecoveryFdr3SourceVerifier fdr3Verifier =
      mock(RecoveryFdr3SourceVerifier.class);

  private final RecoveryFdr1SourceResolver fdr1Resolver =
      mock(RecoveryFdr1SourceResolver.class);

  private final RecoveryPublicationSourceInspector inspector =
      new RecoveryPublicationSourceInspector(
          fdr3Verifier, fdr1Resolver);

  private final RecoveryFlowCandidate candidate =
      new RecoveryFlowCandidate(
          593L,
          "flow-1",
          2L,
          "psp-1",
          "org-1",
          LocalDateTime.parse("2026-10-29T16:39:37"));

  @Test
  void shouldInspectXmlEvenWhenJsonIsVerified() throws IOException {
    var fdr3 = verifiedJson();

    var fdr1 =
        new RecoveryFdr1SourceResolver.Unresolved(
            RecoveryFdr1SourceResolver.UnresolvedReason.NO_MATCH,
            List.of());

    when(fdr3Verifier.verify(candidate)).thenReturn(fdr3);
    when(fdr1Resolver.resolve(candidate)).thenReturn(fdr1);

    var result = inspector.inspect(candidate);

    assertSame(candidate, result.candidate());
    assertSame(fdr3, result.fdr3());
    assertSame(fdr1, result.fdr1());

    verify(fdr1Resolver).resolve(candidate);
  }

  @Test
  void shouldInspectXmlWhenJsonIsMissing() throws IOException {
    var fdr3 =
        new RecoveryFdr3SourceVerifier.BlobMissing(candidate);

    var fdr1 =
        new RecoveryFdr1SourceResolver.Unresolved(
            RecoveryFdr1SourceResolver.UnresolvedReason.NO_CANDIDATES,
            List.of());

    when(fdr3Verifier.verify(candidate)).thenReturn(fdr3);
    when(fdr1Resolver.resolve(candidate)).thenReturn(fdr1);

    var result = inspector.inspect(candidate);

    assertSame(fdr3, result.fdr3());
    assertSame(fdr1, result.fdr1());

    verify(fdr1Resolver).resolve(candidate);
  }

  @Test
  void shouldPreserveAmbiguousXmlResult() throws IOException {
    var fdr3 = verifiedJson();

    var fdr1 =
        new RecoveryFdr1SourceResolver.Unresolved(
            RecoveryFdr1SourceResolver.UnresolvedReason.AMBIGUOUS_MATCH,
            List.of());

    when(fdr3Verifier.verify(candidate)).thenReturn(fdr3);
    when(fdr1Resolver.resolve(candidate)).thenReturn(fdr1);

    var result = inspector.inspect(candidate);

    assertSame(fdr3, result.fdr3());
    assertSame(fdr1, result.fdr1());
  }

  @Test
  void shouldPropagateJsonReadFailure() throws IOException {
    var failure = new IOException("Cannot inspect JSON");

    when(fdr3Verifier.verify(candidate)).thenThrow(failure);

    var actual =
        assertThrows(
            IOException.class,
            () -> inspector.inspect(candidate));

    assertSame(failure, actual);
    verifyNoInteractions(fdr1Resolver);
  }

  @Test
  void shouldPropagateXmlReadFailureAfterJsonVerification()
      throws IOException {

    var failure = new IOException("Cannot inspect XML");

    when(fdr3Verifier.verify(candidate)).thenReturn(verifiedJson());
    when(fdr1Resolver.resolve(candidate)).thenThrow(failure);

    var actual =
        assertThrows(
            IOException.class,
            () -> inspector.inspect(candidate));

    assertSame(failure, actual);
  }

  @Test
  void shouldRejectNullBeforeAccessingSources() {
    assertThrows(
        NullPointerException.class,
        () -> inspector.inspect(null));

    verifyNoInteractions(fdr3Verifier, fdr1Resolver);
  }

  private RecoveryFdr3SourceVerifier.Verified verifiedJson() {
    var blob =
        new RecoveryBlobSnapshot(
            "fdr3-flows",
            candidate.blobName(),
            "\"etag-json\"",
            100L,
            Map.of());

    var metadata =
        new RecoveryFdr3BlobMetadataInspector.Valid(
            "a92570e7-d5f5-49da-bb8f-0226e2d95ac7",
            true);

    var identity =
        new RecoveryFdr3IdentityReader.Identity(
            candidate.name(),
            candidate.revision(),
            candidate.pspDomainId(),
            candidate.orgDomainId(),
            Instant.parse("2026-10-29T16:39:37Z"));

    return new RecoveryFdr3SourceVerifier.Verified(
        candidate, blob, metadata, identity);
  }
}