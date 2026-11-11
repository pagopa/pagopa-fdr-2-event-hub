package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryPublicationExecutionTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final UUID ATTEMPT_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 5);

  private static final Instant NOW =
      Instant.parse("2026-11-11T12:00:00Z");

  @Test
  void shouldCreatePendingExecutionOnlyFromAbsentPublication() {
    var item = item(RecoveryPublicationDiscoveryItem.Status.ABSENT);
    var execution = RecoveryPublicationExecution.pending(item, NOW);

    assertEquals(
        RecoveryPublicationExecution.Status.PENDING,
        execution.status());

    assertSame(
        ((RecoveryPublicationDiscoveryItem.Compared) item.outcome())
            .source(),
        execution.source());

    assertEquals(JOB_ID, execution.jobId());
    assertEquals(DAY, execution.day());
    assertNull(execution.attemptId());
    assertNull(execution.receipt());

    assertEquals(item.partitionKey(), execution.partitionKey());
    assertNotEquals(item.rowKey(), execution.rowKey());
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryPublicationDiscoveryItem.Status.class,
      names = {"PRESENT_UNVERIFIED", "UNRESOLVED"})
  void shouldRejectDiscoveryItemsThatAreNotAbsent(
      RecoveryPublicationDiscoveryItem.Status status) {

    var item = item(status);

    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryPublicationExecution.pending(item, NOW));
  }

  @Test
  void shouldRecordAttemptBeforeSending() {
    var pending = pending();

    var sending =
        pending.startSending(ATTEMPT_ID, NOW.plusSeconds(1));

    assertEquals(
        RecoveryPublicationExecution.Status.SENDING,
        sending.status());

    assertEquals(ATTEMPT_ID, sending.attemptId());
    assertSame(pending.source(), sending.source());
    assertEquals(NOW, sending.createdAt());
    assertEquals(NOW.plusSeconds(1), sending.updatedAt());
    assertNull(sending.receipt());
  }

  @Test
  void shouldRecordSuccessfulSendReceipt() {
    var sent =
        sending().markSent(25L, 3L, NOW.plusSeconds(2));

    assertEquals(
        RecoveryPublicationExecution.Status.SENT,
        sent.status());

    assertEquals(ATTEMPT_ID, sent.attemptId());

    assertEquals(
        new RecoveryPublicationExecution.SendReceipt(25L, 3L),
        sent.receipt());
  }

  @Test
  void shouldSkipPublicationFoundPresentBeforeSending() {
    var skipped =
        pending().skipAlreadyPresent(NOW.plusSeconds(1));

    assertEquals(
        RecoveryPublicationExecution.Status.SKIPPED_PRESENT,
        skipped.status());

    assertNull(skipped.attemptId());
    assertNull(skipped.receipt());
  }

  @Test
  void shouldPreserveAttemptWhenOutcomeIsUncertain() {
    var uncertain =
        sending().markUncertain(NOW.plusSeconds(2));

    assertEquals(
        RecoveryPublicationExecution.Status.UNCERTAIN,
        uncertain.status());

    assertEquals(ATTEMPT_ID, uncertain.attemptId());
    assertNull(uncertain.receipt());
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryPublicationExecution.Status.class,
      names = {"SENDING", "SENT", "SKIPPED_PRESENT", "UNCERTAIN"})
  void shouldRejectAutomaticRestart(
      RecoveryPublicationExecution.Status status) {

    var execution = execution(status);
    UUID newAttemptId = UUID.randomUUID();
    Instant later = NOW.plusSeconds(10);

    assertThrows(
        IllegalStateException.class,
        () -> execution.startSending(newAttemptId, later));
  }

  @ParameterizedTest
  @CsvSource({
      "0,0",
      "1,0",
      "0,1",
      "1,2",
      "-1,1"
  })
  void shouldRejectInvalidSendReceipt(long events, long batches) {
    var sending = sending();
    Instant later = NOW.plusSeconds(2);

    assertThrows(
        IllegalArgumentException.class,
        () -> sending.markSent(events, batches, later));
  }

  @Test
  void shouldNotMarkUnstartedExecutionAsSent() {
    var pending = pending();
    Instant later = NOW.plusSeconds(1);

    assertThrows(
        IllegalStateException.class,
        () -> pending.markSent(1L, 1L, later));
  }

  @Test
  void shouldRejectReceiptOnUncertainExecution() {
    var sending = sending();
    var receipt =
        new RecoveryPublicationExecution.SendReceipt(1L, 1L);

    Instant later = NOW.plusSeconds(2);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryPublicationExecution(
                JOB_ID,
                DAY,
                sending.source(),
                RecoveryPublicationExecution.Status.UNCERTAIN,
                ATTEMPT_ID,
                receipt,
                NOW,
                later));
  }

  @Test
  void shouldRejectSendingWithoutAttemptIdentifier() {
    var source = source();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryPublicationExecution(
                JOB_ID,
                DAY,
                source,
                RecoveryPublicationExecution.Status.SENDING,
                null,
                null,
                NOW,
                NOW));
  }

  @Test
  void shouldRejectTransitionTimeMovingBackwards() {
    var pending = pending();
    Instant earlier = NOW.minusSeconds(1);

    assertThrows(
        IllegalArgumentException.class,
        () -> pending.startSending(ATTEMPT_ID, earlier));
  }

  private static RecoveryPublicationExecution pending() {
    return RecoveryPublicationExecution.pending(
        item(RecoveryPublicationDiscoveryItem.Status.ABSENT),
        NOW);
  }

  private static RecoveryPublicationExecution sending() {
    return pending().startSending(
        ATTEMPT_ID, NOW.plusSeconds(1));
  }

  private static RecoveryPublicationExecution execution(
      RecoveryPublicationExecution.Status status) {

    return switch (status) {
      case PENDING -> pending();
      case SENDING -> sending();
      case SENT ->
          sending().markSent(1L, 1L, NOW.plusSeconds(2));
      case SKIPPED_PRESENT ->
          pending().skipAlreadyPresent(NOW.plusSeconds(1));
      case UNCERTAIN ->
          sending().markUncertain(NOW.plusSeconds(2));
    };
  }

  private static RecoveryPublicationDiscoveryItem item(
      RecoveryPublicationDiscoveryItem.Status status) {

    var source = source();

    RecoveryPublicationDiscoveryItem.Outcome outcome;

    if (status == RecoveryPublicationDiscoveryItem.Status.UNRESOLVED) {
      outcome =
          new RecoveryPublicationDiscoveryItem.Unresolved(
              "ORIGIN_UNDETERMINED", Map.of());
    } else {
      outcome =
          new RecoveryPublicationDiscoveryItem.Compared(
              source,
              status,
              status == RecoveryPublicationDiscoveryItem.Status.ABSENT
                  ? 0L
                  : 1L);
    }

    return new RecoveryPublicationDiscoveryItem(
        JOB_ID, DAY, source.candidate(), outcome);
  }

  private static RecoveryPublicationSource source() {
    var candidate =
        new RecoveryFlowCandidate(
            155333L,
            "recovery-flow",
            1L,
            "88888888888",
            "15376371009",
            LocalDateTime.parse("2026-10-05T12:50:26.001454"));

    return new RecoveryPublicationSource(
        candidate,
        new RecoveryBlobSnapshot(
            "fdr3-flows",
            "original.json.zip",
            "\"original-etag\"",
            100L,
            Map.of(
                "sessionId",
                "a92570e7-d5f5-49da-bb8f-0226e2d95ac7")),
        RecoveryPublicationSource.SourceType.FDR3_JSON,
        "a92570e7-d5f5-49da-bb8f-0226e2d95ac7",
        Instant.parse("2026-10-05T12:50:26.001454Z"));
  }
}