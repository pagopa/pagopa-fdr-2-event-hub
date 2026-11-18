package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryBatchRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryDiscoveryCheckpoint;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class RecoveryPersistedDiscoveryService {

  private final RecoveryAdxRepository adxRepository;
  private final RecoveryFlowRepository flowRepository;
  private final RecoveryDiscoveryBatchRepository batchRepository;
  private final RecoveryDiscoveryCheckpointRepository checkpointRepository;
  private final int pageSize;

  public RecoveryPersistedDiscoveryService(
      RecoveryAdxRepository adxRepository,
      RecoveryFlowRepository flowRepository,
      RecoveryDiscoveryBatchRepository batchRepository,
      RecoveryDiscoveryCheckpointRepository checkpointRepository,
      int pageSize) {

    this.adxRepository =
        Objects.requireNonNull(adxRepository, "adxRepository is required");

    this.flowRepository =
        Objects.requireNonNull(flowRepository, "flowRepository is required");

    this.batchRepository =
        Objects.requireNonNull(batchRepository, "batchRepository is required");

    this.checkpointRepository =
        Objects.requireNonNull(
            checkpointRepository,
            "checkpointRepository is required");

    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and 100");
    }

    this.pageSize = pageSize;
  }

  public RecoveryDiscoveryCheckpoint discover(
      UUID jobId,
      LocalDate day)
      throws SQLException, DataClientException, DataServiceException {

    return discover(jobId, day, () -> true);
  }

  public RecoveryDiscoveryCheckpoint discover(
      UUID jobId,
      LocalDate day,
      BooleanSupplier shouldContinue)
      throws SQLException, DataClientException, DataServiceException {

    return discover(jobId, day, shouldContinue, () -> {});
  }

  public RecoveryDiscoveryCheckpoint discover(
      UUID jobId,
      LocalDate day,
      BooleanSupplier shouldContinue,
      Runnable requireOwnership)
      throws SQLException, DataClientException, DataServiceException {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(shouldContinue, "shouldContinue is required");
    Objects.requireNonNull(requireOwnership, "requireOwnership is required");

    StoredRecoveryDiscoveryCheckpoint stored =
        loadOrCreate(jobId, day, requireOwnership);

    requireOwnership.run();

    if (stored.checkpoint().status()
        == RecoveryDiscoveryCheckpoint.Status.DISCOVERED) {
      return stored.checkpoint();
    }

    if (!shouldContinue.getAsBoolean()) {
      return stored.checkpoint();
    }

    requireOwnership.run();

    RecoveryFlowPresenceIndex index =
        new RecoveryFlowPresenceIndex(adxRepository.findByDay(day));

    while (true) {
      RecoveryDiscoveryCheckpoint current = stored.checkpoint();

      requireOwnership.run();

      if (!shouldContinue.getAsBoolean()) {
        return current;
      }

      requireOwnership.run();

      List<RecoveryFlowCandidate> page =
          List.copyOf(
              flowRepository.findPage(
                  day,
                  current.lastFlowId(),
                  pageSize));

      if (page.isEmpty()) {
        RecoveryDiscoveryCheckpoint completed =
            current.completeDiscovery();

        requireOwnership.run();
        checkpointRepository.update(completed, stored.etag());

        requireOwnership.run();
        return completed;
      }

      if (page.size() > pageSize) {
        throw new IllegalStateException(
            "PostgreSQL returned more candidates than requested");
      }

      List<RecoveryDiscoveryItem> items =
          page.stream()
              .map(index::compare)
              .map(
                  comparison ->
                      RecoveryDiscoveryItem.fromComparison(
                          jobId, day, comparison))
              .toList();

      RecoveryDiscoveryCheckpoint next =
          current.afterSavedPage(items);

      requireOwnership.run();
      batchRepository.createIfAbsent(items);

      requireOwnership.run();
      checkpointRepository.update(next, stored.etag());

      stored = requireCheckpoint(jobId, day, requireOwnership);

      if (!next.equals(stored.checkpoint())) {
        throw new IllegalStateException(
            "Discovery checkpoint changed after the page update");
      }
    }
  }

  private StoredRecoveryDiscoveryCheckpoint loadOrCreate(
      UUID jobId,
      LocalDate day,
      Runnable requireOwnership) {

    requireOwnership.run();

    Optional<StoredRecoveryDiscoveryCheckpoint> existing =
        checkpointRepository.find(jobId, day);

    if (existing.isPresent()) {
      return existing.orElseThrow();
    }

    requireOwnership.run();

    checkpointRepository.create(
        RecoveryDiscoveryCheckpoint.start(jobId, day));

    return requireCheckpoint(jobId, day, requireOwnership);
  }

  private StoredRecoveryDiscoveryCheckpoint requireCheckpoint(
      UUID jobId,
      LocalDate day,
      Runnable requireOwnership) {

    requireOwnership.run();

    return checkpointRepository.find(jobId, day)
        .orElseThrow(
            () -> new IllegalStateException(
                "Expected discovery checkpoint was not found"));
  }
}