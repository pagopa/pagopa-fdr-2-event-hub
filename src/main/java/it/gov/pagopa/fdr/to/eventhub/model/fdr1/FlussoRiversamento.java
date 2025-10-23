package it.gov.pagopa.fdr.to.eventhub.model.fdr1;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlussoRiversamento {

  private String versioneOggetto;

  private String identificativoFlusso;

  private String dataOraFlusso;

  private String identificativoUnivocoRegolamento;

  private String dataRegolamento;

  private Istituto istitutoMittente;

  private Istituto istitutoRicevente;

  private int numeroTotalePagamenti;

  private double importoTotalePagamenti;

  @Builder.Default private List<DatiSingoloPagamento> datiSingoliPagamenti = new ArrayList<>();

  public void releaseResources() {
    // clear and nullify heavy maps/collections
    if (this.datiSingoliPagamenti != null) {
      this.datiSingoliPagamenti.clear();
      this.datiSingoliPagamenti = null;
    }

    // nullify string fields
    this.versioneOggetto = null;
    this.identificativoFlusso = null;
    this.dataOraFlusso = null;
    this.identificativoUnivocoRegolamento = null;
    this.dataRegolamento = null;

    if (this.istitutoMittente != null) {
      this.istitutoMittente.releaseResources();
      this.istitutoMittente = null;
    }

    if (this.istitutoRicevente != null) {
      this.istitutoRicevente.releaseResources();
      this.istitutoRicevente = null;
    }
  }
}
