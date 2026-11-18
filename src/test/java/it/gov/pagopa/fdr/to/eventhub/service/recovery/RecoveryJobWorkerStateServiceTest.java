package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.data.tables.models.TableServiceException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class RecoveryJobWorkerStateServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DATE_FROM =
      LocalDate.of(2026, 10, 1);

  private static final LocalDate DATE_TO =
      LocalDate.of(2026, 10, 31);

  private static final LocalDate NEXT_DATE =
      LocalDate.of(2026, 10, 4);

  private static final Instant CREATED_AT =
      Instant.parse("2026-11-03T10:00:00Z");

  private static final Instant NOW =
      Instant.parse("2026-11-03T11:00:00Z");

  private static final String ETAG = "W/\"version-1\"";

  private final RecoveryJobRepository repository =
      mock(RecoveryJobRepository.class);

  private final RecoveryJobWorkerStateService service =
      new RecoveryJobWorkerStateService(
          repository,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private RecoveryJob job(RecoveryJobStatus status) {
    LocalDate nextDate =
        status == RecoveryJobStatus.COMPLETED
            ? DATE_TO.plusDays(1)
            : NEXT_DATE;

    return new RecoveryJob(
        JOB_ID,
        DATE_FROM,
        DATE_TO,
        false,
        status,
        nextDate,
        CREATED_AT,
        CREATED_AT);
  }

  private void stubJob(RecoveryJobStatus status) {
    StoredRecoveryJob stored =
        new StoredRecoveryJob(job(status), ETAG);

    when(repository.find(JOB_ID))
        .thenReturn(Optional.of(stored));
  }

  private RecoveryJob expectedUpdate(RecoveryJobStatus status) {
    return new RecoveryJob(
        JOB_ID,
        DATE_FROM,
        DATE_TO,
        false,
        status,
        NEXT_DATE,
        CREATED_AT,
        NOW);
  }

  @Test
  void shouldStartPendingJobPreservingProgressAndRequest() {
    stubJob(RecoveryJobStatus.PENDING);

    RecoveryJob result =
        service.tryStart(JOB_ID).orElseThrow();

    RecoveryJob expected =
        expectedUpdate(RecoveryJobStatus.RUNNING);

    assertEquals(expected, result);
    verify(repository).update(expected, ETAG);
  }

  @Test
  void shouldAcknowledgePausePreservingProgressAndRequest() {
    stubJob(RecoveryJobStatus.PAUSING);

    RecoveryJob result =
        service.acknowledgePause(JOB_ID).orElseThrow();

    RecoveryJob expected =
        expectedUpdate(RecoveryJobStatus.PAUSED);

    assertEquals(expected, result);
    verify(repository).update(expected, ETAG);
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = "PENDING",
      mode = EnumSource.Mode.EXCLUDE)
  void shouldNotStartJobsInOtherStates(RecoveryJobStatus status) {
    stubJob(status);

    Optional<RecoveryJob> result =
        service.tryStart(JOB_ID);

    assertTrue(result.isEmpty());
    verify(repository, never()).update(any(), anyString());
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = "PAUSING",
      mode = EnumSource.Mode.EXCLUDE)
  void shouldNotAcknowledgePauseInOtherStates(
      RecoveryJobStatus status) {

    stubJob(status);

    Optional<RecoveryJob> result =
        service.acknowledgePause(JOB_ID);

    assertTrue(result.isEmpty());
    verify(repository, never()).update(any(), anyString());
  }

  @Test
  void shouldReturnEmptyForMissingJob() {
    when(repository.find(JOB_ID))
        .thenReturn(Optional.empty());

    Optional<RecoveryJob> started =
        service.tryStart(JOB_ID);

    Optional<RecoveryJob> paused =
        service.acknowledgePause(JOB_ID);

    assertTrue(started.isEmpty());
    assertTrue(paused.isEmpty());

    verify(repository, never()).update(any(), anyString());
  }

  @Test
  void shouldPropagateStartConflictWithoutRetryingUpdate() {
    stubJob(RecoveryJobStatus.PENDING);

    TableServiceException failure =
        mock(TableServiceException.class);

    doThrow(failure)
        .when(repository)
        .update(any(RecoveryJob.class), anyString());

    TableServiceException thrown =
        assertThrows(
            TableServiceException.class,
            () -> service.tryStart(JOB_ID));

    assertSame(failure, thrown);

    ArgumentCaptor<RecoveryJob> captor =
        ArgumentCaptor.forClass(RecoveryJob.class);

    verify(repository).update(
        captor.capture(),
        org.mockito.ArgumentMatchers.eq(ETAG));

    assertEquals(
        RecoveryJobStatus.RUNNING,
        captor.getValue().status());
  }

  @Test
  void shouldPropagatePauseConflictWithoutRetryingUpdate() {
    stubJob(RecoveryJobStatus.PAUSING);

    TableServiceException failure =
        mock(TableServiceException.class);

    doThrow(failure)
        .when(repository)
        .update(any(RecoveryJob.class), anyString());

    TableServiceException thrown =
        assertThrows(
            TableServiceException.class,
            () -> service.acknowledgePause(JOB_ID));

    assertSame(failure, thrown);

    verify(repository).update(
        expectedUpdate(RecoveryJobStatus.PAUSED),
        ETAG);
  }
}