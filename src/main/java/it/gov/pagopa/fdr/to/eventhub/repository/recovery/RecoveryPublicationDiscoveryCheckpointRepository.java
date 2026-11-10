package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RecoveryPublicationDiscoveryCheckpointRepository {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String ETAG_PROPERTY = "odata.etag";

  private final TableClient client;
  private final RecoveryPublicationDiscoveryCheckpointTableMapper mapper;

  public RecoveryPublicationDiscoveryCheckpointRepository(
      TableClient client,
      RecoveryPublicationDiscoveryCheckpointTableMapper mapper) {

    this.client = Objects.requireNonNull(client, "client is required");
    this.mapper = Objects.requireNonNull(mapper, "mapper is required");
  }

  public void create(
      RecoveryPublicationDiscoveryCheckpoint checkpoint) {

    var entity = mapper.toEntity(checkpoint);

    client.createEntityWithResponse(entity, TIMEOUT, Context.NONE);
  }

  public Optional<StoredRecoveryPublicationDiscoveryCheckpoint> find(
      UUID jobId,
      LocalDate day) {

    var key =
        RecoveryPublicationDiscoveryCheckpoint.start(jobId, day);

    TableEntity entity;

    try {
      var response =
          client.getEntityWithResponse(
              key.partitionKey(),
              key.rowKey(),
              null,
              TIMEOUT,
              Context.NONE);

      if (response == null || response.getValue() == null) {
        throw new IllegalStateException(
            "ADX recovery checkpoint storage returned no entity");
      }

      entity = response.getValue();
    } catch (TableServiceException exception) {
      if (isMissingEntity(exception)) {
        return Optional.empty();
      }

      throw exception;
    }

    var checkpoint = mapper.toCheckpoint(entity);

    if (!jobId.equals(checkpoint.jobId())
        || !day.equals(checkpoint.day())) {
      throw new IllegalArgumentException(
          "Stored checkpoint belongs to a different job or day");
    }

    return Optional.of(
        new StoredRecoveryPublicationDiscoveryCheckpoint(
            checkpoint, entity.getETag()));
  }

  public void update(
      RecoveryPublicationDiscoveryCheckpoint checkpoint,
      String etag) {

    var stored =
        new StoredRecoveryPublicationDiscoveryCheckpoint(
            checkpoint, etag);

    var entity = mapper.toEntity(stored.checkpoint());
    entity.addProperty(ETAG_PROPERTY, stored.etag());

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