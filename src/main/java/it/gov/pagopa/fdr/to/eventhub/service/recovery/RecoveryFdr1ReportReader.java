package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public final class RecoveryFdr1ReportReader {

  private static final String ROOT = "FlussoRiversamento";

  private static final String NAMESPACE =
      "http://www.digitpa.gov.it/schemas/2011/Pagamenti/";

  private static final String PAYMENT = "datiSingoliPagamenti";

  private enum Field {
    NAME,
    DATE,
    SENDER_TYPE,
    SENDER_ID,
    RECEIVER_TYPE,
    RECEIVER_ID,
    DECLARED_COUNT
  }

  private static final Map<String, Field> FIELDS =
      Map.of(
          ROOT + "/identificativoFlusso",
          Field.NAME,
          ROOT + "/dataOraFlusso",
          Field.DATE,
          ROOT + "/istitutoMittente/identificativoUnivocoMittente/tipoIdentificativoUnivoco",
          Field.SENDER_TYPE,
          ROOT + "/istitutoMittente/identificativoUnivocoMittente/codiceIdentificativoUnivoco",
          Field.SENDER_ID,
          ROOT + "/istitutoRicevente/identificativoUnivocoRicevente/tipoIdentificativoUnivoco",
          Field.RECEIVER_TYPE,
          ROOT + "/istitutoRicevente/identificativoUnivocoRicevente/codiceIdentificativoUnivoco",
          Field.RECEIVER_ID,
          ROOT + "/numeroTotalePagamenti",
          Field.DECLARED_COUNT);

  private static final Set<String> RELEVANT_NAMES =
      Set.of(
          "identificativoFlusso",
          "dataOraFlusso",
          "tipoIdentificativoUnivoco",
          "codiceIdentificativoUnivoco",
          "numeroTotalePagamenti",
          "istitutoMittente",
          "istitutoRicevente",
          "identificativoUnivocoMittente",
          "identificativoUnivocoRicevente");

  private static final Set<String> UNIQUE_CONTAINERS =
      Set.of(
          ROOT + "/istitutoMittente",
          ROOT + "/istitutoRicevente",
          ROOT + "/istitutoMittente/identificativoUnivocoMittente",
          ROOT + "/istitutoRicevente/identificativoUnivocoRicevente");

  public record ReportIdentity(
      String name,
      String flowDate,
      String senderType,
      String senderId,
      String receiverType,
      String receiverId,
      long declaredPaymentCount,
      long observedPaymentCount) {
  }

  public ReportIdentity read(byte[] xml) throws IOException {
    ReportHandler handler = new ReportHandler();
    RecoveryXmlParser.parse(xml, handler);

    try {
      return handler.identity();
    } catch (DateTimeException | NumberFormatException exception) {
      throw new IOException(
          "Invalid FDR1 report identity", exception);
    }
  }

  private static final class ReportHandler extends DefaultHandler {

    private final List<String> path = new ArrayList<>();

    private final Map<Field, String> values =
        new EnumMap<>(Field.class);

    private final Set<String> seenContainers = new HashSet<>();

    private Field capturing;
    private StringBuilder text;
    private long observedPayments;

    @Override
    public void startElement(
        String uri,
        String name,
        String qualifiedName,
        Attributes attributes) throws SAXException {

      if (capturing != null) {
        throw new SAXException(
            "Nested element inside report identity field");
      }

      path.add(name);

      if (path.size() > 64) {
        throw new SAXException("XML nesting limit exceeded");
      }

      if (!NAMESPACE.equals(uri)) {
        throw new SAXException("Unexpected FDR1 report namespace");
      }

      if (path.size() == 1 && !ROOT.equals(name)) {
        throw new SAXException("Expected FlussoRiversamento");
      }

      if (path.size() == 2 && PAYMENT.equals(name)) {
        observedPayments++;
      }

      if (RELEVANT_NAMES.contains(name)) {
        inspectField();
      }
    }

    private void inspectField() throws SAXException {
      String key = String.join("/", path);

      if (UNIQUE_CONTAINERS.contains(key)
          && !seenContainers.add(key)) {

        throw new SAXException(
            "Duplicate report identity section");
      }

      Field field = FIELDS.get(key);

      if (field == null) {
        return;
      }

      if (values.containsKey(field)) {
        throw new SAXException(
            "Duplicate report identity field");
      }

      capturing = field;
      text = new StringBuilder();
    }

    @Override
    public void characters(
        char[] characters,
        int start,
        int length) throws SAXException {

      if (capturing != null) {
        if (length > 512 - text.length()) {
          throw new SAXException(
              "Report identity field length limit exceeded");
        }

        text.append(characters, start, length);
      }
    }

    @Override
    public void endElement(
        String uri,
        String name,
        String qualifiedName) {

      if (capturing != null) {
        values.put(capturing, text.toString().strip());
        capturing = null;
        text = null;
      }

      path.remove(path.size() - 1);
    }

    @Override
    public InputSource resolveEntity(
        String publicId,
        String systemId) throws SAXException {

      throw new SAXException("External entities are not allowed");
    }

    private ReportIdentity identity() throws IOException {
      String date = required(Field.DATE);

      LocalDateTime.parse(date, DateTimeFormatter.ISO_DATE_TIME);

      long declared =
          Long.parseLong(required(Field.DECLARED_COUNT));

      if (declared < 0) {
        throw new IOException("Negative declared payment count");
      }

      return new ReportIdentity(
          required(Field.NAME),
          date,
          required(Field.SENDER_TYPE),
          required(Field.SENDER_ID),
          required(Field.RECEIVER_TYPE),
          required(Field.RECEIVER_ID),
          declared,
          observedPayments);
    }

    private String required(Field field) throws IOException {
      String value = values.get(field);

      if (value == null || value.isBlank()) {
        throw new IOException(
            "Missing report identity field: " + field);
      }

      return value;
    }
  }
}