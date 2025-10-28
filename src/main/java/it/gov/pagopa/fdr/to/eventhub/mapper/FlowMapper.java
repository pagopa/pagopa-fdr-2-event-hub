package it.gov.pagopa.fdr.to.eventhub.mapper;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Payment;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.UtilityClass;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;

@UtilityClass
public class FlowMapper {

  private static final ModelMapper modelMapper = new ModelMapper();
  private static final String TIME_ZONE_REGEX = "([+\\-]\\d{2}:\\d{2}|Z)$";
  private static final Pattern pattern = Pattern.compile(TIME_ZONE_REGEX);
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
          .optionalEnd()
          .optionalStart()
          .appendPattern("XXX")
          .optionalEnd()
          .toFormatter();
  @Getter @Setter private static int maxDistinctDates = 110;

  static {
    modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
  }

  public static LocalDateTime parseDate(String dateStr) {
    if (dateStr == null || dateStr.isEmpty()) {
      return null;
    }
    try {
      Matcher matcher = pattern.matcher(dateStr);

      if (matcher.find()) {
        // Parsing as ZonedDateTime and adjust to UTC+1
        ZonedDateTime zonedDateTime = ZonedDateTime.parse(dateStr, DATE_TIME_FORMATTER);
        ZonedDateTime adjustedDateTime = zonedDateTime.withZoneSameInstant(ZoneOffset.ofHours(1));
        return adjustedDateTime.toLocalDateTime();
      } else {
        return LocalDateTime.parse(dateStr, DATE_TIME_FORMATTER);
      }
    } catch (DateTimeParseException e1) {
      try {
        return LocalDateTime.parse(dateStr + "T00:00:00", DATE_TIME_FORMATTER);
      } catch (DateTimeParseException e2) {
        throw new IllegalArgumentException("Date format not supported: " + dateStr);
      }
    }
  }

  /**
   * Converts FlussoRendicontazione into a list of FlowTxEventModel.
   *
   * @param flusso to convert.
   * @return List of FlowTxEventModel.
   */
  public static FlowTxEventModel toFlowTxEventList(Flow flusso) {

    List<String> allDates = flusso.getPayments().stream().map(Payment::getPayDate).distinct().toList();

    // last fake date as alert if there are more than 'this.maxDistinctDates' dates
    if (allDates.size() > maxDistinctDates) {
      allDates = allDates.stream().limit(maxDistinctDates).collect(Collectors.toList());
      allDates.add("9999-12-31");
    }

    return FlowTxEventModel.builder()
        .flowId(flusso.getFdr())
        .flowDateTime(parseDate(flusso.getPublished().toString()))
        .regulationDate(parseDate(flusso.getRegulationDate()))
        .paymentsNum(Math.toIntExact(flusso.getComputedTotPayments()))
        .amountPaid(flusso.getComputedSumPayments())
        .domainId(flusso.getReceiver().getOrganizationId())
        .intPsp(flusso.getSender().getPspBrokerId())
        .uniqueId(flusso.getMetadata().get("sessionId"))
        .insertedTimestamp(parseDate(flusso.getMetadata().get("insertedTimestamp")))
        .psp(flusso.getSender().getPspId())
        .causal(flusso.getRegulation())
        .allDates(allDates)
        .build();
  }

  /**
   * Converts Flow into a list of ReportedIUVEventModel.
   *
   * @param flusso to convert.
   * @return List of ReportedIUVEventModel.
   */
  public static List<ReportedIUVEventModel> toReportedIUVEventList(Flow flusso) {
    return flusso.getPayments().stream()
        .map(
            singoloPagamento ->
                ReportedIUVEventModel.builder()
                    .iuv(singoloPagamento.getIuv())
                    .idTransfer(singoloPagamento.getIdTransfer())
                    .iur(singoloPagamento.getIur())
                    .amount(singoloPagamento.getPay())
                    .outcomeCode(convertPayStatus(singoloPagamento.getPayStatus()))
                    .idsp(
                        singoloPagamento.getIndex() != null
                            ? singoloPagamento.getIndex().toString()
                            : null)
                    .singlePaymentOutcomeDate(parseDate(singoloPagamento.getPayDate()))
                    .flowId(flusso.getFdr())
                    .flowDateTime(parseDate(flusso.getFdrDate().toString()))
                    .domainId(flusso.getReceiver().getOrganizationId())
                    .intPsp(flusso.getSender().getPspBrokerId())
                    .uniqueId(flusso.getMetadata().get("sessionId"))
                    .insertedTimestamp(parseDate(flusso.getMetadata().get("insertedTimestamp")))
                    .psp(flusso.getSender().getPspId())
                    .build())
        .toList();
  }

  public static Stream<ReportedIUVEventModel> toReportedIUVEventStream(Flow flusso) {
    return flusso.getPayments().stream()
            .map(
                    singoloPagamento ->
                            ReportedIUVEventModel.builder()
                                    .iuv(singoloPagamento.getIuv())
                                    .idTransfer(singoloPagamento.getIdTransfer())
                                    .iur(singoloPagamento.getIur())
                                    .amount(singoloPagamento.getPay())
                                    .outcomeCode(convertPayStatus(singoloPagamento.getPayStatus()))
                                    .idsp(
                                            singoloPagamento.getIndex() != null
                                                    ? singoloPagamento.getIndex().toString()
                                                    : null)
                                    .singlePaymentOutcomeDate(parseDate(singoloPagamento.getPayDate()))
                                    .flowId(flusso.getFdr())
                                    .flowDateTime(parseDate(flusso.getFdrDate().toString()))
                                    .domainId(flusso.getReceiver().getOrganizationId())
                                    .intPsp(flusso.getSender().getPspBrokerId())
                                    .uniqueId(flusso.getMetadata().get("sessionId"))
                                    .insertedTimestamp(parseDate(flusso.getMetadata().get("insertedTimestamp")))
                                    .psp(flusso.getSender().getPspId())
                                    .build());
  }

  public static Integer convertPayStatus(String payStatus) {
    Integer outcomeCode = null;
    if (payStatus != null) {
      outcomeCode =
          switch (payStatus) {
            case "EXECUTED" -> 0;
            case "REVOKED" -> 3;
            case "STAND_IN" -> 4;
            case "STAND_IN_NO_RPT" -> 8;
            case "NO_RPT" -> 9;
            default -> outcomeCode;
          };
    }
    return outcomeCode;
  }
}
