package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;

public final class RecoveryFdr3IdentityReader {

  private static final JsonFactory JSON_FACTORY =
      JsonFactory.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  public record Identity(
      String name,
      long revision,
      String pspDomainId,
      String orgDomainId,
      Instant flowDate) {
  }

  public Identity read(byte[] json) throws IOException {
    Objects.requireNonNull(json, "json is required");

    try (JsonParser parser = JSON_FACTORY.createParser(json)) {
      requireToken(parser.nextToken(), JsonToken.START_OBJECT);

      String name = null;
      Long revision = null;
      String pspDomainId = null;
      String orgDomainId = null;
      Instant flowDate = null;

      while (parser.nextToken() != JsonToken.END_OBJECT) {
        requireToken(parser.currentToken(), JsonToken.FIELD_NAME);

        String field = parser.currentName();
        parser.nextToken();

        switch (field) {
          case "fdr" ->
              name = readText(parser, "fdr");
          case "revision" ->
              revision = readRevision(parser);
          case "sender" ->
              pspDomainId =
                  readIdentifier(parser, "sender", "pspId");
          case "receiver" ->
              orgDomainId =
                  readIdentifier(
                      parser, "receiver", "organizationId");
          case "fdrDate" ->
              flowDate = readDate(parser);
          default ->
              parser.skipChildren();
        }
      }

      if (parser.nextToken() != null) {
        throw new IOException(
            "Unexpected content after the FDR3 JSON document");
      }

      return new Identity(
          required(name, "fdr"),
          required(revision, "revision"),
          required(pspDomainId, "sender.pspId"),
          required(orgDomainId, "receiver.organizationId"),
          required(flowDate, "fdrDate"));
    }
  }

  private static String readIdentifier(
      JsonParser parser,
      String objectName,
      String identifierName) throws IOException {

    requireToken(parser.currentToken(), JsonToken.START_OBJECT);

    String identifier = null;

    while (parser.nextToken() != JsonToken.END_OBJECT) {
      requireToken(parser.currentToken(), JsonToken.FIELD_NAME);

      String field = parser.currentName();
      parser.nextToken();

      if (identifierName.equals(field)) {
        identifier =
            readText(parser, objectName + "." + identifierName);
      } else {
        parser.skipChildren();
      }
    }

    return required(
        identifier, objectName + "." + identifierName);
  }

  private static String readText(
      JsonParser parser,
      String field) throws IOException {

    requireToken(parser.currentToken(), JsonToken.VALUE_STRING);

    String value = parser.getText();

    if (value.isBlank()) {
      throw new IOException("Blank identity field: " + field);
    }

    return value;
  }

  private static long readRevision(JsonParser parser)
      throws IOException {

    requireToken(
        parser.currentToken(), JsonToken.VALUE_NUMBER_INT);

    long revision = parser.getLongValue();

    if (revision <= 0) {
      throw new IOException("revision must be positive");
    }

    return revision;
  }

  private static Instant readDate(JsonParser parser)
      throws IOException {

    try {
      if (parser.currentToken() == JsonToken.VALUE_STRING) {
        return Instant.parse(parser.getText());
      }

      if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT
          || parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {

        BigDecimal epochSeconds = parser.getDecimalValue();

        long seconds =
            epochSeconds.setScale(0, RoundingMode.FLOOR)
                .longValueExact();

        int nanos =
            epochSeconds.subtract(BigDecimal.valueOf(seconds))
                .movePointRight(9)
                .intValueExact();

        return Instant.ofEpochSecond(seconds, nanos);
      }

      throw new IOException(
          "fdrDate must be an ISO instant or numeric epoch seconds");

    } catch (DateTimeException | ArithmeticException exception) {
      throw new IOException("Invalid fdrDate", exception);
    }
  }

  private static void requireToken(
      JsonToken actual,
      JsonToken expected) throws IOException {

    if (actual != expected) {
      throw new IOException("Expected JSON token: " + expected);
    }
  }

  private static <T> T required(T value, String field)
      throws IOException {

    if (value == null) {
      throw new IOException("Missing identity field: " + field);
    }

    return value;
  }
}