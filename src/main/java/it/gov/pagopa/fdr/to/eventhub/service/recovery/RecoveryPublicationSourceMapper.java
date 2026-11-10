package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.util.Objects;

public final class RecoveryPublicationSourceMapper {

  private final RecoveryFdr1DateNormalizer dateNormalizer;

  public RecoveryPublicationSourceMapper(
      RecoveryFdr1DateNormalizer dateNormalizer) {

    this.dateNormalizer =
        Objects.requireNonNull(
            dateNormalizer, "dateNormalizer is required");
  }

  public RecoveryPublicationSource map(
      RecoveryFdr3SourceVerifier.Verified verified) {

    Objects.requireNonNull(verified, "verified is required");

    return new RecoveryPublicationSource(
        verified.candidate(),
        verified.blob(),
        RecoveryPublicationSource.SourceType.FDR3_JSON,
        verified.metadata().sessionId(),
        verified.identity().flowDate());
  }

  public RecoveryPublicationSource map(
      RecoveryFdr1SourceResolver.Resolved resolved) {

    Objects.requireNonNull(resolved, "resolved is required");

    var inspected = resolved.source();
    var source = inspected.source();

    return new RecoveryPublicationSource(
        source.candidate(),
        source.blob(),
        RecoveryPublicationSource.SourceType.FDR1_XML,
        inspected.metadata().sessionId(),
        dateNormalizer.normalize(source.report().flowDate()));
  }
}