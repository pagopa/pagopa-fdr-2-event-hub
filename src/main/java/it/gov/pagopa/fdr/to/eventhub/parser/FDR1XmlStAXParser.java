package it.gov.pagopa.fdr.to.eventhub.parser;

import it.gov.pagopa.fdr.to.eventhub.exception.XmlParsingException;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import java.io.InputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import lombok.experimental.UtilityClass;
import org.xml.sax.SAXException;

@UtilityClass
public class FDR1XmlStAXParser {

  private final XMLInputFactory factory;

  static {
    factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
  }

  public static FlussoRendicontazione parseXmlStream(InputStream xmlStream)
      throws SAXException, XMLStreamException {

    if (xmlStream == null) {
      throw new XmlParsingException("The XML stream is null");
    }

    return new FlussoRendicontazioneTagParser().parse(factory, xmlStream);
  }
}
