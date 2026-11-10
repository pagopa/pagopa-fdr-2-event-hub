package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryPublicationDiscoveryCheckpointTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  @Test
  void shouldStartWithZeroCountersAndStableKeys() {
    var checkpoint =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);

    assertEquals(0L, checkpoint.lastFlowId());
    assertEquals(0L, checkpoint.candidateCount());
    assertEquals(0L, checkpoint.unresolvedCandidateCount());
    assertEquals(0L, checkpoint.pageCount());

    assertEquals(
        RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERING,
        checkpoint.status());

    assertEquals(JOB_ID + "_2026-10-01", checkpoint.partitionKey());
    assertEquals("DISCOVERY", checkpoint.rowKey());
  }

  @Test
  void shouldCountEachOutcomeSeparately() {
    var initial =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);

    var page =
        List.of(
            item(1L, RecoveryPublicationDiscoveryItem.Status.ABSENT),
            item(2L, RecoveryPublicationDiscoveryItem.Status.UNRESOLVED),
            item(
                3L,
                RecoveryPublicationDiscoveryItem.Status.PRESENT_UNVERIFIED));

    var next = initial.afterSavedPage(page);

    assertEquals(3L, next.lastFlowId());
    assertEquals(3L, next.candidateCount());
    assertEquals(1L, next.absentCandidateCount());
    assertEquals(1L, next.presentCandidateCount());
    assertEquals(1L, next.unresolvedCandidateCount());
    assertEquals(1L, next.pageCount());

    assertEquals(0L, initial.candidateCount());
  }

  @Test
  void shouldAccumulateAcrossPages() {
    var first =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY)
            .afterSavedPage(
                List.of(
                    item(
                        1L,
                        RecoveryPublicationDiscoveryItem.Status.UNRESOLVED)));

    var next =
        first.afterSavedPage(
            List.of(
                item(4L, RecoveryPublicationDiscoveryItem.Status.ABSENT),
                item(
                    7L,
                    RecoveryPublicationDiscoveryItem.Status.PRESENT_UNVERIFIED)));

    assertEquals(7L, next.lastFlowId());
    assertEquals(3L, next.candidateCount());
    assertEquals(1L, next.absentCandidateCount());
    assertEquals(1L, next.presentCandidateCount());
    assertEquals(1L, next.unresolvedCandidateCount());
    assertEquals(2L, next.pageCount());
  }

  @Test
  void shouldCompleteWithoutChangingCountsAndRejectFurtherPages() {
    var checkpoint =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY)
            .afterSavedPage(
                List.of(
                    item(
                        1L,
                        RecoveryPublicationDiscoveryItem.Status.UNRESOLVED)));

    var completed = checkpoint.completeDiscovery();

    assertEquals(
        RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED,
        completed.status());

    assertEquals(checkpoint.lastFlowId(), completed.lastFlowId());
    assertEquals(checkpoint.candidateCount(), completed.candidateCount());
    assertEquals(
        checkpoint.unresolvedCandidateCount(),
        completed.unresolvedCandidateCount());

    assertSame(completed, completed.completeDiscovery());

    var nextPage =
        List.of(item(2L, RecoveryPublicationDiscoveryItem.Status.ABSENT));

    assertThrows(
        IllegalStateException.class,
        () -> completed.afterSavedPage(nextPage));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldRejectAnotherJobOrDay(boolean anotherJob) {
    var checkpoint =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);

    var original =
        item(1L, RecoveryPublicationDiscoveryItem.Status.UNRESOLVED);

    var different =
        new RecoveryPublicationDiscoveryItem(
            anotherJob
                ? UUID.fromString("22222222-2222-4222-8222-222222222222")
                : JOB_ID,
            anotherJob ? DAY : DAY.plusDays(1),
            original.candidate(),
            original.outcome());

    var page = List.of(different);

    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoint.afterSavedPage(page));
  }

  @ParameterizedTest
  @CsvSource({"2,1", "1,1"})
  void shouldRejectUnorderedOrDuplicateIds(long first, long second) {
    var checkpoint =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);

    var page =
        List.of(
            item(first, RecoveryPublicationDiscoveryItem.Status.UNRESOLVED),
            item(second, RecoveryPublicationDiscoveryItem.Status.UNRESOLVED));

    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoint.afterSavedPage(page));
  }

  @Test
  void shouldRejectAlreadyCountedPage() {
    var page =
        List.of(item(1L, RecoveryPublicationDiscoveryItem.Status.ABSENT));

    var checkpoint =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY)
            .afterSavedPage(page);

    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoint.afterSavedPage(page));
  }

  @Test
  void shouldRejectEmptySavedPage() {
    var checkpoint =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);

    List<RecoveryPublicationDiscoveryItem> page = List.of();

    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoint.afterSavedPage(page));
  }

  @Test
  void shouldRejectCounterOverflow() {
    var checkpoint =
        new RecoveryPublicationDiscoveryCheckpoint(
            JOB_ID,
            DAY,
            1L,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            0L,
            0L,
            1L,
            RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERING);

    var page =
        List.of(item(2L, RecoveryPublicationDiscoveryItem.Status.ABSENT));

    assertThrows(
        ArithmeticException.class,
        () -> checkpoint.afterSavedPage(page));
  }

  @ParameterizedTest
  @CsvSource({
      "-1,0,0,0,0,0",
      "0,-1,0,0,0,0",
      "1,1,-1,1,1,1",
      "1,1,0,-1,2,1",
      "1,1,0,2,-1,1",
      "1,1,1,0,0,-1",
      "1,2,1,0,0,1",
      "1,0,0,0,0,0",
      "0,0,0,0,0,1",
      "0,1,1,0,0,1",
      "1,1,1,0,0,0",
      "1,1,1,0,0,2"
  })
  void shouldRejectInvalidCounters(
      long cursor,
      long candidates,
      long absent,
      long present,
      long unresolved,
      long pages) {

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryPublicationDiscoveryCheckpoint(
                JOB_ID,
                DAY,
                cursor,
                candidates,
                absent,
                present,
                unresolved,
                pages,
                RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERING));
  }

  private static RecoveryPublicationDiscoveryItem item(
      long flowId,
      RecoveryPublicationDiscoveryItem.Status status) {

    var candidate =
        new RecoveryFlowCandidate(
            flowId,
            "flow-1",
            1L,
            "psp-1",
            "org-1",
            LocalDateTime.parse("2026-10-05T10:31:35"));

    RecoveryPublicationDiscoveryItem.Outcome outcome;

    if (status == RecoveryPublicationDiscoveryItem.Status.UNRESOLVED) {
      outcome =
          new RecoveryPublicationDiscoveryItem.Unresolved(
              "ORIGIN_UNDETERMINED", Map.of());
    } else {
      var source =
          new RecoveryPublicationSource(
              candidate,
              new RecoveryBlobSnapshot(
                  "fdr3-flows",
                  candidate.blobName(),
                  "\"fixture-etag\"",
                  100L,
                  Map.of()),
              RecoveryPublicationSource.SourceType.FDR3_JSON,
              new UUID(0L, flowId).toString(),
              Instant.parse("2026-10-05T10:31:35Z"));

      outcome =
          new RecoveryPublicationDiscoveryItem.Compared(
              source,
              status,
              status == RecoveryPublicationDiscoveryItem.Status.ABSENT
                  ? 0L : 1L);
    }

    return new RecoveryPublicationDiscoveryItem(
        JOB_ID, DAY, candidate, outcome);
  }
}