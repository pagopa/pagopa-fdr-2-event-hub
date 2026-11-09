package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryBatchRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecoveryDiscoveryLeaseGuardTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private static final Instant CREATED_AT =
      Instant.parse("2026-11-03T10:00:00Z");

  private static final String ETAG_ONE = "W/\"version-1\"";
  private static final String ETAG_TWO = "W/\"version-2\"";

  private final RecoveryJobRepository jobs =
      mock(RecoveryJobRepository.class);

  private final RecoveryAdxRepository adx =
      mock(RecoveryAdxRepository.class);

  private final RecoveryFlowRepository flows =
      mock(RecoveryFlowRepository.class);

  private final RecoveryDiscoveryBatchRepository batches =
      mock(RecoveryDiscoveryBatchRepository.class);

  private final RecoveryDiscoveryCheckpointRepository checkpoints =
      mock(RecoveryDiscoveryCheckpointRepository.class);

  private final RecoveryWorkerLeaseSession lease =
      mock(RecoveryWorkerLeaseSession.class);

  private final AtomicBoolean leaseHeld = new AtomicBoolean(true);

  private final IllegalStateException leaseFailure =
      new IllegalStateException("Simulated lease loss");

  private final RecoveryPersistedDiscoveryService persisted =
      new RecoveryPersistedDiscoveryService(
          adx, flows, batches, checkpoints, 2);

  private final RecoveryDiscoveryJobService service =
      new RecoveryDiscoveryJobService(jobs, persisted);

  @BeforeEach
  void setUp() throws Exception {
    doAnswer(invocation -> {
      if (!leaseHeld.get()) {
        throw leaseFailure;
      }
      return null;
    }).when(lease).requireHeld();

    when(jobs.find(JOB_ID))
        .thenReturn(Optional.of(storedJob(RecoveryJobStatus.RUNNING)));

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(Optional.of(stored(initial(), ETAG_ONE)));

    when(adx.findByDay(DAY)).thenReturn(List.of());
    when(flows.findPage(DAY, 0L, 2)).thenReturn(List.of());
  }

  private RecoveryDiscoveryCheckpoint initial() {
    return RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY);
  }

  private StoredRecoveryJob storedJob(RecoveryJobStatus status) {
    RecoveryJob job =
        new RecoveryJob(
            JOB_ID,
            DAY,
            DAY,
            true,
            status,
            DAY,
            CREATED_AT,
            CREATED_AT);

    return new StoredRecoveryJob(job, ETAG_ONE);
  }

  private StoredRecoveryDiscoveryCheckpoint stored(
      RecoveryDiscoveryCheckpoint checkpoint,
      String etag) {

    return new StoredRecoveryDiscoveryCheckpoint(checkpoint, etag);
  }

  private RecoveryFlowCandidate candidate() {
    return new RecoveryFlowCandidate(
        10L,
        "flow-10",
        1L,
        "88888888888",
        "77777777777",
        LocalDateTime.of(2026, 10, 2, 12, 43, 18, 448_000_000));
  }

  private List<RecoveryDiscoveryItem> items(
      RecoveryFlowCandidate candidate) {

    return List.of(
        new RecoveryDiscoveryItem(
            JOB_ID,
            DAY,
            candidate,
            RecoveryFlowPresenceIndex.Presence.ABSENT,
            0L));
  }

  private RecoveryDiscoveryCheckpoint discover() throws Exception {
    return service.discover(JOB_ID, DAY, lease);
  }

  @Test
  void shouldStopBeforeReadingJobWhenLeaseIsAlreadyLost() {
    leaseHeld.set(false);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, this::discover);

    assertSame(leaseFailure, thrown);
    verifyNoInteractions(jobs, checkpoints, adx, flows, batches);
  }

  @Test
  void shouldStopBeforePostgresWhenLeaseIsLostDuringAdxQuery()
      throws Exception {

    when(adx.findByDay(DAY)).thenAnswer(invocation -> {
      leaseHeld.set(false);
      return List.of();
    });

    assertThrows(IllegalStateException.class, this::discover);

    verifyNoInteractions(flows, batches);
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldNotSavePageWhenLeaseIsLostDuringPostgresQuery()
      throws Exception {

    RecoveryFlowCandidate candidate = candidate();

    when(flows.findPage(DAY, 0L, 2)).thenAnswer(invocation -> {
      leaseHeld.set(false);
      return List.of(candidate);
    });

    assertThrows(IllegalStateException.class, this::discover);

    verifyNoInteractions(batches);
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldNotAdvanceCheckpointWhenLeaseIsLostDuringBatchSave()
      throws Exception {

    RecoveryFlowCandidate candidate = candidate();
    when(flows.findPage(DAY, 0L, 2))
        .thenReturn(List.of(candidate));

    doAnswer(invocation -> {
      leaseHeld.set(false);
      return null;
    }).when(batches).createIfAbsent(anyList());

    assertThrows(IllegalStateException.class, this::discover);

    verify(batches).createIfAbsent(items(candidate));
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldNotCompleteDayWhenLeaseIsLostDuringEmptyPageRead()
      throws Exception {

    when(flows.findPage(DAY, 0L, 2)).thenAnswer(invocation -> {
      leaseHeld.set(false);
      return List.of();
    });

    assertThrows(IllegalStateException.class, this::discover);

    verifyNoInteractions(batches);
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldCompleteDiscoveryWhileLeaseRemainsHeld()
      throws Exception {

    RecoveryFlowCandidate candidate = candidate();
    List<RecoveryDiscoveryItem> pageItems = items(candidate);

    RecoveryDiscoveryCheckpoint next =
        initial().afterSavedPage(pageItems);

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(
            Optional.of(stored(initial(), ETAG_ONE)),
            Optional.of(stored(next, ETAG_TWO)));

    when(flows.findPage(DAY, 0L, 2))
        .thenReturn(List.of(candidate));

    when(flows.findPage(DAY, 10L, 2))
        .thenReturn(List.of());

    RecoveryDiscoveryCheckpoint result = discover();

    assertEquals(next.completeDiscovery(), result);

    verify(batches).createIfAbsent(pageItems);
    verify(checkpoints).update(next, ETAG_ONE);
    verify(checkpoints).update(result, ETAG_TWO);
  }

  @Test
  void shouldStopForPauseWithoutTreatingItAsLeaseLoss()
      throws Exception {

    when(jobs.find(JOB_ID))
        .thenReturn(
            Optional.of(storedJob(RecoveryJobStatus.RUNNING)),
            Optional.of(storedJob(RecoveryJobStatus.RUNNING)),
            Optional.of(storedJob(RecoveryJobStatus.PAUSING)));

    RecoveryDiscoveryCheckpoint result = discover();

    assertEquals(initial(), result);

    verifyNoInteractions(flows, batches);
    verify(checkpoints, never()).update(any(), anyString());
  }

  @Test
  void shouldFinishCurrentPageBeforeHonouringPause()
      throws Exception {

    RecoveryFlowCandidate candidate = candidate();
    List<RecoveryDiscoveryItem> pageItems = items(candidate);

    RecoveryDiscoveryCheckpoint next =
        initial().afterSavedPage(pageItems);

    when(checkpoints.find(JOB_ID, DAY))
        .thenReturn(
            Optional.of(stored(initial(), ETAG_ONE)),
            Optional.of(stored(next, ETAG_TWO)));

    when(flows.findPage(DAY, 0L, 2)).thenAnswer(invocation -> {
      when(jobs.find(JOB_ID))
          .thenReturn(
              Optional.of(storedJob(RecoveryJobStatus.PAUSING)));

      return List.of(candidate);
    });

    RecoveryDiscoveryCheckpoint result = discover();

    assertEquals(next, result);

    verify(batches).createIfAbsent(pageItems);
    verify(checkpoints).update(next, ETAG_ONE);
    verify(flows, never()).findPage(DAY, 10L, 2);
  }
}