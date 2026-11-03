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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryJobDryRunProgressTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DATE_FROM =
      LocalDate.of(2026, 10, 1);

  private static final LocalDate DATE_TO =
      LocalDate.of(2026, 10, 31);

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

  private RecoveryJob job(
      boolean dryRun,
      RecoveryJobStatus status,
      LocalDate nextDate) {

    return new RecoveryJob(
        JOB_ID,
        DATE_FROM,
        DATE_TO,
        dryRun,
        status,
        nextDate,
        CREATED_AT,
        CREATED_AT);
  }

  private void stubJob(RecoveryJob job) {
    StoredRecoveryJob stored =
        new StoredRecoveryJob(job, ETAG);

    when(repository.find(JOB_ID))
        .thenReturn(Optional.of(stored));
  }

  private RecoveryDiscoveryCheckpoint completedDay(LocalDate day) {
    return RecoveryDiscoveryCheckpoint.start(JOB_ID, day)
        .completeDiscovery();
  }

  private RecoveryJob expected(
      RecoveryJobStatus status,
      LocalDate nextDate) {

    return new RecoveryJob(
        JOB_ID,
        DATE_FROM,
        DATE_TO,
        true,
        status,
        nextDate,
        CREATED_AT,
        NOW);
  }

  @Test
  void shouldAdvanceToNextDayAndKeepJobRunning() {
    stubJob(job(true, RecoveryJobStatus.RUNNING, DATE_FROM));

    RecoveryDiscoveryCheckpoint checkpoint =
        completedDay(DATE_FROM);

    RecoveryJob result =
        service.completeDryRunDay(checkpoint).orElseThrow();

    RecoveryJob expectedJob =
        expected(
            RecoveryJobStatus.RUNNING,
            DATE_FROM.plusDays(1));

    assertEquals(expectedJob, result);
    verify(repository).update(expectedJob, ETAG);
  }

  @Test
  void shouldCompleteJobAfterLastDay() {
    stubJob(job(true, RecoveryJobStatus.RUNNING, DATE_TO));

    RecoveryDiscoveryCheckpoint checkpoint =
        completedDay(DATE_TO);

    RecoveryJob result =
        service.completeDryRunDay(checkpoint).orElseThrow();

    RecoveryJob expectedJob =
        expected(
            RecoveryJobStatus.COMPLETED,
            DATE_TO.plusDays(1));

    assertEquals(expectedJob, result);
    verify(repository).update(expectedJob, ETAG);
  }

  @Test
  void shouldRejectIncompleteDiscoveryBeforeReadingJob() {
    RecoveryDiscoveryCheckpoint checkpoint =
        RecoveryDiscoveryCheckpoint.start(JOB_ID, DATE_FROM);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.completeDryRunDay(checkpoint));

    verify(repository, never()).find(any());
    verify(repository, never()).update(any(), anyString());
  }

  @Test
  void shouldRejectProgressForExecutionJob() {
    stubJob(job(false, RecoveryJobStatus.RUNNING, DATE_FROM));

    RecoveryDiscoveryCheckpoint checkpoint =
        completedDay(DATE_FROM);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.completeDryRunDay(checkpoint));

    verify(repository, never()).update(any(), anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-09-30", "2026-10-02"})
  void shouldRejectDayDifferentFromExpectedDate(String value) {
    stubJob(job(true, RecoveryJobStatus.RUNNING, DATE_FROM));

    LocalDate differentDay = LocalDate.parse(value);

    RecoveryDiscoveryCheckpoint checkpoint =
        completedDay(differentDay);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.completeDryRunDay(checkpoint));

    verify(repository, never()).update(any(), anyString());
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = "RUNNING",
      mode = EnumSource.Mode.EXCLUDE)
  void shouldNotAdvanceJobsInOtherStates(
      RecoveryJobStatus status) {

    LocalDate nextDate =
        status == RecoveryJobStatus.COMPLETED
            ? DATE_TO.plusDays(1)
            : DATE_FROM;

    stubJob(job(true, status, nextDate));

    RecoveryDiscoveryCheckpoint checkpoint =
        completedDay(DATE_FROM);

    Optional<RecoveryJob> result =
        service.completeDryRunDay(checkpoint);

    assertTrue(result.isEmpty());
    verify(repository, never()).update(any(), anyString());
  }

  @Test
  void shouldReturnEmptyForMissingJob() {
    when(repository.find(JOB_ID))
        .thenReturn(Optional.empty());

    RecoveryDiscoveryCheckpoint checkpoint =
        completedDay(DATE_FROM);

    Optional<RecoveryJob> result =
        service.completeDryRunDay(checkpoint);

    assertTrue(result.isEmpty());
    verify(repository, never()).update(any(), anyString());
  }

  @Test
  void shouldPropagateEtagConflictWithoutRetryingUpdate() {
    stubJob(job(true, RecoveryJobStatus.RUNNING, DATE_FROM));

    RecoveryDiscoveryCheckpoint checkpoint =
        completedDay(DATE_FROM);

    RecoveryJob expectedJob =
        expected(
            RecoveryJobStatus.RUNNING,
            DATE_FROM.plusDays(1));

    TableServiceException failure =
        mock(TableServiceException.class);

    doThrow(failure)
        .when(repository)
        .update(expectedJob, ETAG);

    TableServiceException thrown =
        assertThrows(
            TableServiceException.class,
            () -> service.completeDryRunDay(checkpoint));

    assertSame(failure, thrown);
    verify(repository).update(expectedJob, ETAG);
  }
}