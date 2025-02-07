package it.gov.pagopa.fdr.to.eventhub.model.eventhub;

import com.fasterxml.jackson.annotation.JsonProperty;
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
  @JsonProperty("IUV")
  private String iuv;

  @JsonProperty("IUR")
  private String iur;

  @JsonProperty("IMPORTO")
  private BigDecimal amount;

  @JsonProperty("COD_ESITO")
  private Integer outcomeCode;

  @JsonProperty("DATA_ESITO_SINGOLO_PAGAMENTO")
  private LocalDateTime singlePaymentOutcomeDate;

  @JsonProperty("IDSP")
  private String idsp;

  @JsonProperty("ID_FLUSSO")
  private String flowId;

  @JsonProperty("DATA_ORA_FLUSSO")
  private LocalDateTime flowDateTime;

  @JsonProperty("ID_DOMINIO")
  private String domainId;

  @JsonProperty("PSP")
  private String psp;

  @JsonProperty("INT_PSP")
  private String intPsp;

  @JsonProperty("UNIQUE_ID")
  private String uniqueId;

  @JsonProperty("INSERTED_TIMESTAMP")
  private LocalDateTime insertedTimestamp;
}
