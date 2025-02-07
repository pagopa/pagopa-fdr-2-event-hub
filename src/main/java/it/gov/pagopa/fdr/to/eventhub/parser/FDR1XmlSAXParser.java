package it.gov.pagopa.fdr.to.eventhub.parser;

import it.gov.pagopa.fdr.to.eventhub.exception.XmlParsingException;
import it.gov.pagopa.fdr.to.eventhub.model.DatiSingoloPagamento;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRiversamento;
import it.gov.pagopa.fdr.to.eventhub.model.Istituto;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import lombok.experimental.UtilityClass;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

@UtilityClass
public class FDR1XmlSAXParser {

  public static FlussoRendicontazione parseXmlContent(String xmlContent)
      throws ParserConfigurationException, SAXException, IOException {

    if (xmlContent == null || xmlContent.isBlank()) {
      throw new XmlParsingException("The XML content is empty or null");
    }

    try (InputStream xmlStream =
        new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8))) {
      return parseXml(xmlStream);
    }
  }

  public static FlussoRendicontazione parseXmlStream(InputStream xmlStream)
      throws ParserConfigurationException, SAXException, IOException {

    if (xmlStream == null) {
      throw new XmlParsingException("The XML stream is null");
    }

    return parseXml(xmlStream);
  }

  private static FlussoRendicontazione parseXml(InputStream xmlStream)
      throws ParserConfigurationException, SAXException, IOException {

    SAXParserFactory factory = SAXParserFactory.newInstance();
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

    SAXParser parser = factory.newSAXParser();
    AtomicReference<FlussoRendicontazione> flusso = new AtomicReference<>();

    parser.parse(
        xmlStream,
        new DefaultHandler() {
          private final StringBuilder value = new StringBuilder();
          private final Map<String, String> dati = new HashMap<>();
          private String flussoRiversamentoBase64;

          @Override
          public void startElement(
              String uri, String localName, String qName, Attributes attributes) {
            value.setLength(0);
          }

          @Override
          public void characters(char[] ch, int start, int length) {
            value.append(ch, start, length);
          }

          @Override
          public void endElement(String uri, String localName, String qName)
              throws XmlParsingException {
            String content = value.toString().trim();
            String tagName = qName.contains(":") ? qName.substring(qName.indexOf(":") + 1) : qName;

            if ("xmlRendicontazione".equals(tagName)) {
              flussoRiversamentoBase64 = content;
            } else if ("nodoInviaFlussoRendicontazione".equals(tagName)) {
              flusso.set(
                  FlussoRendicontazione.builder()
                      .identificativoPSP(dati.get("identificativoPSP"))
                      .identificativoIntermediarioPSP(dati.get("identificativoIntermediarioPSP"))
                      .identificativoCanale(dati.get("identificativoCanale"))
                      .password(dati.get("password"))
                      .identificativoDominio(dati.get("identificativoDominio"))
                      .identificativoFlusso(dati.get("identificativoFlusso"))
                      .dataOraFlusso(dati.get("dataOraFlusso"))
                      .flussoRiversamento(
                          decodeAndParseFlussoRiversamento(flussoRiversamentoBase64))
                      .build());
            } else {
              dati.put(tagName, content);
            }
          }
        });

    return Optional.ofNullable(flusso.get())
        .orElseThrow(
            () -> new XmlParsingException("Parsing failed: check the XML content of the file"));
  }

  /*
   * public static FlussoRendicontazione parseXmlContent(String xmlContent)
   * throws ParserConfigurationException, SAXException, IOException {
   *
   * if (xmlContent == null || xmlContent.isBlank()) { throw new
   * XmlParsingException("The XML content is empty or null"); }
   *
   * SAXParserFactory factory = SAXParserFactory.newInstance();
   * factory.setFeature(
   * "http://xml.org/sax/features/external-general-entities", false);
   * factory.setFeature(
   * "http://xml.org/sax/features/external-parameter-entities", false);
   * factory.setFeature(
   * "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
   *
   * SAXParser parser = factory.newSAXParser();
   * AtomicReference<FlussoRendicontazione> flusso = new AtomicReference<>();
   *
   * parser.parse( new
   * ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)), new
   * DefaultHandler() { private final StringBuilder value = new
   * StringBuilder(); private final Map<String, String> dati = new
   * HashMap<>(); private String flussoRiversamentoBase64;
   *
   * @Override public void startElement( String uri, String localName, String
   * qName, Attributes attributes) { value.setLength(0); }
   *
   * @Override public void characters(char[] ch, int start, int length) {
   * value.append(ch, start, length); }
   *
   * @Override public void endElement(String uri, String localName, String
   * qName) throws XmlParsingException { String content =
   * value.toString().trim(); // Removes the namespace if present String
   * tagName = qName.contains(":") ? qName.substring(qName.indexOf(":") + 1) :
   * qName; if ("xmlRendicontazione".equals(tagName)) {
   * flussoRiversamentoBase64 = content; } else if
   * ("nodoInviaFlussoRendicontazione".equals(tagName)) { flusso.set(
   * FlussoRendicontazione.builder()
   * .identificativoPSP(dati.get("identificativoPSP"))
   * .identificativoIntermediarioPSP(dati.get("identificativoIntermediarioPSP"
   * )) .identificativoCanale(dati.get("identificativoCanale"))
   * .password(dati.get("password"))
   * .identificativoDominio(dati.get("identificativoDominio"))
   * .identificativoFlusso(dati.get("identificativoFlusso"))
   * .dataOraFlusso(dati.get("dataOraFlusso")) .flussoRiversamento(
   * decodeAndParseFlussoRiversamento(flussoRiversamentoBase64)) .build()); }
   * else { dati.put(tagName, content); } } });
   *
   * return Optional.ofNullable(flusso.get()) .orElseThrow( () -> new
   * XmlParsingException("Parsing failed: check the XML content of the file"))
   * ; }
   */

  /*
   * private static FlussoRiversamento decodeAndParseFlussoRiversamento(String
   * base64Content) throws XmlParsingException { if (base64Content == null ||
   * base64Content.isEmpty()) { return null; } byte[] decodedBytes =
   * Base64.getDecoder().decode(base64Content); String decodedXml = new
   * String(decodedBytes, StandardCharsets.UTF_8);
   *
   * return parseFlussoRiversamento(decodedXml); }
   */

  private static FlussoRiversamento decodeAndParseFlussoRiversamento(String base64Content)
      throws XmlParsingException {
    if (base64Content == null || base64Content.isEmpty()) {
      return null;
    }

    byte[] decodedBytes = Base64.getDecoder().decode(base64Content);

    try (InputStream xmlStream = new ByteArrayInputStream(decodedBytes)) {
      return parseFlussoRiversamento(xmlStream);
    } catch (IOException e) {
      throw new XmlParsingException("Error handling XML stream", e);
    }
  }

  public static FlussoRiversamento parseFlussoRiversamento(InputStream xmlStream)
      throws XmlParsingException {
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

      SAXParser parser = factory.newSAXParser();
      FlussoRiversamentoHandler handler = new FlussoRiversamentoHandler();

      parser.parse(xmlStream, handler);
      return handler.getFlussoRiversamento();
    } catch (Exception e) {
      throw new XmlParsingException("Error parsing flusso riversamento", e);
    }
  }

  /*
   * public static FlussoRiversamento parseFlussoRiversamento(String
   * decodedXml) throws XmlParsingException { try { SAXParserFactory factory =
   * SAXParserFactory.newInstance(); factory.setFeature(
   * "http://xml.org/sax/features/external-general-entities", false);
   * factory.setFeature(
   * "http://xml.org/sax/features/external-parameter-entities", false);
   * factory.setFeature(
   * "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
   *
   * SAXParser parser = factory.newSAXParser(); FlussoRiversamentoHandler
   * handler = new FlussoRiversamentoHandler();
   *
   * parser.parse(new
   * ByteArrayInputStream(decodedXml.getBytes(StandardCharsets.UTF_8)),
   * handler); return handler.getFlussoRiversamento(); } catch (Exception e) {
   * throw new XmlParsingException("Error parsing flusso riversamento", e); }
   * }
   */
}

