package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public final class RecoveryFdr1FlowDateMatcher {

  public enum Match {
    EXACT,
    MILLISECOND_PROJECTION,
    DIFFERENT
  }

  public Match compare(Instant postgresDate, Instant outerXmlDate) {
    Objects.requireNonNull(postgresDate, "postgresDate is required");
    Objects.requireNonNull(outerXmlDate, "outerXmlDate is required");

    if (postgresDate.equals(outerXmlDate)) {
      return Match.EXACT;
    }

    boolean postgresHasMillisecondPrecision =
        postgresDate.getNano() % 1_000_000 == 0;

    if (postgresHasMillisecondPrecision
        && postgresDate.equals(
            outerXmlDate.truncatedTo(ChronoUnit.MILLIS))) {
      return Match.MILLISECOND_PROJECTION;
    }

    return Match.DIFFERENT;
  }
}