package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RecoveryJobRepository {

  private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(30);

  private final TableClient tableClient;
  private final RecoveryJobTableMapper mapper;

  public RecoveryJobRepository(
      TableClient tableClient, RecoveryJobTableMapper mapper) {

    this.tableClient = Objects.requireNonNull(tableClient, "tableClient is required");
    this.mapper = Objects.requireNonNull(mapper, "mapper is required");
  }

  public void create(RecoveryJob job) {
    TableEntity entity = mapper.toEntity(job);

    tableClient.createEntityWithResponse(
        entity, OPERATION_TIMEOUT, Context.NONE);
  }

  public Optional<StoredRecoveryJob> find(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId is required");

    final TableEntity entity;

    try {
      entity =
          tableClient.getEntityWithResponse(
                  jobId.toString(),
                  RecoveryJobTableMapper.ROW_KEY,
                  null,
                  OPERATION_TIMEOUT,
                  Context.NONE)
              .getValue();
    } catch (TableServiceException e) {
      if (isMissingEntity(e)) {
        return Optional.empty();
      }

      throw e;
    }

    return Optional.of(
        new StoredRecoveryJob(mapper.toJob(entity), entity.getETag()));
  }

  public void update(RecoveryJob job, String expectedEtag) {
    // Validate the version before accessing storage.
    StoredRecoveryJob validated = new StoredRecoveryJob(job, expectedEtag);

    TableEntity entity = mapper.toEntity(validated.job());
    entity.addProperty("odata.etag", validated.etag());

    tableClient.updateEntityWithResponse(
        entity,
        TableEntityUpdateMode.REPLACE,
        true,
        OPERATION_TIMEOUT,
        Context.NONE);
  }

  private static boolean isMissingEntity(TableServiceException exception) {
    return exception.getResponse() != null
        && exception.getResponse().getStatusCode() == 404
        && exception.getValue() != null
        && TableErrorCode.RESOURCE_NOT_FOUND.equals(
            exception.getValue().getErrorCode());
  }
}