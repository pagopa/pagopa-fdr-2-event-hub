package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryWorkerLeaseRepository;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecoveryWorkerLeaseSessionTest {

  private static final String LEASE_ID =
      "11111111-1111-1111-1111-111111111111";

  private final RecoveryWorkerLeaseRepository repository =
      mock(RecoveryWorkerLeaseRepository.class);

  private final ScheduledExecutorService scheduler =
      mock(ScheduledExecutorService.class);

  private final ScheduledFuture<?> scheduledTask =
      mock(ScheduledFuture.class);

  private final AtomicLong ticker = new AtomicLong();

  @BeforeEach
  void setUp() {
    when(repository.tryAcquire())
        .thenReturn(Optional.of(LEASE_ID));

    doReturn(scheduledTask)
        .when(scheduler)
        .scheduleWithFixedDelay(
            any(Runnable.class),
            eq(15L),
            eq(15L),
            eq(TimeUnit.SECONDS));
  }

  private RecoveryWorkerLeaseSession acquire() {
    return RecoveryWorkerLeaseSession.tryAcquire(
            repository,
            scheduler,
            ticker::get)
        .orElseThrow();
  }

  private void setSeconds(long seconds) {
    ticker.set(TimeUnit.SECONDS.toNanos(seconds));
  }

  private Runnable renewalAction() {
    ArgumentCaptor<Runnable> captor =
        ArgumentCaptor.forClass(Runnable.class);

    verify(scheduler).scheduleWithFixedDelay(
        captor.capture(),
        eq(15L),
        eq(15L),
        eq(TimeUnit.SECONDS));

    return captor.getValue();
  }

  @Test
  void shouldAcquireValidSessionAndScheduleRenewal() {
    RecoveryWorkerLeaseSession session = acquire();

    assertTrue(session.isHeld());
    session.requireHeld();

    verify(scheduler).scheduleWithFixedDelay(
        any(Runnable.class),
        eq(15L),
        eq(15L),
        eq(TimeUnit.SECONDS));
  }

  @Test
  void shouldNotScheduleRenewalWhenLeaseIsOccupied() {
    when(repository.tryAcquire()).thenReturn(Optional.empty());

    Optional<RecoveryWorkerLeaseSession> result =
        RecoveryWorkerLeaseSession.tryAcquire(
            repository,
            scheduler,
            ticker::get);

    assertTrue(result.isEmpty());
    verifyNoInteractions(scheduler);
  }

  @Test
  void shouldPropagateAcquisitionFailure() {
    IllegalStateException failure =
        new IllegalStateException("Simulated acquisition failure");

    when(repository.tryAcquire()).thenThrow(failure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            this::acquire);

    assertSame(failure, thrown);
    verifyNoInteractions(scheduler);
    verify(repository, never()).release(LEASE_ID);
  }

  @Test
  void shouldExtendConfirmationAfterSuccessfulRenewal() {
    RecoveryWorkerLeaseSession session = acquire();
    Runnable renewal = renewalAction();

    setSeconds(15);
    renewal.run();

    setSeconds(50);

    assertTrue(session.isHeld());
    verify(repository).renew(LEASE_ID);
  }

  @Test
  void shouldPermanentlyLoseSessionAfterRenewalFailure() {
    RecoveryWorkerLeaseSession session = acquire();
    Runnable renewal = renewalAction();

    IllegalStateException failure =
        new IllegalStateException("Simulated renewal failure");

    doThrow(failure).when(repository).renew(LEASE_ID);

    setSeconds(15);
    renewal.run();

    assertFalse(session.isHeld());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            session::requireHeld);

    assertSame(failure, thrown.getCause());

    // A lost session must not attempt further renewals.
    renewal.run();
    verify(repository).renew(LEASE_ID);
  }

  @Test
  void shouldLoseSessionWhenSchedulerRunsTooLate() {
    RecoveryWorkerLeaseSession session = acquire();
    Runnable renewal = renewalAction();

    setSeconds(45);
    renewal.run();

    assertFalse(session.isHeld());
    verify(repository, never()).renew(LEASE_ID);
  }

  @Test
  void shouldNotReactivateSessionAfterLateRenewalResponse() {
    RecoveryWorkerLeaseSession session = acquire();
    Runnable renewal = renewalAction();

    doAnswer(invocation -> {
      setSeconds(46);
      return null;
    }).when(repository).renew(LEASE_ID);

    setSeconds(30);
    renewal.run();

    assertFalse(session.isHeld());

    assertThrows(
        IllegalStateException.class,
        session::requireHeld);
  }

  @Test
  void shouldMeasureConfirmationFromRenewalRequestStart() {
    RecoveryWorkerLeaseSession session = acquire();
    Runnable renewal = renewalAction();

    doAnswer(invocation -> {
      setSeconds(20);
      return null;
    }).when(repository).renew(LEASE_ID);

    setSeconds(15);
    renewal.run();

    // Request started at second 15: validity ends at second 60.
    setSeconds(59);
    assertTrue(session.isHeld());

    setSeconds(60);
    assertFalse(session.isHeld());
  }

  @Test
  void shouldCancelRenewalAndReleaseLeaseOnClose() {
    RecoveryWorkerLeaseSession session = acquire();
    Runnable renewal = renewalAction();

    session.close();

    assertFalse(session.isHeld());

    verify(scheduledTask).cancel(false);
    verify(repository).release(LEASE_ID);

    renewal.run();
    verify(repository, never()).renew(LEASE_ID);

    verify(scheduler, never()).shutdown();
  }

  @Test
  void shouldCloseOnlyOnce() {
    RecoveryWorkerLeaseSession session = acquire();

    session.close();
    session.close();

    verify(scheduledTask).cancel(false);
    verify(repository).release(LEASE_ID);
  }

  @Test
  void shouldRemainClosedWhenReleaseFails() {
    RecoveryWorkerLeaseSession session = acquire();

    IllegalStateException failure =
        new IllegalStateException("Simulated release failure");

    doThrow(failure).when(repository).release(LEASE_ID);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            session::close);

    assertSame(failure, thrown);
    assertFalse(session.isHeld());

    session.close();

    verify(repository).release(LEASE_ID);
  }

  @Test
  void shouldReleaseLeaseWhenSchedulingFails() {
    RejectedExecutionException failure =
        new RejectedExecutionException("Scheduler is unavailable");

    doThrow(failure)
        .when(scheduler)
        .scheduleWithFixedDelay(
            any(Runnable.class),
            eq(15L),
            eq(15L),
            eq(TimeUnit.SECONDS));

    RejectedExecutionException thrown =
        assertThrows(
            RejectedExecutionException.class,
            this::acquire);

    assertSame(failure, thrown);
    verify(repository).release(LEASE_ID);
  }

  @Test
  void shouldRejectAcquisitionWhoseResponseArrivesTooLate() {
    when(repository.tryAcquire()).thenAnswer(invocation -> {
      setSeconds(45);
      return Optional.of(LEASE_ID);
    });

    assertThrows(
        IllegalStateException.class,
        this::acquire);

    verify(repository).release(LEASE_ID);
    verifyNoInteractions(scheduler);
  }

  @Test
  void shouldPreserveStartupFailureWhenCleanupAlsoFails() {
    RejectedExecutionException startupFailure =
        new RejectedExecutionException("Scheduler is unavailable");

    IllegalStateException cleanupFailure =
        new IllegalStateException("Simulated release failure");

    doThrow(startupFailure)
        .when(scheduler)
        .scheduleWithFixedDelay(
            any(Runnable.class),
            eq(15L),
            eq(15L),
            eq(TimeUnit.SECONDS));

    doThrow(cleanupFailure)
        .when(repository)
        .release(LEASE_ID);

    RejectedExecutionException thrown =
        assertThrows(
            RejectedExecutionException.class,
            this::acquire);

    assertSame(startupFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(cleanupFailure, thrown.getSuppressed()[0]);
  }
}