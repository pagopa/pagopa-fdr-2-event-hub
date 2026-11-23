package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.microsoft.azure.kusto.data.exceptions.KustoServiceQueryError;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryPublicationBatchServiceTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryPublicationSourceInspector sourceInspector =
      mock(RecoveryPublicationSourceInspector.class);

  private final RecoveryPublicationSourceSelector sourceSelector =
      mock(RecoveryPublicationSourceSelector.class);

  private final RecoveryPublicationPresenceService presenceService =
      mock(RecoveryPublicationPresenceService.class);

  private final RecoveryPublicationBatchService service =
      new RecoveryPublicationBatchService(
          sourceInspector, sourceSelector, presenceService);

  private final RecoveryFlowCandidate first = candidate(1L);
  private final RecoveryFlowCandidate second = candidate(2L);
  private final RecoveryFlowCandidate third = candidate(3L);

  @Test
  void shouldPreserveOrderAndCompareOnlySelectedSources()
      throws Exception {

    var firstSource = stubSelected(first);
    var issue = stubUnresolved(second);
    var thirdSource = stubSelected(third);

    var sources = List.of(firstSource, thirdSource);

    when(presenceService.compare(DAY, sources))
        .thenReturn(
            List.of(
                comparison(
                    firstSource,
                    RecoveryPublicationPresenceIndex.Presence
                        .PRESENT_UNVERIFIED,
                    4L),
                comparison(
                    thirdSource,
                    RecoveryPublicationPresenceIndex.Presence.ABSENT,
                    0L)));

    var result = service.compare(DAY, List.of(first, second, third));

    assertEquals(3, result.size());
    assertEquals(first, result.get(0).candidate());
    assertEquals(second, result.get(1).candidate());
    assertEquals(third, result.get(2).candidate());

    var present =
        assertInstanceOf(
            RecoveryPublicationBatchService.Compared.class,
            result.get(0));

    assertEquals(
        RecoveryPublicationPresenceIndex.Presence.PRESENT_UNVERIFIED,
        present.comparison().presence());

    var unresolved =
        assertInstanceOf(
            RecoveryPublicationBatchService.Unresolved.class,
            result.get(1));

    assertSame(issue, unresolved.issue());

    var absent =
        assertInstanceOf(
            RecoveryPublicationBatchService.Compared.class,
            result.get(2));

    assertEquals(
        RecoveryPublicationPresenceIndex.Presence.ABSENT,
        absent.comparison().presence());

    verify(presenceService).compare(DAY, sources);
    verifyNoMoreInteractions(presenceService);
  }

  @Test
  void shouldSkipPresenceQueryWhenAllSourcesAreUnresolved()
      throws Exception {

    stubUnresolved(first);

    var result = service.compare(DAY, List.of(first));

    assertInstanceOf(
        RecoveryPublicationBatchService.Unresolved.class,
        result.get(0));

    verifyNoInteractions(presenceService);
  }

  @Test
  void shouldSkipAllDependenciesForEmptyBatch() throws Exception {
    assertTrue(service.compare(DAY, List.of()).isEmpty());

    verifyNoInteractions(
        sourceInspector, sourceSelector, presenceService);
  }

  @Test
  void shouldRejectOversizedBatchBeforeInspection() {
    var candidates = Collections.nCopies(101, first);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.compare(DAY, candidates));

    verifyNoInteractions(
        sourceInspector, sourceSelector, presenceService);
  }

  @Test
  void shouldRejectNullCandidateBeforeInspection() {
    var candidates = Arrays.asList(first, null);

    assertThrows(
        NullPointerException.class,
        () -> service.compare(DAY, candidates));

    verifyNoInteractions(
        sourceInspector, sourceSelector, presenceService);
  }

  @Test
  void shouldNotQueryAdxWhenLaterSourceInspectionFails()
      throws Exception {

    stubSelected(first);

    var failure = new IOException("Cannot inspect second source");

    when(sourceInspector.inspect(second)).thenThrow(failure);

    var candidates = List.of(first, second);

    var actual =
        assertThrows(
            IOException.class,
            () -> service.compare(DAY, candidates));

    assertSame(failure, actual);
    verifyNoInteractions(presenceService);
  }

  @Test
  void shouldPropagateAdxFailure() throws Exception {
    var source = stubSelected(first);
    var sources = List.of(source);

    var failure =
        new KustoServiceQueryError("Simulated ADX failure");

    when(presenceService.compare(DAY, sources)).thenThrow(failure);

    var candidates = List.of(first);

    var actual =
        assertThrows(
            KustoServiceQueryError.class,
            () -> service.compare(DAY, candidates));

    assertSame(failure, actual);
  }

  @Test
  void shouldRejectIncompleteComparisonList() throws Exception {
    var source = stubSelected(first);

    when(presenceService.compare(DAY, List.of(source)))
        .thenReturn(List.of());

    var candidates = List.of(first);

    assertThrows(
        IllegalStateException.class,
        () -> service.compare(DAY, candidates));
  }

  private RecoveryPublicationSource stubSelected(
      RecoveryFlowCandidate candidate) throws IOException {

    var evidence = evidence(candidate);

    var source =
        new RecoveryPublicationSource(
            candidate,
            new RecoveryBlobSnapshot(
                "fdr3-flows",
                candidate.blobName(),
                "\"fixture-etag\"",
                100L,
                Map.of()),
            RecoveryPublicationSource.SourceType.FDR3_JSON,
            new UUID(0L, candidate.flowId()).toString(),
            Instant.parse("2026-10-05T10:31:35Z"));

    when(sourceInspector.inspect(candidate)).thenReturn(evidence);

    when(sourceSelector.select(evidence))
        .thenReturn(
            new RecoveryPublicationSourceSelector.Selected(
                source, evidence));

    return source;
  }

  private RecoveryPublicationSourceSelector.Unresolved stubUnresolved(
      RecoveryFlowCandidate candidate) throws IOException {

    var evidence = evidence(candidate);

    var issue =
        new RecoveryPublicationSourceSelector.Unresolved(
            RecoveryPublicationSourceSelector.UnresolvedReason
                .ORIGIN_UNDETERMINED,
            evidence);

    when(sourceInspector.inspect(candidate)).thenReturn(evidence);
    when(sourceSelector.select(evidence)).thenReturn(issue);

    return issue;
  }

  private static RecoveryPublicationSourceInspector.Inspection evidence(
      RecoveryFlowCandidate candidate) {

    return new RecoveryPublicationSourceInspector.Inspection(
        candidate,
        new RecoveryFdr3SourceVerifier.BlobMissing(candidate),
        new RecoveryFdr1SourceResolver.Unresolved(
            RecoveryFdr1SourceResolver.UnresolvedReason.NO_CANDIDATES,
            List.of()));
  }

  private static RecoveryPublicationPresenceIndex.Comparison comparison(
      RecoveryPublicationSource source,
      RecoveryPublicationPresenceIndex.Presence presence,
      long rows) {

    return new RecoveryPublicationPresenceIndex.Comparison(
        DAY, source, presence, rows);
  }

  private static RecoveryFlowCandidate candidate(long id) {
    return new RecoveryFlowCandidate(
        id,
        "flow-1",
        id,
        "psp-1",
        "org-1",
        LocalDateTime.parse("2026-10-05T10:31:35"));
  }
}