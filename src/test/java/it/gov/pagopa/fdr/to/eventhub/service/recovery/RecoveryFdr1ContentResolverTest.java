package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr1ContentResolverTest {

  private static final String SESSION =
      "1486cdfe-0fe0-44c6-b618-0e67d77eebe2";

  private static final Instant DATE =
      Instant.parse("2026-10-15T12:00:37Z");

  private final RecoveryFlowCandidate candidate =
      new RecoveryFlowCandidate(
          155850L,
          "test-flow",
          2L,
          "ABI88515",
          "00493410583",
          LocalDateTime.parse("2026-10-15T12:00:37"));

  private final RecoveryFdr3PaymentEventReader jsonReader =
      mock(RecoveryFdr3PaymentEventReader.class);

  private final RecoveryFdr1PaymentEventReader xmlReader =
      mock(RecoveryFdr1PaymentEventReader.class);

  private final RecoveryPublicationSourceMapper mapper =
      new RecoveryPublicationSourceMapper(
          new RecoveryFdr1DateNormalizer());

  private final RecoveryFdr1ContentResolver resolver =
      new RecoveryFdr1ContentResolver(
          jsonReader,
          xmlReader,
          mapper,
          new RecoveryPaymentContentMatcher());

  @Test
  void shouldResolveOnlyOriginalXmlWithMatchingPayments()
      throws IOException {

    var json = json(false);
    var different = xml("different.xml.zip", false);
    var matching = xml("matching.xml.zip", true);
    var unresolved = ambiguous(different, matching);

    stubJson(json);
    stubXml(different, "different-iuv");
    stubXml(matching, "expected-iuv");

    var resolved = assertInstanceOf(
        RecoveryFdr1SourceResolver.Resolved.class,
        resolver.resolve(json, unresolved));

    assertSame(matching, resolved.source());

    verify(jsonReader).readAll(mapper.map(json));
    verify(xmlReader).readAll(mapper.map(
        new RecoveryFdr1SourceResolver.Resolved(different)));
    verify(xmlReader).readAll(mapper.map(
        new RecoveryFdr1SourceResolver.Resolved(matching)));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "both contents match,expected-iuv,expected-iuv",
      "neither content matches,different-1,different-2",
      "only non-original XML matches,expected-iuv,different-iuv"
  })
  void shouldPreserveAmbiguityWithoutUniqueOriginalContent(
      String scenario,
      String firstIuv,
      String secondIuv) throws IOException {

    var json = json(false);
    var first = xml("first.xml.zip", false);
    var second = xml("second.xml.zip", true);
    var unresolved = ambiguous(first, second);

    stubJson(json);
    stubXml(first, firstIuv);
    stubXml(second, secondIuv);

    assertSame(
        unresolved,
        resolver.resolve(json, unresolved),
        scenario);
  }

  @Test
  void shouldNotUseNativeJsonToDisambiguateXml()
      throws IOException {

    var json = json(true);
    var unresolved = ambiguous(
        xml("first.xml.zip", false),
        xml("second.xml.zip", true));

    assertSame(unresolved, resolver.resolve(json, unresolved));
    verifyNoInteractions(jsonReader, xmlReader);
  }

  @Test
  void shouldNotHandleOtherUnresolvedReasons() throws IOException {
    var json = json(false);

    var unresolved = new RecoveryFdr1SourceResolver.Unresolved(
        RecoveryFdr1SourceResolver.UnresolvedReason.INCONSISTENT_REPORT,
        List.of());

    assertSame(unresolved, resolver.resolve(json, unresolved));
    verifyNoInteractions(jsonReader, xmlReader);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldPropagateReadFailure(boolean failJson)
      throws IOException {

    var json = json(false);
    var first = xml("first.xml.zip", false);
    var second = xml("second.xml.zip", true);
    var unresolved = ambiguous(first, second);
    var failure = new IOException("Conditional download failed");

    if (failJson) {
      when(jsonReader.readAll(any())).thenThrow(failure);
    } else {
      stubJson(json);
      when(xmlReader.readAll(any())).thenThrow(failure);
    }

    var actual = assertThrows(
        IOException.class,
        () -> resolver.resolve(json, unresolved));

    assertSame(failure, actual);
  }

  private void stubJson(
      RecoveryFdr3SourceVerifier.Verified json) throws IOException {

    when(jsonReader.readAll(mapper.map(json)))
        .thenAnswer(ignored -> Stream.of(payment("expected-iuv")));
  }

  private void stubXml(
      RecoveryFdr1SourceVerifier.Inspected inspected,
      String iuv) throws IOException {

    var source = mapper.map(
        new RecoveryFdr1SourceResolver.Resolved(inspected));

    when(xmlReader.readAll(source))
        .thenAnswer(ignored -> Stream.of(payment(iuv)));
  }

  private RecoveryFdr3SourceVerifier.Verified json(boolean elaborate) {
    return new RecoveryFdr3SourceVerifier.Verified(
        candidate,
        new RecoveryBlobSnapshot(
            "fdr3-flows", "revision-2.json.zip", "etag-json", 100L, Map.of()),
        new RecoveryFdr3BlobMetadataInspector.Valid(SESSION, elaborate),
        new RecoveryFdr3IdentityReader.Identity(
            candidate.name(),
            candidate.revision(),
            candidate.pspDomainId(),
            candidate.orgDomainId(),
            DATE));
  }

  private RecoveryFdr1SourceVerifier.Inspected xml(
      String blobName,
      boolean elaborate) {

    var inspection = new RecoveryFdr1SourceInspector.Inspection(
        candidate,
        new RecoveryBlobSnapshot(
            "fdr1-flows", blobName, "etag-xml", 100L, Map.of()),
        new RecoveryFdr1EnvelopeReader.RequestIdentity(
            candidate.name(),
            candidate.pspDomainId(),
            candidate.orgDomainId(),
            DATE.toString()),
        new RecoveryFdr1ReportReader.ReportIdentity(
            candidate.name(), DATE.toString(),
            "B", "ABI88515", "G", "00493410583", 1L, 1L),
        new RecoveryFdr1IdentityMatcher.Comparison(Set.of()));

    return new RecoveryFdr1SourceVerifier.Inspected(
        inspection,
        new RecoveryFdr1BlobMetadataInspector.Valid(SESSION, elaborate));
  }

  private static RecoveryFdr1SourceResolver.Unresolved ambiguous(
      RecoveryFdr1SourceVerifier.Inspected first,
      RecoveryFdr1SourceVerifier.Inspected second) {

    return new RecoveryFdr1SourceResolver.Unresolved(
        RecoveryFdr1SourceResolver.UnresolvedReason.AMBIGUOUS_MATCH,
        List.of(first, second));
  }

  private static ReportedIUVEventModel payment(String iuv) {
    return ReportedIUVEventModel.builder()
        .iuv(iuv)
        .iur("expected-iur")
        .amount(new BigDecimal("230.73"))
        .outcomeCode(0)
        .idsp("1")
        .singlePaymentOutcomeDate(
            LocalDate.of(2026, 10, 14).atStartOfDay())
        .build();
  }
}