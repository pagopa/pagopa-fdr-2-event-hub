package it.gov.pagopa.fdr.to.eventhub.model.fdr3;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Flow {

  private String fdr;

  private Instant fdrDate;

  private Long revision;

  private Instant created;

  private Instant updated;

  private Instant published;

  private String status;

  private Sender sender;

  private Receiver receiver;

  private String regulation;

  private String regulationDate;

  private String bicCodePouringBank;

  private Long computedTotPayments;

  private BigDecimal computedSumPayments;

  private List<Payment> payments;

  private Map<String, String> metadata; // generated from blob file metadata

  public void releaseResources() {
    // clear and nullify heavy maps/collections
    if (this.metadata != null) {
      this.metadata.clear();
      this.metadata = null;
    }

    if (this.payments != null) {
      this.payments.clear();
      this.payments = null;
    }

    // nullify string fields
    this.fdr = null;
    this.status = null;
    this.regulation = null;
    this.regulationDate = null;
    this.bicCodePouringBank = null;
    if (this.sender != null) {
      this.sender.releaseResources();
      this.sender = null;
    }
    if (this.receiver != null) {
      this.receiver.releaseResources();
      this.receiver = null;
    }
  }
}
