package it.gov.pagopa.fdr.to.eventhub.mapper;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Payment;
import it.gov.pagopa.fdr.to.eventhub.util.DateParsingUtil;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
  public static FlowTxEventModel toFlowTxEventList(Flow flusso) {

	List<String> allDates =
			    flusso.getPayments().stream()
			        .map(Payment::getPayDate)               
			        .map(DateParsingUtil::parseToLocalDate)
			        .map(LocalDate::toString)                // "yyyy-MM-dd"
			        .distinct()
			        .toList();

    // last fake date as alert if there are more than 'this.maxDistinctDates' dates
    if (allDates.size() > maxDistinctDates) {
      allDates = allDates.stream().limit(maxDistinctDates).collect(Collectors.toList());
      allDates.add("9999-12-31");
    }
    // FLUSSI_RENDICONTAZIONE table 
    return FlowTxEventModel.builder()
        .flowId(flusso.getFdr())
        // PIDM-1459: change to fdrDate instead of published. See https://pagopa.atlassian.net/wiki/spaces/IQCGJ/pages/695271671/FDR+-+how+to+convert+xml+to+json
        .flowDateTime(DateParsingUtil.parseDateTimeToUtcLocal(flusso.getFdrDate().toString()))
        .regulationDate(DateParsingUtil.parseToLocalDate(flusso.getRegulationDate()).atStartOfDay())
        .paymentsNum(Math.toIntExact(flusso.getComputedTotPayments()))
        .amountPaid(flusso.getComputedSumPayments())
        .domainId(flusso.getReceiver().getOrganizationId())
        .intPsp(flusso.getSender().getPspBrokerId())
        .uniqueId(flusso.getMetadata().get("sessionId"))
        .insertedTimestamp(DateParsingUtil.parseDateTimeToUtcLocal(flusso.getMetadata().get("insertedTimestamp")))
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
  public static Stream<ReportedIUVEventModel> toReportedIUVEventStream(Flow flusso) {
	// IUV_RENDICONTATI table
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
                                    	    Optional.ofNullable(singoloPagamento.getIdTransfer())
                                    	        .map(Object::toString)
                                    	        .orElse(null)
                                    )
                                    .singlePaymentOutcomeDate(DateParsingUtil.parseToLocalDate(singoloPagamento.getPayDate()).atStartOfDay())
                                    .flowId(flusso.getFdr())
                                    .flowDateTime(DateParsingUtil.parseDateTimeToUtcLocal(flusso.getFdrDate().toString()))
                                    .domainId(flusso.getReceiver().getOrganizationId())
                                    .intPsp(flusso.getSender().getPspBrokerId())
                                    .uniqueId(flusso.getMetadata().get("sessionId"))
                                    .insertedTimestamp(DateParsingUtil.parseDateTimeToUtcLocal(flusso.getMetadata().get("insertedTimestamp")))
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
