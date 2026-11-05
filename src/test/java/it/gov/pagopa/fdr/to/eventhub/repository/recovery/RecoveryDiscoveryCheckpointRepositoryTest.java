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
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryDiscoveryCheckpointRepositoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String ETAG = "W/\"version-1\"";

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final TableClient client = mock(TableClient.class);

  private final RecoveryDiscoveryCheckpointTableMapper mapper =
      new RecoveryDiscoveryCheckpointTableMapper();

  private final RecoveryDiscoveryCheckpointRepository repository =
      new RecoveryDiscoveryCheckpointRepository(client, mapper);

  private RecoveryDiscoveryCheckpoint checkpoint() {
    return RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY);
  }

  private TableServiceException storageError(int status, String code) {
    HttpResponse response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);

    return new TableServiceException(
        "Simulated storage error",
        response,
        new TableServiceError(code, "Simulated storage error"));
  }

  private void stubRead(TableEntity entity) {
    RecoveryDiscoveryCheckpoint key = checkpoint();

    when(client.getEntityWithResponse(
            eq(key.partitionKey()),
            eq(key.rowKey()),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenReturn(
            new SimpleResponse<>(
                null, 200, new HttpHeaders(), entity));
  }

  private void stubReadError(TableServiceException failure) {
    RecoveryDiscoveryCheckpoint key = checkpoint();

    when(client.getEntityWithResponse(
            eq(key.partitionKey()),
            eq(key.rowKey()),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);
  }

  @Test
  void shouldCreateWithoutUpsert() {
    RecoveryDiscoveryCheckpoint original = checkpoint();

    repository.create(original);

    ArgumentCaptor<TableEntity> captor =
        ArgumentCaptor.forClass(TableEntity.class);

    verify(client).createEntityWithResponse(
        captor.capture(), eq(TIMEOUT), eq(Context.NONE));

    assertEquals(original, mapper.toCheckpoint(captor.getValue()));
  }

  @Test
  void shouldPropagateDuplicateCreation() {
    RecoveryDiscoveryCheckpoint original = checkpoint();

    TableServiceException failure =
        storageError(409, "EntityAlreadyExists");

    doThrow(failure)
        .when(client)
        .createEntityWithResponse(
            any(TableEntity.class), eq(TIMEOUT), eq(Context.NONE));

    TableServiceException thrown =
        assertThrows(
            TableServiceException.class,
            () -> repository.create(original));

    assertSame(failure, thrown);
  }

  @Test
  void shouldReadCheckpointAndEtag() {
    RecoveryDiscoveryCheckpoint original = checkpoint();

    TableEntity entity = mapper.toEntity(original);
    entity.addProperty("odata.etag", ETAG);
    stubRead(entity);

    StoredRecoveryDiscoveryCheckpoint stored =
        repository.find(JOB_ID, DAY).orElseThrow();

    assertEquals(original, stored.checkpoint());
    assertEquals(ETAG, stored.etag());
  }

  @Test
  void shouldReturnEmptyForMissingEntity() {
    stubReadError(storageError(404, "ResourceNotFound"));

    assertTrue(repository.find(JOB_ID, DAY).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"TableNotFound", "UnknownError"})
  void shouldPropagateOtherNotFoundErrors(String code) {
    TableServiceException failure = storageError(404, code);
    stubReadError(failure);

    TableServiceException thrown =
        assertThrows(
            TableServiceException.class,
            () -> repository.find(JOB_ID, DAY));

    assertSame(failure, thrown);
  }

  @Test
  void shouldPropagateAuthorizationFailure() {
    TableServiceException failure =
        storageError(403, "AuthorizationFailure");

    stubReadError(failure);

    TableServiceException thrown =
        assertThrows(
            TableServiceException.class,
            () -> repository.find(JOB_ID, DAY));

    assertSame(failure, thrown);
  }

  @Test
  void shouldUpdateWithExactEtagAndConcurrencyCheck() {
    RecoveryDiscoveryCheckpoint completed =
        checkpoint().completeDiscovery();

    repository.update(completed, ETAG);

    ArgumentCaptor<TableEntity> captor =
        ArgumentCaptor.forClass(TableEntity.class);

    verify(client).updateEntityWithResponse(
        captor.capture(),
        eq(TableEntityUpdateMode.REPLACE),
        eq(true),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(ETAG, captor.getValue().getETag());
    assertEquals(completed, mapper.toCheckpoint(captor.getValue()));
  }

  @Test
  void shouldPropagateEtagConflictWithoutApplicationRetry() {
    RecoveryDiscoveryCheckpoint original = checkpoint();

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

    TableServiceException thrown =
        assertThrows(
            TableServiceException.class,
            () -> repository.update(original, ETAG));

    assertSame(failure, thrown);

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
  void shouldRejectMissingOrWildcardEtag(String etag) {
    RecoveryDiscoveryCheckpoint original = checkpoint();

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.update(original, etag));

    verifyNoInteractions(client);
  }

  @Test
  void shouldRejectCorruptedStoredCheckpoint() {
    TableEntity entity = mapper.toEntity(checkpoint());

    entity.addProperty("Status", "UNKNOWN");
    entity.addProperty("odata.etag", ETAG);
    stubRead(entity);

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.find(JOB_ID, DAY));
  }

  @Test
  void shouldRejectValidCheckpointFromDifferentDay() {
    RecoveryDiscoveryCheckpoint anotherDay =
        RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY.plusDays(1));

    TableEntity entity = mapper.toEntity(anotherDay);
    entity.addProperty("odata.etag", ETAG);
    stubRead(entity);

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.find(JOB_ID, DAY));
  }
}