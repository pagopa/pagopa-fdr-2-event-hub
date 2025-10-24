package it.gov.pagopa.fdr.to.eventhub.parser;

import it.gov.pagopa.fdr.to.eventhub.model.fdr1.DatiSingoloPagamento;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRiversamento;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.Istituto;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

class FlussoRiversamentoTagParser2 {

  // NESSUN CAMPO DI ISTANZA. La classe è completamente stateless.

  /**
   * Esegue il parsing di un Flusso di Riversamento da uno stream GIA' DECODIFICATO.
   */
  public FlussoRiversamento parse(XMLInputFactory factory, InputStream decodedStream)
          throws XMLStreamException {

    // 1. Tutto lo stato è locale al metodo
    FlussoRiversamento completeFlow = new FlussoRiversamento();
    completeFlow.setDatiSingoliPagamenti(new ArrayList<>());

    Map<String, String> rawData = new HashMap<>();
    StringBuilder value = new StringBuilder();
    Map<String, String> analyzedIstitutoTag = null;
    Map<String, String> analyzedDatiPagamentoTag = null;
    boolean insideDatiSingoliPagamentiTag = false;

    XMLStreamReader reader = factory.createXMLStreamReader(decodedStream);

    // 2. Loop di parsing
    while (reader.hasNext()) {
      int event = reader.next();
      switch (event) {
        case XMLStreamConstants.START_ELEMENT:
          value.setLength(0);
          String startTagName = normalizeTag(reader.getLocalName());

          if ("istitutoMittente".equals(startTagName) || "istitutoRicevente".equals(startTagName)) {
            analyzedIstitutoTag = new HashMap<>();
          } else if ("datiSingoliPagamenti".equals(startTagName)) {
            analyzedDatiPagamentoTag = new HashMap<>();
            insideDatiSingoliPagamentiTag = true;
          }
          break;

        case XMLStreamConstants.CHARACTERS:
          value.append(reader.getText().trim());
          break;

        case XMLStreamConstants.END_ELEMENT:
          String content = value.toString().trim();
          String endTagName = normalizeTag(reader.getLocalName());

          if (analyzedIstitutoTag != null) {
            analyzedIstitutoTag.put(endTagName, content);
            if ("istitutoMittente".equals(endTagName)) {
              completeFlow.setIstitutoMittente(mapToIstituto(analyzedIstitutoTag));
              analyzedIstitutoTag = null;
            } else if ("istitutoRicevente".equals(endTagName)) {
              completeFlow.setIstitutoRicevente(mapToIstituto(analyzedIstitutoTag));
              analyzedIstitutoTag = null;
            }
          } else if (insideDatiSingoliPagamentiTag && analyzedDatiPagamentoTag != null) {
            analyzedDatiPagamentoTag.put(endTagName, content);
            if ("datiSingoliPagamenti".equals(endTagName)) {
              completeFlow
                      .getDatiSingoliPagamenti()
                      .add(mapToDatiSingoloPagamento(analyzedDatiPagamentoTag));
              analyzedDatiPagamentoTag = null;
              insideDatiSingoliPagamentiTag = false;
            }
          } else {
            rawData.put(endTagName, content);
          }
          break;
      }
    }
    reader.close(); // Lo stream 'decodedStream' viene chiuso dal chiamante

    // 3. Mappaggio finale
    return mapToFlussoRiversamento(rawData, completeFlow);
  }

  private String normalizeTag(String qName) {
    return qName.contains(":") ? qName.substring(qName.indexOf(":") + 1) : qName;
  }

  // Il mapping ora riceve 'flow' come parametro
  private FlussoRiversamento mapToFlussoRiversamento(Map<String, String> fieldMap, FlussoRiversamento flow) {
    flow.setVersioneOggetto(fieldMap.get("versioneOggetto"));
    flow.setIdentificativoFlusso(fieldMap.get("identificativoFlusso"));
    flow.setDataOraFlusso(fieldMap.get("dataOraFlusso"));
    flow.setIdentificativoUnivocoRegolamento(
            fieldMap.get("identificativoUnivocoRegolamento"));
    flow.setDataRegolamento(fieldMap.get("dataRegolamento"));

    // Aggiungi controlli di nullità per evitare NullPointerException
    String numTotale = fieldMap.get("numeroTotalePagamenti");
    if (numTotale != null) {
      flow.setNumeroTotalePagamenti(Integer.parseInt(numTotale));
    }

    String importoTotale = fieldMap.get("importoTotalePagamenti");
    if (importoTotale != null) {
      flow.setImportoTotalePagamenti(Double.parseDouble(importoTotale));
    }

    return flow;
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