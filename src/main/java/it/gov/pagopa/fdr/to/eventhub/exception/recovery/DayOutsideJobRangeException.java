package it.gov.pagopa.fdr.to.eventhub.exception.recovery;

public final class DayOutsideJobRangeException
    extends IllegalArgumentException {

  private static final long serialVersionUID = 2704319422762995466L;

  public DayOutsideJobRangeException() {
    super("day must belong to the recovery job date range");
  }
}