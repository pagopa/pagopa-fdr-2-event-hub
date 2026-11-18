package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class RecoveryPaymentContentMatcher {

  /**
   * Compares complete payment contents, ignoring order and publication metadata.
   * Both streams are consumed and closed.
   * Empty contents do not establish a match.
   */
  public boolean matches(
      Stream<ReportedIUVEventModel> reference,
      Stream<ReportedIUVEventModel> candidate) {

    Objects.requireNonNull(reference, "reference is required");
    Objects.requireNonNull(candidate, "candidate is required");

    try (reference; candidate) {
      Map<PaymentKey, Long> remaining = new HashMap<>();

      var referenceIterator = reference.iterator();

      while (referenceIterator.hasNext()) {
        PaymentKey key = paymentKey(referenceIterator.next());
        remaining.merge(key, 1L, Math::addExact);
      }

      boolean nonEmptyReference = !remaining.isEmpty();
      boolean unexpectedPayment = false;

      var candidateIterator = candidate.iterator();

      while (candidateIterator.hasNext()) {
        PaymentKey key = paymentKey(candidateIterator.next());
        Long occurrences = remaining.get(key);

        if (occurrences == null) {
          unexpectedPayment = true;
        } else if (occurrences == 1L) {
          remaining.remove(key);
        } else {
          remaining.put(key, occurrences - 1L);
        }
      }

      return nonEmptyReference
          && !unexpectedPayment
          && remaining.isEmpty();
    }
  }

  private static PaymentKey paymentKey(ReportedIUVEventModel event) {
    Objects.requireNonNull(event, "payment is required");

    var paymentDate =
        Objects.requireNonNull(
            event.getSinglePaymentOutcomeDate(),
            "payment date is required");

    if (!paymentDate.equals(paymentDate.toLocalDate().atStartOfDay())) {
      throw new IllegalArgumentException(
          "Mapped payment date must be normalized to midnight");
    }

    return new PaymentKey(
        requiredText(event.getIuv(), "IUV"),
        requiredText(event.getIur(), "IUR"),
        Objects.requireNonNull(
                event.getAmount(), "payment amount is required")
            .stripTrailingZeros(),
        Objects.requireNonNull(
            event.getOutcomeCode(), "payment outcome is required"),
        event.getIdsp(),
        paymentDate.toLocalDate());
  }

  private static String requiredText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }

    return value;
  }

  private record PaymentKey(
      String iuv,
      String iur,
      BigDecimal amount,
      Integer outcomeCode,
      String idsp,
      LocalDate paymentDay) {
  }
}