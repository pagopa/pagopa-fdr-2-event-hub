package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFdr3BlobContentRepository;

import java.io.IOException;
import java.util.Objects;

public final class RecoveryFdr3SourceVerifier {

  private final RecoveryFdr3SourceInspector sourceInspector;
  private final RecoveryFdr3BlobContentRepository contentRepository;
  private final RecoveryBlobDecompressor decompressor;
  private final RecoveryFdr3IdentityReader identityReader;
  private final RecoveryFdr3IdentityMatcher identityMatcher;

  public RecoveryFdr3SourceVerifier(
      RecoveryFdr3SourceInspector sourceInspector,
      RecoveryFdr3BlobContentRepository contentRepository,
      RecoveryBlobDecompressor decompressor,
      RecoveryFdr3IdentityReader identityReader,
      RecoveryFdr3IdentityMatcher identityMatcher) {

    this.sourceInspector =
        Objects.requireNonNull(
            sourceInspector, "sourceInspector is required");

    this.contentRepository =
        Objects.requireNonNull(
            contentRepository, "contentRepository is required");

    this.decompressor =
        Objects.requireNonNull(
            decompressor, "decompressor is required");

    this.identityReader =
        Objects.requireNonNull(
            identityReader, "identityReader is required");

    this.identityMatcher =
        Objects.requireNonNull(
            identityMatcher, "identityMatcher is required");
  }

  public sealed interface Result
          permits BlobMissing, MetadataUnresolved, IdentityMismatch, SizeLimitExceeded, Verified {

      RecoveryFlowCandidate candidate();
  }

  public record SizeLimitExceeded(RecoveryFlowCandidate candidate, RecoveryBlobSnapshot blob,
          RecoveryContentSizeException failure) implements Result {
  }

  public record BlobMissing(
      RecoveryFlowCandidate candidate) implements Result {
  }

  public record MetadataUnresolved(
      RecoveryFlowCandidate candidate,
      RecoveryBlobSnapshot blob,
      RecoveryFdr3BlobMetadataInspector.Unresolved issue)
      implements Result {
  }

  public record IdentityMismatch(
      RecoveryFlowCandidate candidate,
      RecoveryBlobSnapshot blob,
      RecoveryFdr3BlobMetadataInspector.Valid metadata,
      RecoveryFdr3IdentityReader.Identity identity,
      RecoveryFdr3IdentityMatcher.Comparison comparison)
      implements Result {
  }

  public record Verified(
      RecoveryFlowCandidate candidate,
      RecoveryBlobSnapshot blob,
      RecoveryFdr3BlobMetadataInspector.Valid metadata,
      RecoveryFdr3IdentityReader.Identity identity)
      implements Result {
  }

  public Result verify(RecoveryFlowCandidate candidate)
      throws IOException {

    Objects.requireNonNull(candidate, "candidate is required");

    RecoveryFdr3SourceInspector.Result inspection =
        sourceInspector.inspect(candidate);

    if (inspection
        instanceof RecoveryFdr3SourceInspector.BlobMissing) {

      return new BlobMissing(candidate);
    }

    if (inspection
        instanceof RecoveryFdr3SourceInspector.MetadataUnresolved
            unresolved) {

      return new MetadataUnresolved(
          candidate,
          unresolved.blob(),
          unresolved.issue());
    }

    RecoveryFdr3SourceInspector.Inspected inspected =
        (RecoveryFdr3SourceInspector.Inspected) inspection;

    byte[] json;

    try {
      byte[] compressed = contentRepository.download(inspected.blob());
      json = decompressor.decompress(compressed);
    } catch (RecoveryContentSizeException failure) {
      return new SizeLimitExceeded(
          candidate,
          inspected.blob(),
          failure);
    }

    RecoveryFdr3IdentityReader.Identity identity =
        identityReader.read(json);

    RecoveryFdr3IdentityMatcher.Comparison comparison =
        identityMatcher.compare(candidate, identity);

    if (!comparison.matches()) {
      return new IdentityMismatch(
          candidate,
          inspected.blob(),
          inspected.metadata(),
          identity,
          comparison);
    }

    return new Verified(
        candidate,
        inspected.blob(),
        inspected.metadata(),
        identity);
  }
}