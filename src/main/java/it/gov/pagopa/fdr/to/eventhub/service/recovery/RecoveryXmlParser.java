package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

final class RecoveryXmlParser {

  private RecoveryXmlParser() {
  }

  static void parse(byte[] xml, DefaultHandler handler)
      throws IOException {

    Objects.requireNonNull(xml, "xml is required");
    Objects.requireNonNull(handler, "handler is required");

    try {
      SAXParserFactory factory =
          SAXParserFactory.newDefaultInstance();

      factory.setNamespaceAware(true);
      factory.setFeature(
          XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature(
          "http://apache.org/xml/features/disallow-doctype-decl",
          true);
      factory.setFeature(
          "http://xml.org/sax/features/external-general-entities",
          false);
      factory.setFeature(
          "http://xml.org/sax/features/external-parameter-entities",
          false);

      SAXParser parser = factory.newSAXParser();

      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

      try (ByteArrayInputStream input =
          new ByteArrayInputStream(xml)) {

        parser.parse(input, handler);
      }

    } catch (ParserConfigurationException | SAXException exception) {
      throw new IOException(
          "Cannot parse recovery XML", exception);
    }
  }
}