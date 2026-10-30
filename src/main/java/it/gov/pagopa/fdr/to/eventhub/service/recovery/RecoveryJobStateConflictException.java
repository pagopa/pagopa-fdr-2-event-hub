package it.gov.pagopa.fdr.to.eventhub.service.recovery;

public final class RecoveryJobStateConflictException
    extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public RecoveryJobStateConflictException(String message) {
    super(message);
  }
}