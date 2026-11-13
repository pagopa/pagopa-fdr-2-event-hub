package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.microsoft.azure.kusto.data.exceptions.KustoServiceQueryError;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryBatchRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryDiscoveryCheckpoint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RecoveryPersistedDiscoveryServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final String ETAG_ONE = "W/\"version-1\"";
  private static final String ETAG_TWO = "W/\"version-2\"";

  private final RecoveryAdxRepository adx =
      mock(RecoveryAdxRepository.class);

  private final RecoveryFlowRepository flows =
      mock(RecoveryFlowRepository.class);

  private final RecoveryDiscoveryBatchRepository batches =
      mock(RecoveryDiscoveryBatchRepository.class);

  private final RecoveryDiscoveryCheckpointRepository checkpoints =
      mock(RecoveryDiscoveryCheckpointRepository.class);

  private final RecoveryPersistedDiscoveryService service =
      new RecoveryPersistedDiscoveryService(
          adx, flows, batches, checkpoints, 2);

  @BeforeEach
  void setUp() throws Exception {
    when(adx.findByDay(DAY)).thenReturn(List.of());
  }

  private RecoveryDiscoveryCheckpoint initial() {
    return RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY);
  }

  private RecoveryFlowCandidate candidate(long id) {
    return new RecoveryFlowCandidate(
        id,
        "flow-" + id,
        1L,
        "88888888888",
        "77777777777",
        LocalDateTime.of(2026, 10, 2, 12, 43, 18, 448_000_000));
  }

  private List<RecoveryDiscoveryItem> absentItems(
      List<RecoveryFlowCandidate> candidates) {

    return candidates.stream()
        .map(
            candidate ->
                new RecoveryDiscoveryItem(
                    JOB_ID,
                    DAY,
                    candidate,
                    RecoveryFlowPresenceIndex.Presence.ABSENT,
                    0L))
        .toList();
  }

  private StoredRecoveryDiscoveryCheckpoint stored(
      RecoveryDiscoveryCheckpoint checkpoint,
      String etag) {

    return new StoredRecoveryDiscoveryCheckpoint(checkpoint, etag);
  }

  @Test
  void shouldCreateCheckpointSavePageAndCompleteInCorrectOrder()
      throws Exception {

    RecoveryDiscoveryCheckpoint start = initial();
    List<RecoveryFlowCandidate> page =
        List.of(candidate(10L), candidate(68L));

    List<RecoveryDiscoveryItem> items = absentItems(page);

    RecoveryDiscoveryCheckpoint next =
        start.afterSavedPage(items);

    RecoveryDiscoveryCheckpoint completed =
        next.completeDiscovery();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(
            Optional.empty(),
            Optional.of(stored(start, ETAG_ONE)),
            Optional.of(stored(next, ETAG_TWO)));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(page);
    when(flows.findPage(DAY, 68L, 2)).thenReturn(List.of());

    RecoveryDiscoveryCheckpoint result =
        service.discover(JOB_ID, DAY);

    assertEquals(completed, result);

    InOrder order = inOrder(checkpoints, batches, flows);

    order.verify(checkpoints).find(JOB_ID, DAY);
    order.verify(checkpoints).create(start);
    order.verify(checkpoints).find(JOB_ID, DAY);
    order.verify(flows).findPage(DAY, 0L, 2);
    order.verify(batches).createIfAbsent(items);
    order.verify(checkpoints).update(next, ETAG_ONE);
    order.verify(checkpoints).find(JOB_ID, DAY);
    order.verify(flows).findPage(DAY, 68L, 2);
    order.verify(checkpoints).update(completed, ETAG_TWO);

    verify(adx).findByDay(DAY);
  }

  @Test
  void shouldResumeFromPersistedCursorAndKeepPreviousCounters()
      throws Exception {

    RecoveryDiscoveryCheckpoint previous =
        initial().afterSavedPage(
            absentItems(List.of(candidate(68L))));

    List<RecoveryFlowCandidate> page = List.of(candidate(74L));
    List<RecoveryDiscoveryItem> items = absentItems(page);

    RecoveryDiscoveryCheckpoint next =
        previous.afterSavedPage(items);

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(
            Optional.of(stored(previous, ETAG_ONE)),
            Optional.of(stored(next, ETAG_TWO)));

    when(flows.findPage(DAY, 68L, 2)).thenReturn(page);
    when(flows.findPage(DAY, 74L, 2)).thenReturn(List.of());

    RecoveryDiscoveryCheckpoint result =
        service.discover(JOB_ID, DAY);

    assertEquals(74L, result.lastFlowId());
    assertEquals(2L, result.candidateCount());
    assertEquals(2L, result.absentCandidateCount());
    assertEquals(2L, result.pageCount());

    verify(flows, never()).findPage(DAY, 0L, 2);
    verify(checkpoints, never()).create(any());
    verify(adx).findByDay(DAY);
  }

  @Test
  void shouldSkipCompletedDiscoveryWithoutReadingSources()
      throws Exception {

    RecoveryDiscoveryCheckpoint completed =
        initial().completeDiscovery();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(completed, ETAG_ONE)));

    RecoveryDiscoveryCheckpoint result =
        service.discover(JOB_ID, DAY);

    assertEquals(completed, result);
    verifyNoInteractions(adx, flows, batches);
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldNotAdvanceCheckpointWhenBatchFails() throws Exception {
    RecoveryDiscoveryCheckpoint start = initial();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(start, ETAG_ONE)));

    when(flows.findPage(DAY, 0L, 2))
        .thenReturn(List.of(candidate(68L)));

    IllegalStateException failure =
        new IllegalStateException("Simulated batch failure");

    when(batches.createIfAbsent(anyList())).thenThrow(failure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> service.discover(JOB_ID, DAY));

    assertSame(failure, thrown);
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldStopIfCheckpointUpdateFailsAfterSavingPage()
      throws Exception {

    RecoveryDiscoveryCheckpoint start = initial();

    List<RecoveryFlowCandidate> page = List.of(candidate(68L));
    List<RecoveryDiscoveryItem> items = absentItems(page);

    RecoveryDiscoveryCheckpoint next =
        start.afterSavedPage(items);

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(start, ETAG_ONE)));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(page);

    IllegalStateException failure =
        new IllegalStateException("Simulated checkpoint conflict");

    doThrow(failure)
        .when(checkpoints)
        .update(next, ETAG_ONE);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> service.discover(JOB_ID, DAY));

    assertSame(failure, thrown);

    InOrder order = inOrder(batches, checkpoints);
    order.verify(batches).createIfAbsent(items);
    order.verify(checkpoints).update(next, ETAG_ONE);

    verify(flows, never()).findPage(DAY, 68L, 2);
  }

  @Test
  void shouldNotWriteItemsOrAdvanceCheckpointWhenAdxFails()
      throws Exception {

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(initial(), ETAG_ONE)));

    KustoServiceQueryError failure =
        new KustoServiceQueryError("Simulated ADX failure");

    when(adx.findByDay(DAY)).thenThrow(failure);

    KustoServiceQueryError thrown =
        assertThrows(
            KustoServiceQueryError.class,
            () -> service.discover(JOB_ID, DAY));

    assertSame(failure, thrown);
    verifyNoInteractions(flows, batches);
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldRejectInvalidPageBeforeWritingItems() throws Exception {
    RecoveryDiscoveryCheckpoint previous =
        initial().afterSavedPage(
            absentItems(List.of(candidate(68L))));

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(previous, ETAG_ONE)));

    // This ID must not reappear after cursor 68.
    when(flows.findPage(DAY, 68L, 2))
        .thenReturn(List.of(candidate(68L)));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.discover(JOB_ID, DAY));

    verifyNoInteractions(batches);
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldCompleteEmptyDayWithoutSavingItems() throws Exception {
    RecoveryDiscoveryCheckpoint start = initial();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(start, ETAG_ONE)));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(List.of());

    RecoveryDiscoveryCheckpoint completed =
        service.discover(JOB_ID, DAY);

    assertEquals(start.completeDiscovery(), completed);

    verify(checkpoints).update(completed, ETAG_ONE);
    verifyNoInteractions(batches);
  }

  @Test
  void shouldStopBeforeReadingSourcesWhenContinuationIsDenied()
      throws Exception {

    RecoveryDiscoveryCheckpoint start = initial();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(start, ETAG_ONE)));

    RecoveryDiscoveryCheckpoint result =
        service.discover(JOB_ID, DAY, () -> false);

    assertEquals(start, result);

    verifyNoInteractions(adx, flows, batches);
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldStopBeforeFirstPageWhenContinuationChangesDuringAdxRead()
      throws Exception {

    RecoveryDiscoveryCheckpoint start = initial();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(start, ETAG_ONE)));

    BooleanSupplier shouldContinue = mock(BooleanSupplier.class);

    // Consent before ADX, then stop before the first PostgreSQL page.
    when(shouldContinue.getAsBoolean()).thenReturn(true, false);

    RecoveryDiscoveryCheckpoint result =
        service.discover(JOB_ID, DAY, shouldContinue);

    assertEquals(start, result);

    verify(adx).findByDay(DAY);
    verifyNoInteractions(flows, batches);
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldFinishCurrentPageBeforeStoppingAndResumeFromSavedCursor()
      throws Exception {

    RecoveryDiscoveryCheckpoint start = initial();

    List<RecoveryFlowCandidate> page =
        List.of(candidate(10L), candidate(68L));

    List<RecoveryDiscoveryItem> items = absentItems(page);

    RecoveryDiscoveryCheckpoint next =
        start.afterSavedPage(items);

    RecoveryDiscoveryCheckpoint completed =
        next.completeDiscovery();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(
            Optional.of(stored(start, ETAG_ONE)),
            Optional.of(stored(next, ETAG_TWO)),
            Optional.of(stored(next, ETAG_TWO)));

    when(flows.findPage(DAY, 0L, 2)).thenReturn(page);
    when(flows.findPage(DAY, 68L, 2)).thenReturn(List.of());

    AtomicBoolean continuationAllowed = new AtomicBoolean(true);

    // Simulate a pause request arriving while the page is being saved.
    doAnswer(invocation -> {
      continuationAllowed.set(false);
      return null;
    }).when(batches).createIfAbsent(items);

    RecoveryDiscoveryCheckpoint stopped =
        service.discover(
            JOB_ID,
            DAY,
            continuationAllowed::get);

    assertEquals(next, stopped);
    assertEquals(
        RecoveryDiscoveryCheckpoint.Status.DISCOVERING,
        stopped.status());

    InOrder order = inOrder(batches, checkpoints);

    order.verify(batches).createIfAbsent(items);
    order.verify(checkpoints).update(next, ETAG_ONE);
    order.verify(checkpoints).find(JOB_ID, DAY);

    // Stopping must prevent the next PostgreSQL page from being read.
    verify(flows, never()).findPage(DAY, 68L, 2);
    verify(checkpoints, never()).update(completed, ETAG_TWO);

    continuationAllowed.set(true);

    RecoveryDiscoveryCheckpoint resumed =
        service.discover(
            JOB_ID,
            DAY,
            continuationAllowed::get);

    assertEquals(completed, resumed);
    assertEquals(2L, resumed.candidateCount());
    assertEquals(1L, resumed.pageCount());

    // The first page is neither read nor saved again.
    verify(flows).findPage(DAY, 0L, 2);
    verify(batches).createIfAbsent(items);

    verify(flows).findPage(DAY, 68L, 2);
    verify(checkpoints).update(completed, ETAG_TWO);
  }

  @Test
  void shouldSkipContinuationCheckWhenDiscoveryIsAlreadyCompleted()
      throws Exception {

    RecoveryDiscoveryCheckpoint completed =
        initial().completeDiscovery();

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(completed, ETAG_ONE)));

    BooleanSupplier shouldContinue = mock(BooleanSupplier.class);

    RecoveryDiscoveryCheckpoint result =
        service.discover(JOB_ID, DAY, shouldContinue);

    assertEquals(completed, result);

    verifyNoInteractions(shouldContinue, adx, flows, batches);
    verify(checkpoints, never()).update(any(), anyString());
  }
}