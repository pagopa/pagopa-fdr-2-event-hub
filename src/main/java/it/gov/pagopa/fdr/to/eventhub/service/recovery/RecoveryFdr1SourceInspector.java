package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryBlobContentRepository;
import java.io.IOException;
import java.util.Objects;

public final class RecoveryFdr1SourceInspector {

  public record Inspection(
      RecoveryFlowCandidate candidate,
      RecoveryBlobSnapshot blob,
      RecoveryFdr1EnvelopeReader.RequestIdentity envelope,
      RecoveryFdr1ReportReader.ReportIdentity report,
      RecoveryFdr1IdentityMatcher.Comparison comparison) {
  }

  private final RecoveryBlobContentRepository contentRepository;
  private final RecoveryBlobDecompressor decompressor;
  private final RecoveryFdr1EnvelopeReader envelopeReader;
  private final RecoveryFdr1PayloadDecoder payloadDecoder;
  private final RecoveryFdr1ReportReader reportReader;
  private final RecoveryFdr1IdentityMatcher identityMatcher;

  public RecoveryFdr1SourceInspector(
      RecoveryBlobContentRepository contentRepository,
      RecoveryBlobDecompressor decompressor,
      RecoveryFdr1EnvelopeReader envelopeReader,
      RecoveryFdr1PayloadDecoder payloadDecoder,
      RecoveryFdr1ReportReader reportReader,
      RecoveryFdr1IdentityMatcher identityMatcher) {

    this.contentRepository =
        Objects.requireNonNull(contentRepository);
    this.decompressor =
        Objects.requireNonNull(decompressor);
    this.envelopeReader =
        Objects.requireNonNull(envelopeReader);
    this.payloadDecoder =
        Objects.requireNonNull(payloadDecoder);
    this.reportReader =
        Objects.requireNonNull(reportReader);
    this.identityMatcher =
        Objects.requireNonNull(identityMatcher);
  }

  public Inspection inspect(
      RecoveryFlowCandidate candidate,
      RecoveryBlobSnapshot blob) throws IOException {

    Objects.requireNonNull(candidate, "candidate is required");
    Objects.requireNonNull(blob, "blob is required");

    if (!"fdr1-flows".equals(blob.containerName())) {
      throw new IllegalArgumentException(
          "The FDR1 source must belong to fdr1-flows");
    }

    byte[] compressed = contentRepository.download(blob);
    byte[] outerXml = decompressor.decompress(compressed);

    var envelope = envelopeReader.read(outerXml);

    byte[] innerXml = payloadDecoder.decode(outerXml);
    var report = reportReader.read(innerXml);

    var comparison = identityMatcher.compare(candidate, envelope);

    return new Inspection(
        candidate, blob, envelope, report, comparison);
  }
}