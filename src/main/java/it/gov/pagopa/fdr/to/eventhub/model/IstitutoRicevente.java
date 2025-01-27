package it.gov.pagopa.fdr.to.eventhub.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IstitutoRicevente {
	private String tipoIdentificativoUnivoco;
    private String codiceIdentificativoUnivoco;
    private String denominazione;
}
