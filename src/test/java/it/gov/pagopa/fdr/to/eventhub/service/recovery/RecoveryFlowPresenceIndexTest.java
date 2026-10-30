package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxFlowPresence;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryFlowPresenceIndexTest {

  private static final String FLOW_NAME = "flow-1";
  private static final String PSP = "88888888888";
  private static final String ORGANIZATION = "77777777777";

  private RecoveryFlowCandidate candidate(
      long id,
      String name,
      long revision,
      String psp,
      String organization) {

    return new RecoveryFlowCandidate(
        id, name, revision, psp, organization);
  }

  private RecoveryFlowCandidate candidate() {
    return candidate(68L, FLOW_NAME, 1L, PSP, ORGANIZATION);
  }

  private RecoveryFlowPresenceIndex index() {
    RecoveryAdxFlowPresence presence =
        new RecoveryAdxFlowPresence(
            FLOW_NAME, PSP, ORGANIZATION, 25L);

    return new RecoveryFlowPresenceIndex(List.of(presence));
  }

  @Test
  void shouldFindMatchingFlowPspAndOrganization() {
    RecoveryFlowCandidate flow = candidate();

    RecoveryFlowPresenceIndex.Comparison result =
        index().compare(flow);

    assertSame(flow, result.candidate());
    assertEquals(
        RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED,
        result.presence());
    assertEquals(25L, result.presentRows());
  }

  @Test
  void shouldClassifyDifferentFlowNameAsAbsent() {
    RecoveryFlowCandidate flow =
        candidate(68L, "another-flow", 1L, PSP, ORGANIZATION);

    RecoveryFlowPresenceIndex.Comparison result =
        index().compare(flow);

    assertEquals(
        RecoveryFlowPresenceIndex.Presence.ABSENT,
        result.presence());
    assertEquals(0L, result.presentRows());
  }

  @Test
  void shouldClassifyDifferentPspAsAbsent() {
    RecoveryFlowCandidate flow =
        candidate(68L, FLOW_NAME, 1L, "99999999999", ORGANIZATION);

    RecoveryFlowPresenceIndex.Comparison result =
        index().compare(flow);

    assertEquals(
        RecoveryFlowPresenceIndex.Presence.ABSENT,
        result.presence());
  }

  @Test
  void shouldClassifyDifferentOrganizationAsAbsent() {
    RecoveryFlowCandidate flow =
        candidate(68L, FLOW_NAME, 1L, PSP, "66666666666");

    RecoveryFlowPresenceIndex.Comparison result =
        index().compare(flow);

    assertEquals(
        RecoveryFlowPresenceIndex.Presence.ABSENT,
        result.presence());
  }

  @Test
  void shouldMatchByFlowNameRegardlessOfNumericDatabaseId() {
    RecoveryFlowCandidate flow =
        candidate(999L, FLOW_NAME, 1L, PSP, ORGANIZATION);

    RecoveryFlowPresenceIndex.Comparison result =
        index().compare(flow);

    assertEquals(
        RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED,
        result.presence());
  }

  @Test
  void shouldLeaveDifferentRevisionUnverified() {
    RecoveryFlowCandidate flow =
        candidate(68L, FLOW_NAME, 2L, PSP, ORGANIZATION);

    RecoveryFlowPresenceIndex.Comparison result =
        index().compare(flow);

    assertEquals(
        RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED,
        result.presence());
    assertEquals(2L, result.candidate().revision());
  }

  @Test
  void shouldClassifyCandidateAsAbsentForEmptyDailyInventory() {
    RecoveryFlowPresenceIndex emptyIndex =
        new RecoveryFlowPresenceIndex(List.of());

    RecoveryFlowPresenceIndex.Comparison result =
        emptyIndex.compare(candidate());

    assertEquals(
        RecoveryFlowPresenceIndex.Presence.ABSENT,
        result.presence());
    assertEquals(0L, result.presentRows());
  }

  @Test
  void shouldRejectDuplicateAdxKeys() {
    RecoveryAdxFlowPresence first =
        new RecoveryAdxFlowPresence(
            FLOW_NAME, PSP, ORGANIZATION, 25L);

    RecoveryAdxFlowPresence second =
        new RecoveryAdxFlowPresence(
            FLOW_NAME, PSP, ORGANIZATION, 30L);

    List<RecoveryAdxFlowPresence> presences =
        List.of(first, second);

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryFlowPresenceIndex(presences));
  }

  @Test
  void shouldKeepSnapshotWhenInputListChanges() {
    List<RecoveryAdxFlowPresence> presences = new ArrayList<>();

    presences.add(
        new RecoveryAdxFlowPresence(
            FLOW_NAME, PSP, ORGANIZATION, 25L));

    RecoveryFlowPresenceIndex snapshot =
        new RecoveryFlowPresenceIndex(presences);

    presences.clear();

    RecoveryFlowPresenceIndex.Comparison result =
        snapshot.compare(candidate());

    assertEquals(
        RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED,
        result.presence());
  }

  @Test
  void shouldRejectNullCandidate() {
    RecoveryFlowPresenceIndex presenceIndex = index();

    assertThrows(
        NullPointerException.class,
        () -> presenceIndex.compare(null));
  }
}