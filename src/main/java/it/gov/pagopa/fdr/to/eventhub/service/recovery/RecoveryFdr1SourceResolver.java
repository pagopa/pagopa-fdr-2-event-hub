package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFdr1BlobRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RecoveryFdr1SourceResolver {

  public enum UnresolvedReason {
    NO_CANDIDATES,
    NO_MATCH,
    AMBIGUOUS_MATCH,
    METADATA_UNRESOLVED,
    INCONSISTENT_REPORT
  }

  public sealed interface Resolution permits Resolved, Unresolved {
  }

  public record Resolved(
      RecoveryFdr1SourceVerifier.Inspected source)
      implements Resolution {
  }

  public record Unresolved(
      UnresolvedReason reason,
      List<RecoveryFdr1SourceVerifier.Result> inspections)
      implements Resolution {

    public Unresolved {
      inspections = List.copyOf(inspections);
    }
  }

  private final RecoveryFdr1BlobRepository blobRepository;
  private final RecoveryFdr1SourceVerifier sourceVerifier;

  public RecoveryFdr1SourceResolver(
      RecoveryFdr1BlobRepository blobRepository,
      RecoveryFdr1SourceVerifier sourceVerifier) {

    this.blobRepository =
        Objects.requireNonNull(
            blobRepository, "blobRepository is required");

    this.sourceVerifier =
        Objects.requireNonNull(
            sourceVerifier, "sourceVerifier is required");
  }

  public Resolution resolve(RecoveryFlowCandidate candidate)
      throws IOException {

    Objects.requireNonNull(candidate, "candidate is required");

    var blobs = blobRepository.listCandidates(candidate);

    if (blobs.isEmpty()) {
      return new Unresolved(
          UnresolvedReason.NO_CANDIDATES, List.of());
    }

    List<RecoveryFdr1SourceVerifier.Result> inspections =
        new ArrayList<>();

    for (var blob : blobs) {
      inspections.add(sourceVerifier.verify(candidate, blob));
    }

    return select(inspections);
  }

  private Resolution select(
      List<RecoveryFdr1SourceVerifier.Result> inspections) {

    List<RecoveryFdr1SourceVerifier.Inspected> matches =
        new ArrayList<>();

    for (var result : inspections) {
      if (result
          instanceof RecoveryFdr1SourceVerifier.MetadataUnresolved) {

        return new Unresolved(
            UnresolvedReason.METADATA_UNRESOLVED, inspections);
      }

      var inspected = (RecoveryFdr1SourceVerifier.Inspected) result;

      if (!isConsistentReport(inspected.source())) {
        return new Unresolved(
            UnresolvedReason.INCONSISTENT_REPORT, inspections);
      }

      if (inspected.source().comparison().matches()) {
        matches.add(inspected);
      }
    }

    if (matches.isEmpty()) {
      return new Unresolved(
          UnresolvedReason.NO_MATCH, inspections);
    }

    if (matches.size() > 1) {
      return new Unresolved(
          UnresolvedReason.AMBIGUOUS_MATCH, inspections);
    }

    return new Resolved(matches.get(0));
  }

  private static boolean isConsistentReport(
      RecoveryFdr1SourceInspector.Inspection source) {

    return Objects.equals(
            source.envelope().name(), source.report().name())
        && source.report().declaredPaymentCount()
            == source.report().observedPaymentCount();
  }
}