package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;

public final class RecoveryFdr1PayloadDecoder {

  private static final String REQUEST =
      "nodoInviaFlussoRendicontazione";

  private static final String REQUEST_NAMESPACE =
      "http://ws.pagamenti.telematici.gov/";

  private static final String PAYLOAD = "xmlRendicontazione";

  private static final Set<String> SOAP_NAMESPACES =
      Set.of(
          "http://schemas.xmlsoap.org/soap/envelope/",
          "http://www.w3.org/2003/05/soap-envelope");

  private final int maxDecodedBytes;

  public RecoveryFdr1PayloadDecoder(int maxDecodedBytes) {
    if (maxDecodedBytes <= 0) {
      throw new IllegalArgumentException(
          "maxDecodedBytes must be positive");
    }

    this.maxDecodedBytes = maxDecodedBytes;
  }

  public byte[] decode(byte[] xml) throws IOException {
    Objects.requireNonNull(xml, "xml is required");

    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    factory.setProperty(XMLInputFactory.IS_COALESCING, false);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(
        XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(
        XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);

    factory.setXMLResolver(
        (publicId, systemId, baseUri, namespace) -> {
          throw new XMLStreamException(
              "External entities are not allowed");
        });

    try (ByteArrayInputStream input = new ByteArrayInputStream(xml);
        XmlResource resource =
            new XmlResource(factory.createXMLStreamReader(input))) {

      ScanState state = new ScanState();
      XMLStreamReader parser = resource.parser();

      while (parser.hasNext()) {
          switch (parser.next()) {
          case XMLStreamConstants.START_ELEMENT -> state.start(parser);
          case XMLStreamConstants.END_ELEMENT -> state.end();
          case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
              throw new IOException("DTD and entity references are not allowed");
          default -> {
              // Other XML events carry no envelope structure to validate here.
          }
          }
      }

      return state.result();

    } catch (XMLStreamException exception) {
      throw new IOException(
          "Cannot read FDR1 Base64 payload", exception);
    }
  }

  private final class ScanState {

      private final List<String> path = new ArrayList<>();

      private String soapNamespace;
      private int bodies;
      private int requests;
      private int payloads;
      private byte[] content;

      private boolean requestPath() {
          return path.size() >= 3 && "Body".equals(path.get(1)) && REQUEST.equals(path.get(2));
      }

      private void start(XMLStreamReader parser) throws IOException {

          path.add(parser.getLocalName());

          if (path.size() > 64) {
              throw new IOException("XML nesting limit exceeded");
          }

          validateStructure(parser);

          if (path.size() == 4 && requestPath() && PAYLOAD.equals(parser.getLocalName())) {

              if (++payloads > 1) {
                  throw new IOException("Duplicate FDR1 payload");
              }

              String namespace = parser.getNamespaceURI();

              if (namespace != null && !namespace.isEmpty() && !REQUEST_NAMESPACE.equals(namespace)) {

                  throw new IOException("Unexpected payload namespace");
              }

              content = decodeElement(parser);

              // decodeElement consumes the payload's closing element.
              end();
          }
      }

      private void validateStructure(XMLStreamReader parser) throws IOException {

          String name = parser.getLocalName();
          String namespace = parser.getNamespaceURI();

          if (path.size() == 1) {
              if (!"Envelope".equals(name) || namespace == null || !SOAP_NAMESPACES.contains(namespace)) {

                  throw new IOException("Expected SOAP Envelope");
              }

              soapNamespace = namespace;
          }

          if (path.size() == 2 && "Body".equals(name) && (!soapNamespace.equals(namespace) || ++bodies > 1)) {

              throw new IOException("Expected one SOAP Body");
          }

          if (path.size() == 3 && requestPath() && (!REQUEST_NAMESPACE.equals(namespace) || ++requests > 1)) {

              throw new IOException("Expected one FDR1 request");
          }
      }

      private void end() {
          path.remove(path.size() - 1);
      }

      private byte[] result() throws IOException {
          if (bodies != 1 || requests != 1 || payloads != 1 || content == null) {

              throw new IOException("Missing FDR1 payload");
          }

          return content;
      }

      private byte[] decodeElement(XMLStreamReader parser) throws IOException {

          ElementInputStream encoded = new ElementInputStream(parser);

          try (InputStream decoded = Base64.getDecoder().wrap(encoded)) {

              ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(8192, maxDecodedBytes));

              byte[] buffer = new byte[8192];

              while (true) {
                  int remaining = maxDecodedBytes - output.size();

                  int requested = (int) Math.min(buffer.length, (long) remaining + 1);

                  int read = decoded.read(buffer, 0, requested);

                  if (read == -1) {
                      break;
                  }

                  if (read > remaining) {
                      throw new RecoveryContentSizeException(
                          RecoveryContentSizeException.Stage.INNER_XML,
                          maxDecodedBytes,
                          (long) output.size() + read);
                  }

                  output.write(buffer, 0, read);
              }

              if (encoded.read() != -1) {
                  throw new IOException("Unexpected content after Base64 padding");
              }

              return output.toByteArray();
          }
      }
  }

  private record XmlResource(XMLStreamReader parser)
      implements AutoCloseable {

    @Override
    public void close() throws XMLStreamException {
      parser.close();
    }
  }

  private static final class ElementInputStream
      extends InputStream {

    private final XMLStreamReader parser;

    private int position;
    private int end;
    private boolean finished;

    private ElementInputStream(XMLStreamReader parser) {
      this.parser = parser;
    }

    @Override
    public int read() throws IOException {
      while (true) {
        if (position < end) {
          char value = parser.getTextCharacters()[position++];

          if (value == ' '
              || value == '\t'
              || value == '\r'
              || value == '\n') {

            continue;
          }

          if (value > 127) {
            throw new IOException(
                "Non-ASCII character in Base64 payload");
          }

          return value;
        }

        if (finished) {
          return -1;
        }

        advance();
      }
    }

    private void advance() throws IOException {
        try {
            if (!parser.hasNext()) {
                throw new IOException("Incomplete Base64 element");
            }

            switch (parser.next()) {
            case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {

                position = parser.getTextStart();
                end = position + parser.getTextLength();
            }

            case XMLStreamConstants.END_ELEMENT -> {
                if (!PAYLOAD.equals(parser.getLocalName())) {
                    throw new IOException("Unexpected Base64 element end");
                }

                finished = true;
            }

            case XMLStreamConstants.START_ELEMENT, XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE,
                    XMLStreamConstants.END_DOCUMENT ->
                throw new IOException("Unexpected XML content inside Base64 payload");

            default -> {
                // Comments and processing instructions contribute no Base64 characters.
            }
            }

        } catch (XMLStreamException exception) {
            throw new IOException("Cannot read Base64 element", exception);
        }
    }

    @Override
    public int read(byte[] bytes, int offset, int length)
        throws IOException {

      Objects.checkFromIndexSize(offset, length, bytes.length);

      int count = 0;

      while (count < length) {
        int value = read();

        if (value == -1) {
          return count == 0 ? -1 : count;
        }

        bytes[offset + count++] = (byte) value;
      }

      return count;
    }
  }
}