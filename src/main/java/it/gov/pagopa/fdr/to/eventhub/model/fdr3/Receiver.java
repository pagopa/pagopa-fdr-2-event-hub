package it.gov.pagopa.fdr.to.eventhub.model.fdr3;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
public class Receiver {

  private String id;
  private String organizationId;
  private String organizationName;
}