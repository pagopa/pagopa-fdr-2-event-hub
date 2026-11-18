package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.http.rest.PagedResponse;
import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryRunnableJobRepository {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final TableClient client;
  private final RecoveryJobTableMapper mapper;
  private final boolean includeExecution;

  public RecoveryRunnableJobRepository(TableClient client, RecoveryJobTableMapper mapper) {
      this(client, mapper, false);
  }

  public RecoveryRunnableJobRepository(TableClient client, RecoveryJobTableMapper mapper, boolean includeExecution) {
      this.client = Objects.requireNonNull(client, "client is required");
      this.mapper = Objects.requireNonNull(mapper, "mapper is required");

      if (includeExecution && !"PUBLICATION".equals(mapper.discoveryMode().orElse(null))) {
          throw new IllegalArgumentException("Execution requires the publication discovery job format");
      }

      this.includeExecution = includeExecution;
  }

  public JobPage findPage(String continuationToken, int pageSize) {
    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and 100");
    }

    ListEntitiesOptions options =
        new ListEntitiesOptions()
            .setFilter(runnableFilter())
            .setTop(pageSize)
            .setSelect(
                List.of(
                    "PartitionKey",
                    "RowKey",
                    "SchemaVersion",
                    "DiscoveryMode",
                    "Status",
                    "JobJson"));

    Iterator<PagedResponse<TableEntity>> pages =
        client.listEntities(options, TIMEOUT, Context.NONE)
            .iterableByPage(continuationToken, pageSize)
            .iterator();

    if (!pages.hasNext()) {
      return new JobPage(List.of(), null);
    }

    try (PagedResponse<TableEntity> page = pages.next()) {
      List<TableEntity> entities = page.getValue();

      if (entities.size() > pageSize) {
        throw new IllegalStateException(
            "Storage returned more jobs than requested");
      }

      List<UUID> jobIds =
          entities.stream()
              .map(mapper::toJob)
              .filter(this::isRunnable)
              .map(RecoveryJob::jobId)
              .toList();

      return new JobPage(jobIds, page.getContinuationToken());

    } catch (IOException exception) {
      throw new UncheckedIOException(
          "Unable to close the recovery job page",
          exception);
    }
  }

  private boolean isRunnable(RecoveryJob job) {
      return (job.dryRun() || includeExecution) && switch (job.status()) {
      case PENDING, RUNNING, PAUSING -> true;
      case PAUSED, COMPLETED, FAILED -> false;
      };
  }

  public record JobPage(
      List<UUID> jobIds,
      String continuationToken) {

    public JobPage {
      jobIds = List.copyOf(jobIds);
    }
  }

  private String runnableFilter() {
      String filter = "RowKey eq 'JOB' and SchemaVersion eq '%s' and ".formatted(mapper.schemaVersion())
              + "(Status eq 'PENDING' or Status eq 'RUNNING' " + "or Status eq 'PAUSING')";

      return mapper.discoveryMode().map(mode -> filter + " and DiscoveryMode eq '" + mode + "'").orElse(filter);
  }
}