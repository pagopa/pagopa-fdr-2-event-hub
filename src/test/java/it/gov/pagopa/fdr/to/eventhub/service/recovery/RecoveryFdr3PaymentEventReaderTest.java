package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryBlobContentRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr3PaymentEventReaderTest {

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 5);

  private static final Instant FLOW_DATE =
      Instant.parse("2026-10-05T12:50:26.001454Z");

  private static final String SESSION_ID =
      "a92570e7-d5f5-49da-bb8f-0226e2d95ac7";

  private static final String INSERTED_TIMESTAMP =
      "insertedTimestamp";

  private static final String FLOW_NAME = "recovery-flow";
  private static final String PSP = "88888888888";
  private static final String ORGANIZATION = "15376371009";

  private static final String JSON = """
      {
        "fdr":"recovery-flow",
        "revision":1,
        "fdrDate":%s,
        "sender":{"pspId":"88888888888","pspBrokerId":"broker"},
        "receiver":{"organizationId":"15376371009"},
        "payments":[
          {
            "iuv":"IUV-1","iur":"IUR-1","pay":12.34,
            "payStatus":"EXECUTED","idTransfer":2,
            "payDate":"2026-10-05T00:00:00+02:00"
          },
          {
            "iuv":"IUV-2","iur":"IUR-2","pay":5.00,
            "payStatus":"EXECUTED","idTransfer":1,
            "payDate":"2026-10-05"
          },
          {
            "iuv":"IUV-3","iur":"IUR-3","pay":7.00,
            "payStatus":"EXECUTED","idTransfer":1,
            "payDate":"2026-10-06"
          }
        ]
      }
      """;

  private final RecoveryBlobContentRepository content =
      mock(RecoveryBlobContentRepository.class);

  private final RecoveryFdr3PaymentEventReader reader =
      reader(64 * 1024);

  @Test
  void shouldDownloadSnapshotAndPrepareOnlyRequestedDay()
      throws Exception {

    var source = source(metadata());
    stubJson(source, json());

    try (var events = reader.read(DAY, source)) {
      var result = events.toList();

      assertEquals(
          List.of("IUV-1", "IUV-2"),
          result.stream().map(event -> event.getIuv()).toList());

      var first = result.get(0);

      assertEquals(new BigDecimal("12.34"), first.getAmount());
      assertEquals("IUR-1", first.getIur());
      assertEquals("2", first.getIdsp());
      assertEquals(SESSION_ID, first.getUniqueId());
      assertEquals(DAY.atStartOfDay(),
          first.getSinglePaymentOutcomeDate());

      assertEquals(
          LocalDateTime.ofInstant(FLOW_DATE, ZoneOffset.UTC),
          first.getFlowDateTime());

      assertEquals(
          LocalDateTime.of(2026, 10, 5, 12, 51),
          first.getInsertedTimestamp());
    }

    verify(content).download(source.blob());
  }

  @Test
  void shouldPreserveNumericEpochSecondsPrecision()
      throws Exception {

    var source = source(metadata());

    BigDecimal epoch =
        BigDecimal.valueOf(FLOW_DATE.getEpochSecond())
            .add(BigDecimal.valueOf(FLOW_DATE.getNano(), 9));

    stubJson(source, JSON.formatted(epoch.toPlainString()));

    try (var events = reader.read(DAY, source)) {
      assertEquals(
          LocalDateTime.ofInstant(FLOW_DATE, ZoneOffset.UTC),
          events.findFirst().orElseThrow().getFlowDateTime());
    }
  }

  @Test
  void shouldSupportMetadataKeyCaseAndPreserveOriginalMap()
      throws Exception {

    Map<String, String> metadata =
        Map.of(
            "SESSIONID", SESSION_ID,
            "SERVICEIDENTIFIER", "FDR03",
            "ELABORATE", "false",
            "INSERTEDTIMESTAMP", "2026-10-05T14:51:00+02:00");

    var source = source(metadata);
    stubJson(source, json());

    try (var events = reader.read(DAY, source)) {
      var first = events.findFirst().orElseThrow();

      assertEquals(SESSION_ID, first.getUniqueId());
      assertEquals(
          LocalDateTime.of(2026, 10, 5, 12, 51),
          first.getInsertedTimestamp());
    }

    assertEquals(metadata, source.blob().metadata());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "MISSING_TIMESTAMP",
      "AMBIGUOUS_TIMESTAMP",
      "INVALID_TIMESTAMP",
      "WRONG_SESSION"
  })
  void shouldRejectInvalidMetadataBeforeDownload(String scenario) {
    var metadata = new HashMap<>(metadata());

    switch (scenario) {
      case "MISSING_TIMESTAMP" ->
          metadata.remove(INSERTED_TIMESTAMP);
      case "AMBIGUOUS_TIMESTAMP" ->
          metadata.put(
              "INSERTEDTIMESTAMP", "2026-10-05T12:51:00Z");
      case "INVALID_TIMESTAMP" ->
          metadata.put(INSERTED_TIMESTAMP, "invalid-date");
      case "WRONG_SESSION" ->
          metadata.put(
              "sessionId",
              "11111111-1111-4111-8111-111111111111");
      default -> throw new IllegalArgumentException(
          "Unknown test scenario");
    }

    var source = source(metadata);

    assertThrows(IOException.class, () -> reader.read(DAY, source));

    verifyNoInteractions(content);
  }

  @Test
  void shouldRejectContentFromAnotherRevision() throws Exception {
    var source = source(metadata());

    stubJson(source, json().replace("\"revision\":1", "\"revision\":2"));

    assertThrows(IOException.class, () -> reader.read(DAY, source));
  }

  @Test
  void shouldPropagateDownloadFailureWithoutRefreshingSnapshot() throws RecoveryContentSizeException {
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
  void shouldEnforceDecompressedLimit() throws Exception {
    var source = source(metadata());
    stubJson(source, json());

    var limitedReader = reader(32);

    assertThrows(
        IOException.class,
        () -> limitedReader.read(DAY, source));
  }

  @Test
  void shouldRejectXmlSourceBeforeDownload() {
    var jsonSource = source(metadata());

    var xmlSource =
        new RecoveryPublicationSource(
            jsonSource.candidate(),
            new RecoveryBlobSnapshot(
                "fdr1-flows",
                "original.xml.zip",
                "\"original-etag\"",
                100L,
                metadata()),
            RecoveryPublicationSource.SourceType.FDR1_XML,
            SESSION_ID,
            FLOW_DATE);

    assertThrows(
        IllegalArgumentException.class,
        () -> reader.read(DAY, xmlSource));

    verifyNoInteractions(content);
  }

  @Test
  void shouldReadAllPaymentDaysFromConvertedJson() throws Exception {
    var metadata = new HashMap<>(metadata());
    metadata.put("elaborate", "false");

    var source = source(metadata);
    stubJson(source, json());

    try (var events = reader.readAll(source)) {
      var result = events.toList();

      assertEquals(
          List.of("IUV-1", "IUV-2", "IUV-3"),
          result.stream().map(event -> event.getIuv()).toList());

      assertEquals(
          List.of(DAY, DAY, DAY.plusDays(1)),
          result.stream()
              .map(event -> event.getSinglePaymentOutcomeDate().toLocalDate())
              .toList());

      assertEquals(SESSION_ID, result.get(2).getUniqueId());
    }

    verify(content).download(source.blob());
  }

  @Test
  void shouldRejectAnotherRevisionWhenReadingAllPayments()
      throws Exception {

    var source = source(metadata());
    stubJson(source, json().replace("\"revision\":1", "\"revision\":2"));

    assertThrows(
        IOException.class,
        () -> reader.readAll(source));
  }

  @Test
  void shouldValidateMetadataBeforeReadingAllPayments() {
    var metadata = new HashMap<>(metadata());
    metadata.remove(INSERTED_TIMESTAMP);

    var source = source(metadata);

    assertThrows(
        IOException.class,
        () -> reader.readAll(source));

    verifyNoInteractions(content);
  }

  private RecoveryFdr3PaymentEventReader reader(int limit) {
    return new RecoveryFdr3PaymentEventReader(
        content,
        new RecoveryBlobDecompressor(limit),
        new RecoveryPaymentEventSelector());
  }

  private void stubJson(
      RecoveryPublicationSource source,
      String json) throws IOException {

    byte[] compressed = gzip(json);

    when(content.download(source.blob())).thenReturn(compressed);
  }

  private static byte[] gzip(String value) throws IOException {
    var output = new ByteArrayOutputStream();

    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(value.getBytes(StandardCharsets.UTF_8));
    }

    return output.toByteArray();
  }

  private static String json() {
    return JSON.formatted("\"" + FLOW_DATE + "\"");
  }

  private static Map<String, String> metadata() {
    return Map.of(
        "sessionId", SESSION_ID,
        "serviceIdentifier", "FDR03",
        "elaborate", "true",
        INSERTED_TIMESTAMP, "2026-10-05T12:51:00Z");
  }

  private static RecoveryPublicationSource source(
      Map<String, String> metadata) {

    var candidate =
        new RecoveryFlowCandidate(
            1L,
            FLOW_NAME,
            1L,
            PSP,
            ORGANIZATION,
            LocalDateTime.ofInstant(FLOW_DATE, ZoneOffset.UTC));

    return new RecoveryPublicationSource(
        candidate,
        new RecoveryBlobSnapshot(
            "fdr3-flows",
            "original.json.zip",
            "\"original-etag\"",
            100L,
            metadata),
        RecoveryPublicationSource.SourceType.FDR3_JSON,
        SESSION_ID,
        FLOW_DATE);
  }
}