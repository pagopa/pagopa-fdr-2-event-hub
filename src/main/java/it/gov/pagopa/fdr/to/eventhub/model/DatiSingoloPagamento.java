package it.gov.pagopa.fdr.to.eventhub.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatiSingoloPagamento {
  private String identificativoUnivocoVersamento;
  private String identificativoUnivocoRiscossione;
  private String indiceDatiSingoloPagamento;
  private double singoloImportoPagato;
  private int codiceEsitoSingoloPagamento;
  private String dataEsitoSingoloPagamento;
}
