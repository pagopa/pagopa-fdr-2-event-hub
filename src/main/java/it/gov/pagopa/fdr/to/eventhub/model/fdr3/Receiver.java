package it.gov.pagopa.fdr.to.eventhub.model.fdr3;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
@NoArgsConstructor
@AllArgsConstructor
public class Receiver {

  private String id;
  private String organizationId;
  private String organizationName;
}