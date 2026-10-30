package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxFlowPresence;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RecoveryFlowPresenceIndex {

  public enum Presence {
    ABSENT,
    PRESENT_UNVERIFIED
  }

  public record Comparison(
      RecoveryFlowCandidate candidate,
      Presence presence,
      long presentRows) {
  }

  private record FlowKey(
      String flowName,
      String pspDomainId,
      String orgDomainId) {

    private FlowKey {
      requireText(flowName, "flowName");
      requireText(pspDomainId, "pspDomainId");
      requireText(orgDomainId, "orgDomainId");
    }

    private static void requireText(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " is required");
      }
    }
  }

  private final Map<FlowKey, RecoveryAdxFlowPresence> presences;

  public RecoveryFlowPresenceIndex(
      List<RecoveryAdxFlowPresence> dailyPresences) {

    Objects.requireNonNull(
        dailyPresences, "dailyPresences is required");

    Map<FlowKey, RecoveryAdxFlowPresence> index = new HashMap<>();

    for (RecoveryAdxFlowPresence presence : dailyPresences) {
      Objects.requireNonNull(
          presence, "dailyPresences must not contain null");

      FlowKey key =
          new FlowKey(
              presence.flowName(),
              presence.pspDomainId(),
              presence.orgDomainId());

      if (index.putIfAbsent(key, presence) != null) {
        throw new IllegalArgumentException(
            "ADX daily presences contain a duplicate flow key");
      }
    }

    this.presences = Map.copyOf(index);
  }

  public Comparison compare(RecoveryFlowCandidate candidate) {
    Objects.requireNonNull(candidate, "candidate is required");

    FlowKey key =
        new FlowKey(
            candidate.name(),
            candidate.pspDomainId(),
            candidate.orgDomainId());

    RecoveryAdxFlowPresence presence = presences.get(key);

    if (presence == null) {
      return new Comparison(candidate, Presence.ABSENT, 0L);
    }

    return new Comparison(
        candidate,
        Presence.PRESENT_UNVERIFIED,
        presence.presentRows());
  }
}