package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public final class RecoveryFdr1EnvelopeReader {

  private static final String REQUEST =
      "nodoInviaFlussoRendicontazione";

  private static final String REQUEST_NAMESPACE =
      "http://ws.pagamenti.telematici.gov/";

  private static final Set<String> SOAP_NAMESPACES =
      Set.of(
          "http://schemas.xmlsoap.org/soap/envelope/",
          "http://www.w3.org/2003/05/soap-envelope");

  private static final String FLOW_NAME = "identificativoFlusso";
  private static final String PSP = "identificativoPSP";
  private static final String ORGANIZATION = "identificativoDominio";
  private static final String FLOW_DATE = "dataOraFlusso";
  private static final String PAYLOAD = "xmlRendicontazione";

  private static final Set<String> IDENTITY_FIELDS =
      Set.of(FLOW_NAME, PSP, ORGANIZATION, FLOW_DATE);

  private static final int MAX_DEPTH = 64;
  private static final int MAX_FIELD_CHARACTERS = 512;

  public record RequestIdentity(
      String name,
      String pspDomainId,
      String orgDomainId,
      String flowDate) {
  }

  public RequestIdentity read(byte[] xml) throws IOException {
      Objects.requireNonNull(xml, "xml is required");

      HeaderHandler handler = new HeaderHandler();
      RecoveryXmlParser.parse(xml, handler);

      try {
          return handler.identity();
      } catch (DateTimeException exception) {
          throw new IOException("Invalid FDR1 request identity", exception);
      }
  }

  private static final class HeaderHandler extends DefaultHandler {

    private final List<String> path = new ArrayList<>();
    private final Map<String, String> values = new HashMap<>();

    private String soapNamespace;
    private String capturing;
    private StringBuilder text;

    private int bodies;
    private int requests;
    private int payloads;

    @Override
    public void startElement(
        String uri,
        String localName,
        String qualifiedName,
        Attributes attributes) throws SAXException {

      if (capturing != null) {
        throw new SAXException(
            "Nested element inside identity field");
      }

      path.add(localName);

      if (path.size() > MAX_DEPTH) {
        throw new SAXException("XML nesting limit exceeded");
      }

      validateStructure(uri, localName);

      if (isRequestChild()) {
        startRequestField(uri, localName);
      }
    }

    private void validateStructure(String uri, String name)
        throws SAXException {

      if (path.size() == 1) {
        if (!"Envelope".equals(name)
            || !SOAP_NAMESPACES.contains(uri)) {

          throw new SAXException("Expected SOAP Envelope");
        }

        soapNamespace = uri;
      }

      if (path.size() == 2 && "Body".equals(name)) {
        if (!soapNamespace.equals(uri) || ++bodies > 1) {
          throw new SAXException("Expected one SOAP Body");
        }
      }

      if (path.size() == 3
          && "Body".equals(path.get(1))
          && REQUEST.equals(name)) {

        if (!REQUEST_NAMESPACE.equals(uri) || ++requests > 1) {
          throw new SAXException("Expected one FDR1 request");
        }
      }
    }

    private boolean isRequestChild() {
      return path.size() == 4
          && "Body".equals(path.get(1))
          && REQUEST.equals(path.get(2));
    }

    private void startRequestField(String uri, String name)
        throws SAXException {

      if (PAYLOAD.equals(name)) {
        if (++payloads > 1) {
          throw new SAXException("Duplicate FDR1 payload");
        }

        return;
      }

      if (!IDENTITY_FIELDS.contains(name)) {
        return;
      }

      if ((!uri.isEmpty() && !REQUEST_NAMESPACE.equals(uri))
          || values.containsKey(name)) {

        throw new SAXException(
            "Ambiguous FDR1 identity field: " + name);
      }

      capturing = name;
      text = new StringBuilder();
    }

    @Override
    public void characters(
        char[] characters,
        int start,
        int length) throws SAXException {

      if (capturing != null) {
        if (length > MAX_FIELD_CHARACTERS - text.length()) {
          throw new SAXException(
              "Identity field length limit exceeded");
        }

        text.append(characters, start, length);
      }
    }

    @Override
    public void endElement(
        String uri,
        String localName,
        String qualifiedName) {

      if (capturing != null && capturing.equals(localName)) {
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

    private RequestIdentity identity() throws IOException {
      if (bodies != 1 || requests != 1 || payloads != 1) {
        throw new IOException("Missing FDR1 request or payload");
      }

      String date = required(FLOW_DATE);

      // Validate syntax without assigning a timezone or losing precision.
      LocalDateTime.parse(date, DateTimeFormatter.ISO_DATE_TIME);

      return new RequestIdentity(
          required(FLOW_NAME),
          required(PSP),
          required(ORGANIZATION),
          date);
    }

    private String required(String field) throws IOException {
      String value = values.get(field);

      if (value == null || value.isBlank()) {
        throw new IOException(
            "Missing FDR1 identity field: " + field);
      }

      return value;
    }
  }
}