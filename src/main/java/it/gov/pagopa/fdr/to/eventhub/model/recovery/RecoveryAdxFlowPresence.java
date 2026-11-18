package it.gov.pagopa.fdr.to.eventhub.model.recovery;

public record RecoveryAdxFlowPresence(
    String flowName,
    String pspDomainId,
    String orgDomainId,
    long presentRows) {

  public RecoveryAdxFlowPresence {
    requireText(flowName, "flowName");
    requireText(pspDomainId, "pspDomainId");
    requireText(orgDomainId, "orgDomainId");

    if (presentRows <= 0) {
      throw new IllegalArgumentException("presentRows must be positive");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}