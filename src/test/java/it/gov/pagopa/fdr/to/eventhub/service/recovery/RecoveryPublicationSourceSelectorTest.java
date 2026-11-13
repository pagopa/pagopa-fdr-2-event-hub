package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecoveryPublicationSourceSelectorTest {

  private static final String JSON_SESSION =
      "a92570e7-d5f5-49da-bb8f-0226e2d95ac7";

  private static final String XML_SESSION =
      "6ecc100f-cd56-422d-815b-8243edc81a84";

  private final RecoveryPublicationSourceSelector selector =
      new RecoveryPublicationSourceSelector(
          new RecoveryPublicationSourceMapper(
              new RecoveryFdr1DateNormalizer()));

  private final RecoveryFlowCandidate candidate =
      new RecoveryFlowCandidate(
          593L,
          "flow-1",
          2L,
          "psp-1",
          "org-1",
          LocalDateTime.parse("2026-10-29T16:39:37"));

  @ParameterizedTest
  @CsvSource({
      "true,false,FDR3_JSON",
      "false,true,FDR1_XML"
  })
  void shouldSelectAccordingToVerifiedOriginEvidence(
      boolean jsonElaborate,
      boolean xmlElaborate,
      RecoveryPublicationSource.SourceType expectedType) {

    var evidence =
        evidence(json(jsonElaborate), xml(xmlElaborate));

    var result = selector.select(evidence);

    var selected =
        assertInstanceOf(
            RecoveryPublicationSourceSelector.Selected.class,
            result);

    assertEquals(expectedType, selected.source().sourceType());

    String expectedSession =
        expectedType == RecoveryPublicationSource.SourceType.FDR3_JSON
            ? JSON_SESSION : XML_SESSION;

    assertEquals(expectedSession, selected.source().sessionId());
    assertSame(evidence, selected.evidence());
  }

  @Test
  void shouldRejectConflictingOrigins() {
    var evidence = evidence(json(true), xml(true));

    assertUnresolved(
        selector.select(evidence),
        RecoveryPublicationSourceSelector.UnresolvedReason
            .CONFLICTING_ORIGINS);
  }

  @Test
  void shouldKeepBothFalseUnresolved() {
    var evidence = evidence(json(false), xml(false));

    assertUnresolved(
        selector.select(evidence),
        RecoveryPublicationSourceSelector.UnresolvedReason
            .ORIGIN_UNDETERMINED);
  }

  @Test
  void shouldSelectXmlWhenJsonIsMissing() {
    var evidence =
        evidence(
            new RecoveryFdr3SourceVerifier.BlobMissing(candidate),
            xml(true));

    var selected =
        assertInstanceOf(
            RecoveryPublicationSourceSelector.Selected.class,
            selector.select(evidence));

    assertEquals(
        RecoveryPublicationSource.SourceType.FDR1_XML,
        selected.source().sourceType());

    assertEquals(XML_SESSION, selected.source().sessionId());
  }

  @Test
  void shouldSelectJsonWhenXmlHasNoCandidates() {
    var evidence =
        evidence(
            json(true),
            unresolvedXml(
                RecoveryFdr1SourceResolver.UnresolvedReason
                    .NO_CANDIDATES));

    var selected =
        assertInstanceOf(
            RecoveryPublicationSourceSelector.Selected.class,
            selector.select(evidence));

    assertEquals(
        RecoveryPublicationSource.SourceType.FDR3_JSON,
        selected.source().sourceType());
  }

  @Test
  void shouldBlockAmbiguousXmlEvenWhenJsonIsEligible() {
    var evidence =
        evidence(
            json(true),
            unresolvedXml(
                RecoveryFdr1SourceResolver.UnresolvedReason
                    .AMBIGUOUS_MATCH));

    assertUnresolved(
        selector.select(evidence),
        RecoveryPublicationSourceSelector.UnresolvedReason
            .XML_UNRESOLVED);
  }

  @Test
  void shouldKeepMissingSourcesUnresolved() {
    var evidence =
        evidence(
            new RecoveryFdr3SourceVerifier.BlobMissing(candidate),
            unresolvedXml(
                RecoveryFdr1SourceResolver.UnresolvedReason
                    .NO_CANDIDATES));

    assertUnresolved(
        selector.select(evidence),
        RecoveryPublicationSourceSelector.UnresolvedReason
            .ORIGIN_UNDETERMINED);
  }

  @Test
  void shouldBlockInvalidJsonMetadataEvenWhenXmlIsEligible() {
    var issue =
            new RecoveryFdr3BlobMetadataInspector.Unresolved(RecoveryFdr3BlobMetadataInspector.Reason.MISSING_METADATA,
                    "sessionId");

    var evidence =
        evidence(
            new RecoveryFdr3SourceVerifier.MetadataUnresolved(
                candidate, blob("fdr3-flows"), issue),
            xml(true));

    assertUnresolved(
        selector.select(evidence),
        RecoveryPublicationSourceSelector.UnresolvedReason
            .JSON_UNRESOLVED);
  }

  @Test
  void shouldRejectNull() {
    assertThrows(
        NullPointerException.class,
        () -> selector.select(null));
  }

  private RecoveryPublicationSourceInspector.Inspection evidence(
      RecoveryFdr3SourceVerifier.Result json,
      RecoveryFdr1SourceResolver.Resolution xml) {

    return new RecoveryPublicationSourceInspector.Inspection(
        candidate, json, xml);
  }

  private RecoveryFdr3SourceVerifier.Verified json(
      boolean elaborate) {

    return new RecoveryFdr3SourceVerifier.Verified(
        candidate,
        blob("fdr3-flows"),
        new RecoveryFdr3BlobMetadataInspector.Valid(
            JSON_SESSION, elaborate),
        new RecoveryFdr3IdentityReader.Identity(
            candidate.name(),
            candidate.revision(),
            candidate.pspDomainId(),
            candidate.orgDomainId(),
            Instant.parse("2026-10-29T16:39:37Z")));
  }

  private RecoveryFdr1SourceResolver.Resolved xml(
      boolean elaborate) {

    var inspection =
        new RecoveryFdr1SourceInspector.Inspection(
            candidate,
            blob("fdr1-flows"),
            new RecoveryFdr1EnvelopeReader.RequestIdentity(
                candidate.name(),
                candidate.pspDomainId(),
                candidate.orgDomainId(),
                "2026-10-29T16:39:37Z"),
            new RecoveryFdr1ReportReader.ReportIdentity(
                candidate.name(),
                "2026-10-29T16:39:37Z",
                "A",
                "inner-sender",
                "G",
                "inner-receiver",
                1L,
                1L),
            new RecoveryFdr1IdentityMatcher.Comparison(Set.of()));

    return new RecoveryFdr1SourceResolver.Resolved(
        new RecoveryFdr1SourceVerifier.Inspected(
            inspection,
            new RecoveryFdr1BlobMetadataInspector.Valid(
                XML_SESSION, elaborate)));
  }

  private static RecoveryFdr1SourceResolver.Unresolved unresolvedXml(
      RecoveryFdr1SourceResolver.UnresolvedReason reason) {

    return new RecoveryFdr1SourceResolver.Unresolved(
        reason, List.of());
  }

  private static RecoveryBlobSnapshot blob(String container) {
    return new RecoveryBlobSnapshot(
        container,
        "original-blob",
        "\"etag-1\"",
        100L,
        Map.of());
  }

  private static void assertUnresolved(
      RecoveryPublicationSourceSelector.Selection result,
      RecoveryPublicationSourceSelector.UnresolvedReason reason) {

    var unresolved =
        assertInstanceOf(
            RecoveryPublicationSourceSelector.Unresolved.class,
            result);

    assertEquals(reason, unresolved.reason());
  }
}