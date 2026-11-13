package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr3IdentityReaderTest {

  private final RecoveryFdr3IdentityReader reader =
      new RecoveryFdr3IdentityReader();

  private static String json(String date) {
    return """
        {
          "fdr": "flow-1",
          "revision": 1,
          "fdrDate": %s,
          "sender": {
            "id": "different-sender-id",
            "pspId": "PSP-A"
          },
          "receiver": {
            "id": "different-receiver-id",
            "organizationId": "ORG-A"
          },
          "payments": [
            {"index": 1, "iuv": "iuv-1", "other": [1, 2]},
            {"index": 2, "iuv": "iuv-2"}
          ],
          "unknown": {"nested": true}
        }
        """.formatted(date);
  }

  private RecoveryFdr3IdentityReader.Identity read(String json)
      throws IOException {

    return reader.read(json.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @CsvSource({
      "1791204626, 1791204626, 0",
      "1791204626.001, 1791204626, 1000000",
      "-0.1, -1, 900000000"
  })
  void shouldReadNumericDatesAndCorrectPartyIdentifiers(
      String date,
      long seconds,
      int nanos) throws IOException {

    assertEquals(
        new RecoveryFdr3IdentityReader.Identity(
            "flow-1",
            1L,
            "PSP-A",
            "ORG-A",
            Instant.ofEpochSecond(seconds, nanos)),
        read(json(date)));
  }

  @Test
  void shouldReadIsoInstant() throws IOException {
    String date = "2026-10-05T12:50:26.001234Z";

    assertEquals(
        Instant.parse(date),
        read(json("\"" + date + "\"")).flowDate());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "fdr", "revision", "fdrDate", "sender", "receiver"
  })
  void shouldRejectMissingIdentityFields(String field)
      throws IOException {

    ObjectMapper mapper = new ObjectMapper();
    ObjectNode document =
        (ObjectNode) mapper.readTree(json("1791204626.001"));

    document.remove(field);
    String incomplete = mapper.writeValueAsString(document);

    assertThrows(
        IOException.class,
        () -> read(incomplete));
  }

  @ParameterizedTest
  @CsvSource({
      "pspId, sender",
      "organizationId, receiver"
  })
  void shouldRequireDomainIdentifiers(
      String identifier,
      String objectName) throws IOException {

    ObjectMapper mapper = new ObjectMapper();
    ObjectNode document =
        (ObjectNode) mapper.readTree(json("1791204626.001"));

    ((ObjectNode) document.get(objectName)).remove(identifier);
    String incomplete = mapper.writeValueAsString(document);

    assertThrows(
        IOException.class,
        () -> read(incomplete));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "0", "-1", "1.5", "\"1\"", "9223372036854775808"
  })
  void shouldRejectInvalidRevision(String revision) {
    String document =
        json("1791204626.001")
            .replace(
                "\"revision\": 1,",
                "\"revision\": " + revision + ",");

    assertThrows(
        IOException.class,
        () -> read(document));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "null", "true", "{}", "\"not-a-date\"",
      "1791204626.0000000001"
  })
  void shouldRejectInvalidDate(String date) {
    String document = json(date);

    assertThrows(
        IOException.class,
        () -> read(document));
  }

  @Test
  void shouldRejectDuplicateRootFields() {
    String document =
        json("1791204626.001")
            .replace(
                "\"revision\": 1,",
                "\"revision\": 1, \"revision\": 2,");

    assertThrows(
        IOException.class,
        () -> read(document));
  }

  @Test
  void shouldRejectDuplicatePartyIdentifiers() {
    String document =
        json("1791204626.001")
            .replace(
                "\"pspId\": \"PSP-A\"",
                "\"pspId\": \"PSP-A\", \"pspId\": \"PSP-B\"");

    assertThrows(
        IOException.class,
        () -> read(document));
  }

  @Test
  void shouldRejectAdditionalJsonDocument() {
    String document = json("1791204626.001") + "{}";

    assertThrows(
        IOException.class,
        () -> read(document));
  }

  @Test
  void shouldValidateSyntaxInsideSkippedPayments() {
    String document =
        json("1791204626.001")
            .replace("\"other\": [1, 2]", "\"other\": [1, }");

    assertThrows(
        IOException.class,
        () -> read(document));
  }

  @Test
  void shouldRejectNullInput() {
    assertThrows(
        NullPointerException.class,
        () -> reader.read(null));
  }
}