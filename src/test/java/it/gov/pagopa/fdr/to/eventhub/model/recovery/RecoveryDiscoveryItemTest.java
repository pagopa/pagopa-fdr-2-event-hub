package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFlowPresenceIndex;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryDiscoveryItemTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private RecoveryFlowCandidate candidate(long id, long revision) {
    return new RecoveryFlowCandidate(
        id,
        "flow-1",
        revision,
        "88888888888",
        "77777777777");
  }

  private RecoveryDiscoveryItem absent(
      UUID jobId,
      LocalDate day,
      long flowId,
      long revision) {

    return new RecoveryDiscoveryItem(
        jobId,
        day,
        candidate(flowId, revision),
        RecoveryFlowPresenceIndex.Presence.ABSENT,
        0L);
  }

  @Test
  void shouldPreserveComparisonAndCandidateRevision() {
    RecoveryFlowCandidate flow = candidate(68L, 2L);

    RecoveryFlowPresenceIndex.Comparison comparison =
        new RecoveryFlowPresenceIndex.Comparison(
            flow,
            RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED,
            25L);

    RecoveryDiscoveryItem item =
        RecoveryDiscoveryItem.fromComparison(JOB_ID, DAY, comparison);

    assertEquals(JOB_ID, item.jobId());
    assertEquals(DAY, item.day());
    assertSame(flow, item.candidate());
    assertEquals(2L, item.candidate().revision());
    assertEquals(
        RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED,
        item.presence());
    assertEquals(25L, item.presentRows());
  }

  @Test
  void shouldBuildExpectedStorageKeys() {
    RecoveryDiscoveryItem item = absent(JOB_ID, DAY, 68L, 1L);

    assertEquals(
        "11111111-1111-1111-1111-111111111111_2026-10-01",
        item.partitionKey());

    assertEquals(
        "FLOW_00000000000000000068",
        item.rowKey());
  }

  @Test
  void shouldKeepSameKeysWhenObservedPresenceChanges() {
    RecoveryFlowCandidate flow = candidate(68L, 1L);

    RecoveryDiscoveryItem missing =
        new RecoveryDiscoveryItem(
            JOB_ID,
            DAY,
            flow,
            RecoveryFlowPresenceIndex.Presence.ABSENT,
            0L);

    RecoveryDiscoveryItem present =
        new RecoveryDiscoveryItem(
            JOB_ID,
            DAY,
            flow,
            RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED,
            25L);

    assertEquals(missing.partitionKey(), present.partitionKey());
    assertEquals(missing.rowKey(), present.rowKey());
  }

  @Test
  void shouldSeparateJobsAndDaysIntoDifferentPartitions() {
    UUID otherJob =
        UUID.fromString("22222222-2222-2222-2222-222222222222");

    RecoveryDiscoveryItem original =
        absent(JOB_ID, DAY, 68L, 1L);

    RecoveryDiscoveryItem anotherJob =
        absent(otherJob, DAY, 68L, 1L);

    RecoveryDiscoveryItem anotherDay =
        absent(JOB_ID, DAY.plusDays(1), 68L, 1L);

    assertNotEquals(original.partitionKey(), anotherJob.partitionKey());
    assertNotEquals(original.partitionKey(), anotherDay.partitionKey());
  }

  @Test
  void shouldKeepDifferentDatabaseCandidatesSeparate() {
    RecoveryDiscoveryItem revisionOne =
        absent(JOB_ID, DAY, 68L, 1L);

    RecoveryDiscoveryItem revisionTwo =
        absent(JOB_ID, DAY, 74L, 2L);

    assertEquals(revisionOne.partitionKey(), revisionTwo.partitionKey());
    assertNotEquals(revisionOne.rowKey(), revisionTwo.rowKey());
  }

  @Test
  void shouldSortRowKeysByNumericFlowId() {
    RecoveryDiscoveryItem first = absent(JOB_ID, DAY, 9L, 1L);
    RecoveryDiscoveryItem second = absent(JOB_ID, DAY, 10L, 1L);

    assertTrue(first.rowKey().compareTo(second.rowKey()) < 0);
  }

  @ParameterizedTest
  @ValueSource(longs = {-1L, 1L, 25L})
  void shouldRejectNonZeroCountForAbsentCandidate(long rows) {
    RecoveryFlowCandidate flow = candidate(68L, 1L);

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryDiscoveryItem(
            JOB_ID,
            DAY,
            flow,
            RecoveryFlowPresenceIndex.Presence.ABSENT,
            rows));
  }

  @Test
  void shouldRejectZeroCountForPresentCandidate() {
    RecoveryFlowCandidate flow = candidate(68L, 1L);

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryDiscoveryItem(
            JOB_ID,
            DAY,
            flow,
            RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED,
            0L));
  }
}