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
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationExecution;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryPublicationExecutionRepositoryTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final UUID ATTEMPT_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 5);

  private static final Instant NOW =
      Instant.parse("2026-11-11T12:00:00Z");

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String ETAG = "W/\"etag-1\"";
  private static final String EVENTS = "AcknowledgedEvents";

  private final TableClient client = mock(TableClient.class);

  private final RecoveryPublicationExecutionTableMapper mapper =
      new RecoveryPublicationExecutionTableMapper();

  private final RecoveryPublicationExecutionRepository repository =
      new RecoveryPublicationExecutionRepository(client, mapper);

  @ParameterizedTest
  @EnumSource(RecoveryPublicationExecution.Status.class)
  void shouldRoundTripEveryExecutionStatus(
      RecoveryPublicationExecution.Status status) {

    var execution = execution(status);

    assertEquals(
        execution,
        mapper.toExecution(mapper.toEntity(execution)));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "SchemaVersion",
      "RecordType",
      "SourceJson",
      "PartitionKey",
      "RowKey"
  })
  void shouldRejectInvalidStoredProperty(String property) {
    var entity = mapper.toEntity(pending());
    entity.addProperty(property, "invalid");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toExecution(entity));
  }

  @Test
  void shouldRejectInt32AcknowledgementCount() {
    var entity =
        mapper.toEntity(
            execution(RecoveryPublicationExecution.Status.SENT));

    entity.addProperty(EVENTS, Integer.valueOf(1));

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toExecution(entity));
  }

  @Test
  void shouldRejectAcknowledgementCountersOnUncertainExecution() {
    var entity =
        mapper.toEntity(
            execution(RecoveryPublicationExecution.Status.UNCERTAIN));

    entity.addProperty(EVENTS, 1L);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toExecution(entity));
  }

  @Test
  void shouldCreateAndReadExecutionWithEtag() {
    var requested = pending();
    stubRead(withEtag(requested));

    var stored = repository.createOrRead(requested);

    assertEquals(requested, stored.execution());
    assertEquals(ETAG, stored.etag());

    var captured = ArgumentCaptor.forClass(TableEntity.class);

    verify(client).createEntityWithResponse(
        captured.capture(), eq(TIMEOUT), eq(Context.NONE));

    assertEquals(
        requested, mapper.toExecution(captured.getValue()));
  }

  @Test
  void shouldPreserveExistingSendingAttemptOnCreationConflict() {
    var requested = pending();
    var existing =
        execution(RecoveryPublicationExecution.Status.SENDING);

    var conflict = storageError(409, "EntityAlreadyExists");

    doThrow(conflict)
        .when(client)
        .createEntityWithResponse(
            any(TableEntity.class),
            eq(TIMEOUT),
            eq(Context.NONE));

    stubRead(withEtag(existing));

    var stored = repository.createOrRead(requested);

    assertEquals(existing, stored.execution());
    assertEquals(ATTEMPT_ID, stored.execution().attemptId());
  }

  @Test
  void shouldRejectExistingExecutionWithDifferentSource() {
    var requested = pending();
    var original = requested.source();

    var differentSource =
        new RecoveryPublicationSource(
            original.candidate(),
            new RecoveryBlobSnapshot(
                original.blob().containerName(),
                original.blob().blobName(),
                "\"different-etag\"",
                original.blob().contentLength(),
                original.blob().metadata()),
            original.sourceType(),
            original.sessionId(),
            original.eventFlowDate());

    var existing =
        new RecoveryPublicationExecution(
            JOB_ID,
            DAY,
            differentSource,
            RecoveryPublicationExecution.Status.PENDING,
            null,
            null,
            NOW,
            NOW);

    doThrow(storageError(409, "EntityAlreadyExists"))
        .when(client)
        .createEntityWithResponse(
            any(TableEntity.class),
            eq(TIMEOUT),
            eq(Context.NONE));

    stubRead(withEtag(existing));

    assertThrows(
        IllegalStateException.class,
        () -> repository.createOrRead(requested));
  }

  @Test
  void shouldReturnEmptyOnlyForMissingEntity() {
    stubReadFailure(storageError(404, "ResourceNotFound"));

    assertTrue(repository.find(JOB_ID, DAY, 1L).isEmpty());
  }

  @Test
  void shouldPropagateMissingTable() {
    var failure = storageError(404, "TableNotFound");
    stubReadFailure(failure);

    var actual =
        assertThrows(
            TableServiceException.class,
            () -> repository.find(JOB_ID, DAY, 1L));

    assertSame(failure, actual);
  }

  @Test
  void shouldRejectStoredExecutionWithoutEtag() {
    stubRead(mapper.toEntity(pending()));

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.find(JOB_ID, DAY, 1L));
  }

  @Test
  void shouldUpdateUsingSpecificEtag() {
    var sending =
        execution(RecoveryPublicationExecution.Status.SENDING);

    repository.update(sending, ETAG);

    var captured = ArgumentCaptor.forClass(TableEntity.class);

    verify(client).updateEntityWithResponse(
        captured.capture(),
        eq(TableEntityUpdateMode.REPLACE),
        eq(true),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(ETAG, captured.getValue().getETag());
    assertEquals(
        sending, mapper.toExecution(captured.getValue()));

    verifyNoMoreInteractions(client);
  }

  @Test
  void shouldPropagateStaleEtagWithoutRefreshingOrRetrying() {
    var sending =
        execution(RecoveryPublicationExecution.Status.SENDING);

    var failure =
        storageError(412, "UpdateConditionNotSatisfied");

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
            () -> repository.update(sending, ETAG));

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
    var execution = pending();

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.update(execution, etag));

    verifyNoInteractions(client);
  }

  private TableEntity withEtag(
      RecoveryPublicationExecution execution) {

    return mapper.toEntity(execution)
        .addProperty("odata.etag", ETAG);
  }

  private void stubRead(TableEntity entity) {
    var key = pending();

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

  private void stubReadFailure(TableServiceException failure) {
    var key = pending();

    when(client.getEntityWithResponse(
            eq(key.partitionKey()),
            eq(key.rowKey()),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);
  }

  private static RecoveryPublicationExecution execution(
      RecoveryPublicationExecution.Status status) {

    var pending = pending();

    return switch (status) {
      case PENDING -> pending;
      case SENDING ->
          pending.startSending(ATTEMPT_ID, NOW.plusSeconds(1));
      case SENT ->
          pending.startSending(ATTEMPT_ID, NOW.plusSeconds(1))
              .markSent(2L, 1L, NOW.plusSeconds(2));
      case SKIPPED_PRESENT ->
          pending.skipAlreadyPresent(NOW.plusSeconds(1));
      case UNCERTAIN ->
          pending.startSending(ATTEMPT_ID, NOW.plusSeconds(1))
              .markUncertain(NOW.plusSeconds(2));
    };
  }

  private static RecoveryPublicationExecution pending() {
    var candidate =
        new RecoveryFlowCandidate(
            1L,
            "recovery-flow",
            1L,
            "88888888888",
            "15376371009",
            LocalDateTime.parse("2026-10-05T12:50:26.001454"));

    var source =
        new RecoveryPublicationSource(
            candidate,
            new RecoveryBlobSnapshot(
                "fdr3-flows",
                "original.json.zip",
                "\"original-etag\"",
                100L,
                Map.of(
                    "sessionId",
                    "a92570e7-d5f5-49da-bb8f-0226e2d95ac7")),
            RecoveryPublicationSource.SourceType.FDR3_JSON,
            "a92570e7-d5f5-49da-bb8f-0226e2d95ac7",
            Instant.parse("2026-10-05T12:50:26.001454Z"));

    var item =
        new RecoveryPublicationDiscoveryItem(
            JOB_ID,
            DAY,
            candidate,
            new RecoveryPublicationDiscoveryItem.Compared(
                source,
                RecoveryPublicationDiscoveryItem.Status.ABSENT,
                0L));

    return RecoveryPublicationExecution.pending(item, NOW);
  }

  private static TableServiceException storageError(
      int status,
      String code) {

    var response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);

    return new TableServiceException(
        "Simulated storage error",
        response,
        new TableServiceError(code, "Simulated storage error"));
  }
}