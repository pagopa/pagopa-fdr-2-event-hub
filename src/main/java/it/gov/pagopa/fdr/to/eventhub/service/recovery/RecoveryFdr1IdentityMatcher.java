package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class RecoveryFdr1IdentityMatcher {

  public enum Field {
    FLOW_NAME,
    PSP_DOMAIN_ID,
    ORG_DOMAIN_ID,
    FLOW_DATE
  }

  public record Comparison(Set<Field> differences) {

    public Comparison {
      differences = Set.copyOf(differences);
    }

    public boolean matches() {
      return differences.isEmpty();
    }
  }

  private final RecoveryFdr1DateNormalizer dateNormalizer;

  public RecoveryFdr1IdentityMatcher(
      RecoveryFdr1DateNormalizer dateNormalizer) {

    this.dateNormalizer =
        Objects.requireNonNull(
            dateNormalizer, "dateNormalizer is required");
  }

  public Comparison compare(
      RecoveryFlowCandidate candidate,
      RecoveryFdr1EnvelopeReader.RequestIdentity identity) {

    Objects.requireNonNull(candidate, "candidate is required");
    Objects.requireNonNull(identity, "identity is required");

    EnumSet<Field> differences = EnumSet.noneOf(Field.class);

    if (!Objects.equals(candidate.name(), identity.name())) {
      differences.add(Field.FLOW_NAME);
    }

    if (!Objects.equals(
        candidate.pspDomainId(), identity.pspDomainId())) {
      differences.add(Field.PSP_DOMAIN_ID);
    }

    if (!Objects.equals(
        candidate.orgDomainId(), identity.orgDomainId())) {
      differences.add(Field.ORG_DOMAIN_ID);
    }

    var expectedDate = candidate.flowDate().toInstant(ZoneOffset.UTC);
    var actualDate = dateNormalizer.normalize(identity.flowDate());

    if (!expectedDate.equals(actualDate)) {
      differences.add(Field.FLOW_DATE);
    }

    return new Comparison(differences);
  }
}