package it.gov.pagopa.fdr.to.eventhub.model.fdr1;

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

  private Map<String, String> metadata; // generated from blob file metadata

  public void releaseResources() {
    // clear and nullify heavy maps/collections
    if (this.metadata != null) {
      this.metadata.clear();
      this.metadata = null;
    }

    if (this.flussoRiversamento != null) {
      this.flussoRiversamento.releaseResources();
      this.flussoRiversamento = null;
    }

    // nullify string fields
    this.identificativoPSP = null;
    this.identificativoIntermediarioPSP = null;
    this.identificativoCanale = null;
    this.password = null;
    this.identificativoDominio = null;
    this.identificativoFlusso = null;
    this.dataOraFlusso = null;
  }
}
