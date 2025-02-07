package it.gov.pagopa.fdr.to.eventhub.model;

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
}
