package it.gov.pagopa.fdr.to.eventhub.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlussoRendicontazione {
	private String identificativoPSP;
    private String identificativoIntermediarioPSP;
    private String identificativoCanale;
    private String password;
    private String identificativoDominio;
    private String identificativoFlusso;
    private String dataOraFlusso;
    private FlussoRiversamento flussoRiversamento; // base64 <xmlRendicontazione> block
    private Map<String, String> metadata;
}
