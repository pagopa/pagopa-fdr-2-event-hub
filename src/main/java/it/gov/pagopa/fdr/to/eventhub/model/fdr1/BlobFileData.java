package it.gov.pagopa.fdr.to.eventhub.model.fdr1;

import java.util.ArrayList;
import java.util.List;
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
  private String fileName;
  private byte[] fileContent;
  private Map<String, String> metadata;
  @Builder.Default private List<String> unprocessableFileDetail = new ArrayList<>();
}
