package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.util.Objects;

public final class RecoveryPublicationSourceSelector {

  public enum UnresolvedReason {
    JSON_UNRESOLVED,
    XML_UNRESOLVED,
    CONFLICTING_ORIGINS,
    ORIGIN_UNDETERMINED
  }

  public sealed interface Selection permits Selected, Unresolved {
  }

  public record Selected(
      RecoveryPublicationSource source,
      RecoveryPublicationSourceInspector.Inspection evidence)
      implements Selection {
  }

  public record Unresolved(
      UnresolvedReason reason,
      RecoveryPublicationSourceInspector.Inspection evidence)
      implements Selection {
  }

  private final RecoveryPublicationSourceMapper mapper;

  public RecoveryPublicationSourceSelector(
      RecoveryPublicationSourceMapper mapper) {

    this.mapper = Objects.requireNonNull(mapper, "mapper is required");
  }

  public Selection select(
      RecoveryPublicationSourceInspector.Inspection inspection) {

    Objects.requireNonNull(inspection, "inspection is required");

    var blockingReason = blockingReason(inspection);

    if (blockingReason != null) {
      return new Unresolved(blockingReason, inspection);
    }

    var json =
        inspection.fdr3()
                instanceof RecoveryFdr3SourceVerifier.Verified verified
            ? verified
            : null;

    var xml =
        inspection.fdr1()
                instanceof RecoveryFdr1SourceResolver.Resolved resolved
            ? resolved
            : null;

    boolean jsonOriginal =
        json != null && json.metadata().elaborate();

    boolean xmlOriginal =
        xml != null && xml.source().metadata().elaborate();

    if (jsonOriginal && xmlOriginal) {
      return new Unresolved(
          UnresolvedReason.CONFLICTING_ORIGINS, inspection);
    }

    if (jsonOriginal) {
      return new Selected(mapper.map(json), inspection);
    }

    if (xmlOriginal) {
      return new Selected(mapper.map(xml), inspection);
    }

    return new Unresolved(
        UnresolvedReason.ORIGIN_UNDETERMINED, inspection);
  }

  private static UnresolvedReason blockingReason(
      RecoveryPublicationSourceInspector.Inspection inspection) {

    boolean jsonVerified =
        inspection.fdr3()
            instanceof RecoveryFdr3SourceVerifier.Verified;

    boolean jsonMissing =
        inspection.fdr3()
            instanceof RecoveryFdr3SourceVerifier.BlobMissing;

    if (!jsonVerified && !jsonMissing) {
      return UnresolvedReason.JSON_UNRESOLVED;
    }

    if (inspection.fdr1()
        instanceof RecoveryFdr1SourceResolver.Unresolved unresolved) {

      boolean noCompatibleXml =
          unresolved.reason()
                  == RecoveryFdr1SourceResolver.UnresolvedReason.NO_CANDIDATES
              || unresolved.reason()
                  == RecoveryFdr1SourceResolver.UnresolvedReason.NO_MATCH;

      if (!noCompatibleXml) {
        return UnresolvedReason.XML_UNRESOLVED;
      }
    }

    return null;
  }
}