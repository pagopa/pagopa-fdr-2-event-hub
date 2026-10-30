package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import java.util.Objects;

public record RecoveryFlowCandidate(
    long flowId,
    String name,
    long revision,
    String pspDomainId,
    String orgDomainId) {

  public RecoveryFlowCandidate {
    Objects.requireNonNull(name, "name is required");
    Objects.requireNonNull(pspDomainId, "pspDomainId is required");
    Objects.requireNonNull(orgDomainId, "orgDomainId is required");
  }

  public String blobName() {
    return name + "_" + pspDomainId + "_" + revision + ".json.zip";
  }
}