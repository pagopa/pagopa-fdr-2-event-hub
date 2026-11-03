package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpResponse;
import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableTransactionAction;
import com.azure.data.tables.models.TableTransactionActionType;
import com.azure.data.tables.models.TableTransactionFailedException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFlowPresenceIndex;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryDiscoveryBatchRepositoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private final TableClient client = mock(TableClient.class);

  private final RecoveryDiscoveryItemTableMapper mapper =
      new RecoveryDiscoveryItemTableMapper();

  private final RecoveryDiscoveryItemRepository singleRepository =
      mock(RecoveryDiscoveryItemRepository.class);

  private final RecoveryDiscoveryBatchRepository repository =
      new RecoveryDiscoveryBatchRepository(
          client, mapper, singleRepository);

  private RecoveryDiscoveryItem item(long flowId, LocalDate day) {
    return new RecoveryDiscoveryItem(
        JOB_ID,
        day,
        new RecoveryFlowCandidate(
            flowId,
            "flow-" + flowId,
            1L,
            "88888888888",
            "77777777777"),
        RecoveryFlowPresenceIndex.Presence.ABSENT,
        0L);
  }

  private TableTransactionFailedException batchError(
      int status,
      String code) {

    HttpResponse response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);

    return new TableTransactionFailedException(
        "Simulated batch failure",
        response,
        new TableServiceError(code, "Simulated batch failure"),
        0);
  }

  private void stubBatchFailure(
      TableTransactionFailedException failure) {

    doThrow(failure)
        .when(client)
        .submitTransactionWithResponse(
            anyList(), eq(TIMEOUT), eq(Context.NONE));
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldCreateOneHundredItemsWithOneTransaction() {
    List<RecoveryDiscoveryItem> items =
        LongStream.rangeClosed(1, 100)
            .mapToObj(id -> item(id, DAY))
            .toList();

    RecoveryDiscoveryBatchRepository.BatchSaveResult result =
        repository.createIfAbsent(items);

    assertEquals(100, result.created());
    assertEquals(0, result.alreadyPresent());

    ArgumentCaptor<List<TableTransactionAction>> captor =
        ArgumentCaptor.forClass(List.class);

    verify(client).submitTransactionWithResponse(
        captor.capture(), eq(TIMEOUT), eq(Context.NONE));

    List<TableTransactionAction> actions = captor.getValue();

    assertEquals(100, actions.size());

    for (int i = 0; i < actions.size(); i++) {
      assertEquals(
          TableTransactionActionType.CREATE,
          actions.get(i).getActionType());

      assertEquals(
          items.get(i),
          mapper.toItem(actions.get(i).getEntity()));
    }

    verifyNoMoreInteractions(client);
    verifyNoInteractions(singleRepository);
  }

  @Test
  void shouldSkipStorageForEmptyBatch() {
    List<RecoveryDiscoveryItem> items = List.of();

    RecoveryDiscoveryBatchRepository.BatchSaveResult result =
        repository.createIfAbsent(items);

    assertEquals(0, result.created());
    assertEquals(0, result.alreadyPresent());

    verifyNoInteractions(client, singleRepository);
  }

  @Test
  void shouldRejectMoreThanOneHundredItemsBeforeStorage() {
    List<RecoveryDiscoveryItem> items =
        LongStream.rangeClosed(1, 101)
            .mapToObj(id -> item(id, DAY))
            .toList();

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.createIfAbsent(items));

    verifyNoInteractions(client, singleRepository);
  }

  @Test
  void shouldRejectMixedPartitionsBeforeStorage() {
    List<RecoveryDiscoveryItem> items =
        List.of(
            item(1L, DAY),
            item(2L, DAY.plusDays(1)));

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.createIfAbsent(items));

    verifyNoInteractions(client, singleRepository);
  }

  @Test
  void shouldRejectDuplicateKeysBeforeStorage() {
    RecoveryDiscoveryItem first = item(1L, DAY);
    List<RecoveryDiscoveryItem> items = List.of(first, first);

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.createIfAbsent(items));

    verifyNoInteractions(client, singleRepository);
  }

  @Test
  void shouldReconcileItemsOnlyAfterDuplicateBatchConflict() {
    RecoveryDiscoveryItem first = item(1L, DAY);
    RecoveryDiscoveryItem second = item(2L, DAY);
    List<RecoveryDiscoveryItem> items = List.of(first, second);

    stubBatchFailure(batchError(409, "EntityAlreadyExists"));

    when(singleRepository.createIfAbsent(first))
        .thenReturn(
            RecoveryDiscoveryItemRepository.SaveResult.ALREADY_PRESENT);

    when(singleRepository.createIfAbsent(second))
        .thenReturn(
            RecoveryDiscoveryItemRepository.SaveResult.CREATED);

    RecoveryDiscoveryBatchRepository.BatchSaveResult result =
        repository.createIfAbsent(items);

    assertEquals(1, result.created());
    assertEquals(1, result.alreadyPresent());

    verify(singleRepository).createIfAbsent(first);
    verify(singleRepository).createIfAbsent(second);
  }

  @Test
  void shouldStopReconciliationOnContentConflict() {
    RecoveryDiscoveryItem first = item(1L, DAY);
    RecoveryDiscoveryItem second = item(2L, DAY);
    List<RecoveryDiscoveryItem> items = List.of(first, second);

    stubBatchFailure(batchError(409, "EntityAlreadyExists"));

    IllegalStateException failure =
        new IllegalStateException("Simulated content conflict");

    when(singleRepository.createIfAbsent(first)).thenThrow(failure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> repository.createIfAbsent(items));

    assertSame(failure, thrown);

    verify(singleRepository, never()).createIfAbsent(second);
  }

  @Test
  void shouldPropagateOtherConflictWithoutReconciliation() {
    List<RecoveryDiscoveryItem> items = List.of(item(1L, DAY));

    TableTransactionFailedException failure =
        batchError(409, "UnknownError");

    stubBatchFailure(failure);

    TableTransactionFailedException thrown =
        assertThrows(
            TableTransactionFailedException.class,
            () -> repository.createIfAbsent(items));

    assertSame(failure, thrown);
    verifyNoInteractions(singleRepository);
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 500})
  void shouldPropagateOtherFailuresWithoutReconciliation(int status) {
    List<RecoveryDiscoveryItem> items = List.of(item(1L, DAY));

    TableTransactionFailedException failure =
        batchError(status, "SimulatedError");

    stubBatchFailure(failure);

    TableTransactionFailedException thrown =
        assertThrows(
            TableTransactionFailedException.class,
            () -> repository.createIfAbsent(items));

    assertSame(failure, thrown);
    verifyNoInteractions(singleRepository);
  }
}