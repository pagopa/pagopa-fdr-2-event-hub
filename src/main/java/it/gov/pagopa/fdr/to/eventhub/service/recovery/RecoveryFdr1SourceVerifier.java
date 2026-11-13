package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.io.IOException;
import java.util.Objects;

public final class RecoveryFdr1SourceVerifier {

  public sealed interface Result permits MetadataUnresolved, SizeLimitExceeded, Inspected {
  }

  public record SizeLimitExceeded(RecoveryFlowCandidate candidate, RecoveryBlobSnapshot blob,
            RecoveryContentSizeException failure) implements Result {
  }

  public record MetadataUnresolved(
      RecoveryFlowCandidate candidate,
      RecoveryBlobSnapshot blob,
      RecoveryFdr1BlobMetadataInspector.Unresolved issue)
      implements Result {
  }

  public record Inspected(
      RecoveryFdr1SourceInspector.Inspection source,
      RecoveryFdr1BlobMetadataInspector.Valid metadata)
      implements Result {
  }

  private final RecoveryFdr1BlobMetadataInspector metadataInspector;
  private final RecoveryFdr1SourceInspector sourceInspector;

  public RecoveryFdr1SourceVerifier(
      RecoveryFdr1BlobMetadataInspector metadataInspector,
      RecoveryFdr1SourceInspector sourceInspector) {

    this.metadataInspector =
        Objects.requireNonNull(
            metadataInspector, "metadataInspector is required");

    this.sourceInspector =
        Objects.requireNonNull(
            sourceInspector, "sourceInspector is required");
  }

  public Result verify(
      RecoveryFlowCandidate candidate,
      RecoveryBlobSnapshot blob) throws IOException {

    Objects.requireNonNull(candidate, "candidate is required");
    Objects.requireNonNull(blob, "blob is required");

    if (!"fdr1-flows".equals(blob.containerName())) {
      throw new IllegalArgumentException(
          "The FDR1 source must belong to fdr1-flows");
    }

    var metadata = metadataInspector.inspect(blob);

    if (metadata
        instanceof RecoveryFdr1BlobMetadataInspector.Unresolved issue) {

      return new MetadataUnresolved(candidate, blob, issue);
    }

    var validMetadata =
        (RecoveryFdr1BlobMetadataInspector.Valid) metadata;

    try {
        var source = sourceInspector.inspect(candidate, blob);
        return new Inspected(source, validMetadata);
    } catch (RecoveryContentSizeException failure) {
        return new SizeLimitExceeded(candidate, blob, failure);
    }
  }
}