package it.gov.pagopa.fdr.to.eventhub.model.eventhub;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportedIUVEventModel {
	
	private String iuv;
	private String iur;
	private BigDecimal amount;
	private Integer outcomeCode;
	private LocalDateTime singlePaymentOutcomeDate;
	private String idsp;
	private String flowId;
	private LocalDateTime flowDateTime;
	private String domainId;
	private String psp;
	private String intPsp;
	private String uniqueId;
	private LocalDateTime insertedTimestamp;
}
