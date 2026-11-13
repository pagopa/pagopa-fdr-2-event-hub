package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFdr1BlobRepository;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryFdr1SourceResolverTest {

  private final RecoveryFdr1BlobRepository blobRepository =
      mock(RecoveryFdr1BlobRepository.class);

  private final RecoveryFdr1SourceVerifier sourceVerifier =
      mock(RecoveryFdr1SourceVerifier.class);

  private final RecoveryFdr1SourceResolver resolver =
      new RecoveryFdr1SourceResolver(blobRepository, sourceVerifier);

  private final RecoveryFlowCandidate candidate =
      new RecoveryFlowCandidate(
          593L,
          "flow-1",
          2L,
          "psp-1",
          "org-1",
          LocalDateTime.parse("2026-10-29T16:39:37"));

  private final RecoveryBlobSnapshot first = blob("first");
  private final RecoveryBlobSnapshot second = blob("second");

  @Test
  void shouldReportNoCandidates() throws IOException {
    when(blobRepository.listCandidates(candidate))
        .thenReturn(List.of());

    var result = resolver.resolve(candidate);

    assertUnresolved(
        result,
        RecoveryFdr1SourceResolver.UnresolvedReason.NO_CANDIDATES);

    verifyNoInteractions(sourceVerifier);
  }

  @Test
  void shouldResolveSingleMatchWithElaborateFalse()
      throws IOException {

    var source = inspected(first, true, "flow-1", 1L);

    when(blobRepository.listCandidates(candidate))
        .thenReturn(List.of(first));

    when(sourceVerifier.verify(candidate, first)).thenReturn(source);

    var result = resolver.resolve(candidate);

    var resolved =
        assertInstanceOf(
            RecoveryFdr1SourceResolver.Resolved.class, result);

    assertSame(source, resolved.source());
  }

  @Test
  void shouldContinueAfterFirstMatch() throws IOException {
    var matching = inspected(first, true, "flow-1", 1L);
    var different = inspected(second, false, "flow-1", 1L);

    when(blobRepository.listCandidates(candidate))
        .thenReturn(List.of(first, second));

    when(sourceVerifier.verify(candidate, first)).thenReturn(matching);
    when(sourceVerifier.verify(candidate, second)).thenReturn(different);

    var result = resolver.resolve(candidate);

    var resolved =
        assertInstanceOf(
            RecoveryFdr1SourceResolver.Resolved.class, result);

    assertSame(matching, resolved.source());
    verify(sourceVerifier).verify(candidate, second);
  }

  @Test
  void shouldReportNoMatch() throws IOException {
    when(blobRepository.listCandidates(candidate))
        .thenReturn(List.of(first));

    when(sourceVerifier.verify(candidate, first))
        .thenReturn(inspected(first, false, "flow-1", 1L));

    var result = resolver.resolve(candidate);

    assertUnresolved(
        result,
        RecoveryFdr1SourceResolver.UnresolvedReason.NO_MATCH);
  }

  @Test
  void shouldRejectMultipleMatches() throws IOException {
    when(blobRepository.listCandidates(candidate))
        .thenReturn(List.of(first, second));

    when(sourceVerifier.verify(candidate, first))
        .thenReturn(inspected(first, true, "flow-1", 1L));

    when(sourceVerifier.verify(candidate, second))
        .thenReturn(inspected(second, true, "flow-1", 1L));

    var result = resolver.resolve(candidate);

    var unresolved =
        assertUnresolved(
            result,
            RecoveryFdr1SourceResolver.UnresolvedReason
                .AMBIGUOUS_MATCH);

    assertEquals(2, unresolved.inspections().size());
  }

  @Test
  void shouldNotResolveWhenAnotherCandidateHasUnknownMetadata()
      throws IOException {

    var issue =
        new RecoveryFdr1BlobMetadataInspector.Unresolved(
            RecoveryFdr1BlobMetadataInspector.UnresolvedReason
                .MISSING_METADATA,
            "sessionId");

    when(blobRepository.listCandidates(candidate))
        .thenReturn(List.of(first, second));

    when(sourceVerifier.verify(candidate, first))
        .thenReturn(inspected(first, true, "flow-1", 1L));

    when(sourceVerifier.verify(candidate, second))
        .thenReturn(
            new RecoveryFdr1SourceVerifier.MetadataUnresolved(
                candidate, second, issue));

    var result = resolver.resolve(candidate);

    assertUnresolved(
        result,
        RecoveryFdr1SourceResolver.UnresolvedReason
            .METADATA_UNRESOLVED);
  }

  @Test
  void shouldRejectDifferentInnerFlowName() throws IOException {
    when(blobRepository.listCandidates(candidate))
        .thenReturn(List.of(first));

    when(sourceVerifier.verify(candidate, first))
        .thenReturn(inspected(first, true, "another-flow", 1L));

    var result = resolver.resolve(candidate);

    assertUnresolved(
        result,
        RecoveryFdr1SourceResolver.UnresolvedReason
            .INCONSISTENT_REPORT);
  }

  @Test
  void shouldRejectInconsistentPaymentCount() throws IOException {
    when(blobRepository.listCandidates(candidate))
        .thenReturn(List.of(first));

    when(sourceVerifier.verify(candidate, first))
        .thenReturn(inspected(first, true, "flow-1", 2L));

    var result = resolver.resolve(candidate);

    assertUnresolved(
        result,
        RecoveryFdr1SourceResolver.UnresolvedReason
            .INCONSISTENT_REPORT);
  }

  @Test
  void shouldPropagateFailureAfterAnEarlierMatch()
      throws IOException {

    var failure = new IOException("Cannot read second candidate");

    when(blobRepository.listCandidates(candidate))
        .thenReturn(List.of(first, second));

    when(sourceVerifier.verify(candidate, first))
        .thenReturn(inspected(first, true, "flow-1", 1L));

    when(sourceVerifier.verify(candidate, second)).thenThrow(failure);

    var actual =
        assertThrows(
            IOException.class,
            () -> resolver.resolve(candidate));

    assertSame(failure, actual);
  }

  @Test
  void shouldNotResolveWhenAnotherCandidateExceedsSizeLimit()
      throws IOException {

    var failure =
        new RecoveryContentSizeException(
            RecoveryContentSizeException.Stage.DECOMPRESSED_BLOB,
            64L,
            65L);

    var exceeded =
        new RecoveryFdr1SourceVerifier.SizeLimitExceeded(
            candidate, second, failure);

    when(blobRepository.listCandidates(candidate))
        .thenReturn(List.of(first, second));

    when(sourceVerifier.verify(candidate, first))
        .thenReturn(inspected(first, true, "flow-1", 1L));

    when(sourceVerifier.verify(candidate, second))
        .thenReturn(exceeded);

    var unresolved =
        assertUnresolved(
            resolver.resolve(candidate),
            RecoveryFdr1SourceResolver.UnresolvedReason
                .SIZE_LIMIT_EXCEEDED);

    assertEquals(2, unresolved.inspections().size());
    assertSame(exceeded, unresolved.inspections().get(1));
  }

  private RecoveryFdr1SourceVerifier.Inspected inspected(
      RecoveryBlobSnapshot snapshot,
      boolean matches,
      String innerFlowName,
      long declaredPayments) {

    var envelope =
        new RecoveryFdr1EnvelopeReader.RequestIdentity(
            "flow-1",
            "psp-1",
            "org-1",
            "2026-10-29T16:39:37Z");

    var report =
        new RecoveryFdr1ReportReader.ReportIdentity(
            innerFlowName,
            "2026-10-29T16:39:37Z",
            "A",
            "inner-sender",
            "G",
            "inner-receiver",
            declaredPayments,
            1L);

    Set<RecoveryFdr1IdentityMatcher.Field> differences =
        matches
            ? Set.of()
            : Set.of(RecoveryFdr1IdentityMatcher.Field.FLOW_DATE);

    var inspection =
        new RecoveryFdr1SourceInspector.Inspection(
            candidate,
            snapshot,
            envelope,
            report,
            new RecoveryFdr1IdentityMatcher.Comparison(differences));

    return new RecoveryFdr1SourceVerifier.Inspected(
        inspection,
        new RecoveryFdr1BlobMetadataInspector.Valid(
            "6ecc100f-cd56-422d-815b-8243edc81a84",
            false));
  }

  private static RecoveryFdr1SourceResolver.Unresolved assertUnresolved(
      RecoveryFdr1SourceResolver.Resolution result,
      RecoveryFdr1SourceResolver.UnresolvedReason expected) {

    var unresolved =
        assertInstanceOf(
            RecoveryFdr1SourceResolver.Unresolved.class, result);

    assertEquals(expected, unresolved.reason());

    return unresolved;
  }

  private static RecoveryBlobSnapshot blob(String suffix) {
    return new RecoveryBlobSnapshot(
        "fdr1-flows",
        "flow-1_" + suffix + ".xml.zip",
        "\"etag-" + suffix + "\"",
        100L,
        Map.of());
  }
}