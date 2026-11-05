package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.rest.SimpleResponse;
import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryJobRepositoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String ETAG = "W/\"version-1\"";

  private final TableClient client = mock(TableClient.class);
  private final RecoveryJobTableMapper mapper = new RecoveryJobTableMapper();
  private final RecoveryJobRepository repository =
      new RecoveryJobRepository(client, mapper);

  private RecoveryJob newJob() {
    return RecoveryJob.create(
        new RecoveryJobRequest("2026-10-01", "2026-10-31", true),
        Clock.fixed(
            Instant.parse("2026-10-29T15:00:00Z"),
            ZoneOffset.UTC));
  }

  private void stubRead(RecoveryJob job, TableEntity entity) {
    when(client.getEntityWithResponse(
            eq(job.jobId().toString()),
            eq("JOB"),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenReturn(
            new SimpleResponse<>(null, 200, new HttpHeaders(), entity));
  }

  private void stubReadError(
      RecoveryJob job, TableServiceException exception) {

    when(client.getEntityWithResponse(
            eq(job.jobId().toString()),
            eq("JOB"),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(exception);
  }

  private TableServiceException storageError(int status, String code) {
    HttpResponse response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);

    return new TableServiceException(
        "Simulated storage error",
        response,
        new TableServiceError(code, "Simulated storage error"));
  }

  @Test
  void shouldCreateEntityWithoutUpsert() {
    RecoveryJob job = newJob();

    repository.create(job);

    ArgumentCaptor<TableEntity> captor =
        ArgumentCaptor.forClass(TableEntity.class);

    verify(client).createEntityWithResponse(
        captor.capture(), eq(TIMEOUT), eq(Context.NONE));

    assertEquals(job, mapper.toJob(captor.getValue()));
  }

  @Test
  void shouldPropagateDuplicateCreation() {
    RecoveryJob job = newJob();
    TableServiceException failure =
        storageError(409, "EntityAlreadyExists");

    doThrow(failure)
        .when(client)
        .createEntityWithResponse(
            any(TableEntity.class), eq(TIMEOUT), eq(Context.NONE));

    assertSame(
        failure,
        assertThrows(
            TableServiceException.class,
            () -> repository.create(job)));
  }

  @Test
  void shouldReadJobWithItsEtag() {
    RecoveryJob job = newJob();
    TableEntity entity = mapper.toEntity(job);
    entity.addProperty("odata.etag", ETAG);
    stubRead(job, entity);

    StoredRecoveryJob stored = repository.find(job.jobId()).orElseThrow();

    assertEquals(job, stored.job());
    assertEquals(ETAG, stored.etag());
  }

  @Test
  void shouldReturnEmptyForMissingEntity() {
    RecoveryJob job = newJob();
    stubReadError(job, storageError(404, "ResourceNotFound"));

    assertTrue(repository.find(job.jobId()).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"TableNotFound", "UnknownError"})
  void shouldNotTreatOther404ErrorsAsMissingJob(String code) {
    RecoveryJob job = newJob();
    UUID jobId = job.jobId();
    TableServiceException failure = storageError(404, code);
    stubReadError(job, failure);

    assertSame(
        failure,
        assertThrows(
            TableServiceException.class,
            () -> repository.find(jobId)));
  }

  @Test
  void shouldPropagateAuthorizationFailure() {
    RecoveryJob job = newJob();
    UUID jobId = job.jobId();
    TableServiceException failure =
        storageError(403, "AuthorizationFailure");
    stubReadError(job, failure);

    assertSame(
        failure,
        assertThrows(
            TableServiceException.class,
            () -> repository.find(jobId)));
  }

  @Test
  void shouldRejectCorruptedStoredJob() {
    RecoveryJob job = newJob();
    UUID jobId = job.jobId();

    TableEntity entity =
        mapper.toEntity(job).addProperty("JobJson", "{}");
    entity.addProperty("odata.etag", ETAG);
    stubRead(job, entity);

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.find(jobId));
  }

  @Test
  void shouldUpdateWithExactEtagAndConcurrencyCheck() {
    RecoveryJob job = newJob();

    repository.update(job, ETAG);

    ArgumentCaptor<TableEntity> captor =
        ArgumentCaptor.forClass(TableEntity.class);

    verify(client).updateEntityWithResponse(
        captor.capture(),
        eq(TableEntityUpdateMode.REPLACE),
        eq(true),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(ETAG, captor.getValue().getETag());
    assertEquals(job, mapper.toJob(captor.getValue()));
  }

  @Test
  void shouldPropagateEtagConflictWithoutApplicationRetry() {
    RecoveryJob job = newJob();
    TableServiceException failure =
        storageError(412, "UpdateConditionNotSatisfied");

    doThrow(failure)
        .when(client)
        .updateEntityWithResponse(
            any(TableEntity.class),
            eq(TableEntityUpdateMode.REPLACE),
            eq(true),
            eq(TIMEOUT),
            eq(Context.NONE));

    assertSame(
        failure,
        assertThrows(
            TableServiceException.class,
            () -> repository.update(job, ETAG)));

    verify(client, times(1)).updateEntityWithResponse(
        any(TableEntity.class),
        eq(TableEntityUpdateMode.REPLACE),
        eq(true),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "*", " * "})
  void shouldRejectMissingOrWildcardEtagBeforeAccessingStorage(String etag) {
    RecoveryJob job = newJob();

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.update(job, etag));

    verifyNoInteractions(client);
  }
}