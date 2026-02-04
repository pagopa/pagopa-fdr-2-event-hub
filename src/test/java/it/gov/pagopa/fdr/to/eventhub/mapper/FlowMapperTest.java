package it.gov.pagopa.fdr.to.eventhub.mapper;

import static org.junit.jupiter.api.Assertions.*;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Payment;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Receiver;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Sender;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FlowMapperTest {

  @Test
  void toFlowTxEventList_buildsExpectedDatesAndMidnights() {

    Payment p1 = Payment.builder().payDate("2025-03-06T11:01:36+01:00").build();
    Payment p2 = Payment.builder().payDate("2025-03-06").build();
    Payment p3 = Payment.builder().payDate("2025-03-07T00:00:00Z").build();

    Sender sender =
        Sender.builder()
            .pspId("PSP1")
            .pspBrokerId("BROKER1")
            .build();

    Receiver receiver =
        Receiver.builder()
            .organizationId("PA1")
            .build();

    Flow flow =
        Flow.builder()
            .fdr("FDR123")
            .fdrDate(Instant.parse("2025-03-06T10:01:36Z"))
            .published(Instant.parse("2025-03-06T12:34:56Z")) // PIDM-1459: different from fdrDate: must NOT be used
            .regulation("REG")
            .regulationDate("2025-03-06")
            .computedTotPayments(3L)
            .computedSumPayments(new BigDecimal("10.00"))
            .sender(sender)
            .receiver(receiver)
            .metadata(
                Map.of(
                    "sessionId", "S1",
                    "insertedTimestamp", "2025-03-06T11:01:36+01:00"))
            .payments(List.of(p1, p2, p3))
            .build();

    FlowTxEventModel out = FlowMapper.toFlowTxEventList(flow);

    assertEquals(List.of("2025-03-06", "2025-03-07"), out.getAllDates());
    assertTrue(out.getAllDates().stream().allMatch(d -> d.matches("\\d{4}-\\d{2}-\\d{2}")),
    	    "ALL_DATES must be yyyy-MM-dd only, but was: " + out.getAllDates());
    assertEquals(2, out.getAllDates().size(), "Expected distinct dates only");
    assertEquals(LocalDateTime.of(2025, 3, 6, 10, 1, 36), out.getFlowDateTime());
    assertEquals(LocalDateTime.of(2025, 3, 6, 0, 0), out.getRegulationDate());
  }

  @Test
  void toReportedIUVEventStream_setsIdspFromIdTransfer_andMidnightOutcomeDate() {

    Payment payment =
        Payment.builder()
            .iuv("IUV1")
            .iur("IUR1")
            .pay(new BigDecimal("1.00"))
            .payStatus("EXECUTED")
            .idTransfer(5L)
            .payDate("2025-03-06")
            .build();

    Sender sender =
        Sender.builder()
            .pspId("PSP1")
            .pspBrokerId("BROKER1")
            .build();

    Receiver receiver =
        Receiver.builder()
            .organizationId("PA1")
            .build();

    Flow flow =
        Flow.builder()
            .fdr("FDR123")
            .fdrDate(Instant.parse("2025-03-06T10:01:36Z"))
            .sender(sender)
            .receiver(receiver)
            .metadata(
                Map.of(
                    "sessionId", "S1",
                    "insertedTimestamp", "2025-03-06T10:01:36Z"))
            .payments(List.of(payment))
            .build();

    ReportedIUVEventModel out =
        FlowMapper.toReportedIUVEventStream(flow).findFirst().orElseThrow();

    assertEquals("5", out.getIdsp());
    assertEquals(LocalDateTime.of(2025, 3, 6, 0, 0), out.getSinglePaymentOutcomeDate());
    assertEquals(LocalDateTime.of(2025, 3, 6, 10, 1, 36), out.getFlowDateTime());
    assertEquals(LocalDateTime.of(2025, 3, 6, 10, 1, 36), out.getInsertedTimestamp());
  }
}