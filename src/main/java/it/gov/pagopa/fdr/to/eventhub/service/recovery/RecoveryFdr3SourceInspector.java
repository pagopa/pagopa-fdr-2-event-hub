package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFdr3BlobRepository;
import java.util.Objects;
import java.util.Optional;

public final class RecoveryFdr3SourceInspector {

  private final RecoveryFdr3BlobRepository repository;
  private final RecoveryFdr3BlobMetadataInspector metadataInspector;

  public RecoveryFdr3SourceInspector(
      RecoveryFdr3BlobRepository repository,
      RecoveryFdr3BlobMetadataInspector metadataInspector) {

    this.repository =
        Objects.requireNonNull(repository, "repository is required");

    this.metadataInspector =
        Objects.requireNonNull(
            metadataInspector, "metadataInspector is required");
  }

  public sealed interface Result
      permits BlobMissing, MetadataUnresolved, Inspected {

    RecoveryFlowCandidate candidate();
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

  public record Inspected(
      RecoveryFlowCandidate candidate,
      RecoveryBlobSnapshot blob,
      RecoveryFdr3BlobMetadataInspector.Valid metadata)
      implements Result {
  }

  public Result inspect(RecoveryFlowCandidate candidate) {
    Objects.requireNonNull(candidate, "candidate is required");

    Optional<RecoveryBlobSnapshot> found =
        repository.find(candidate);

    if (found.isEmpty()) {
      return new BlobMissing(candidate);
    }

    RecoveryBlobSnapshot blob = found.orElseThrow();

    RecoveryFdr3BlobMetadataInspector.Inspection inspection =
        metadataInspector.inspect(blob);

    if (inspection
        instanceof RecoveryFdr3BlobMetadataInspector.Valid valid) {

      return new Inspected(candidate, blob, valid);
    }

    RecoveryFdr3BlobMetadataInspector.Unresolved unresolved =
        (RecoveryFdr3BlobMetadataInspector.Unresolved) inspection;

    return new MetadataUnresolved(candidate, blob, unresolved);
  }
}