package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableTransactionAction;
import com.azure.data.tables.models.TableTransactionActionType;
import com.azure.data.tables.models.TableTransactionFailedException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RecoveryDiscoveryBatchRepository {

  public record BatchSaveResult(int created, int alreadyPresent) {
  }

  private static final int MAX_BATCH_SIZE = 100;
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final TableClient client;
  private final RecoveryDiscoveryItemTableMapper mapper;
  private final RecoveryDiscoveryItemRepository singleItemRepository;

  public RecoveryDiscoveryBatchRepository(
      TableClient client,
      RecoveryDiscoveryItemTableMapper mapper) {

    this(
        client,
        mapper,
        new RecoveryDiscoveryItemRepository(client, mapper));
  }

  RecoveryDiscoveryBatchRepository(
      TableClient client,
      RecoveryDiscoveryItemTableMapper mapper,
      RecoveryDiscoveryItemRepository singleItemRepository) {

    this.client = Objects.requireNonNull(client, "client is required");
    this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    this.singleItemRepository =
        Objects.requireNonNull(
            singleItemRepository,
            "singleItemRepository is required");
  }

  public BatchSaveResult createIfAbsent(
      List<RecoveryDiscoveryItem> items) {

    List<RecoveryDiscoveryItem> snapshot =
        List.copyOf(Objects.requireNonNull(items, "items is required"));

    if (snapshot.isEmpty()) {
      return new BatchSaveResult(0, 0);
    }

    validateBatch(snapshot);

    List<TableTransactionAction> actions =
        snapshot.stream()
            .map(mapper::toEntity)
            .map(
                entity ->
                    new TableTransactionAction(
                        TableTransactionActionType.CREATE,
                        entity))
            .toList();

    try {
      client.submitTransactionWithResponse(
          actions, TIMEOUT, Context.NONE);

      return new BatchSaveResult(snapshot.size(), 0);
    } catch (TableTransactionFailedException exception) {
      if (!isDuplicateConflict(exception)) {
        throw exception;
      }
    }

    return reconcileExistingItems(snapshot);
  }

  private static void validateBatch(
      List<RecoveryDiscoveryItem> items) {

    if (items.size() > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "A discovery batch must contain at most 100 items");
    }

    String partitionKey = items.get(0).partitionKey();
    Set<String> rowKeys = new HashSet<>();

    for (RecoveryDiscoveryItem item : items) {
      if (!partitionKey.equals(item.partitionKey())) {
        throw new IllegalArgumentException(
            "All discovery batch items must share the same partition");
      }

      if (!rowKeys.add(item.rowKey())) {
        throw new IllegalArgumentException(
            "A discovery batch must not contain duplicate row keys");
      }
    }
  }

  private BatchSaveResult reconcileExistingItems(
      List<RecoveryDiscoveryItem> items) {

    int created = 0;
    int alreadyPresent = 0;

    for (RecoveryDiscoveryItem item : items) {
      RecoveryDiscoveryItemRepository.SaveResult result =
          singleItemRepository.createIfAbsent(item);

      switch (result) {
        case CREATED -> created++;
        case ALREADY_PRESENT -> alreadyPresent++;
      }
    }

    return new BatchSaveResult(created, alreadyPresent);
  }

  private static boolean isDuplicateConflict(TableTransactionFailedException exception) {
      return exception.getValue() != null
              && TableErrorCode.ENTITY_ALREADY_EXISTS.equals(exception.getValue().getErrorCode());
  }
}