class FlussoRiversamentoHandler extends DefaultHandler {
  private FlussoRiversamento flussoRiversamento;
  private Map<String, String> flussoDati;
  private Map<String, String> currentIstituto;
  private Map<String, String> currentDatiPagamento;
  private StringBuilder value = new StringBuilder();
  private boolean insideDatiSingoliPagamenti = false;

  public FlussoRiversamentoHandler() {
    this.flussoRiversamento = new FlussoRiversamento();
    this.flussoRiversamento.setDatiSingoliPagamenti(new ArrayList<>());
    this.flussoDati = new HashMap<>();
  }

  public FlussoRiversamento getFlussoRiversamento() {
    flussoRiversamento.setVersioneOggetto(flussoDati.get("versioneOggetto"));
    flussoRiversamento.setIdentificativoFlusso(flussoDati.get("identificativoFlusso"));
    flussoRiversamento.setDataOraFlusso(flussoDati.get("dataOraFlusso"));
    flussoRiversamento.setIdentificativoUnivocoRegolamento(
        flussoDati.get("identificativoUnivocoRegolamento"));
    flussoRiversamento.setDataRegolamento(flussoDati.get("dataRegolamento"));
    flussoRiversamento.setNumeroTotalePagamenti(
        Integer.parseInt(flussoDati.get("numeroTotalePagamenti")));
    flussoRiversamento.setImportoTotalePagamenti(
        Double.parseDouble(flussoDati.get("importoTotalePagamenti")));
    return flussoRiversamento;
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes attributes)
      throws SAXException {
    value.setLength(0);
    String tagName = normalizeTag(qName);

    if ("istitutoMittente".equals(tagName) || "istitutoRicevente".equals(tagName)) {
      currentIstituto = new HashMap<>();
    } else if ("datiSingoliPagamenti".equals(tagName)) {
      currentDatiPagamento = new HashMap<>();
      insideDatiSingoliPagamenti = true;
    }
  }

