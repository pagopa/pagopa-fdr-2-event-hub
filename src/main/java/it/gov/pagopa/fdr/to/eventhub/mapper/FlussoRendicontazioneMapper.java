package it.gov.pagopa.fdr.to.eventhub.mapper;

import it.gov.pagopa.fdr.to.eventhub.model.DatiSingoloPagamento;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import java.math.BigDecimal;
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
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.UtilityClass;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;

@UtilityClass
public class FlussoRendicontazioneMapper {

  private static final ModelMapper modelMapper = new ModelMapper();
  private static final String TIME_ZONE_REGEX = "([+\\-]\\d{2}:\\d{2}|Z)$";
  private static final Pattern pattern = Pattern.compile(TIME_ZONE_REGEX);
  @Getter @Setter private static int maxDistinctDates = 110;

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

  static {
    modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
  }

  public static LocalDateTime parseDate(String dateStr) {
    if (dateStr == null || dateStr.isEmpty()) {
      return null;
    }
    try {
      Matcher matcher = pattern.matcher(dateStr);

      if(matcher.find()) {
        // Parsing as ZonedDateTime and adjust to UTC+1
        ZonedDateTime zonedDateTime = ZonedDateTime.parse(dateStr, DATE_TIME_FORMATTER);
        ZonedDateTime adjustedDateTime = zonedDateTime.withZoneSameInstant(ZoneOffset.ofHours(1));
        return adjustedDateTime.toLocalDateTime();
      } else return LocalDateTime.parse(dateStr, DATE_TIME_FORMATTER);
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
  public static FlowTxEventModel toFlowTxEventList(FlussoRendicontazione flusso) {

    List<String> allDates =
        flusso.getFlussoRiversamento().getDatiSingoliPagamenti().stream()
            .map(DatiSingoloPagamento::getDataEsitoSingoloPagamento)
            .distinct()
            .collect(Collectors.toList());

    // last fake date as alert if there are more than 'this.maxDistinctDates'
    // dates
    if (allDates.size() > maxDistinctDates) {
      allDates = allDates.stream().limit(maxDistinctDates).collect(Collectors.toList());
      allDates.add("9999-12-31");
    }

    return FlowTxEventModel.builder()
        .flowId(flusso.getFlussoRiversamento().getIdentificativoFlusso())
        .flowDateTime(parseDate(flusso.getFlussoRiversamento().getDataOraFlusso()))
        .regulationDate(parseDate(flusso.getFlussoRiversamento().getDataRegolamento()))
        .paymentsNum(flusso.getFlussoRiversamento().getNumeroTotalePagamenti())
        .amountPaid(BigDecimal.valueOf(flusso.getFlussoRiversamento().getImportoTotalePagamenti()))
        .domainId(flusso.getIdentificativoDominio())
        .intPsp(flusso.getIdentificativoIntermediarioPSP())
        .uniqueId(flusso.getMetadata().get("sessionId"))
        .insertedTimestamp(parseDate(flusso.getMetadata().get("insertedTimestamp")))
        .psp(flusso.getIdentificativoPSP())
        .causal(flusso.getFlussoRiversamento().getIdentificativoUnivocoRegolamento())
        .allDates(allDates)
        .build();
  }

  /**
   * Converts FlussoRendicontazione into a list of ReportedIUVEventModel.
   *
   * @param flusso to convert.
   * @return List of ReportedIUVEventModel.
   */
  public static List<ReportedIUVEventModel> toReportedIUVEventList(FlussoRendicontazione flusso) {
    return flusso.getFlussoRiversamento().getDatiSingoliPagamenti().stream()
        .map(
            singoloPagamento ->
                ReportedIUVEventModel.builder()
                    .iuv(singoloPagamento.getIdentificativoUnivocoVersamento())
                    .iur(singoloPagamento.getIdentificativoUnivocoRiscossione())
                    .amount(BigDecimal.valueOf(singoloPagamento.getSingoloImportoPagato()))
                    .outcomeCode(singoloPagamento.getCodiceEsitoSingoloPagamento())
                    .idsp(singoloPagamento.getIndiceDatiSingoloPagamento())
                    .singlePaymentOutcomeDate(
                        parseDate(singoloPagamento.getDataEsitoSingoloPagamento()))
                    .flowId(flusso.getFlussoRiversamento().getIdentificativoFlusso())
                    .flowDateTime(parseDate(flusso.getFlussoRiversamento().getDataOraFlusso()))
                    .domainId(flusso.getIdentificativoDominio())
                    .intPsp(flusso.getIdentificativoIntermediarioPSP())
                    .uniqueId(flusso.getMetadata().get("sessionId"))
                    .insertedTimestamp(parseDate(flusso.getMetadata().get("insertedTimestamp")))
                    .psp(flusso.getIdentificativoPSP())
                    .build())
        .toList();
  }
}
