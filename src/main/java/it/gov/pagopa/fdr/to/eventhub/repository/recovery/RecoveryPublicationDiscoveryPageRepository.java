package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryPublicationDiscoveryPageRepository {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_PAGE_SIZE = 100;

  private final TableClient client;
  private final RecoveryPublicationDiscoveryItemTableMapper mapper;

  public RecoveryPublicationDiscoveryPageRepository(
      TableClient client,
      RecoveryPublicationDiscoveryItemTableMapper mapper) {

    this.client =
        Objects.requireNonNull(client, "client is required");

    this.mapper =
        Objects.requireNonNull(mapper, "mapper is required");
  }

  public List<RecoveryPublicationDiscoveryItem> findPage(
      UUID jobId,
      LocalDate day,
      long afterFlowId,
      int pageSize) {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");

    if (afterFlowId < 0) {
      throw new IllegalArgumentException(
          "afterFlowId must not be negative");
    }

    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and " + MAX_PAGE_SIZE);
    }

    String partitionKey = jobId + "_" + day;

    String filter =
        "PartitionKey eq '%s' and RowKey gt '%s' and RowKey le '%s'"
            .formatted(
                partitionKey,
                flowRowKey(afterFlowId),
                flowRowKey(Long.MAX_VALUE));

    var options =
        new ListEntitiesOptions()
            .setFilter(filter)
            .setTop(pageSize);

    var iterator =
        client.listEntities(options, TIMEOUT, Context.NONE)
            .iterator();

    List<RecoveryPublicationDiscoveryItem> items =
        new ArrayList<>(pageSize);

    long previousFlowId = afterFlowId;

    // Check the application limit before asking the SDK for another item.
    // The SDK follows continuation tokens, including empty service pages.
    while (items.size() < pageSize && iterator.hasNext()) {
      var item = mapper.toItem(iterator.next());

      if (!jobId.equals(item.jobId()) || !day.equals(item.day())) {
        throw new IllegalStateException(
            "Stored discovery item belongs to a different job or day");
      }

      long flowId = item.candidate().flowId();

      if (flowId <= previousFlowId) {
        throw new IllegalStateException(
            "Stored discovery flow IDs must be strictly increasing");
      }

      items.add(item);
      previousFlowId = flowId;
    }

    return List.copyOf(items);
  }

  private static String flowRowKey(long flowId) {
    return String.format(
        Locale.ROOT, "FLOW_%020d", flowId);
  }
}