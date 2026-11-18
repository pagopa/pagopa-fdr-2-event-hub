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

class RecoveryFdr1ReportReaderTest {

  private static final String DATE =
      "2026-10-09T01:00:06.208475643";

  private final RecoveryFdr1ReportReader reader =
      new RecoveryFdr1ReportReader();

  private static String report(String date) {
    return """
        <FlussoRiversamento
            xmlns="http://www.digitpa.gov.it/schemas/2011/Pagamenti/">
          <identificativoFlusso>flow-1</identificativoFlusso>
          <dataOraFlusso>%s</dataOraFlusso>
          <istitutoMittente>
            <identificativoUnivocoMittente>
              <tipoIdentificativoUnivoco>B</tipoIdentificativoUnivoco>
              <codiceIdentificativoUnivoco>PSP-INNER</codiceIdentificativoUnivoco>
            </identificativoUnivocoMittente>
          </istitutoMittente>
          <istitutoRicevente>
            <identificativoUnivocoRicevente>
              <tipoIdentificativoUnivoco>G</tipoIdentificativoUnivoco>
              <codiceIdentificativoUnivoco>ORG-INNER</codiceIdentificativoUnivoco>
            </identificativoUnivocoRicevente>
          </istitutoRicevente>
          <numeroTotalePagamenti>1</numeroTotalePagamenti>
          <datiSingoliPagamenti/>
        </FlussoRiversamento>
        """.formatted(date);
  }

  private RecoveryFdr1ReportReader.ReportIdentity read(String xml)
      throws IOException {

    return reader.read(xml.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "2026-10-09T01:00:06.208475643",
      "2026-10-09T01:00:06.208475643+02:00",
      "2026-10-08T23:00:06.208475643Z"
  })
  void shouldReadIdentityAndCountsPreservingDate(String date)
      throws IOException {

    assertEquals(
        new RecoveryFdr1ReportReader.ReportIdentity(
            "flow-1", date,
            "B", "PSP-INNER",
            "G", "ORG-INNER",
            1L, 1L),
        read(report(date)));
  }

  @Test
  void shouldStripSurroundingWhitespace() throws IOException {
    String xml =
        report(DATE).replace("PSP-INNER", " \n PSP-INNER \n ");

    assertEquals("PSP-INNER", read(xml).senderId());
  }

  @Test
  void shouldAcceptNamespacePrefix() throws IOException {
    String xml =
        report(DATE)
            .replace(
                "<FlussoRiversamento",
                """
                <p:FlussoRiversamento
                    xmlns:p="http://www.digitpa.gov.it/schemas/2011/Pagamenti/"
                """)
            .replace(
                "</FlussoRiversamento>",
                "</p:FlussoRiversamento>");

    assertEquals("flow-1", read(xml).name());
  }

  @ParameterizedTest
  @CsvSource({
      "identificativoFlusso, flow-1",
      "dataOraFlusso, 2026-10-09T01:00:06.208475643",
      "tipoIdentificativoUnivoco, B",
      "codiceIdentificativoUnivoco, PSP-INNER",
      "tipoIdentificativoUnivoco, G",
      "codiceIdentificativoUnivoco, ORG-INNER",
      "numeroTotalePagamenti, 1"
  })
  void shouldRejectMissingField(String field, String value) {
    String element = "<" + field + ">" + value + "</" + field + ">";
    String xml = report(DATE).replace(element, "");

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectDuplicateIdentityField() {
    String xml =
        report(DATE)
            .replace(
                "</identificativoFlusso>",
                """
                </identificativoFlusso>
                <identificativoFlusso>other-flow</identificativoFlusso>
                """);

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectDuplicateIdentitySection() {
    String xml =
        report(DATE)
            .replace(
                "</istitutoMittente>",
                "</istitutoMittente><istitutoMittente/>");

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldExposeDifferentDeclaredAndObservedCounts()
      throws IOException {

    String xml =
        report(DATE)
            .replace(
                "<numeroTotalePagamenti>1</numeroTotalePagamenti>",
                "<numeroTotalePagamenti>2</numeroTotalePagamenti>");

    RecoveryFdr1ReportReader.ReportIdentity identity = read(xml);

    assertEquals(2L, identity.declaredPaymentCount());
    assertEquals(1L, identity.observedPaymentCount());
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "x", "1.5"})
  void shouldRejectInvalidDeclaredCount(String value) {
    String xml =
        report(DATE)
            .replace(
                "<numeroTotalePagamenti>1</numeroTotalePagamenti>",
                "<numeroTotalePagamenti>" + value
                    + "</numeroTotalePagamenti>");

    assertThrows(IOException.class, () -> read(xml));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "not-a-date", "2026-02-30T10:00:00", "2026-10-09"
  })
  void shouldRejectInvalidDate(String date) {
    String xml = report(date);

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectUnexpectedNamespace() {
    String xml =
        report(DATE)
            .replace(
                "http://www.digitpa.gov.it/schemas/2011/Pagamenti/",
                "urn:unexpected");

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectNestedIdentityContent() {
    String xml =
        report(DATE).replace("PSP-INNER", "<nested/>");

    assertThrows(IOException.class, () -> read(xml));
  }

  @Test
  void shouldRejectDoctype() {
    String xml =
        """
        <!DOCTYPE FlussoRiversamento [
          <!ENTITY external SYSTEM "file:///must-not-be-read">
        ]>
        """
        + report(DATE).replace("flow-1", "&external;");

    IOException failure =
        assertThrows(IOException.class, () -> read(xml));

    assertNotNull(failure.getCause());
    assertTrue(failure.getCause().getMessage().contains("DOCTYPE"));
  }

  @Test
  void shouldRejectNullInput() {
    assertThrows(
        NullPointerException.class,
        () -> reader.read(null));
  }

  @Test
  void shouldReportZeroPayments() throws IOException {
    String xml =
        report(DATE)
            .replace(
                "<numeroTotalePagamenti>1</numeroTotalePagamenti>",
                "<numeroTotalePagamenti>0</numeroTotalePagamenti>")
            .replace("<datiSingoliPagamenti/>", "");

    RecoveryFdr1ReportReader.ReportIdentity identity = read(xml);

    assertEquals(0L, identity.declaredPaymentCount());
    assertEquals(0L, identity.observedPaymentCount());
  }

  @Test
  void shouldIgnoreIdentityNamesInsidePayments()
      throws IOException {

    String xml =
        report(DATE)
            .replace(
                "<datiSingoliPagamenti/>",
                """
                <datiSingoliPagamenti>
                  <identificativoFlusso>other-flow</identificativoFlusso>
                </datiSingoliPagamenti>
                """);

    assertEquals("flow-1", read(xml).name());
  }
}