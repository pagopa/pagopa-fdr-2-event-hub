package it.gov.pagopa.fdr.to.eventhub.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Istituto {
  private String tipoIdentificativoUnivoco;
  private String codiceIdentificativoUnivoco;
  private String denominazione;
}
