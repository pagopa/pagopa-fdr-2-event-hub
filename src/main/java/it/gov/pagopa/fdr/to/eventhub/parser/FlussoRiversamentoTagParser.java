package it.gov.pagopa.fdr.to.eventhub.parser;

import it.gov.pagopa.fdr.to.eventhub.model.fdr1.DatiSingoloPagamento;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRiversamento;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.Istituto;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

class FlussoRiversamentoTagParser {

  private final FlussoRiversamento completeFlow;

  private final Map<String, String> rawData;

  private final StringBuilder value = new StringBuilder();

  private Map<String, String> analyzedIstitutoTag;

  private Map<String, String> analyzedDatiPagamentoTag;

  private boolean insideDatiSingoliPagamentiTag = false;

  public FlussoRiversamentoTagParser() {
    this.completeFlow = new FlussoRiversamento();
    this.completeFlow.setDatiSingoliPagamenti(new ArrayList<>());
    this.rawData = new HashMap<>();
  }

  public FlussoRiversamento parse(XMLInputFactory factory, String rawBase64Content)
      throws XMLStreamException {

    if (rawBase64Content == null || rawBase64Content.isEmpty()) {
      return null;
    }

    byte[] decodedBytes = Base64.getDecoder().decode(rawBase64Content);
    XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(decodedBytes));
    while (reader.hasNext()) {
      int event = reader.next();
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> this.startElement(reader);
        case XMLStreamConstants.CHARACTERS -> this.characters(reader);
        case XMLStreamConstants.END_ELEMENT -> this.endElement(reader);
      }
    }
    reader.close();

    return mapToFlussoRiversamento(this.rawData);
  }

  private void startElement(XMLStreamReader reader) {

    value.setLength(0);
    String tagName = normalizeTag(reader.getLocalName());

    if ("istitutoMittente".equals(tagName) || "istitutoRicevente".equals(tagName)) {
      analyzedIstitutoTag = new HashMap<>();
    } else if ("datiSingoliPagamenti".equals(tagName)) {
      analyzedDatiPagamentoTag = new HashMap<>();
      insideDatiSingoliPagamentiTag = true;
    }
  }

  private void characters(XMLStreamReader reader) {
    value.append(reader.getText().trim());
  }

  private void endElement(XMLStreamReader reader) {

    String content = value.toString().trim();
    String tagName = normalizeTag(reader.getLocalName());

    if (analyzedIstitutoTag != null) {
      analyzedIstitutoTag.put(tagName, content);
      if ("istitutoMittente".equals(tagName)) {
        completeFlow.setIstitutoMittente(mapToIstituto(analyzedIstitutoTag));
        analyzedIstitutoTag = null;
      } else if ("istitutoRicevente".equals(tagName)) {
        completeFlow.setIstitutoRicevente(mapToIstituto(analyzedIstitutoTag));
        analyzedIstitutoTag = null;
      }
    } else if (insideDatiSingoliPagamentiTag && analyzedDatiPagamentoTag != null) {
      analyzedDatiPagamentoTag.put(tagName, content);
      if ("datiSingoliPagamenti".equals(tagName)) {
        completeFlow
            .getDatiSingoliPagamenti()
            .add(mapToDatiSingoloPagamento(analyzedDatiPagamentoTag));
        analyzedDatiPagamentoTag = null;
        insideDatiSingoliPagamentiTag = false;
      }
    } else {
      rawData.put(tagName, content);
    }
  }

  private String normalizeTag(String qName) {
    // Removes the namespace if present
    return qName.contains(":") ? qName.substring(qName.indexOf(":") + 1) : qName;
  }

  private FlussoRiversamento mapToFlussoRiversamento(Map<String, String> fieldMap) {
    completeFlow.setVersioneOggetto(fieldMap.get("versioneOggetto"));
    completeFlow.setIdentificativoFlusso(fieldMap.get("identificativoFlusso"));
    completeFlow.setDataOraFlusso(fieldMap.get("dataOraFlusso"));
    completeFlow.setIdentificativoUnivocoRegolamento(
        fieldMap.get("identificativoUnivocoRegolamento"));
    completeFlow.setDataRegolamento(fieldMap.get("dataRegolamento"));
    completeFlow.setNumeroTotalePagamenti(Integer.parseInt(fieldMap.get("numeroTotalePagamenti")));
    completeFlow.setImportoTotalePagamenti(
        Double.parseDouble(fieldMap.get("importoTotalePagamenti")));
    return completeFlow;
  }

  private Istituto mapToIstituto(Map<String, String> fieldMap) {
    Istituto istituto = new Istituto();
    istituto.setTipoIdentificativoUnivoco(fieldMap.get("tipoIdentificativoUnivoco"));
    istituto.setCodiceIdentificativoUnivoco(fieldMap.get("codiceIdentificativoUnivoco"));
    istituto.setDenominazione(
        fieldMap.get("denominazioneMittente") != null
            ? fieldMap.get("denominazioneMittente")
            : fieldMap.get("denominazioneRicevente"));
    return istituto;
  }

  private DatiSingoloPagamento mapToDatiSingoloPagamento(Map<String, String> fieldMap) {
    DatiSingoloPagamento datiSingoloPagamento = new DatiSingoloPagamento();
    datiSingoloPagamento.setIdentificativoUnivocoVersamento(
        fieldMap.get("identificativoUnivocoVersamento"));
    datiSingoloPagamento.setIdentificativoUnivocoRiscossione(
        fieldMap.get("identificativoUnivocoRiscossione"));
    datiSingoloPagamento.setIndiceDatiSingoloPagamento(fieldMap.get("indiceDatiSingoloPagamento"));
    datiSingoloPagamento.setSingoloImportoPagato(
        Double.parseDouble(fieldMap.get("singoloImportoPagato")));
    datiSingoloPagamento.setCodiceEsitoSingoloPagamento(
        Integer.parseInt(fieldMap.get("codiceEsitoSingoloPagamento")));
    datiSingoloPagamento.setDataEsitoSingoloPagamento(fieldMap.get("dataEsitoSingoloPagamento"));
    return datiSingoloPagamento;
  }
}
