package it.gov.pagopa.fdr.to.eventhub.mapper;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.DatiSingoloPagamento;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
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
import java.util.stream.Stream;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.UtilityClass;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import java.time.LocalDate;

@UtilityClass
public class FlussoRendicontazioneMapper {

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
	  if (dateStr == null || dateStr.isBlank()) {
	    return null;
	  }

	  String s = dateStr.trim();

	  try {
	    Matcher matcher = pattern.matcher(s);

	    if (matcher.find()) {
	      // Case: string contains timezone info (e.g. 'Z' or +02:00).
	      // Convert to the SAME INSTANT in UTC, then drop the zone.
	      ZonedDateTime zdt = ZonedDateTime.parse(s, DATE_TIME_FORMATTER);
	      return zdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
	    }

	    // Case: no timezone info.
	    // If it's full datetime "yyyy-MM-ddTHH:mm:ss" -> parse as LocalDateTime.
	    // If it's just date "yyyy-MM-dd" -> normalize to midnight (stable).
	    if (s.length() == 10) { // "yyyy-MM-dd"
	      return LocalDate.parse(s).atStartOfDay();
	    }

	    return LocalDateTime.parse(s, DATE_TIME_FORMATTER);

	  } catch (DateTimeParseException e) {
	    // Fallbacks (keep behavior similar but deterministic)
	    try {
	      // Try take first 10 chars as date and set midnight
	      return LocalDate.parse(s.substring(0, 10)).atStartOfDay();
	    } catch (Exception ex) {
	      throw new IllegalArgumentException("Date format not supported: " + dateStr, e);
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
  // stream is lazy evaluated, so it can be used to process large flows
  // so we avoid building the whole list in memory and, using foreach, we elaborate
  // only one ReportedIUVEventModel at a time
  public static Stream<ReportedIUVEventModel> toReportedIUVEventStream(FlussoRendicontazione flusso) {
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
                                    .build());
  }
}
