package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;

class RecoveryFdr1PayloadDecoderTest {

  private final RecoveryFdr1PayloadDecoder decoder =
      new RecoveryFdr1PayloadDecoder(1024);

  private static String soap(String payload) {
    return """
        <s:Envelope
            xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
          <s:Body>
            <n:nodoInviaFlussoRendicontazione
                xmlns:n="http://ws.pagamenti.telematici.gov/">
              <xmlRendicontazione>%s</xmlRendicontazione>
            </n:nodoInviaFlussoRendicontazione>
          </s:Body>
        </s:Envelope>
        """.formatted(payload);
  }

  private byte[] decode(String xml) throws IOException {
    return decoder.decode(xml.getBytes(StandardCharsets.UTF_8));
  }

  private static String encoded(String value) {
    return Base64.getEncoder().encodeToString(
        value.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "", "a", "ab", "abc", "abcd", "<FlussoRiversamento/>"
  })
  void shouldDecodePayloadAcrossBase64Boundaries(String value)
      throws IOException {

    assertArrayEquals(
        value.getBytes(StandardCharsets.UTF_8),
        decode(soap(encoded(value))));
  }

  @Test
  void shouldAcceptXmlWhitespaceInsideBase64() throws IOException {
    assertArrayEquals(
        new byte[] {1, 2, 3},
        decode(soap(" A Q I D \r\n\t")));
  }

  @Test
  void shouldAcceptCdata() throws IOException {
    assertArrayEquals(
        new byte[] {1, 2, 3},
        decode(soap("<![CDATA[AQID]]>")));
  }

  @Test
  void shouldAcceptExactDecodedLimit() throws IOException {
    RecoveryFdr1PayloadDecoder limited =
        new RecoveryFdr1PayloadDecoder(3);

    byte[] xml =
        soap("AQID").getBytes(StandardCharsets.UTF_8);

    assertArrayEquals(
        new byte[] {1, 2, 3},
        limited.decode(xml));
  }

  @Test
  void shouldRejectDecodedContentAboveLimit() {
    RecoveryFdr1PayloadDecoder limited =
        new RecoveryFdr1PayloadDecoder(2);

    byte[] xml =
        soap("AQID").getBytes(StandardCharsets.UTF_8);

    var failure =
        assertThrows(
            RecoveryContentSizeException.class,
            () -> limited.decode(xml));

    assertEquals(
        RecoveryContentSizeException.Stage.INNER_XML,
        failure.stage());

    assertEquals(2, failure.limitBytes());
    assertEquals(3, failure.observedBytesAtLeast());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "@@@", "A", "AA=A", "AA==AAAA", "AA==!", "AQ\u00a0=="
  })
  void shouldRejectInvalidBase64OrTrailingContent(String value) {
    String xml = soap(value);

    assertThrows(IOException.class, () -> decode(xml));
  }

  @Test
  void shouldRejectMissingPayload() {
    String xml =
        soap("AQID")
            .replace(
                "<xmlRendicontazione>AQID</xmlRendicontazione>",
                "");

    assertThrows(IOException.class, () -> decode(xml));
  }

  @Test
  void shouldRejectDuplicatePayload() {
    String xml =
        soap("AQID")
            .replace(
                "</xmlRendicontazione>",
                """
                </xmlRendicontazione>
                <xmlRendicontazione>AQID</xmlRendicontazione>
                """);

    assertThrows(IOException.class, () -> decode(xml));
  }

  @Test
  void shouldIgnorePayloadInSoapHeader() throws IOException {
    String xml =
        soap("AQID")
            .replace(
                "<s:Body>",
                """
                <s:Header>
                  <xmlRendicontazione>invalid-header-data</xmlRendicontazione>
                </s:Header>
                <s:Body>
                """);

    assertArrayEquals(new byte[] {1, 2, 3}, decode(xml));
  }

  @Test
  void shouldRejectNestedPayloadElements() {
    String xml = soap("<nested>AQID</nested>");

    assertThrows(IOException.class, () -> decode(xml));
  }

  @Test
  void shouldRejectDoctype() {
    String xml =
        """
        <!DOCTYPE s:Envelope [
          <!ENTITY external SYSTEM "file:///must-not-be-read">
        ]>
        """
        + soap("AQID");

    assertThrows(IOException.class, () -> decode(xml));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void shouldRejectInvalidLimit(int limit) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryFdr1PayloadDecoder(limit));
  }

  @Test
  void shouldRejectNullInput() {
    assertThrows(
        NullPointerException.class,
        () -> decoder.decode(null));
  }
}