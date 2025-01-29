package it.gov.pagopa.fdr.to.eventhub.exception;

public class EventHubException extends Exception{

	private static final long serialVersionUID = 5074102297461899947L;

	public EventHubException(String message) {
        super(message);
    }

    public EventHubException(String message, Throwable cause) {
        super(message, cause);
    }

}
