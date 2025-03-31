package it.gov.pagopa.fdr.to.eventhub.parser;

import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRiversamento;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

class FlussoRendicontazioneTagParser {

  private final StringBuilder value = new StringBuilder();
  private final Map<String, String> rawData = new HashMap<>();
  private FlussoRiversamento completeFlow;
  private String rawBase64Content;

  public FlussoRendicontazione parse(XMLInputFactory factory, InputStream xmlStream)
      throws XMLStreamException {

    XMLStreamReader reader = factory.createXMLStreamReader(xmlStream);

    while (reader.hasNext()) {
      int event = reader.next();
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> this.startElement();
        case XMLStreamConstants.CHARACTERS -> this.characters(reader);
        case XMLStreamConstants.END_ELEMENT -> this.endElement(factory, reader);
      }
    }
    reader.close();

    return mapToFlussoRendicontazione(this.rawData);
  }

  private void startElement() {
    value.setLength(0);
  }

  private void characters(XMLStreamReader reader) {
    value.append(reader.getText().trim());
  }

  private void endElement(XMLInputFactory factory, XMLStreamReader reader)
      throws XMLStreamException {

    String content = value.toString().trim();
    String tagName = normalizeTag(reader.getLocalName());

    if ("xmlRendicontazione".equals(tagName)) {
      rawBase64Content = content;
    } else if ("nodoInviaFlussoRendicontazione".equals(tagName)) {
      FlussoRiversamentoTagParser flussoRiversamentoTagParser = new FlussoRiversamentoTagParser();
      completeFlow = flussoRiversamentoTagParser.parse(factory, rawBase64Content);
    } else {
      rawData.put(tagName, content);
    }
  }

  private String normalizeTag(String qName) {
    // Removes the namespace if present
    return qName.contains(":") ? qName.substring(qName.indexOf(":") + 1) : qName;
  }

  private FlussoRendicontazione mapToFlussoRendicontazione(Map<String, String> rawData) {
    return FlussoRendicontazione.builder()
        .identificativoPSP(rawData.get("identificativoPSP"))
        .identificativoIntermediarioPSP(rawData.get("identificativoIntermediarioPSP"))
        .identificativoCanale(rawData.get("identificativoCanale"))
        .password(rawData.get("password"))
        .identificativoDominio(rawData.get("identificativoDominio"))
        .identificativoFlusso(rawData.get("identificativoFlusso"))
        .dataOraFlusso(rawData.get("dataOraFlusso"))
        .flussoRiversamento(completeFlow)
        .build();
  }
}
