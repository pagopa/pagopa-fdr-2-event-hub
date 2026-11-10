package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.rest.SimpleResponse;
import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryPublicationDiscoveryItemRepositoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final TableClient client = mock(TableClient.class);

  private final RecoveryPublicationDiscoveryItemTableMapper mapper =
      new RecoveryPublicationDiscoveryItemTableMapper();

  private final RecoveryPublicationDiscoveryItemRepository repository =
      new RecoveryPublicationDiscoveryItemRepository(client, mapper);

  @Test
  void shouldCreateWithoutReadingFirst() {
    var requested = item(1L, "ORIGIN_UNDETERMINED");

    var result = repository.createOrRead(requested);

    assertEquals(
        RecoveryPublicationDiscoveryItemRepository.SaveStatus.CREATED,
        result.status());

    assertSame(requested, result.item());

    var captor = ArgumentCaptor.forClass(TableEntity.class);

    verify(client).createEntityWithResponse(
        captor.capture(),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(requested, mapper.toItem(captor.getValue()));
    verifyNoMoreInteractions(client);
  }

  @Test
  void shouldReturnIdenticalStoredItem() {
    var requested = item(1L, "ORIGIN_UNDETERMINED");

    stubDuplicate();
    stubRead(requested, mapper.toEntity(requested));

    var result = repository.createOrRead(requested);

    assertEquals(
        RecoveryPublicationDiscoveryItemRepository.SaveStatus
            .ALREADY_PRESENT,
        result.status());

    assertEquals(requested, result.item());

    verifyCreateAndReadOnly(requested);
  }

  @Test
  void shouldKeepStoredDecisionWhenRecomputedDecisionDiffers() {
    var requested = item(1L, "XML_UNRESOLVED");
    var stored = item(1L, "ORIGIN_UNDETERMINED");

    stubDuplicate();
    stubRead(requested, mapper.toEntity(stored));

    var result = repository.createOrRead(requested);

    assertEquals(
        RecoveryPublicationDiscoveryItemRepository.SaveStatus
            .ALREADY_PRESENT,
        result.status());

    assertEquals(stored, result.item());

    verifyCreateAndReadOnly(requested);
  }

  @Test
  void shouldRejectDifferentRevisionForSameFlowId() {
    var requested = item(1L, "ORIGIN_UNDETERMINED");
    var stored = item(2L, "ORIGIN_UNDETERMINED");

    stubDuplicate();
    stubRead(requested, mapper.toEntity(stored));

    assertThrows(
        IllegalStateException.class,
        () -> repository.createOrRead(requested));
  }

  @ParameterizedTest
  @ValueSource(strings = {"UnknownError", "TableBeingDeleted"})
  void shouldPropagateOtherConflictErrors(String code) {
    var requested = item(1L, "ORIGIN_UNDETERMINED");
    var failure = storageError(409, code);

    stubCreateFailure(failure);

    var actual =
        assertThrows(
            TableServiceException.class,
            () -> repository.createOrRead(requested));

    assertSame(failure, actual);

    verify(client).createEntityWithResponse(
        any(TableEntity.class),
        eq(TIMEOUT),
        eq(Context.NONE));

    verifyNoMoreInteractions(client);
  }

  @Test
  void shouldPropagateAuthorizationFailure() {
    var requested = item(1L, "ORIGIN_UNDETERMINED");
    var failure = storageError(403, "AuthorizationFailure");

    stubCreateFailure(failure);

    var actual =
        assertThrows(
            TableServiceException.class,
            () -> repository.createOrRead(requested));

    assertSame(failure, actual);
  }

  @Test
  void shouldPropagateReadFailureAfterConflict() {
    var requested = item(1L, "ORIGIN_UNDETERMINED");
    var failure = storageError(404, "ResourceNotFound");

    stubDuplicate();

    when(client.getEntityWithResponse(
            eq(requested.partitionKey()),
            eq(requested.rowKey()),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    var actual =
        assertThrows(
            TableServiceException.class,
            () -> repository.createOrRead(requested));

    assertSame(failure, actual);
  }

  @Test
  void shouldRejectPreviousSchemaInsteadOfReinterpretingIt() {
    var requested = item(1L, "ORIGIN_UNDETERMINED");
    var storedEntity = mapper.toEntity(requested);
    storedEntity.addProperty("SchemaVersion", "2");

    stubDuplicate();
    stubRead(requested, storedEntity);

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.createOrRead(requested));
  }

  @Test
  void shouldRejectMissingStoredEntity() {
    var requested = item(1L, "ORIGIN_UNDETERMINED");

    stubDuplicate();
    stubRead(requested, null);

    assertThrows(
        IllegalStateException.class,
        () -> repository.createOrRead(requested));
  }

  private void verifyCreateAndReadOnly(
      RecoveryPublicationDiscoveryItem requested) {

    verify(client).createEntityWithResponse(
        any(TableEntity.class),
        eq(TIMEOUT),
        eq(Context.NONE));

    verify(client).getEntityWithResponse(
        eq(requested.partitionKey()),
        eq(requested.rowKey()),
        isNull(),
        eq(TIMEOUT),
        eq(Context.NONE));

    verifyNoMoreInteractions(client);
  }

  private void stubDuplicate() {
    stubCreateFailure(storageError(409, "EntityAlreadyExists"));
  }

  private void stubCreateFailure(TableServiceException failure) {
    doThrow(failure)
        .when(client)
        .createEntityWithResponse(
            any(TableEntity.class),
            eq(TIMEOUT),
            eq(Context.NONE));
  }

  private void stubRead(
      RecoveryPublicationDiscoveryItem requested,
      TableEntity entity) {

    when(client.getEntityWithResponse(
            eq(requested.partitionKey()),
            eq(requested.rowKey()),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenReturn(
            new SimpleResponse<>(
                null, 200, new HttpHeaders(), entity));
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

  private static RecoveryPublicationDiscoveryItem item(
      long revision, String reason) {

    return new RecoveryPublicationDiscoveryItem(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        LocalDate.of(2026, 10, 1),
        new RecoveryFlowCandidate(
            155333L,
            "flow-1",
            revision,
            "psp-1",
            "org-1",
            LocalDateTime.parse("2026-10-05T10:31:35")),
        new RecoveryPublicationDiscoveryItem.Unresolved(
            reason, Map.of()));
  }
}