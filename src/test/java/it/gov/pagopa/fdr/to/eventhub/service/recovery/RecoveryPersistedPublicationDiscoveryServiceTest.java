package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryPublicationDiscoveryCheckpoint;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryPersistedPublicationDiscoveryServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryFlowRepository flows =
      mock(RecoveryFlowRepository.class);

  private final RecoveryPublicationPageService pages =
      mock(RecoveryPublicationPageService.class);

  private final RecoveryPublicationDiscoveryCheckpointRepository checkpoints =
      mock(RecoveryPublicationDiscoveryCheckpointRepository.class);

  private final Runnable ownership = mock(Runnable.class);

  private final RecoveryPersistedPublicationDiscoveryService service =
      new RecoveryPersistedPublicationDiscoveryService(
          flows, pages, checkpoints, 2);

  private final RecoveryPublicationDiscoveryCheckpoint initial =
      RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);

  private final List<RecoveryFlowCandidate> page =
      List.of(candidate(1L), candidate(2L));

  private final List<RecoveryPublicationDiscoveryItem> saved =
      List.of(item(candidate(1L)), item(candidate(2L)));

  @Test
  void shouldCreateMissingCheckpointAndCompleteEmptyDay()
      throws Exception {

    var completed = initial.completeDiscovery();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(stored(initial, "etag-0")))
        .thenReturn(Optional.of(stored(completed, "etag-1")));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(List.of());

    var result =
        service.discover(JOB_ID, DAY, () -> true, ownership);

    assertEquals(completed, result);

    verify(checkpoints).create(initial);
    verify(checkpoints).update(completed, "etag-0");
    verifyNoInteractions(pages);
  }

  @Test
  void shouldSavePageBeforeAdvancingCheckpointAndCompleteDay()
      throws Exception {

    var next = initial.afterSavedPage(saved);
    var completed = next.completeDiscovery();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(initial, "etag-0")))
        .thenReturn(Optional.of(stored(next, "etag-1")))
        .thenReturn(Optional.of(stored(completed, "etag-2")));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(page);
    when(flows.findPage(DAY, 2L, 2)).thenReturn(List.of());

    when(pages.process(JOB_ID, DAY, page, ownership))
        .thenReturn(saved);

    var result =
        service.discover(JOB_ID, DAY, () -> true, ownership);

    assertEquals(completed, result);
    assertEquals(2L, result.candidateCount());
    assertEquals(2L, result.unresolvedCandidateCount());

    var order = inOrder(flows, pages, checkpoints);

    order.verify(checkpoints).find(JOB_ID, DAY);
    order.verify(flows).findPage(DAY, 0L, 2);
    order.verify(pages).process(JOB_ID, DAY, page, ownership);
    order.verify(checkpoints).update(next, "etag-0");
    order.verify(checkpoints).find(JOB_ID, DAY);
    order.verify(flows).findPage(DAY, 2L, 2);
    order.verify(checkpoints).update(completed, "etag-1");
    order.verify(checkpoints).find(JOB_ID, DAY);
  }

  @Test
  void shouldPauseAfterSavingAndCheckpointingPage()
      throws Exception {

    var next = initial.afterSavedPage(saved);
    var continuation = mock(BooleanSupplier.class);

    when(continuation.getAsBoolean())
        .thenReturn(true, true, false);

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(initial, "etag-0")))
        .thenReturn(Optional.of(stored(next, "etag-1")));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(page);

    when(pages.process(JOB_ID, DAY, page, ownership))
        .thenReturn(saved);

    var result =
        service.discover(JOB_ID, DAY, continuation, ownership);

    assertEquals(next, result);

    verify(flows).findPage(DAY, 0L, 2);
    verifyNoMoreInteractions(flows);
  }

  @Test
  void shouldSkipCompletedDay() throws Exception {
    var completed = initial.completeDiscovery();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(completed, "etag-0")));

    var result =
        service.discover(JOB_ID, DAY, () -> true, ownership);

    assertEquals(completed, result);
    verifyNoInteractions(flows, pages);

    verify(checkpoints).find(JOB_ID, DAY);
    verifyNoMoreInteractions(checkpoints);
  }

  @Test
  void shouldNotAdvanceWhenPageProcessingFails() throws Exception {
    var failure = new IOException("Cannot process page");

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(initial, "etag-0")));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(page);

    when(pages.process(JOB_ID, DAY, page, ownership))
        .thenThrow(failure);

    var actual =
        assertThrows(
            IOException.class,
            () -> service.discover(
                JOB_ID, DAY, () -> true, ownership));

    assertSame(failure, actual);

    verify(checkpoints).find(JOB_ID, DAY);
    verifyNoMoreInteractions(checkpoints);
  }

  @Test
  void shouldResumeFromOldCursorAfterCheckpointUpdateFailure()
      throws Exception {

    var next = initial.afterSavedPage(saved);
    var completed = next.completeDiscovery();
    var failure = new IllegalStateException("Checkpoint update failed");

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(initial, "etag-0")))
        .thenReturn(Optional.of(stored(initial, "etag-0")))
        .thenReturn(Optional.of(stored(next, "etag-1")))
        .thenReturn(Optional.of(stored(completed, "etag-2")));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(page);
    when(flows.findPage(DAY, 2L, 2)).thenReturn(List.of());

    when(pages.process(JOB_ID, DAY, page, ownership))
        .thenReturn(saved);

    doThrow(failure)
        .doNothing()
        .when(checkpoints)
        .update(next, "etag-0");

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.discover(
                JOB_ID, DAY, () -> true, ownership));

    assertSame(failure, actual);

    var resumed =
        service.discover(JOB_ID, DAY, () -> true, ownership);

    assertEquals(completed, resumed);
    assertEquals(2L, resumed.candidateCount());
    assertEquals(2L, resumed.unresolvedCandidateCount());

    verify(flows, times(2)).findPage(DAY, 0L, 2);
    verify(pages, times(2)).process(JOB_ID, DAY, page, ownership);
  }

  @Test
  void shouldRejectIdsBeforeCheckpointBeforeSaving() throws Exception {
    var current =
        initial.afterSavedPage(
            List.of(item(candidate(5L))));

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(current, "etag-0")));

    when(flows.findPage(DAY, 5L, 2))
        .thenReturn(List.of(candidate(4L)));

    assertThrows(
        IllegalStateException.class,
        () -> service.discover(
            JOB_ID, DAY, () -> true, ownership));

    verifyNoInteractions(pages);

    verify(checkpoints).find(JOB_ID, DAY);
    verifyNoMoreInteractions(checkpoints);
  }

  @Test
  void shouldRejectIncompleteSavedPageBeforeCheckpointUpdate()
      throws Exception {

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(initial, "etag-0")));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(page);

    when(pages.process(JOB_ID, DAY, page, ownership))
        .thenReturn(List.of(saved.get(0)));

    assertThrows(
        IllegalStateException.class,
        () -> service.discover(
            JOB_ID, DAY, () -> true, ownership));

    verify(checkpoints).find(JOB_ID, DAY);
    verifyNoMoreInteractions(checkpoints);
  }

  @Test
  void shouldStopBeforeCheckpointUpdateWhenLeaseIsLost()
      throws Exception {

    var owned = new AtomicBoolean(true);

    Runnable guard = () -> {
      if (!owned.get()) {
        throw new IllegalStateException("Lease ownership lost");
      }
    };

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(initial, "etag-0")));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(page);

    when(pages.process(JOB_ID, DAY, page, guard))
        .thenAnswer(invocation -> {
          owned.set(false);
          return saved;
        });

    assertThrows(
        IllegalStateException.class,
        () -> service.discover(JOB_ID, DAY, () -> true, guard));

    verify(checkpoints).find(JOB_ID, DAY);
    verifyNoMoreInteractions(checkpoints);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 101})
  void shouldRejectInvalidPageSize(int pageSize) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryPersistedPublicationDiscoveryService(
                flows, pages, checkpoints, pageSize));
  }

  private static StoredRecoveryPublicationDiscoveryCheckpoint stored(
      RecoveryPublicationDiscoveryCheckpoint checkpoint,
      String etag) {

    return new StoredRecoveryPublicationDiscoveryCheckpoint(
        checkpoint, etag);
  }

  private static RecoveryPublicationDiscoveryItem item(
      RecoveryFlowCandidate candidate) {

    return new RecoveryPublicationDiscoveryItem(
        JOB_ID,
        DAY,
        candidate,
        new RecoveryPublicationDiscoveryItem.Unresolved(
            "ORIGIN_UNDETERMINED", Map.of()));
  }

  private static RecoveryFlowCandidate candidate(long flowId) {
    return new RecoveryFlowCandidate(
        flowId,
        "flow-1",
        1L,
        "psp-1",
        "org-1",
        LocalDateTime.parse("2026-10-05T10:31:35"));
  }
}