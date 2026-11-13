package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.io.IOException;
import java.util.Objects;

public final class RecoveryPublicationSourceInspector {

  public record Inspection(
      RecoveryFlowCandidate candidate,
      RecoveryFdr3SourceVerifier.Result fdr3,
      RecoveryFdr1SourceResolver.Resolution fdr1) {

    public Inspection {
      Objects.requireNonNull(candidate, "candidate is required");
      Objects.requireNonNull(fdr3, "fdr3 is required");
      Objects.requireNonNull(fdr1, "fdr1 is required");
    }
  }

  private final RecoveryFdr3SourceVerifier fdr3Verifier;
  private final RecoveryFdr1SourceResolver fdr1Resolver;
  private final RecoveryFdr1ContentResolver contentResolver;

  public RecoveryPublicationSourceInspector(
      RecoveryFdr3SourceVerifier fdr3Verifier,
      RecoveryFdr1SourceResolver fdr1Resolver) {

    this(fdr3Verifier, fdr1Resolver, null);
  }

  public RecoveryPublicationSourceInspector(
      RecoveryFdr3SourceVerifier fdr3Verifier,
      RecoveryFdr1SourceResolver fdr1Resolver,
      RecoveryFdr1ContentResolver contentResolver) {

    this.fdr3Verifier =
        Objects.requireNonNull(
            fdr3Verifier, "fdr3Verifier is required");

    this.fdr1Resolver =
        Objects.requireNonNull(
            fdr1Resolver, "fdr1Resolver is required");

    this.contentResolver = contentResolver;
  }

  public Inspection inspect(RecoveryFlowCandidate candidate)
      throws IOException {

    Objects.requireNonNull(candidate, "candidate is required");

    var fdr3 = fdr3Verifier.verify(candidate);
    var fdr1 = fdr1Resolver.resolve(candidate);

    if (contentResolver != null
        && fdr3 instanceof RecoveryFdr3SourceVerifier.Verified verified
        && fdr1 instanceof RecoveryFdr1SourceResolver.Unresolved unresolved) {

      fdr1 = contentResolver.resolve(verified, unresolved);
    }

    return new Inspection(candidate, fdr3, fdr1);
  }
}