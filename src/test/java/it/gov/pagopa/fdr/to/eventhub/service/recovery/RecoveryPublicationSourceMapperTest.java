package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecoveryPublicationSourceMapperTest {

  private static final String SESSION_ID =
      "6ecc100f-cd56-422d-815b-8243edc81a84";

  private final RecoveryPublicationSourceMapper mapper =
      new RecoveryPublicationSourceMapper(
          new RecoveryFdr1DateNormalizer());

  private final RecoveryFlowCandidate candidate =
      new RecoveryFlowCandidate(
          593L,
          "flow-1",
          2L,
          "psp-1",
          "org-1",
          LocalDateTime.parse("2026-10-08T23:00:06.208"));

  @Test
  void shouldMapVerifiedFdr3Source() {
    var blob = blob("fdr3-flows");

    var identity =
        new RecoveryFdr3IdentityReader.Identity(
            candidate.name(),
            candidate.revision(),
            candidate.pspDomainId(),
            candidate.orgDomainId(),
            Instant.parse("2026-10-08T23:00:06.208Z"));

    var verified =
        new RecoveryFdr3SourceVerifier.Verified(
            candidate,
            blob,
            new RecoveryFdr3BlobMetadataInspector.Valid(
                SESSION_ID, true),
            identity);

    var result = mapper.map(verified);

    assertSame(candidate, result.candidate());
    assertSame(blob, result.blob());
    assertEquals(
        RecoveryPublicationSource.SourceType.FDR3_JSON,
        result.sourceType());
    assertEquals(SESSION_ID, result.sessionId());
    assertEquals(identity.flowDate(), result.eventFlowDate());
  }

  @Test
  void shouldUseInnerReportDateForFdr1Event() {
    var blob = blob("fdr1-flows");

    var envelope =
        new RecoveryFdr1EnvelopeReader.RequestIdentity(
            candidate.name(),
            candidate.pspDomainId(),
            candidate.orgDomainId(),
            "2026-10-09T01:00:06.208475");

    var report =
        new RecoveryFdr1ReportReader.ReportIdentity(
            candidate.name(),
            "2026-10-09T01:00:06.208475643",
            "A",
            "inner-sender",
            "G",
            "inner-receiver",
            1L,
            1L);

    var inspection =
        new RecoveryFdr1SourceInspector.Inspection(
            candidate,
            blob,
            envelope,
            report,
            new RecoveryFdr1IdentityMatcher.Comparison(Set.of()));

    var resolved =
        new RecoveryFdr1SourceResolver.Resolved(
            new RecoveryFdr1SourceVerifier.Inspected(
                inspection,
                new RecoveryFdr1BlobMetadataInspector.Valid(
                    SESSION_ID, false)));

    var result = mapper.map(resolved);

    assertSame(candidate, result.candidate());
    assertSame(blob, result.blob());
    assertEquals(
        RecoveryPublicationSource.SourceType.FDR1_XML,
        result.sourceType());
    assertEquals(SESSION_ID, result.sessionId());

    assertEquals(
        Instant.parse("2026-10-08T23:00:06.208475643Z"),
        result.eventFlowDate());
  }

  @ParameterizedTest
  @CsvSource({
      "FDR1_XML,fdr3-flows",
      "FDR3_JSON,fdr1-flows"
  })
  void shouldRejectContainerInconsistentWithSourceType(
      RecoveryPublicationSource.SourceType sourceType,
      String containerName) {

    var blob = blob(containerName);
    var eventDate = Instant.parse("2026-10-08T23:00:06Z");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryPublicationSource(
                candidate,
                blob,
                sourceType,
                SESSION_ID,
                eventDate));
  }

  @Test
  void shouldRejectShortUuidRepresentation() {
    var blob = blob("fdr1-flows");
    var eventDate = Instant.parse("2026-10-08T23:00:06Z");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryPublicationSource(
                candidate,
                blob,
                RecoveryPublicationSource.SourceType.FDR1_XML,
                "1-1-1-1-1",
                eventDate));
  }

  private static RecoveryBlobSnapshot blob(String containerName) {
    return new RecoveryBlobSnapshot(
        containerName,
        "original-blob",
        "\"original-etag\"",
        100L,
        Map.of("sessionId", SESSION_ID));
  }
}