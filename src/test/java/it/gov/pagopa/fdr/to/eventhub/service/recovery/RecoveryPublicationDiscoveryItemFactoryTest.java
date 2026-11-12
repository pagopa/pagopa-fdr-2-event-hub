package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecoveryPublicationDiscoveryItemFactoryTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryPublicationDiscoveryItemFactory factory =
      new RecoveryPublicationDiscoveryItemFactory();

  private final RecoveryFlowCandidate candidate =
      new RecoveryFlowCandidate(
          155333L,
          "flow-1",
          1L,
          "psp-1",
          "org-1",
          LocalDateTime.parse("2026-10-05T10:31:35"));

  @ParameterizedTest
  @CsvSource({
      "ABSENT,0",
      "PRESENT_UNVERIFIED,4"
  })
  void shouldMapComparedOutcome(
      RecoveryPublicationPresenceIndex.Presence presence,
      long rows) {

    var result = compared(DAY, presence, rows);

    var item = factory.create(JOB_ID, DAY, result);

    var outcome =
        assertInstanceOf(
            RecoveryPublicationDiscoveryItem.Compared.class,
            item.outcome());

    assertEquals(JOB_ID, item.jobId());
    assertEquals(DAY, item.day());
    assertEquals(candidate, item.candidate());

    assertSame(result.comparison().source(), outcome.source());
    assertEquals(presence.name(), outcome.status().name());
    assertEquals(rows, outcome.presentRows());
  }

  @Test
  void shouldPreserveUnresolvedReason() {
    var result =
        unresolved(
            new RecoveryFdr3SourceVerifier.BlobMissing(candidate),
            RecoveryFdr1SourceResolver.UnresolvedReason.NO_CANDIDATES,
            RecoveryPublicationSourceSelector.UnresolvedReason
                .ORIGIN_UNDETERMINED);

    var item = factory.create(JOB_ID, DAY, result);

    var outcome =
        assertInstanceOf(
            RecoveryPublicationDiscoveryItem.Unresolved.class,
            item.outcome());

    assertEquals("ORIGIN_UNDETERMINED", outcome.reason());
    assertEquals(
        RecoveryPublicationDiscoveryItem.Status.UNRESOLVED,
        outcome.status());
  }

  @Test
  void shouldPreserveXmlDiagnosticReason() {
    var result =
        unresolved(
            new RecoveryFdr3SourceVerifier.BlobMissing(candidate),
            RecoveryFdr1SourceResolver.UnresolvedReason.AMBIGUOUS_MATCH,
            RecoveryPublicationSourceSelector.UnresolvedReason
                .XML_UNRESOLVED);

    var item = factory.create(JOB_ID, DAY, result);

    var outcome =
        assertInstanceOf(
            RecoveryPublicationDiscoveryItem.Unresolved.class,
            item.outcome());

    assertEquals("AMBIGUOUS_MATCH", outcome.details().get("xmlReason"));
    assertEquals("0", outcome.details().get("xmlInspectionCount"));
  }

  @Test
  void shouldPreserveJsonMetadataIssue() {
    var metadataIssue =
        new RecoveryFdr3BlobMetadataInspector.Unresolved(
            RecoveryFdr3BlobMetadataInspector.Reason.MISSING_METADATA,
            "sessionId");

    var result =
        unresolved(
            new RecoveryFdr3SourceVerifier.MetadataUnresolved(
                candidate, source().blob(), metadataIssue),
            RecoveryFdr1SourceResolver.UnresolvedReason.NO_CANDIDATES,
            RecoveryPublicationSourceSelector.UnresolvedReason
                .JSON_UNRESOLVED);

    var item = factory.create(JOB_ID, DAY, result);

    var outcome =
        assertInstanceOf(
            RecoveryPublicationDiscoveryItem.Unresolved.class,
            item.outcome());

    assertEquals("MISSING_METADATA", outcome.details().get("jsonReason"));
    assertEquals("sessionId", outcome.details().get("jsonField"));
  }

  @Test
  void shouldRejectComparisonOfAnotherDay() {
    var result =
        compared(
            DAY.plusDays(1),
            RecoveryPublicationPresenceIndex.Presence.ABSENT,
            0L);

    assertThrows(
        IllegalArgumentException.class,
        () -> factory.create(JOB_ID, DAY, result));
  }

  @Test
  void shouldRejectNullJobId() {
    var result =
        compared(DAY, RecoveryPublicationPresenceIndex.Presence.ABSENT, 0L);

    assertThrows(
        NullPointerException.class,
        () -> factory.create(null, DAY, result));
  }

  @Test
  void shouldRejectNullDay() {
    var result =
        compared(DAY, RecoveryPublicationPresenceIndex.Presence.ABSENT, 0L);

    assertThrows(
        NullPointerException.class,
        () -> factory.create(JOB_ID, null, result));
  }

  @Test
  void shouldRejectNullResult() {
    assertThrows(
        NullPointerException.class,
        () -> factory.create(JOB_ID, DAY, null));
  }

  @ParameterizedTest
  @CsvSource({
      "flow-1,6,12,PAYMENT_COUNT_MISMATCH",
      "different-flow,6,6,FLOW_NAME_MISMATCH",
      "different-flow,6,12,'FLOW_NAME_MISMATCH,PAYMENT_COUNT_MISMATCH'"
  })
  void shouldDescribeInconsistentXmlReport(
      String innerName,
      long declaredPayments,
      long observedPayments,
      String expectedIssues) {

    var blob = new RecoveryBlobSnapshot(
        "fdr1-flows",
        "flow-1_original.xml.zip",
        "\"original-etag\"",
        100L,
        Map.of());

    var envelope = new RecoveryFdr1EnvelopeReader.RequestIdentity(
        candidate.name(),
        candidate.pspDomainId(),
        candidate.orgDomainId(),
        "2026-10-05T10:31:35Z");

    var report = new RecoveryFdr1ReportReader.ReportIdentity(
        innerName,
        "2026-10-05T10:31:35Z",
        "A",
        "sender",
        "G",
        "receiver",
        declaredPayments,
        observedPayments);

    var inspection = new RecoveryFdr1SourceInspector.Inspection(
        candidate,
        blob,
        envelope,
        report,
        new RecoveryFdr1IdentityMatcher.Comparison(
            java.util.Set.of()));

    var inspected = new RecoveryFdr1SourceVerifier.Inspected(
        inspection,
        new RecoveryFdr1BlobMetadataInspector.Valid(
            "78cd4ab1-b279-4c7b-9bb8-c9c8bb9629c0",
            true));

    var evidence = new RecoveryPublicationSourceInspector.Inspection(
        candidate,
        new RecoveryFdr3SourceVerifier.BlobMissing(candidate),
        new RecoveryFdr1SourceResolver.Unresolved(
            RecoveryFdr1SourceResolver.UnresolvedReason.INCONSISTENT_REPORT,
            List.of(inspected)));

    var result = new RecoveryPublicationBatchService.Unresolved(
        new RecoveryPublicationSourceSelector.Unresolved(
            RecoveryPublicationSourceSelector.UnresolvedReason.XML_UNRESOLVED,
            evidence));

    var outcome = assertInstanceOf(
        RecoveryPublicationDiscoveryItem.Unresolved.class,
        factory.create(JOB_ID, DAY, result).outcome());

    assertEquals("XML_UNRESOLVED", outcome.reason());
    assertEquals(
        "INCONSISTENT_REPORT",
        outcome.details().get("xmlReason"));
    assertEquals(expectedIssues, outcome.details().get("xmlReportIssues"));
    assertEquals(blob.blobName(), outcome.details().get("xmlBlob"));
    assertEquals(candidate.name(), outcome.details().get("xmlOuterFlowName"));
    assertEquals(innerName, outcome.details().get("xmlInnerFlowName"));
    assertEquals(
        Long.toString(declaredPayments),
        outcome.details().get("xmlDeclaredPayments"));
    assertEquals(
        Long.toString(observedPayments),
        outcome.details().get("xmlObservedPayments"));
  }

  private RecoveryPublicationBatchService.Compared compared(
      LocalDate day,
      RecoveryPublicationPresenceIndex.Presence presence,
      long rows) {

    return new RecoveryPublicationBatchService.Compared(
        new RecoveryPublicationPresenceIndex.Comparison(
            day, source(), presence, rows));
  }

  private RecoveryPublicationBatchService.Unresolved unresolved(
      RecoveryFdr3SourceVerifier.Result json,
      RecoveryFdr1SourceResolver.UnresolvedReason xmlReason,
      RecoveryPublicationSourceSelector.UnresolvedReason reason) {

    var evidence =
        new RecoveryPublicationSourceInspector.Inspection(
            candidate,
            json,
            new RecoveryFdr1SourceResolver.Unresolved(
                xmlReason, List.of()));

    return new RecoveryPublicationBatchService.Unresolved(
        new RecoveryPublicationSourceSelector.Unresolved(
            reason, evidence));
  }

  private RecoveryPublicationSource source() {
    return new RecoveryPublicationSource(
        candidate,
        new RecoveryBlobSnapshot(
            "fdr3-flows",
            candidate.blobName(),
            "\"original-etag\"",
            100L,
            Map.of()),
        RecoveryPublicationSource.SourceType.FDR3_JSON,
        "6fd3e2eb-1d1d-4068-ba74-fa9866141524",
        Instant.parse("2026-10-05T10:31:35Z"));
  }
}