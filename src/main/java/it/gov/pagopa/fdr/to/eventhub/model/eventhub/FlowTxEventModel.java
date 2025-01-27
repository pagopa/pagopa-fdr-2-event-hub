package it.gov.pagopa.fdr.to.eventhub.model.eventhub;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlowTxEventModel {
	@JsonProperty("ID_FLUSSO")
	private String flowId;
	@JsonProperty("DATA_ORA_FLUSSO")
	private LocalDateTime flowDateTime;
	@JsonProperty("INSERTED_TIMESTAMP")
	private LocalDateTime insertedTimestamp;
	@JsonProperty("DATA_REGOLAMENTO")
	private LocalDateTime regulationDate;
	@JsonProperty("CAUSALE")
	private String causal;
	@JsonProperty("NUM_PAGAMENTI")
	private Integer paymentsNum;
	@JsonProperty("SOMMA_VERSATA")
	private BigDecimal amountPaid;
	@JsonProperty("ID_DOMINIO")
	private String domainId;
	@JsonProperty("PSP")
	private String psp;
	@JsonProperty("INT_PSP")
	private String intPsp;
	@JsonProperty("UNIQUE_ID")
	private String uniqueId;
	@JsonProperty("ALL_DATES")
	private List<String> allDates;	
}
