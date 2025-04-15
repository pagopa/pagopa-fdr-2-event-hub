package it.gov.pagopa.fdr.to.eventhub.exception;

import org.xml.sax.SAXException;

public class XmlParsingException extends SAXException {

  private static final long serialVersionUID = 3927686700759603623L;

  public XmlParsingException(String message) {
    super(message);
  }

  public XmlParsingException(String message, Exception cause) {
    super(message, cause);
  }
}
