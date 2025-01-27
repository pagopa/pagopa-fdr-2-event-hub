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
public class FlowTxEventModel {
	
	private String flowId;
	private LocalDateTime flowDateTime;
	private LocalDateTime insertedTimestamp;
	private LocalDateTime regulationDate;
	private String causal;
	private Integer paymentsNum;
	private BigDecimal amountPaid;
	private String domainId;
	private String psp;
	private String intPsp;
	private String uniqueId;
	private String[] allDates;	
}
