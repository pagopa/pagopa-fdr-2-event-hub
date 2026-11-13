package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryPublicationDiscoveryItemRepository {

  public enum SaveStatus {
    CREATED,
    ALREADY_PRESENT
  }

  public record SaveResult(
      SaveStatus status,
      RecoveryPublicationDiscoveryItem item) {

    public SaveResult {
      Objects.requireNonNull(status, "status is required");
      Objects.requireNonNull(item, "item is required");
    }
  }

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_PAGE_SIZE = 100;

  private final TableClient client;
  private final RecoveryPublicationDiscoveryItemTableMapper mapper;

  public RecoveryPublicationDiscoveryItemRepository(
      TableClient client,
      RecoveryPublicationDiscoveryItemTableMapper mapper) {

    this.client = Objects.requireNonNull(client, "client is required");
    this.mapper = Objects.requireNonNull(mapper, "mapper is required");
  }

  public SaveResult createOrRead(
      RecoveryPublicationDiscoveryItem requested) {

    TableEntity entity = mapper.toEntity(requested);

    try {
      client.createEntityWithResponse(entity, TIMEOUT, Context.NONE);

      return new SaveResult(SaveStatus.CREATED, requested);
    } catch (TableServiceException exception) {
      if (!isAlreadyPresent(exception)) {
        throw exception;
      }
    }

    var response =
        client.getEntityWithResponse(
            requested.partitionKey(),
            requested.rowKey(),
            null,
            TIMEOUT,
            Context.NONE);

    if (response == null || response.getValue() == null) {
      throw new IllegalStateException(
          "Stored publication discovery item was not returned");
    }

    var existing = mapper.toItem(response.getValue());

    if (!requested.jobId().equals(existing.jobId())
        || !requested.day().equals(existing.day())
        || !requested.candidate().equals(existing.candidate())) {

      throw new IllegalStateException(
          "Stored publication discovery item belongs to a different candidate");
    }

    return new SaveResult(SaveStatus.ALREADY_PRESENT, existing);
  }

  public Map<Long, RecoveryPublicationDiscoveryItem> findForPage(UUID jobId, LocalDate day,
          List<RecoveryFlowCandidate> candidates) {

      Objects.requireNonNull(jobId, "jobId is required");
      Objects.requireNonNull(day, "day is required");
      Objects.requireNonNull(candidates, "candidates is required");

      if (candidates.size() > MAX_PAGE_SIZE) {
          throw new IllegalArgumentException("Candidate page must contain at most " + MAX_PAGE_SIZE + " entries");
      }

      var page = List.copyOf(candidates);

      if (page.isEmpty()) {
          return Map.of();
      }

      var expected = validatePage(page);

      String partitionKey = jobId + "_" + day;
      String firstRowKey = flowRowKey(page.get(0).flowId());
      String lastRowKey = flowRowKey(page.get(page.size() - 1).flowId());

      String filter = "PartitionKey eq '%s' and RowKey ge '%s' and RowKey le '%s'".formatted(partitionKey, firstRowKey,
              lastRowKey);

      var options = new ListEntitiesOptions().setFilter(filter).setTop(page.size() + 1);

      Map<Long, RecoveryPublicationDiscoveryItem> found = new HashMap<>();

      for (var entity : client.listEntities(options, TIMEOUT, Context.NONE)) {
          if (found.size() >= page.size()) {
              throw new IllegalStateException("Stored discovery range contains more items than expected");
          }

          var item = mapper.toItem(entity);

          if (!jobId.equals(item.jobId()) || !day.equals(item.day())) {
              throw new IllegalStateException("Stored discovery item belongs to a different job or day");
          }

          var expectedCandidate = expected.get(item.candidate().flowId());

          if (!item.candidate().equals(expectedCandidate)) {
              throw new IllegalStateException("Stored discovery item does not match the PostgreSQL page");
          }

          if (found.putIfAbsent(item.candidate().flowId(), item) != null) {
              throw new IllegalStateException("Stored discovery range contains a duplicate flow ID");
          }
      }

      return Map.copyOf(found);
  }

  private static Map<Long, RecoveryFlowCandidate> validatePage(List<RecoveryFlowCandidate> page) {

      Map<Long, RecoveryFlowCandidate> expected = new HashMap<>();
      long previousFlowId = 0L;

      for (var candidate : page) {
          if (candidate.flowId() <= previousFlowId) {
              throw new IllegalArgumentException("Candidate flow IDs must be positive and strictly increasing");
          }

          expected.put(candidate.flowId(), candidate);
          previousFlowId = candidate.flowId();
      }

      return expected;
  }

  private static String flowRowKey(long flowId) {
      return String.format(Locale.ROOT, "FLOW_%020d", flowId);
  }

  private static boolean isAlreadyPresent(
      TableServiceException exception) {

    return exception.getResponse() != null
        && exception.getResponse().getStatusCode() == 409
        && exception.getValue() != null
        && TableErrorCode.ENTITY_ALREADY_EXISTS.equals(
            exception.getValue().getErrorCode());
  }
}