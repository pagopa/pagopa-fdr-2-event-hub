package it.gov.pagopa.fdr.to.eventhub.model;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlobFileData {

  private byte[] fileContent;
  private Map<String, String> metadata;
}
