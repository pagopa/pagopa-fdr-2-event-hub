package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryPaymentContentMatcherTest {

  private final RecoveryPaymentContentMatcher matcher =
      new RecoveryPaymentContentMatcher();

  private enum Difference {
    IUV,
    IUR,
    AMOUNT,
    OUTCOME,
    IDSP,
    PAYMENT_DAY
  }

  @Test
  void shouldMatchRegardlessOfOrderAmountScaleAndPublicationMetadata() {
    var first = payment("iuv-1", "230.73");
    var second = payment("iuv-2", "10.00");

    var firstCopy = payment("iuv-1", "230.730");
    firstCopy.setUniqueId("different-session");
    firstCopy.setInsertedTimestamp(
        LocalDate.of(2026, 11, 12).atStartOfDay());

    assertTrue(
        matcher.matches(
            Stream.of(first, second),
            Stream.of(payment("iuv-2", "10"), firstCopy)));
  }

  @ParameterizedTest
  @EnumSource(Difference.class)
  void shouldRejectDifferentPaymentContent(Difference difference) {
    var reference = payment("iuv-1", "230.73");
    var candidate = payment("iuv-1", "230.73");

    switch (difference) {
      case IUV -> candidate.setIuv("different-iuv");
      case IUR -> candidate.setIur("different-iur");
      case AMOUNT -> candidate.setAmount(new BigDecimal("230.74"));
      case OUTCOME -> candidate.setOutcomeCode(3);
      case IDSP -> candidate.setIdsp("2");
      case PAYMENT_DAY ->
          candidate.setSinglePaymentOutcomeDate(
              LocalDate.of(2026, 10, 15).atStartOfDay());
    }

    assertFalse(
        matcher.matches(
            Stream.of(reference),
            Stream.of(candidate)));
  }

  @Test
  void shouldRejectDifferentDuplicateCountsWithSameTotalCount() {
    assertFalse(
        matcher.matches(
            Stream.of(
                payment("iuv-1", "10"),
                payment("iuv-1", "10"),
                payment("iuv-2", "10")),
            Stream.of(
                payment("iuv-1", "10"),
                payment("iuv-2", "10"),
                payment("iuv-2", "10"))));
  }

  @Test
  void shouldMatchEqualDuplicates() {
    assertTrue(
        matcher.matches(
            Stream.of(
                payment("iuv-1", "10"),
                payment("iuv-1", "10")),
            Stream.of(
                payment("iuv-1", "10.00"),
                payment("iuv-1", "10.0"))));
  }

  @Test
  void shouldRejectAdditionalPayment() {
    assertFalse(
        matcher.matches(
            Stream.of(payment("iuv-1", "10")),
            Stream.of(
                payment("iuv-1", "10"),
                payment("iuv-2", "20"))));
  }

  @Test
  void shouldRejectMissingPayment() {
    assertFalse(
        matcher.matches(
            Stream.of(
                payment("iuv-1", "10"),
                payment("iuv-2", "20")),
            Stream.of(payment("iuv-1", "10"))));
  }

  @Test
  void shouldNotMatchEmptyContents() {
    assertFalse(
        matcher.matches(Stream.empty(), Stream.empty()));
  }

  @Test
  void shouldCloseBothStreamsWhenValidationFails() {
    var referenceClosed = new AtomicBoolean();
    var candidateClosed = new AtomicBoolean();

    var invalid = payment("iuv-1", "10");
    invalid.setAmount(null);

    var reference =
        Stream.of(payment("iuv-1", "10"))
            .onClose(() -> referenceClosed.set(true));

    var candidate =
        Stream.of(invalid)
            .onClose(() -> candidateClosed.set(true));

    assertThrows(
        NullPointerException.class,
        () -> matcher.matches(reference, candidate));

    assertTrue(referenceClosed.get());
    assertTrue(candidateClosed.get());
  }

  private static ReportedIUVEventModel payment(
      String iuv,
      String amount) {

    return ReportedIUVEventModel.builder()
        .iuv(iuv)
        .iur("iur-" + iuv)
        .amount(new BigDecimal(amount))
        .outcomeCode(0)
        .idsp("1")
        .singlePaymentOutcomeDate(
            LocalDate.of(2026, 10, 14).atStartOfDay())
        .flowId("test-flow")
        .domainId("00493410583")
        .psp("ABI88515")
        .uniqueId("original-session")
        .insertedTimestamp(
            LocalDate.of(2026, 10, 15).atStartOfDay())
        .build();
  }
}