package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryBlobContentRepository;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecoveryFdr1SourceInspectorTest {

  private final RecoveryBlobContentRepository contentRepository =
      mock(RecoveryBlobContentRepository.class);

  private final RecoveryBlobDecompressor decompressor =
      mock(RecoveryBlobDecompressor.class);

  private final RecoveryFdr1EnvelopeReader envelopeReader =
      mock(RecoveryFdr1EnvelopeReader.class);

  private final RecoveryFdr1PayloadDecoder payloadDecoder =
      mock(RecoveryFdr1PayloadDecoder.class);

  private final RecoveryFdr1ReportReader reportReader =
      mock(RecoveryFdr1ReportReader.class);

  private final RecoveryFdr1IdentityMatcher identityMatcher =
      mock(RecoveryFdr1IdentityMatcher.class);

  private final RecoveryFdr1SourceInspector inspector =
      new RecoveryFdr1SourceInspector(
          contentRepository,
          decompressor,
          envelopeReader,
          payloadDecoder,
          reportReader,
          identityMatcher);

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
          3L,
          Map.of("sessionId", "original-session"));

  private final byte[] compressed = {1, 2, 3};
  private final byte[] outerXml = {4, 5, 6};
  private final byte[] innerXml = {7, 8, 9};

  private final RecoveryFdr1EnvelopeReader.RequestIdentity envelope =
      new RecoveryFdr1EnvelopeReader.RequestIdentity(
          "flow-1",
          "psp-1",
          "org-1",
          "2026-10-29T16:39:37Z");

  private final RecoveryFdr1ReportReader.ReportIdentity report =
      new RecoveryFdr1ReportReader.ReportIdentity(
          "flow-1",
          "2026-10-29T16:39:37Z",
          "A",
          "inner-sender",
          "G",
          "inner-receiver",
          1L,
          1L);

  @BeforeEach
  void setUp() throws IOException {
    when(contentRepository.download(blob)).thenReturn(compressed);
    when(decompressor.decompress(compressed)).thenReturn(outerXml);
    when(envelopeReader.read(outerXml)).thenReturn(envelope);
    when(payloadDecoder.decode(outerXml)).thenReturn(innerXml);
    when(reportReader.read(innerXml)).thenReturn(report);
  }

  @Test
  void shouldComposeInspectionAndPreserveSourceSnapshot()
      throws IOException {

    var comparison =
        new RecoveryFdr1IdentityMatcher.Comparison(Set.of());

    when(identityMatcher.compare(candidate, envelope))
        .thenReturn(comparison);

    var result = inspector.inspect(candidate, blob);

    assertEquals(candidate, result.candidate());
    assertSame(blob, result.blob());
    assertEquals(envelope, result.envelope());
    assertEquals(report, result.report());
    assertEquals(comparison, result.comparison());

    verify(contentRepository).download(blob);
    verify(decompressor).decompress(compressed);
    verify(envelopeReader).read(outerXml);
    verify(payloadDecoder).decode(outerXml);
    verify(reportReader).read(innerXml);
    verify(identityMatcher).compare(candidate, envelope);
  }

  @Test
  void shouldPreserveIdentityDifferences() throws IOException {
    var comparison =
        new RecoveryFdr1IdentityMatcher.Comparison(
            Set.of(RecoveryFdr1IdentityMatcher.Field.FLOW_DATE));

    when(identityMatcher.compare(candidate, envelope))
        .thenReturn(comparison);

    var result = inspector.inspect(candidate, blob);

    assertEquals(comparison, result.comparison());
    assertEquals(envelope, result.envelope());
    assertEquals(report, result.report());
  }

  @Test
  void shouldStopWhenDownloadFails() throws IOException {
    var failure = new IllegalStateException("Download failed");

    when(contentRepository.download(blob)).thenThrow(failure);

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> inspector.inspect(candidate, blob));

    assertSame(failure, actual);

    verifyNoInteractions(
        decompressor,
        envelopeReader,
        payloadDecoder,
        reportReader,
        identityMatcher);
  }

  @Test
  void shouldStopWhenPayloadDecodingFails() throws IOException {
    var failure = new IOException("Invalid Base64 payload");

    when(payloadDecoder.decode(outerXml)).thenThrow(failure);

    var actual =
        assertThrows(
            IOException.class,
            () -> inspector.inspect(candidate, blob));

    assertSame(failure, actual);

    verifyNoInteractions(reportReader, identityMatcher);
  }

  @Test
  void shouldStopWhenInnerReportIsInvalid() throws IOException {
    var failure = new IOException("Invalid inner XML");

    when(reportReader.read(innerXml)).thenThrow(failure);

    var actual =
        assertThrows(
            IOException.class,
            () -> inspector.inspect(candidate, blob));

    assertSame(failure, actual);

    verify(identityMatcher, never()).compare(any(), any());
  }

  @Test
  void shouldRejectUnexpectedContainerBeforeDownloading() {
    var unexpectedBlob =
        new RecoveryBlobSnapshot(
            "fdr3-flows",
            "flow-1.json.zip",
            "\"etag-1\"",
            3L,
            Map.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> inspector.inspect(candidate, unexpectedBlob));

    verifyNoInteractions(contentRepository);
  }
}