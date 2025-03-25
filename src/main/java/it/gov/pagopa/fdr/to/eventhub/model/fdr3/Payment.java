package it.gov.pagopa.fdr.to.eventhub.model.fdr3;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class Payment {

  private Long index;
  private String iuv;
  private String iur;
  private BigDecimal pay;
  private String payDate;
  private String payStatus;
  private Long idTransfer;
}