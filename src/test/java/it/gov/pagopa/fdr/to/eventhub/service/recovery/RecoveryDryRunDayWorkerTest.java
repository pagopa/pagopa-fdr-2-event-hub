package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryDryRunDayWorkerTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 1);

  private static final LocalDate LAST_DAY =
      LocalDate.of(2026, 10, 31);

  private static final Instant CREATED_AT =
      Instant.parse("2026-11-03T10:00:00Z");

  private static final Instant NOW =
      Instant.parse("2026-11-03T11:00:00Z");

  private final RecoveryJobRepository jobs =
      mock(RecoveryJobRepository.class);

  private final RecoveryDiscoveryJobService discovery =
      mock(RecoveryDiscoveryJobService.class);

  private final RecoveryWorkerLeaseSession lease =
      mock(RecoveryWorkerLeaseSession.class);

  @SuppressWarnings("unchecked")
  private final Supplier<Optional<RecoveryWorkerLeaseSession>> leases =
      mock(Supplier.class);

  private final AtomicReference<StoredRecoveryJob> stored =
      new AtomicReference<>();

  private final AtomicBoolean leaseHeld =
      new AtomicBoolean(true);

  private final AtomicInteger version = new AtomicInteger();

  private final RecoveryJobWorkerStateService states =
      new RecoveryJobWorkerStateService(
          jobs,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private final RecoveryDryRunDayWorker worker =
      new RecoveryDryRunDayWorker(
          jobs, states, discovery, leases);

  @BeforeEach
  void setUp() throws Exception {
    saveJob(RecoveryJobStatus.PENDING, true, DAY);

    when(leases.get()).thenReturn(Optional.of(lease));

    when(jobs.find(JOB_ID))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));

    doAnswer(invocation -> {
      if (!leaseHeld.get()) {
        throw new IllegalStateException("Simulated lease loss");
      }
      return null;
    }).when(lease).requireHeld();

    doAnswer(invocation -> {
      RecoveryJob updated = invocation.getArgument(0);
      String expectedEtag = invocation.getArgument(1);

      StoredRecoveryJob current = stored.get();

      if (current == null || !current.etag().equals(expectedEtag)) {
        throw new IllegalStateException("Simulated ETag conflict");
      }

      stored.set(
          new StoredRecoveryJob(
              updated,
              "version-" + version.incrementAndGet()));

      return null;
    }).when(jobs).update(any(RecoveryJob.class), anyString());

    when(discovery.discover(
            eq(JOB_ID),
            any(LocalDate.class),
            eq(lease)))
        .thenAnswer(invocation -> {
          LocalDate day = invocation.getArgument(1);
          return completedDay(day);
        });
  }

  private void saveJob(
      RecoveryJobStatus status,
      boolean dryRun,
      LocalDate nextDate) {

    RecoveryJob job =
        new RecoveryJob(
            JOB_ID,
            DAY,
            LAST_DAY,
            dryRun,
            status,
            nextDate,
            CREATED_AT,
            CREATED_AT);

    stored.set(new StoredRecoveryJob(job, "initial-version"));
  }

  private RecoveryDiscoveryCheckpoint completedDay(LocalDate day) {
    return RecoveryDiscoveryCheckpoint.start(JOB_ID, day)
        .completeDiscovery();
  }

  private RecoveryDryRunDayWorker.Outcome run() throws Exception {
    return worker.runOneDay(JOB_ID);
  }

  @Test
  void shouldDoNothingWhenLeaseIsOccupied() throws Exception {
    when(leases.get()).thenReturn(Optional.empty());

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.LEASE_BUSY,
        run());

    verifyNoInteractions(jobs, discovery, lease);
  }

  @Test
  void shouldReleaseSessionWhenJobIsMissing() throws Exception {
    stored.set(null);

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.NOT_RUN,
        run());

    verifyNoInteractions(discovery);
    verify(lease).close();
  }

  @Test
  void shouldRejectExecutionJobsWithoutChangingState() {
    saveJob(RecoveryJobStatus.PENDING, false, DAY);

    assertThrows(IllegalArgumentException.class, this::run);

    verify(jobs, never()).update(any(), anyString());
    verifyNoInteractions(discovery);
    verify(lease).close();
  }

  @Test
  void shouldStartPendingJobAndAdvanceOneDay() throws Exception {
    assertEquals(
        RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED,
        run());

    RecoveryJob result = stored.get().job();

    assertEquals(RecoveryJobStatus.RUNNING, result.status());
    assertEquals(DAY.plusDays(1), result.nextDate());

    verify(jobs, times(2)).update(any(), anyString());
    verify(discovery).discover(JOB_ID, DAY, lease);
    verify(lease).close();
  }

  @Test
  void shouldCompleteJobAfterLastDay() throws Exception {
    saveJob(RecoveryJobStatus.RUNNING, true, LAST_DAY);

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.JOB_COMPLETED,
        run());

    RecoveryJob result = stored.get().job();

    assertEquals(RecoveryJobStatus.COMPLETED, result.status());
    assertEquals(LAST_DAY.plusDays(1), result.nextDate());

    verify(discovery).discover(JOB_ID, LAST_DAY, lease);
    verify(lease).close();
  }

  @Test
  void shouldResumeRunningJobWithoutStartingItAgain()
      throws Exception {

    LocalDate nextDate = DAY.plusDays(3);
    saveJob(RecoveryJobStatus.RUNNING, true, nextDate);

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.DAY_COMPLETED,
        run());

    assertEquals(
        nextDate.plusDays(1),
        stored.get().job().nextDate());

    verify(jobs).update(any(), anyString());
    verify(discovery).discover(JOB_ID, nextDate, lease);
    verify(lease).close();
  }

  @Test
  void shouldAcknowledgePendingPauseWithoutRunningDiscovery()
      throws Exception {

    saveJob(RecoveryJobStatus.PAUSING, true, DAY);

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.PAUSED,
        run());

    assertEquals(
        RecoveryJobStatus.PAUSED,
        stored.get().job().status());

    verifyNoInteractions(discovery);
    verify(lease).close();
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = {"PAUSED", "COMPLETED", "FAILED"})
  void shouldSkipInactiveJobs(RecoveryJobStatus status)
      throws Exception {

    LocalDate nextDate =
        status == RecoveryJobStatus.COMPLETED
            ? LAST_DAY.plusDays(1)
            : DAY;

    saveJob(status, true, nextDate);

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.NOT_RUN,
        run());

    verify(jobs, never()).update(any(), anyString());
    verifyNoInteractions(discovery);
    verify(lease).close();
  }

  @Test
  void shouldAcknowledgePauseAfterIncompleteDiscovery()
      throws Exception {

    saveJob(RecoveryJobStatus.RUNNING, true, DAY);

    when(discovery.discover(JOB_ID, DAY, lease))
        .thenAnswer(invocation -> {
          saveJob(RecoveryJobStatus.PAUSING, true, DAY);
          return RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY);
        });

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.PAUSED,
        run());

    assertEquals(
        RecoveryJobStatus.PAUSED,
        stored.get().job().status());

    assertEquals(DAY, stored.get().job().nextDate());
    verify(lease).close();
  }

  @Test
  void shouldHonourPauseArrivingAfterDiscoveryCompleted()
      throws Exception {

    saveJob(RecoveryJobStatus.RUNNING, true, DAY);

    when(discovery.discover(JOB_ID, DAY, lease))
        .thenAnswer(invocation -> {
          saveJob(RecoveryJobStatus.PAUSING, true, DAY);
          return completedDay(DAY);
        });

    assertEquals(
        RecoveryDryRunDayWorker.Outcome.PAUSED,
        run());

    assertEquals(DAY, stored.get().job().nextDate());
    assertEquals(
        RecoveryJobStatus.PAUSED,
        stored.get().job().status());

    verify(lease).close();
  }

  @Test
  void shouldNotAdvanceJobWhenLeaseIsLostDuringDiscovery()
      throws Exception {

    saveJob(RecoveryJobStatus.RUNNING, true, DAY);

    when(discovery.discover(JOB_ID, DAY, lease))
        .thenAnswer(invocation -> {
          leaseHeld.set(false);
          return completedDay(DAY);
        });

    assertThrows(IllegalStateException.class, this::run);

    verify(jobs, never()).update(any(), anyString());
    verify(lease).close();
  }

  @Test
  void shouldPreserveProgressAndReleaseLeaseWhenDiscoveryFails()
      throws Exception {

    saveJob(RecoveryJobStatus.RUNNING, true, DAY);

    IllegalStateException failure =
        new IllegalStateException("Simulated discovery failure");

    when(discovery.discover(JOB_ID, DAY, lease))
        .thenThrow(failure);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, this::run);

    assertSame(failure, thrown);
    assertEquals(DAY, stored.get().job().nextDate());

    verify(jobs, never()).update(any(), anyString());
    verify(lease).close();
  }

  @Test
  void shouldNotStartJobWhenLeaseIsLostDuringStateRead() {
    AtomicInteger reads = new AtomicInteger();

    when(jobs.find(JOB_ID)).thenAnswer(invocation -> {
      if (reads.incrementAndGet() == 2) {
        leaseHeld.set(false);
      }

      return Optional.of(stored.get());
    });

    assertThrows(IllegalStateException.class, this::run);

    assertEquals(
        RecoveryJobStatus.PENDING,
        stored.get().job().status());

    verify(jobs, never()).update(any(), anyString());
    verifyNoInteractions(discovery);
    verify(lease).close();
  }

  @Test
  void shouldRejectCheckpointForAnotherJob() throws Exception {
    saveJob(RecoveryJobStatus.RUNNING, true, DAY);

    UUID otherJobId =
        UUID.fromString("22222222-2222-2222-2222-222222222222");

    RecoveryDiscoveryCheckpoint wrongCheckpoint =
        RecoveryDiscoveryCheckpoint.start(otherJobId, DAY)
            .completeDiscovery();

    when(discovery.discover(JOB_ID, DAY, lease))
        .thenReturn(wrongCheckpoint);

    assertThrows(IllegalStateException.class, this::run);

    verify(jobs, never()).update(any(), anyString());
    verify(lease).close();
  }
}