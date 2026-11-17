package it.gov.pagopa.fdr.to.eventhub.exception.recovery;

public final class RecoveryCandidateLimitException
    extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int limitCandidates;
  private final int observedCandidatesAtLeast;

  public RecoveryCandidateLimitException(
      long flowId,
      int limitCandidates,
      int observedCandidatesAtLeast) {

    super(
        "FDR1 source candidate limit exceeded for flowId="
            + flowId
            + ", limitCandidates="
            + limitCandidates
            + ", observedCandidatesAtLeast="
            + observedCandidatesAtLeast);

    this.limitCandidates = limitCandidates;
    this.observedCandidatesAtLeast = observedCandidatesAtLeast;
  }

  public int limitCandidates() {
    return limitCandidates;
  }

  public int observedCandidatesAtLeast() {
    return observedCandidatesAtLeast;
  }
}