package it.gov.pagopa.fdr.to.eventhub.parser;

import it.gov.pagopa.fdr.to.eventhub.exception.XmlParsingException;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import java.io.InputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import org.xml.sax.SAXException;

public class FDR1XmlStAXParser {

  private final XMLInputFactory factory;

  public FDR1XmlStAXParser() {
    this.factory = XMLInputFactory.newInstance();
    this.factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
  }

  public FlussoRendicontazione parseXmlStream(InputStream xmlStream)
      throws SAXException, XMLStreamException {

    if (xmlStream == null) {
      throw new XmlParsingException("The XML stream is null");
    }

    return new FlussoRendicontazioneTagParser().parse(factory, xmlStream);
  }
}
