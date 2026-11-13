package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record RecoveryAdxPublicationPresence(
    LocalDate paymentDay,
    String flowName,
    String pspDomainId,
    String orgDomainId,
    UUID publicationId,
    long presentRows) {

  public RecoveryAdxPublicationPresence {
    Objects.requireNonNull(paymentDay, "paymentDay is required");
    Objects.requireNonNull(publicationId, "publicationId is required");

    requireText(flowName, "flowName");
    requireText(pspDomainId, "pspDomainId");
    requireText(orgDomainId, "orgDomainId");

    if (presentRows <= 0) {
      throw new IllegalArgumentException(
          "presentRows must be positive");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}