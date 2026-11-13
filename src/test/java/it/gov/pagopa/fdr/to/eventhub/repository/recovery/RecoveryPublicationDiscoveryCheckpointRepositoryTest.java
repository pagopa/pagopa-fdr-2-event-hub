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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryPublicationDiscoveryCheckpointRepositoryTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String ETAG = "W/\"etag-1\"";

  private final TableClient client = mock(TableClient.class);

  private final RecoveryPublicationDiscoveryCheckpointTableMapper mapper =
      new RecoveryPublicationDiscoveryCheckpointTableMapper();

  private final RecoveryPublicationDiscoveryCheckpointRepository repository =
      new RecoveryPublicationDiscoveryCheckpointRepository(client, mapper);

  @Test
  void shouldCreateCheckpointWithoutUpsert() {
    var checkpoint = checkpoint();

    repository.create(checkpoint);

    var captor = ArgumentCaptor.forClass(TableEntity.class);

    verify(client).createEntityWithResponse(
        captor.capture(),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(checkpoint, mapper.toCheckpoint(captor.getValue()));
    verifyNoMoreInteractions(client);
  }

  @Test
  void shouldReadCheckpointWithSpecificEtag() {
    var entity = mapper.toEntity(checkpoint());
    entity.addProperty("odata.etag", ETAG);

    stubRead(entity);

    var stored = repository.find(JOB_ID, DAY).orElseThrow();

    assertEquals(checkpoint(), stored.checkpoint());
    assertEquals(ETAG, stored.etag());
  }

  @Test
  void shouldReturnEmptyForMissingCheckpoint() {
    stubReadFailure(storageError(404, "ResourceNotFound"));

    assertTrue(repository.find(JOB_ID, DAY).isEmpty());
  }

  @Test
  void shouldPropagateMissingTable() {
    var failure = storageError(404, "TableNotFound");
    stubReadFailure(failure);

    var actual =
        assertThrows(
            TableServiceException.class,
            () -> repository.find(JOB_ID, DAY));

    assertSame(failure, actual);
  }

  @Test
  void shouldPropagateAuthorizationFailure() {
    var failure = storageError(403, "AuthorizationFailure");
    stubReadFailure(failure);

    var actual =
        assertThrows(
            TableServiceException.class,
            () -> repository.find(JOB_ID, DAY));

    assertSame(failure, actual);
  }

  @Test
  void shouldRejectOldCheckpointSchema() {
    var entity = mapper.toEntity(checkpoint());
    entity.addProperty("SchemaVersion", "1");

    stubRead(entity);

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.find(JOB_ID, DAY));
  }

  @Test
  void shouldRejectCheckpointOfAnotherJob() {
    var another =
        RecoveryPublicationDiscoveryCheckpoint.start(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            DAY);

    var entity = mapper.toEntity(another);
    entity.addProperty("odata.etag", ETAG);

    stubRead(entity);

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.find(JOB_ID, DAY));
  }

  @Test
  void shouldRejectMissingEtag() {
    stubRead(mapper.toEntity(checkpoint()));

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.find(JOB_ID, DAY));
  }

  @Test
  void shouldRejectEmptySuccessfulResponse() {
    stubRead(null);

    assertThrows(
        IllegalStateException.class,
        () -> repository.find(JOB_ID, DAY));
  }

  @Test
  void shouldUpdateOnlyIfEtagIsUnchanged() {
    var checkpoint = checkpoint();

    repository.update(checkpoint, ETAG);

    var captor = ArgumentCaptor.forClass(TableEntity.class);

    verify(client).updateEntityWithResponse(
        captor.capture(),
        eq(TableEntityUpdateMode.REPLACE),
        eq(true),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(ETAG, captor.getValue().getETag());
    assertEquals(checkpoint, mapper.toCheckpoint(captor.getValue()));

    verifyNoMoreInteractions(client);
  }

  @Test
  void shouldPropagateStaleEtagWithoutRetry() {
    var checkpoint = checkpoint();
    var failure = storageError(412, "UpdateConditionNotSatisfied");

    doThrow(failure)
        .when(client)
        .updateEntityWithResponse(
            any(TableEntity.class),
            eq(TableEntityUpdateMode.REPLACE),
            eq(true),
            eq(TIMEOUT),
            eq(Context.NONE));

    var actual =
        assertThrows(
            TableServiceException.class,
            () -> repository.update(checkpoint, ETAG));

    assertSame(failure, actual);

    verify(client).updateEntityWithResponse(
        any(TableEntity.class),
        eq(TableEntityUpdateMode.REPLACE),
        eq(true),
        eq(TIMEOUT),
        eq(Context.NONE));

    verifyNoMoreInteractions(client);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "*", " * "})
  void shouldRejectNonSpecificEtagBeforeStorageAccess(String etag) {
    var checkpoint = checkpoint();

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.update(checkpoint, etag));

    verifyNoInteractions(client);
  }

  private void stubRead(TableEntity entity) {
    var key = checkpoint();

    when(client.getEntityWithResponse(
            eq(key.partitionKey()),
            eq(RecoveryPublicationDiscoveryCheckpoint.ROW_KEY),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenReturn(
            new SimpleResponse<>(
                null, 200, new HttpHeaders(), entity));
  }

  private void stubReadFailure(TableServiceException failure) {
    var key = checkpoint();

    when(client.getEntityWithResponse(
            eq(key.partitionKey()),
            eq(RecoveryPublicationDiscoveryCheckpoint.ROW_KEY),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);
  }

  private static RecoveryPublicationDiscoveryCheckpoint checkpoint() {
    return RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);
  }

  private static TableServiceException storageError(
      int status, String code) {

    var response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);

    return new TableServiceException(
        "Simulated storage error",
        response,
        new TableServiceError(code, "Simulated storage error"));
  }
}