  @Override
  public void characters(char[] ch, int start, int length) throws SAXException {
    value.append(ch, start, length);
  }

  @Override
  public void endElement(String uri, String localName, String qName) throws SAXException {
    String content = value.toString().trim();
    String tagName = normalizeTag(qName);

    if (currentIstituto != null) {
      currentIstituto.put(tagName, content);
      if ("istitutoMittente".equals(tagName)) {
        flussoRiversamento.setIstitutoMittente(mapToIstituto(currentIstituto));
        currentIstituto = null;
      } else if ("istitutoRicevente".equals(tagName)) {
        flussoRiversamento.setIstitutoRicevente(mapToIstituto(currentIstituto));
        currentIstituto = null;
      }
    } else if (insideDatiSingoliPagamenti && currentDatiPagamento != null) {
      currentDatiPagamento.put(tagName, content);
      if ("datiSingoliPagamenti".equals(tagName)) {
        flussoRiversamento
            .getDatiSingoliPagamenti()
            .add(mapToDatiSingoloPagamento(currentDatiPagamento));
        currentDatiPagamento = null;
        insideDatiSingoliPagamenti = false;
      }
    } else {
      flussoDati.put(tagName, content);
    }
  }

  private String normalizeTag(String qName) {
    // Removes the namespace if present
    return qName.contains(":") ? qName.substring(qName.indexOf(":") + 1) : qName;
  }

  private Istituto mapToIstituto(Map<String, String> dati) {
    return new Istituto(
        dati.get("tipoIdentificativoUnivoco"),
        dati.get("codiceIdentificativoUnivoco"),
        dati.get("denominazioneMittente") != null
            ? dati.get("denominazioneMittente")
            : dati.get("denominazioneRicevente"));
  }

  private DatiSingoloPagamento mapToDatiSingoloPagamento(Map<String, String> dati) {
    return new DatiSingoloPagamento(
        dati.get("identificativoUnivocoVersamento"),
        dati.get("identificativoUnivocoRiscossione"),
        dati.get("indiceDatiSingoloPagamento"),
        Double.parseDouble(dati.get("singoloImportoPagato")),
        Integer.parseInt(dati.get("codiceEsitoSingoloPagamento")),
        dati.get("dataEsitoSingoloPagamento"));
  }
}
