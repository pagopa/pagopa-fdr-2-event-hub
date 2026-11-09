package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class RecoveryFdr3IdentityMatcher {

  public enum Field {
    FLOW_NAME,
    REVISION,
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

  public Comparison compare(
      RecoveryFlowCandidate candidate,
      RecoveryFdr3IdentityReader.Identity identity) {

    Objects.requireNonNull(candidate, "candidate is required");
    Objects.requireNonNull(identity, "identity is required");

    Set<Field> differences = EnumSet.noneOf(Field.class);

    addDifference(
        differences, Field.FLOW_NAME,
        candidate.name(), identity.name());

    addDifference(
        differences, Field.REVISION,
        candidate.revision(), identity.revision());

    addDifference(
        differences, Field.PSP_DOMAIN_ID,
        candidate.pspDomainId(), identity.pspDomainId());

    addDifference(
        differences, Field.ORG_DOMAIN_ID,
        candidate.orgDomainId(), identity.orgDomainId());

    addDifference(
        differences, Field.FLOW_DATE,
        candidate.flowDate().toInstant(ZoneOffset.UTC),
        identity.flowDate());

    return new Comparison(differences);
  }

  private static void addDifference(
      Set<Field> differences,
      Field field,
      Object expected,
      Object actual) {

    if (!Objects.equals(expected, actual)) {
      differences.add(field);
    }
  }
}