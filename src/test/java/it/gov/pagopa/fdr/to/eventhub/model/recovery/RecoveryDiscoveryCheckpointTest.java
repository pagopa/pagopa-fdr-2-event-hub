package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFlowPresenceIndex;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryDiscoveryCheckpointTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private RecoveryDiscoveryCheckpoint start() {
    return RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY);
  }

  private RecoveryDiscoveryItem item(UUID jobId, LocalDate day, long flowId,
          RecoveryFlowPresenceIndex.Presence presence) {

      return new RecoveryDiscoveryItem(jobId, day,
              new RecoveryFlowCandidate(flowId, "flow-" + flowId, 1L, "88888888888", "77777777777",
                      LocalDateTime.of(2026, 10, 2, 12, 43, 18, 448_000_000)),
              presence, presence == RecoveryFlowPresenceIndex.Presence.ABSENT ? 0L : 25L);
  }

  private RecoveryDiscoveryItem absent(long flowId) {
    return item(
        JOB_ID,
        DAY,
        flowId,
        RecoveryFlowPresenceIndex.Presence.ABSENT);
  }

  @Test
  void shouldStartWithZeroCursorAndCounters() {
    RecoveryDiscoveryCheckpoint checkpoint = start();

    assertEquals(0L, checkpoint.lastFlowId());
    assertEquals(0L, checkpoint.candidateCount());
    assertEquals(0L, checkpoint.absentCandidateCount());
    assertEquals(0L, checkpoint.presentCandidateCount());
    assertEquals(0L, checkpoint.pageCount());

    assertEquals(
        RecoveryDiscoveryCheckpoint.Status.DISCOVERING,
        checkpoint.status());

    assertEquals(
        "11111111-1111-1111-1111-111111111111_2026-10-01",
        checkpoint.partitionKey());

    assertEquals("DISCOVERY", checkpoint.rowKey());
  }

  @Test
  void shouldAccumulatePagesAndUseLastIdAsCursor() {
    RecoveryDiscoveryCheckpoint initial = start();

    RecoveryDiscoveryItem present =
        item(
            JOB_ID,
            DAY,
            25L,
            RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED);

    List<RecoveryDiscoveryItem> firstPage =
        List.of(absent(10L), present);

    RecoveryDiscoveryCheckpoint first =
        initial.afterSavedPage(firstPage);

    List<RecoveryDiscoveryItem> secondPage =
        List.of(absent(40L));

    RecoveryDiscoveryCheckpoint second =
        first.afterSavedPage(secondPage);

    assertEquals(40L, second.lastFlowId());
    assertEquals(3L, second.candidateCount());
    assertEquals(2L, second.absentCandidateCount());
    assertEquals(1L, second.presentCandidateCount());
    assertEquals(2L, second.pageCount());

    // Previous snapshots remain unchanged.
    assertEquals(0L, initial.lastFlowId());
    assertEquals(25L, first.lastFlowId());
    assertEquals(2L, first.candidateCount());
  }

  @Test
  void shouldCompleteEmptyDiscovery() {
    RecoveryDiscoveryCheckpoint completed =
        start().completeDiscovery();

    assertEquals(
        RecoveryDiscoveryCheckpoint.Status.DISCOVERED,
        completed.status());

    assertEquals(0L, completed.candidateCount());
    assertEquals(0L, completed.lastFlowId());
  }

  @Test
  void shouldCompleteWithoutChangingProgressAndRemainIdempotent() {
    RecoveryDiscoveryCheckpoint discovering =
        start().afterSavedPage(List.of(absent(68L)));

    RecoveryDiscoveryCheckpoint completed =
        discovering.completeDiscovery();

    assertEquals(68L, completed.lastFlowId());
    assertEquals(1L, completed.candidateCount());
    assertEquals(1L, completed.absentCandidateCount());
    assertEquals(1L, completed.pageCount());

    assertEquals(
        RecoveryDiscoveryCheckpoint.Status.DISCOVERED,
        completed.status());

    assertSame(completed, completed.completeDiscovery());
  }

  @Test
  void shouldRejectAdvancingCompletedDiscovery() {
    RecoveryDiscoveryCheckpoint completed =
        start().completeDiscovery();

    List<RecoveryDiscoveryItem> page = List.of(absent(68L));

    assertThrows(
        IllegalStateException.class,
        () -> completed.afterSavedPage(page));
  }

  @Test
  void shouldRejectEmptySavedPage() {
    RecoveryDiscoveryCheckpoint checkpoint = start();
    List<RecoveryDiscoveryItem> page = List.of();

    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoint.afterSavedPage(page));
  }

  @ParameterizedTest
  @ValueSource(strings = {"job", "day"})
  void shouldRejectItemsFromAnotherJobOrDay(String changedField) {
    UUID otherJob =
        UUID.fromString("22222222-2222-2222-2222-222222222222");

    UUID jobId = "job".equals(changedField) ? otherJob : JOB_ID;

    LocalDate day =
        "day".equals(changedField) ? DAY.plusDays(1) : DAY;

    RecoveryDiscoveryItem foreignItem =
        item(
            jobId,
            day,
            68L,
            RecoveryFlowPresenceIndex.Presence.ABSENT);

    List<RecoveryDiscoveryItem> page = List.of(foreignItem);
    RecoveryDiscoveryCheckpoint checkpoint = start();

    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoint.afterSavedPage(page));
  }

  @Test
  void shouldRejectUnorderedPage() {
    RecoveryDiscoveryCheckpoint checkpoint = start();

    List<RecoveryDiscoveryItem> page =
        List.of(absent(25L), absent(10L));

    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoint.afterSavedPage(page));
  }

  @Test
  void shouldRejectPageAlreadyIncludedInCheckpoint() {
    List<RecoveryDiscoveryItem> page = List.of(absent(68L));

    RecoveryDiscoveryCheckpoint checkpoint =
        start().afterSavedPage(page);

    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoint.afterSavedPage(page));
  }

  @Test
  void shouldRejectInconsistentCounters() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryDiscoveryCheckpoint(
            JOB_ID,
            DAY,
            68L,
            10L,
            6L,
            5L,
            1L,
            RecoveryDiscoveryCheckpoint.Status.DISCOVERING));
  }
}