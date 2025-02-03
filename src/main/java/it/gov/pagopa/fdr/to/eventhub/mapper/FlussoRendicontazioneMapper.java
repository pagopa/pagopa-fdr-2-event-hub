package it.gov.pagopa.fdr.to.eventhub.mapper;

import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;

@UtilityClass
public class FlussoRendicontazioneMapper {

  private static final ModelMapper modelMapper = new ModelMapper();

  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
          .optionalEnd()
          .toFormatter();

  static {
    modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
  }

  private static LocalDateTime parseDate(String dateStr) {
    if (dateStr == null || dateStr.isEmpty()) {
      return null;
    }
    try {
      return LocalDateTime.parse(dateStr, DATE_TIME_FORMATTER);
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
        .allDates(
            flusso.getFlussoRiversamento().getDatiSingoliPagamenti().stream()
                .map(dsp -> dsp.getDataEsitoSingoloPagamento())
                .toList())
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
            singoloPagamento -> {
              return ReportedIUVEventModel.builder()
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
                  .build();
            })
        .toList();
  }
}
