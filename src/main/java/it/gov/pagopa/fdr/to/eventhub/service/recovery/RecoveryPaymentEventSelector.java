package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.stream.Stream;

public final class RecoveryPaymentEventSelector {

  /**
   * Selects payment events for one business day.
   *
   * The caller must close the returned stream. Closing it also closes
   * the upstream stream.
   *
   * Validation is lazy: callers must consume the stream to validate
   * the selected events.
   */
  public Stream<ReportedIUVEventModel> select(
      LocalDate paymentDay,
      RecoveryPublicationSource source,
      Stream<ReportedIUVEventModel> events) {

    Objects.requireNonNull(paymentDay, "paymentDay is required");
    Objects.requireNonNull(source, "source is required");
    Objects.requireNonNull(events, "events is required");

    LocalDateTime expectedFlowDate =
        LocalDateTime.ofInstant(
            source.eventFlowDate(), ZoneOffset.UTC);

    return events.filter(
        event ->
            selectEvent(
                event,
                paymentDay,
                source,
                expectedFlowDate));
  }

  private static boolean selectEvent(
      ReportedIUVEventModel event,
      LocalDate paymentDay,
      RecoveryPublicationSource source,
      LocalDateTime expectedFlowDate) {

    if (event == null) {
      throw new IllegalArgumentException(
          "Payment event must not be null");
    }

    LocalDateTime outcomeDate =
        event.getSinglePaymentOutcomeDate();

    if (outcomeDate == null) {
      throw new IllegalArgumentException(
          "Payment event business date is required");
    }

    if (!outcomeDate.equals(
        outcomeDate.toLocalDate().atStartOfDay())) {

      throw new IllegalArgumentException(
          "Payment event business date must be normalized to midnight");
    }

    if (!paymentDay.equals(outcomeDate.toLocalDate())) {
      return false;
    }

    validatePublication(event, source, expectedFlowDate);

    return true;
  }

  private static void validatePublication(
      ReportedIUVEventModel event,
      RecoveryPublicationSource source,
      LocalDateTime expectedFlowDate) {

    var candidate = source.candidate();

    if (!candidate.name().equals(event.getFlowId())) {
      throw new IllegalArgumentException(
          "Payment event flow name differs from the selected source");
    }

    if (!candidate.pspDomainId().equals(event.getPsp())) {
      throw new IllegalArgumentException(
          "Payment event PSP differs from the selected source");
    }

    if (!candidate.orgDomainId().equals(event.getDomainId())) {
      throw new IllegalArgumentException(
          "Payment event organization differs from the selected source");
    }

    if (!source.sessionId().equalsIgnoreCase(event.getUniqueId())) {
      throw new IllegalArgumentException(
          "Payment event session differs from the selected source");
    }

    if (!expectedFlowDate.equals(event.getFlowDateTime())) {
      throw new IllegalArgumentException(
          "Payment event flow date differs from the selected source");
    }
  }
}