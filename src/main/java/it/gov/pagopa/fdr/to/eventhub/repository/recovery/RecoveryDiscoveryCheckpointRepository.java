package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RecoveryDiscoveryCheckpointRepository {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final TableClient client;
  private final RecoveryDiscoveryCheckpointTableMapper mapper;

  public RecoveryDiscoveryCheckpointRepository(
      TableClient client,
      RecoveryDiscoveryCheckpointTableMapper mapper) {

    this.client = Objects.requireNonNull(client, "client is required");
    this.mapper = Objects.requireNonNull(mapper, "mapper is required");
  }

  public void create(RecoveryDiscoveryCheckpoint checkpoint) {
    TableEntity entity = mapper.toEntity(checkpoint);

    client.createEntityWithResponse(entity, TIMEOUT, Context.NONE);
  }

  public Optional<StoredRecoveryDiscoveryCheckpoint> find(
      UUID jobId,
      LocalDate day) {

    RecoveryDiscoveryCheckpoint key =
        RecoveryDiscoveryCheckpoint.start(jobId, day);

    TableEntity entity;

    try {
      entity =
          client.getEntityWithResponse(
                  key.partitionKey(),
                  key.rowKey(),
                  null,
                  TIMEOUT,
                  Context.NONE)
              .getValue();
    } catch (TableServiceException exception) {
      if (isMissingEntity(exception)) {
        return Optional.empty();
      }

      throw exception;
    }

    RecoveryDiscoveryCheckpoint checkpoint =
        mapper.toCheckpoint(entity);

    if (!jobId.equals(checkpoint.jobId())
        || !day.equals(checkpoint.day())) {
      throw new IllegalArgumentException(
          "Stored checkpoint does not match the requested job and day");
    }

    return Optional.of(
        new StoredRecoveryDiscoveryCheckpoint(
            checkpoint,
            entity.getETag()));
  }

  public void update(
      RecoveryDiscoveryCheckpoint checkpoint,
      String etag) {

    StoredRecoveryDiscoveryCheckpoint stored =
        new StoredRecoveryDiscoveryCheckpoint(checkpoint, etag);

    TableEntity entity = mapper.toEntity(stored.checkpoint());

    entity.addProperty("odata.etag", stored.etag());

    client.updateEntityWithResponse(
        entity,
        TableEntityUpdateMode.REPLACE,
        true,
        TIMEOUT,
        Context.NONE);
  }

  private static boolean isMissingEntity(
      TableServiceException exception) {

    return exception.getResponse() != null
        && exception.getResponse().getStatusCode() == 404
        && exception.getValue() != null
        && TableErrorCode.RESOURCE_NOT_FOUND.equals(
            exception.getValue().getErrorCode());
  }
}