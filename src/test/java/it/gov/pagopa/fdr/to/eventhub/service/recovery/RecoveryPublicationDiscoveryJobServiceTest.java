package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryPublicationDiscoveryJobServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");

  private final RecoveryJobRepository jobs =
      mock(RecoveryJobRepository.class);

  private final RecoveryPersistedPublicationDiscoveryService discovery =
      mock(RecoveryPersistedPublicationDiscoveryService.class);

  private final RecoveryWorkerLeaseSession lease =
      mock(RecoveryWorkerLeaseSession.class);

  private final RecoveryPublicationDiscoveryJobService service =
      new RecoveryPublicationDiscoveryJobService(jobs, discovery);

  @Test
  void shouldDelegateWithContinuationAndOwnershipChecks()
      throws Exception {

    stubJob(job(true, RecoveryJobStatus.RUNNING, DAY));

    var checkpoint =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);

    when(discovery.discover(
            eq(JOB_ID),
            eq(DAY),
            any(BooleanSupplier.class),
            any(Runnable.class)))
        .thenReturn(checkpoint);

    var result = service.discover(JOB_ID, DAY, lease);

    assertSame(checkpoint, result);

    var continuation =
        ArgumentCaptor.forClass(BooleanSupplier.class);

    var ownership =
        ArgumentCaptor.forClass(Runnable.class);

    verify(discovery).discover(
        eq(JOB_ID),
        eq(DAY),
        continuation.capture(),
        ownership.capture());

    assertTrue(continuation.getValue().getAsBoolean());

    var failure = new IllegalStateException("Lease lost");
    doThrow(failure).when(lease).requireHeld();

    var guard = ownership.getValue();

    var actual =
        assertThrows(
            IllegalStateException.class,
            guard::run);

    assertSame(failure, actual);
  }

  @Test
  void shouldRejectNonDryRunJob() {
    stubJob(job(false, RecoveryJobStatus.RUNNING, DAY));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.discover(JOB_ID, DAY, lease));

    verifyNoInteractions(discovery);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 3})
  void shouldRejectDayOutsideJobRange(int offset) {
    stubJob(job(true, RecoveryJobStatus.RUNNING, DAY));

    LocalDate requestedDay = DAY.plusDays(offset);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.discover(JOB_ID, requestedDay, lease));

    verifyNoInteractions(discovery);
  }

  @Test
  void shouldRejectDayDifferentFromNextDate() {
    stubJob(
        job(true, RecoveryJobStatus.RUNNING, DAY.plusDays(1)));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.discover(JOB_ID, DAY, lease));

    verifyNoInteractions(discovery);
  }

  @Test
  void shouldRejectMissingJob() {
    when(jobs.find(JOB_ID)).thenReturn(Optional.empty());

    assertThrows(
        IllegalStateException.class,
        () -> service.discover(JOB_ID, DAY, lease));

    verifyNoInteractions(discovery);
  }

  @Test
  void shouldStopContinuationWhenPauseIsRequested()
      throws Exception {

    when(jobs.find(JOB_ID))
        .thenReturn(
            Optional.of(
                stored(job(true, RecoveryJobStatus.RUNNING, DAY))))
        .thenReturn(
            Optional.of(
                stored(job(true, RecoveryJobStatus.PAUSING, DAY))));

    var checkpoint =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);

    when(discovery.discover(
            eq(JOB_ID),
            eq(DAY),
            any(BooleanSupplier.class),
            any(Runnable.class)))
        .thenReturn(checkpoint);

    service.discover(JOB_ID, DAY, lease);

    var continuation =
        ArgumentCaptor.forClass(BooleanSupplier.class);

    verify(discovery).discover(
        eq(JOB_ID),
        eq(DAY),
        continuation.capture(),
        any(Runnable.class));

    assertFalse(continuation.getValue().getAsBoolean());
  }

  @Test
  void shouldRejectLostLeaseBeforeReadingJob() {
    var failure = new IllegalStateException("Lease lost");

    doThrow(failure).when(lease).requireHeld();

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.discover(JOB_ID, DAY, lease));

    assertSame(failure, actual);
    verifyNoInteractions(jobs, discovery);
  }

  @Test
  void shouldPropagateSourceReadFailure() throws Exception {
    stubJob(job(true, RecoveryJobStatus.RUNNING, DAY));

    var failure = new IOException("Cannot read source blob");

    when(discovery.discover(
            eq(JOB_ID),
            eq(DAY),
            any(BooleanSupplier.class),
            any(Runnable.class)))
        .thenThrow(failure);

    var actual =
        assertThrows(
            IOException.class,
            () -> service.discover(JOB_ID, DAY, lease));

    assertSame(failure, actual);
  }

  private void stubJob(RecoveryJob job) {
    when(jobs.find(JOB_ID))
        .thenReturn(Optional.of(stored(job)));
  }

  private static StoredRecoveryJob stored(RecoveryJob job) {
    return new StoredRecoveryJob(job, "job-etag-1");
  }

  private static RecoveryJob job(
      boolean dryRun,
      RecoveryJobStatus status,
      LocalDate nextDate) {

    return new RecoveryJob(
        JOB_ID,
        DAY,
        DAY.plusDays(2),
        dryRun,
        status,
        nextDate,
        NOW.minusSeconds(3600),
        NOW);
  }
}