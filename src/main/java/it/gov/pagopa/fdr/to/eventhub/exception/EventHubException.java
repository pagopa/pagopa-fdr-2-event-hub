package it.gov.pagopa.fdr.to.eventhub.exception;

public class EventHubException extends Exception {

  public EventHubException(String message) {
    super(message);
  }

  public EventHubException(String message, Throwable cause) {
    super(message, cause);
  }
}
