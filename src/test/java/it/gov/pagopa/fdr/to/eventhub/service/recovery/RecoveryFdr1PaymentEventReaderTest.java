package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryBlobContentRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr1PaymentEventReaderTest {

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 8);

  private static final String FLOW_NAME = "recovery-flow";
  private static final String SESSION_ID =
      "6ecc100f-cd56-422d-815b-8243edc81a84";

  private static final String OUTER_DATE =
      "2026-10-09T01:00:06.208475";

  private static final String INNER_DATE =
      "2026-10-09T01:00:06.208475643";

  private static final Instant EVENT_DATE =
      Instant.parse("2026-10-08T23:00:06.208475643Z");

  private static final String INNER = """
      <FlussoRiversamento
          xmlns="http://www.digitpa.gov.it/schemas/2011/Pagamenti/">
        <versioneOggetto>1.0</versioneOggetto>
        <identificativoFlusso>recovery-flow</identificativoFlusso>
        <dataOraFlusso>%s</dataOraFlusso>
        <identificativoUnivocoRegolamento>regulation</identificativoUnivocoRegolamento>
        <dataRegolamento>2026-10-09</dataRegolamento>
        <istitutoMittente>
          <identificativoUnivocoMittente>
            <tipoIdentificativoUnivoco>A</tipoIdentificativoUnivoco>
            <codiceIdentificativoUnivoco>SELBIT2B</codiceIdentificativoUnivoco>
          </identificativoUnivocoMittente>
        </istitutoMittente>
        <istitutoRicevente>
          <identificativoUnivocoRicevente>
            <tipoIdentificativoUnivoco>G</tipoIdentificativoUnivoco>
            <codiceIdentificativoUnivoco>APPBIT2B</codiceIdentificativoUnivoco>
          </identificativoUnivocoRicevente>
        </istitutoRicevente>
        <numeroTotalePagamenti>3</numeroTotalePagamenti>
        <importoTotalePagamenti>3.00</importoTotalePagamenti>
        %s
      </FlussoRiversamento>
      """;

  private final RecoveryBlobContentRepository content =
      mock(RecoveryBlobContentRepository.class);

  private final RecoveryFdr1PaymentEventReader reader =
      reader(64 * 1024, 64 * 1024);

  @Test
  void shouldPrepareOnlyRequestedDayWithOriginalIdentity()
      throws Exception {

    var source = source(metadata());
    stubXml(source, outer(inner()));

    try (var events = reader.read(DAY, source)) {
      var result = events.toList();

      assertEquals(
          List.of("IUV-1", "IUV-2"),
          result.stream().map(event -> event.getIuv()).toList());

      var first = result.get(0);

      assertEquals(SESSION_ID, first.getUniqueId());
      assertEquals("88888888888", first.getPsp());
      assertEquals("15376371009", first.getDomainId());
      assertEquals("broker", first.getIntPsp());

      assertEquals(
          LocalDateTime.ofInstant(EVENT_DATE, ZoneOffset.UTC),
          first.getFlowDateTime());

      assertEquals(
          DAY.atStartOfDay(),
          first.getSinglePaymentOutcomeDate());

      assertEquals(
          LocalDateTime.of(2026, 10, 9, 8, 0),
          first.getInsertedTimestamp());
    }

    verify(content).download(source.blob());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "WRONG_NAME",
      "WRONG_COUNT",
      "WRONG_EVENT_DATE"
  })
  void shouldRejectInconsistentReport(String scenario)
      throws Exception {

    var source = source(metadata());
    String report = inner();

    report = switch (scenario) {
      case "WRONG_NAME" ->
          report.replace(FLOW_NAME, "another-flow");
      case "WRONG_COUNT" ->
          report.replace(
              "<numeroTotalePagamenti>3",
              "<numeroTotalePagamenti>4");
      case "WRONG_EVENT_DATE" ->
          report.replace(
              INNER_DATE,
              "2026-10-09T01:00:07");
      default -> throw new IllegalArgumentException(
          "Unknown test scenario");
    };

    stubXml(source, outer(report));

    assertThrows(IOException.class, () -> reader.read(DAY, source));
  }

  @Test
  void shouldRejectOversizedInnerXml() throws Exception {
    var source = source(metadata());
    stubXml(source, outer(inner()));

    var limitedReader = reader(64 * 1024, 32);

    assertThrows(
        IOException.class,
        () -> limitedReader.read(DAY, source));
  }

  @Test
  void shouldRejectOversizedDecompressedContent() throws Exception {
    var source = source(metadata());
    stubXml(source, outer(inner()));

    var limitedReader = reader(32, 64 * 1024);

    assertThrows(
        IOException.class,
        () -> limitedReader.read(DAY, source));
  }

  @Test
  void shouldRejectMissingTimestampBeforeDownload() {
    var source =
        source(
            Map.of(
                "sessionId", SESSION_ID,
                "serviceIdentifier", "FDR001",
                "elaborate", "true"));

    assertThrows(IOException.class, () -> reader.read(DAY, source));

    verifyNoInteractions(content);
  }

  @Test
  void shouldPropagateDownloadFailure() {
    var source = source(metadata());
    var failure = new IllegalStateException("Download rejected");

    when(content.download(source.blob())).thenThrow(failure);

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> reader.read(DAY, source));

    assertSame(failure, actual);
    verify(content).download(source.blob());
  }

  @Test
  void shouldRejectJsonSourceBeforeDownload() {
    var xmlSource = source(metadata());

    var jsonSource =
        new RecoveryPublicationSource(
            xmlSource.candidate(),
            new RecoveryBlobSnapshot(
                "fdr3-flows",
                "original.json.zip",
                "\"original-etag\"",
                100L,
                metadata()),
            RecoveryPublicationSource.SourceType.FDR3_JSON,
            SESSION_ID,
            EVENT_DATE);

    assertThrows(
        IllegalArgumentException.class,
        () -> reader.read(DAY, jsonSource));

    verifyNoInteractions(content);
  }

  private RecoveryFdr1PaymentEventReader reader(
      int decompressedLimit,
      int innerLimit) {

    return new RecoveryFdr1PaymentEventReader(
        content,
        new RecoveryBlobDecompressor(decompressedLimit),
        new RecoveryFdr1PayloadDecoder(innerLimit),
        new RecoveryPaymentEventSelector());
  }

  private void stubXml(
      RecoveryPublicationSource source,
      String xml) throws IOException {

    byte[] compressed = gzip(xml);

    when(content.download(source.blob())).thenReturn(compressed);
  }

  private static String inner() {
    String payments =
        payment("IUV-1", "2026-10-08T00:00:00+02:00")
            + payment("IUV-2", "2026-10-08")
            + payment("IUV-3", "2026-10-09");

    return INNER.formatted(INNER_DATE, payments);
  }

  private static String payment(String iuv, String day) {
    return """
        <datiSingoliPagamenti>
          <identificativoUnivocoVersamento>%s</identificativoUnivocoVersamento>
          <identificativoUnivocoRiscossione>IUR</identificativoUnivocoRiscossione>
          <singoloImportoPagato>1.00</singoloImportoPagato>
          <codiceEsitoSingoloPagamento>0</codiceEsitoSingoloPagamento>
          <dataEsitoSingoloPagamento>%s</dataEsitoSingoloPagamento>
          <indiceDatiSingoloPagamento>1</indiceDatiSingoloPagamento>
        </datiSingoliPagamenti>
        """.formatted(iuv, day);
  }

  private static String outer(String inner) {
    String encoded =
        Base64.getEncoder().encodeToString(
            inner.getBytes(StandardCharsets.UTF_8));

    return """
        <soap:Envelope
            xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
            xmlns:n="http://ws.pagamenti.telematici.gov/">
          <soap:Body>
            <n:nodoInviaFlussoRendicontazione>
              <identificativoPSP>88888888888</identificativoPSP>
              <identificativoIntermediarioPSP>broker</identificativoIntermediarioPSP>
              <identificativoDominio>15376371009</identificativoDominio>
              <identificativoFlusso>recovery-flow</identificativoFlusso>
              <dataOraFlusso>%s</dataOraFlusso>
              <xmlRendicontazione>%s</xmlRendicontazione>
            </n:nodoInviaFlussoRendicontazione>
          </soap:Body>
        </soap:Envelope>
        """.formatted(OUTER_DATE, encoded);
  }

  private static byte[] gzip(String value) throws IOException {
    var output = new ByteArrayOutputStream();

    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(value.getBytes(StandardCharsets.UTF_8));
    }

    return output.toByteArray();
  }

  private static Map<String, String> metadata() {
    return Map.of(
        "sessionId", SESSION_ID,
        "serviceIdentifier", "FDR001",
        "elaborate", "true",
        "insertedTimestamp", "2026-10-09T10:00:00+02:00");
  }

  private static RecoveryPublicationSource source(
      Map<String, String> metadata) {

    var candidate =
        new RecoveryFlowCandidate(
            1L,
            FLOW_NAME,
            1L,
            "88888888888",
            "15376371009",
            LocalDateTime.parse("2026-10-08T23:00:06.208"));

    return new RecoveryPublicationSource(
        candidate,
        new RecoveryBlobSnapshot(
            "fdr1-flows",
            "original.xml.zip",
            "\"original-etag\"",
            100L,
            metadata),
        RecoveryPublicationSource.SourceType.FDR1_XML,
        SESSION_ID,
        EVENT_DATE);
  }
}