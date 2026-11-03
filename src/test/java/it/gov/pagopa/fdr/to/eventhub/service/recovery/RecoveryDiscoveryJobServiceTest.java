package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryDiscoveryJobServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 1);

  private static final LocalDate LAST_DAY =
      LocalDate.of(2026, 10, 31);

  private static final Instant CREATED_AT =
      Instant.parse("2026-11-03T10:00:00Z");

  private static final String ETAG = "W/\"version-1\"";

  private final RecoveryJobRepository jobs =
      mock(RecoveryJobRepository.class);

  private final RecoveryPersistedDiscoveryService discovery =
      mock(RecoveryPersistedDiscoveryService.class);

  private final RecoveryDiscoveryJobService service =
      new RecoveryDiscoveryJobService(jobs, discovery);

  private StoredRecoveryJob stored(RecoveryJobStatus status) {
    LocalDate nextDate =
        status == RecoveryJobStatus.COMPLETED
            ? LAST_DAY.plusDays(1)
            : DAY;

    RecoveryJob job =
        new RecoveryJob(
            JOB_ID,
            DAY,
            LAST_DAY,
            true,
            status,
            nextDate,
            CREATED_AT,
            CREATED_AT);

    return new StoredRecoveryJob(job, ETAG);
  }

  @ParameterizedTest
  @EnumSource(RecoveryJobStatus.class)
  void shouldAllowContinuationOnlyForRunningJobs(
      RecoveryJobStatus status) throws Exception {

    when(jobs.find(JOB_ID))
        .thenReturn(Optional.of(stored(status)));

    RecoveryDiscoveryCheckpoint checkpoint =
        RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY);

    when(discovery.discover(
            eq(JOB_ID),
            eq(DAY),
            any(BooleanSupplier.class)))
        .thenReturn(checkpoint);

    RecoveryDiscoveryCheckpoint result =
        service.discover(JOB_ID, DAY);

    ArgumentCaptor<BooleanSupplier> captor =
        ArgumentCaptor.forClass(BooleanSupplier.class);

    verify(discovery).discover(
        eq(JOB_ID),
        eq(DAY),
        captor.capture());

    assertSame(checkpoint, result);
    assertEquals(
        status == RecoveryJobStatus.RUNNING,
        captor.getValue().getAsBoolean());
  }

  @Test
  void shouldReadLatestJobStateOnEveryContinuationCheck()
      throws Exception {

    when(jobs.find(JOB_ID))
        .thenReturn(
            Optional.of(stored(RecoveryJobStatus.RUNNING)),
            Optional.of(stored(RecoveryJobStatus.RUNNING)),
            Optional.of(stored(RecoveryJobStatus.PAUSING)));

    RecoveryDiscoveryCheckpoint checkpoint =
        RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY);

    when(discovery.discover(
            eq(JOB_ID),
            eq(DAY),
            any(BooleanSupplier.class)))
        .thenReturn(checkpoint);

    service.discover(JOB_ID, DAY);

    ArgumentCaptor<BooleanSupplier> captor =
        ArgumentCaptor.forClass(BooleanSupplier.class);

    verify(discovery).discover(
        eq(JOB_ID),
        eq(DAY),
        captor.capture());

    BooleanSupplier shouldContinue = captor.getValue();

    assertTrue(shouldContinue.getAsBoolean());
    assertFalse(shouldContinue.getAsBoolean());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-09-30", "2026-11-01"})
  void shouldRejectDayOutsideJobRange(String value) {
    LocalDate outsideDay = LocalDate.parse(value);

    when(jobs.find(JOB_ID))
        .thenReturn(Optional.of(stored(RecoveryJobStatus.RUNNING)));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.discover(JOB_ID, outsideDay));

    verifyNoInteractions(discovery);
  }

  @Test
  void shouldRejectMissingJobBeforeStartingDiscovery() {
    when(jobs.find(JOB_ID)).thenReturn(Optional.empty());

    assertThrows(
        IllegalStateException.class,
        () -> service.discover(JOB_ID, DAY));

    verifyNoInteractions(discovery);
  }

  @Test
  void shouldPropagateStorageFailureDuringContinuationCheck()
      throws Exception {

    IllegalStateException failure =
        new IllegalStateException("Simulated storage failure");

    when(jobs.find(JOB_ID))
        .thenReturn(Optional.of(stored(RecoveryJobStatus.RUNNING)))
        .thenThrow(failure);

    RecoveryDiscoveryCheckpoint checkpoint =
        RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY);

    when(discovery.discover(
            eq(JOB_ID),
            eq(DAY),
            any(BooleanSupplier.class)))
        .thenReturn(checkpoint);

    service.discover(JOB_ID, DAY);

    ArgumentCaptor<BooleanSupplier> captor =
        ArgumentCaptor.forClass(BooleanSupplier.class);

    verify(discovery).discover(
        eq(JOB_ID),
        eq(DAY),
        captor.capture());

    BooleanSupplier shouldContinue = captor.getValue();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            shouldContinue::getAsBoolean);

    assertSame(failure, thrown);
  }
}