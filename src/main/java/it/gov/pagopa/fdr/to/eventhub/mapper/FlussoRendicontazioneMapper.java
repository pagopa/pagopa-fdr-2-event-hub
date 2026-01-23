package it.gov.pagopa.fdr.to.eventhub.mapper;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.DatiSingoloPagamento;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.util.DateParsingUtil;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
  @Getter @Setter private static int maxDistinctDates = 110;

  static {
    modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
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
			        .map(DateParsingUtil::parseToLocalDate)
			        .map(LocalDate::toString)
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
        .flowDateTime(DateParsingUtil.parseDateTimeToUtcLocal(flusso.getFlussoRiversamento().getDataOraFlusso()))
        .regulationDate(DateParsingUtil.parseToLocalDate(flusso.getFlussoRiversamento().getDataRegolamento()).atStartOfDay())
        .paymentsNum(flusso.getFlussoRiversamento().getNumeroTotalePagamenti())
        .amountPaid(BigDecimal.valueOf(flusso.getFlussoRiversamento().getImportoTotalePagamenti()))
        .domainId(flusso.getIdentificativoDominio())
        .intPsp(flusso.getIdentificativoIntermediarioPSP())
        .uniqueId(flusso.getMetadata().get("sessionId"))
        .insertedTimestamp(DateParsingUtil.parseDateTimeToUtcLocal(flusso.getMetadata().get("insertedTimestamp")))
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
                                    .idsp(
                                    	    Optional.ofNullable(singoloPagamento.getIndiceDatiSingoloPagamento())
                                    	        .map(Object::toString)
                                    	        .orElse(null)
                                    	)
                                    .singlePaymentOutcomeDate(
                                    		DateParsingUtil.parseToLocalDate(singoloPagamento.getDataEsitoSingoloPagamento()).atStartOfDay())
                                    .flowId(flusso.getFlussoRiversamento().getIdentificativoFlusso())
                                    .flowDateTime(DateParsingUtil.parseDateTimeToUtcLocal(flusso.getFlussoRiversamento().getDataOraFlusso()))
                                    .domainId(flusso.getIdentificativoDominio())
                                    .intPsp(flusso.getIdentificativoIntermediarioPSP())
                                    .uniqueId(flusso.getMetadata().get("sessionId"))
                                    .insertedTimestamp(DateParsingUtil.parseDateTimeToUtcLocal(flusso.getMetadata().get("insertedTimestamp")))
                                    .psp(flusso.getIdentificativoPSP())
                                    .build());
  }
}
