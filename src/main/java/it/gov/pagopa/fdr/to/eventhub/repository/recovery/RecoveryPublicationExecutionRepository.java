package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationExecution;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RecoveryPublicationExecutionRepository {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final TableClient client;
  private final RecoveryPublicationExecutionTableMapper mapper;

  public RecoveryPublicationExecutionRepository(
      TableClient client,
      RecoveryPublicationExecutionTableMapper mapper) {

    this.client =
        Objects.requireNonNull(client, "client is required");

    this.mapper =
        Objects.requireNonNull(mapper, "mapper is required");
  }

  public StoredRecoveryPublicationExecution createOrRead(
      RecoveryPublicationExecution requested) {

    Objects.requireNonNull(requested, "requested is required");

    if (requested.status()
        != RecoveryPublicationExecution.Status.PENDING) {

      throw new IllegalArgumentException(
          "An execution must be created in PENDING status");
    }

    var entity = mapper.toEntity(requested);

    try {
      client.createEntityWithResponse(
          entity, TIMEOUT, Context.NONE);

    } catch (TableServiceException exception) {
      if (!matchesError(
          exception, 409, TableErrorCode.ENTITY_ALREADY_EXISTS)) {

        throw exception;
      }
    }

    var stored =
        find(
            requested.jobId(),
            requested.day(),
            requested.source().candidate().flowId())
            .orElseThrow(
                () -> new IllegalStateException(
                    "Created or existing execution was not returned"));

    if (!requested.source().equals(stored.execution().source())) {
      throw new IllegalStateException(
          "Existing execution refers to a different source snapshot");
    }

    return stored;
  }

  public Optional<StoredRecoveryPublicationExecution> find(
      UUID jobId,
      LocalDate day,
      long flowId) {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");

    if (flowId <= 0) {
      throw new IllegalArgumentException("flowId must be positive");
    }

    String partitionKey = jobId + "_" + day;

    String rowKey =
        String.format(
            Locale.ROOT, "EXECUTION_FLOW_%020d", flowId);

    final TableEntity entity;

    try {
      var response =
          client.getEntityWithResponse(
              partitionKey,
              rowKey,
              null,
              TIMEOUT,
              Context.NONE);

      if (response == null || response.getValue() == null) {
        throw new IllegalStateException(
            "Execution storage returned no entity");
      }

      entity = response.getValue();

    } catch (TableServiceException exception) {
      if (matchesError(
          exception, 404, TableErrorCode.RESOURCE_NOT_FOUND)) {

        return Optional.empty();
      }

      throw exception;
    }

    var execution = mapper.toExecution(entity);

    if (!partitionKey.equals(execution.partitionKey())
        || !rowKey.equals(execution.rowKey())) {

      throw new IllegalArgumentException(
          "Stored execution belongs to a different publication");
    }

    return Optional.of(
        new StoredRecoveryPublicationExecution(
            execution, entity.getETag()));
  }

  public void update(
      RecoveryPublicationExecution execution,
      String etag) {

    var stored =
        new StoredRecoveryPublicationExecution(execution, etag);

    var entity = mapper.toEntity(stored.execution());
    entity.addProperty("odata.etag", stored.etag());

    client.updateEntityWithResponse(
        entity,
        TableEntityUpdateMode.REPLACE,
        true,
        TIMEOUT,
        Context.NONE);
  }

  private static boolean matchesError(
      TableServiceException exception,
      int status,
      TableErrorCode code) {

    return exception.getResponse() != null
        && exception.getResponse().getStatusCode() == status
        && exception.getValue() != null
        && code.equals(exception.getValue().getErrorCode());
  }
}