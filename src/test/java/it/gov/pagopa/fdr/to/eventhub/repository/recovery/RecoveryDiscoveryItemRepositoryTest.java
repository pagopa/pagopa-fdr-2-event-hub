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
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFlowPresenceIndex;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryDiscoveryItemRepositoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final TableClient client = mock(TableClient.class);

  private final RecoveryDiscoveryItemTableMapper mapper =
      new RecoveryDiscoveryItemTableMapper();

  private final RecoveryDiscoveryItemRepository repository =
      new RecoveryDiscoveryItemRepository(client, mapper);

  private RecoveryDiscoveryItem item(
      RecoveryFlowPresenceIndex.Presence presence,
      long revision) {

    return new RecoveryDiscoveryItem(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        LocalDate.of(2026, 10, 1),
        new RecoveryFlowCandidate(
            68L,
            "flow-1",
            revision,
            "88888888888",
            "77777777777",
            LocalDateTime.of(2026, 10, 2, 12, 43, 18, 448_000_000)),
        presence,
        presence == RecoveryFlowPresenceIndex.Presence.ABSENT
            ? 0L
            : 25L);
  }

  private RecoveryDiscoveryItem absentItem() {
    return item(RecoveryFlowPresenceIndex.Presence.ABSENT, 1L);
  }

  private TableServiceException storageError(int status, String code) {
    HttpResponse response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);

    return new TableServiceException(
        "Simulated storage error",
        response,
        new TableServiceError(code, "Simulated storage error"));
  }

  private void stubCreateFailure(TableServiceException failure) {
    doThrow(failure)
        .when(client)
        .createEntityWithResponse(
            any(TableEntity.class),
            eq(TIMEOUT),
            eq(Context.NONE));
  }

  private void stubDuplicate() {
    stubCreateFailure(storageError(409, "EntityAlreadyExists"));
  }

  private void stubRead(
      RecoveryDiscoveryItem requested,
      TableEntity storedEntity) {

    when(client.getEntityWithResponse(
            eq(requested.partitionKey()),
            eq(requested.rowKey()),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenReturn(
            new SimpleResponse<>(
                null, 200, new HttpHeaders(), storedEntity));
  }

  @Test
  void shouldCreateNewItemWithoutReadingItFirst() {
    RecoveryDiscoveryItem requested = absentItem();

    RecoveryDiscoveryItemRepository.SaveResult result =
        repository.createIfAbsent(requested);

    assertEquals(
        RecoveryDiscoveryItemRepository.SaveResult.CREATED,
        result);

    ArgumentCaptor<TableEntity> captor =
        ArgumentCaptor.forClass(TableEntity.class);

    verify(client).createEntityWithResponse(
        captor.capture(),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(requested, mapper.toItem(captor.getValue()));
    verifyNoMoreInteractions(client);
  }

  @Test
  void shouldAcceptIdenticalExistingItem() {
    RecoveryDiscoveryItem requested = absentItem();

    stubDuplicate();
    stubRead(requested, mapper.toEntity(requested));

    RecoveryDiscoveryItemRepository.SaveResult result =
        repository.createIfAbsent(requested);

    assertEquals(
        RecoveryDiscoveryItemRepository.SaveResult.ALREADY_PRESENT,
        result);

    verify(client).createEntityWithResponse(
        any(TableEntity.class), eq(TIMEOUT), eq(Context.NONE));

    verify(client).getEntityWithResponse(
        eq(requested.partitionKey()),
        eq(requested.rowKey()),
        isNull(),
        eq(TIMEOUT),
        eq(Context.NONE));

    verifyNoMoreInteractions(client);
  }

  @Test
  void shouldRejectDifferentPresenceForSameKey() {
    RecoveryDiscoveryItem requested = absentItem();

    RecoveryDiscoveryItem existing =
        item(
            RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED,
            1L);

    stubDuplicate();
    stubRead(requested, mapper.toEntity(existing));

    assertThrows(
        IllegalStateException.class,
        () -> repository.createIfAbsent(requested));
  }

  @Test
  void shouldRejectDifferentRevisionForSameDatabaseId() {
    RecoveryDiscoveryItem requested = absentItem();
    RecoveryDiscoveryItem existing =
        item(RecoveryFlowPresenceIndex.Presence.ABSENT, 2L);

    stubDuplicate();
    stubRead(requested, mapper.toEntity(existing));

    assertThrows(
        IllegalStateException.class,
        () -> repository.createIfAbsent(requested));
  }

  @ParameterizedTest
  @ValueSource(strings = {"UnknownError", "TableBeingDeleted"})
  void shouldPropagateOtherConflictErrors(String code) {
    RecoveryDiscoveryItem requested = absentItem();
    TableServiceException failure = storageError(409, code);

    stubCreateFailure(failure);

    TableServiceException thrown =
        assertThrows(
            TableServiceException.class,
            () -> repository.createIfAbsent(requested));

    assertSame(failure, thrown);

    verify(client).createEntityWithResponse(
        any(TableEntity.class), eq(TIMEOUT), eq(Context.NONE));

    verifyNoMoreInteractions(client);
  }

  @Test
  void shouldPropagateAuthorizationFailure() {
    RecoveryDiscoveryItem requested = absentItem();

    TableServiceException failure =
        storageError(403, "AuthorizationFailure");

    stubCreateFailure(failure);

    TableServiceException thrown =
        assertThrows(
            TableServiceException.class,
            () -> repository.createIfAbsent(requested));

    assertSame(failure, thrown);
  }

  @Test
  void shouldPropagateFailureWhenReadingExistingItem() {
    RecoveryDiscoveryItem requested = absentItem();

    TableServiceException failure =
        storageError(404, "ResourceNotFound");

    stubDuplicate();

    when(client.getEntityWithResponse(
            eq(requested.partitionKey()),
            eq(requested.rowKey()),
            isNull(),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    TableServiceException thrown =
        assertThrows(
            TableServiceException.class,
            () -> repository.createIfAbsent(requested));

    assertSame(failure, thrown);
  }

  @Test
  void shouldRejectCorruptedExistingItem() {
    RecoveryDiscoveryItem requested = absentItem();

    TableEntity corrupted = mapper.toEntity(requested);
    corrupted.addProperty("Presence", "UNKNOWN");

    stubDuplicate();
    stubRead(requested, corrupted);

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.createIfAbsent(requested));
  }
}