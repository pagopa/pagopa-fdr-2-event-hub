package it.gov.pagopa.fdr.to.eventhub.exception.recovery;

public final class UnsupportedRecoveryJobFormatException
    extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public UnsupportedRecoveryJobFormatException(String message) {
    super(message);
  }
}