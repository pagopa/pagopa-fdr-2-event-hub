package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr1EnvelopeReaderTest {

  private static final String DATE =
      "2026-10-09T01:00:06.208475643";

  private final RecoveryFdr1EnvelopeReader reader =
      new RecoveryFdr1EnvelopeReader();

  private static String request(String date) {
    return """
        <n:nodoInviaFlussoRendicontazione
            xmlns:n="http://ws.pagamenti.telematici.gov/">
          <identificativoPSP>PSP-A</identificativoPSP>
          <identificativoDominio>ORG-A</identificativoDominio>
          <identificativoFlusso>flow-1</identificativoFlusso>
          <dataOraFlusso>%s</dataOraFlusso>
          <xmlRendicontazione>AA==</xmlRendicontazione>
        </n:nodoInviaFlussoRendicontazione>
        """.formatted(date);
  }

  private static String soap(String request) {
    return """
        <s:Envelope
            xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
          <s:Body>%s</s:Body>
        </s:Envelope>
        """.formatted(request);
  }

  private RecoveryFdr1EnvelopeReader.RequestIdentity read(
      String xml) throws IOException {

    return reader.read(xml.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "2026-10-09T01:00:06.208475643",
      "2026-10-09T01:00:06.208475643+02:00",
      "2026-10-08T23:00:06.208475643Z"
  })
  void shouldReadOuterIdentityPreservingDate(String date)
      throws IOException {

    assertEquals(
        new RecoveryFdr1EnvelopeReader.RequestIdentity(
            "flow-1", "PSP-A", "ORG-A", date),
        read(soap(request(date))));
  }

  @Test
  void shouldStripSurroundingFieldWhitespace()
      throws IOException {

    String xml =
        soap(request(DATE))
            .replace("PSP-A", " \n PSP-A \n ");

    assertEquals("PSP-A", read(xml).pspDomainId());
  }

  @Test
  void shouldIgnoreIdentifiersInSoapHeader() throws IOException {
    String xml =
        soap(request(DATE))
            .replace(
                "<s:Body>",
                """
                <s:Header>
                  <identificativoPSP>OTHER</identificativoPSP>
                </s:Header>
                <s:Body>
                """);

    assertEquals("PSP-A", read(xml).pspDomainId());
  }

  @ParameterizedTest
  @CsvSource({
      "identificativoPSP, PSP-A",
      "identificativoDominio, ORG-A",
      "identificativoFlusso, flow-1",
      "dataOraFlusso, 2026-10-09T01:00:06.208475643"
  })
  void shouldRejectMissingField(String field, String value) {
    String element = "<" + field + ">" + value + "</" + field + ">";
    String xml = soap(request(DATE).replace(element, ""));

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectDuplicateIdentityField() {
    String xml =
        soap(request(DATE))
            .replace(
                "</identificativoPSP>",
                """
                </identificativoPSP>
                <identificativoPSP>OTHER</identificativoPSP>
                """);

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectNestedContentInsideIdentityField() {
    String xml =
        soap(request(DATE))
            .replace("PSP-A", "<nested>PSP-A</nested>");

    assertThrows(IOException.class, () -> read(xml));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "not-a-date",
      "2026-02-30T10:00:00",
      "2026-10-09"
  })
  void shouldRejectInvalidDate(String date) {
    String xml = soap(request(date));

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectDoctypeAndExternalEntities() {
    String xml =
        """
        <!DOCTYPE s:Envelope [
          <!ENTITY external SYSTEM "file:///must-not-be-read">
        ]>
        """
        + soap(request(DATE).replace("PSP-A", "&external;"));

    IOException failure =
        assertThrows(IOException.class, () -> read(xml));

    assertNotNull(failure.getCause());
    assertTrue(failure.getCause().getMessage().contains("DOCTYPE"));
  }

  @Test
  void shouldRejectOversizedIdentityField() {
    String xml =
        soap(request(DATE))
            .replace("PSP-A", "x".repeat(513));

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectMultipleRequests() {
    String xml = soap(request(DATE) + request(DATE));

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectMissingPayloadElement() {
    String xml =
        soap(request(DATE))
            .replace(
                "<xmlRendicontazione>AA==</xmlRendicontazione>",
                "");

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectUnexpectedSoapNamespace() {
    String xml =
        soap(request(DATE))
            .replace(
                "http://schemas.xmlsoap.org/soap/envelope/",
                "urn:unexpected");

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectNullInput() {
    assertThrows(
        NullPointerException.class,
        () -> reader.read(null));
  }
}