package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxPublicationPresence;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryPublicationPresenceIndex {

  public enum Presence {
    ABSENT,
    PRESENT_UNVERIFIED
  }

  public record Comparison(
      LocalDate paymentDay,
      RecoveryPublicationSource source,
      Presence presence,
      long presentRows) {
  }

  private record PublicationKey(
      String flowName,
      String pspDomainId,
      String orgDomainId,
      UUID publicationId) {
  }

  private final LocalDate paymentDay;

  private final Map<PublicationKey, RecoveryAdxPublicationPresence>
      presences;

  public RecoveryPublicationPresenceIndex(
      LocalDate paymentDay,
      List<RecoveryAdxPublicationPresence> dailyPresences) {

    this.paymentDay =
        Objects.requireNonNull(paymentDay, "paymentDay is required");

    Objects.requireNonNull(
        dailyPresences, "dailyPresences is required");

    Map<PublicationKey, RecoveryAdxPublicationPresence> index =
        new HashMap<>();

    for (var presence : dailyPresences) {
      Objects.requireNonNull(
          presence, "dailyPresences must not contain null");

      if (!paymentDay.equals(presence.paymentDay())) {
        throw new IllegalArgumentException(
            "ADX presence belongs to a different payment day");
      }

      var key =
          new PublicationKey(
              presence.flowName(),
              presence.pspDomainId(),
              presence.orgDomainId(),
              presence.publicationId());

      if (index.putIfAbsent(key, presence) != null) {
        throw new IllegalArgumentException(
            "ADX presences contain a duplicate publication key");
      }
    }

    this.presences = Map.copyOf(index);
  }

  public Comparison compare(RecoveryPublicationSource source) {
    Objects.requireNonNull(source, "source is required");

    var candidate = source.candidate();

    var key =
        new PublicationKey(
            candidate.name(),
            candidate.pspDomainId(),
            candidate.orgDomainId(),
            UUID.fromString(source.sessionId()));

    var presence = presences.get(key);

    if (presence == null) {
      return new Comparison(
          paymentDay, source, Presence.ABSENT, 0L);
    }

    return new Comparison(
        paymentDay,
        source,
        Presence.PRESENT_UNVERIFIED,
        presence.presentRows());
  }
}