package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import java.time.Duration;
import java.util.Objects;

public final class RecoveryDiscoveryItemRepository {

  public enum SaveResult {
    CREATED,
    ALREADY_PRESENT
  }

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final TableClient client;
  private final RecoveryDiscoveryItemTableMapper mapper;

  public RecoveryDiscoveryItemRepository(
      TableClient client,
      RecoveryDiscoveryItemTableMapper mapper) {

    this.client = Objects.requireNonNull(client, "client is required");
    this.mapper = Objects.requireNonNull(mapper, "mapper is required");
  }

  public SaveResult createIfAbsent(RecoveryDiscoveryItem item) {
    TableEntity entity = mapper.toEntity(item);

    try {
      client.createEntityWithResponse(entity, TIMEOUT, Context.NONE);
      return SaveResult.CREATED;
    } catch (TableServiceException exception) {
      if (!isEntityAlreadyPresent(exception)) {
        throw exception;
      }
    }

    TableEntity existingEntity =
        client.getEntityWithResponse(
                item.partitionKey(),
                item.rowKey(),
                null,
                TIMEOUT,
                Context.NONE)
            .getValue();

    RecoveryDiscoveryItem existing = mapper.toItem(existingEntity);

    if (!item.equals(existing)) {
      throw new IllegalStateException(
          "Stored discovery item differs from the requested item");
    }

    return SaveResult.ALREADY_PRESENT;
  }

  private static boolean isEntityAlreadyPresent(TableServiceException exception) {

      return exception.getResponse() != null 
              && exception.getResponse().getStatusCode() == 409
              && exception.getValue() != null
              && TableErrorCode.ENTITY_ALREADY_EXISTS.equals(
                      exception.getValue().getErrorCode());
  }
}