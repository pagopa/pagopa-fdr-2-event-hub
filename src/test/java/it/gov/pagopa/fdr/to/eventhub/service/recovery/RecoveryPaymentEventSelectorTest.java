package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gov.pagopa.fdr.to.eventhub.mapper.FlowMapper;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.DatiSingoloPagamento;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRiversamento;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Payment;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Receiver;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Sender;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryPaymentEventSelectorTest {

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 5);

  private static final String FLOW_NAME = "recovery-flow";
  private static final String PSP = "88888888888";
  private static final String ORGANIZATION = "15376371009";

  private static final String SESSION_ID =
      "a92570e7-d5f5-49da-bb8f-0226e2d95ac7";

  private static final Instant FLOW_DATE =
      Instant.parse("2026-10-05T12:50:26.001454Z");

  private static final String INSERTED_TIMESTAMP =
      "2026-10-05T12:51:00Z";

  private final RecoveryPaymentEventSelector selector =
      new RecoveryPaymentEventSelector();

  @ParameterizedTest
  @EnumSource(RecoveryPublicationSource.SourceType.class)
  void shouldSelectOnlyRequestedDayFromExistingMappers(
      RecoveryPublicationSource.SourceType type) {

    var source = source(type);

    try (var selected =
        selector.select(DAY, source, mappedEvents(type))) {

      var events = selected.toList();

      assertEquals(
          List.of("IUV-1", "IUV-2"),
          events.stream()
              .map(ReportedIUVEventModel::getIuv)
              .toList());

      for (var event : events) {
        assertEquals(DAY.atStartOfDay(),
            event.getSinglePaymentOutcomeDate());

        assertEquals(SESSION_ID, event.getUniqueId());

        assertEquals(
            LocalDateTime.ofInstant(FLOW_DATE, ZoneOffset.UTC),
            event.getFlowDateTime());

        assertEquals(
            LocalDateTime.of(2026, 10, 5, 12, 51),
            event.getInsertedTimestamp());
      }
    }
  }

  @Test
  void shouldReturnOriginalEventWithoutModifyingIt() {
    var event = event();

    try (var selected =
        selector.select(
            DAY,
            source(RecoveryPublicationSource.SourceType.FDR3_JSON),
            Stream.of(event))) {

      assertSame(event, selected.findFirst().orElseThrow());
      assertEquals(new BigDecimal("12.34"), event.getAmount());
      assertEquals("IUR-1", event.getIur());
      assertEquals("2", event.getIdsp());
      assertEquals(Integer.valueOf(0), event.getOutcomeCode());
    }
  }

  @Test
  void shouldAcceptSessionCaseWithoutChangingOriginalValue() {
    var event = event();
    String originalSession =
        SESSION_ID.toUpperCase(Locale.ROOT);

    event.setUniqueId(originalSession);

    try (var selected =
        selector.select(
            DAY,
            source(RecoveryPublicationSource.SourceType.FDR3_JSON),
            Stream.of(event))) {

      var result = selected.findFirst().orElseThrow();

      assertEquals(originalSession, result.getUniqueId());
    }
  }

  @Test
  void shouldEvaluateLazilyAndCloseUpstream() {
    var evaluated = new AtomicInteger();
    var closed = new AtomicBoolean();

    Stream<ReportedIUVEventModel> upstream =
        Stream.of(event())
            .peek(ignored -> evaluated.incrementAndGet())
            .onClose(() -> closed.set(true));

    try (var selected =
        selector.select(
            DAY,
            source(RecoveryPublicationSource.SourceType.FDR3_JSON),
            upstream)) {

      assertEquals(0, evaluated.get());
      assertEquals(1, selected.toList().size());
      assertEquals(1, evaluated.get());
    }

    assertTrue(closed.get());
  }

  @Test
  void shouldReturnEmptyStreamWhenDayHasNoPayments() {
    var event = event();
    event.setSinglePaymentOutcomeDate(
        DAY.plusDays(1).atStartOfDay());

    try (var selected =
        selector.select(
            DAY,
            source(RecoveryPublicationSource.SourceType.FDR3_JSON),
            Stream.of(event))) {

      assertTrue(selected.toList().isEmpty());
    }
  }

  @Test
  void shouldAcceptEmptyInput() {
    try (var selected =
        selector.select(
            DAY,
            source(RecoveryPublicationSource.SourceType.FDR3_JSON),
            Stream.empty())) {

      assertTrue(selected.toList().isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "FLOW",
      "PSP",
      "ORGANIZATION",
      "SESSION",
      "FLOW_DATE",
      "PAYMENT_DATE_MISSING",
      "PAYMENT_DATE_NOT_NORMALIZED"
  })
  void shouldRejectInvalidSelectedEvent(String invalidField) {
    var event = event();

    switch (invalidField) {
      case "FLOW" -> event.setFlowId("another-flow");
      case "PSP" -> event.setPsp("another-psp");
      case "ORGANIZATION" -> event.setDomainId("another-organization");
      case "SESSION" -> event.setUniqueId(
          "11111111-1111-4111-8111-111111111111");
      case "FLOW_DATE" -> event.setFlowDateTime(
          event.getFlowDateTime().plusNanos(1));
      case "PAYMENT_DATE_MISSING" ->
          event.setSinglePaymentOutcomeDate(null);
      case "PAYMENT_DATE_NOT_NORMALIZED" ->
          event.setSinglePaymentOutcomeDate(
              DAY.atTime(12, 30));
      default -> throw new IllegalArgumentException(
          "Unknown test scenario");
    }

    try (var selected =
        selector.select(
            DAY,
            source(RecoveryPublicationSource.SourceType.FDR3_JSON),
            Stream.of(event))) {

      assertThrows(
          IllegalArgumentException.class,
          selected::toList);
    }
  }

  @Test
  void shouldRejectNullEvent() {
    Stream<ReportedIUVEventModel> events =
        Stream.of((ReportedIUVEventModel) null);

    try (var selected =
        selector.select(
            DAY,
            source(RecoveryPublicationSource.SourceType.FDR3_JSON),
            events)) {

      assertThrows(
          IllegalArgumentException.class,
          selected::toList);
    }
  }

  private static Stream<ReportedIUVEventModel> mappedEvents(
      RecoveryPublicationSource.SourceType type) {

    List<String> paymentDates =
        List.of(
            "2026-10-04",
            "2026-10-05T00:00:00+02:00",
            "2026-10-05T23:59:59Z",
            "2026-10-06");

    if (type == RecoveryPublicationSource.SourceType.FDR3_JSON) {
      var flow =
          Flow.builder()
              .fdr(FLOW_NAME)
              .fdrDate(FLOW_DATE)
              .revision(1L)
              .sender(
                  Sender.builder()
                      .pspId(PSP)
                      .pspBrokerId("broker")
                      .build())
              .receiver(
                  Receiver.builder()
                      .organizationId(ORGANIZATION)
                      .build())
              .metadata(metadata())
              .payments(
                  java.util.stream.IntStream
                      .range(0, paymentDates.size())
                      .mapToObj(
                          index ->
                              Payment.builder()
                                  .iuv("IUV-" + index)
                                  .iur("IUR-" + index)
                                  .pay(BigDecimal.ONE)
                                  .payStatus("EXECUTED")
                                  .idTransfer(1L)
                                  .payDate(paymentDates.get(index))
                                  .build())
                      .toList())
              .build();

      return FlowMapper.toReportedIUVEventStream(flow);
    }

    var report =
        FlussoRiversamento.builder()
            .identificativoFlusso(FLOW_NAME)
            .dataOraFlusso(FLOW_DATE.toString())
            .datiSingoliPagamenti(
                java.util.stream.IntStream
                    .range(0, paymentDates.size())
                    .mapToObj(
                        index ->
                            DatiSingoloPagamento.builder()
                                .identificativoUnivocoVersamento(
                                    "IUV-" + index)
                                .identificativoUnivocoRiscossione(
                                    "IUR-" + index)
                                .singoloImportoPagato(1.0)
                                .codiceEsitoSingoloPagamento(0)
                                .indiceDatiSingoloPagamento("1")
                                .dataEsitoSingoloPagamento(
                                    paymentDates.get(index))
                                .build())
                    .toList())
            .build();

    var flow =
        FlussoRendicontazione.builder()
            .identificativoFlusso(FLOW_NAME)
            .identificativoPSP(PSP)
            .identificativoIntermediarioPSP("broker")
            .identificativoDominio(ORGANIZATION)
            .flussoRiversamento(report)
            .metadata(metadata())
            .build();

    return FlussoRendicontazioneMapper
        .toReportedIUVEventStream(flow);
  }

  private static ReportedIUVEventModel event() {
    return ReportedIUVEventModel.builder()
        .iuv("IUV-1")
        .iur("IUR-1")
        .amount(new BigDecimal("12.34"))
        .outcomeCode(0)
        .idsp("2")
        .singlePaymentOutcomeDate(DAY.atStartOfDay())
        .flowId(FLOW_NAME)
        .flowDateTime(
            LocalDateTime.ofInstant(FLOW_DATE, ZoneOffset.UTC))
        .domainId(ORGANIZATION)
        .psp(PSP)
        .intPsp("broker")
        .uniqueId(SESSION_ID)
        .insertedTimestamp(
            LocalDateTime.of(2026, 10, 5, 12, 51))
        .build();
  }

  private static Map<String, String> metadata() {
    return Map.of(
        "sessionId", SESSION_ID,
        "insertedTimestamp", INSERTED_TIMESTAMP);
  }

  private static RecoveryPublicationSource source(
      RecoveryPublicationSource.SourceType type) {

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
            type.containerName(),
            "original-blob",
            "\"original-etag\"",
            100L,
            metadata()),
        type,
        SESSION_ID,
        FLOW_DATE);
  }
}