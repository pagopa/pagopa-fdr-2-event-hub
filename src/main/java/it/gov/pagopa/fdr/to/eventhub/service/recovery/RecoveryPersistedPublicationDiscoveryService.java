package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryPublicationDiscoveryCheckpoint;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class RecoveryPersistedPublicationDiscoveryService {

  private final RecoveryFlowRepository flowRepository;
  private final RecoveryPublicationPageService pageService;
  private final RecoveryPublicationDiscoveryCheckpointRepository checkpoints;
  private final int pageSize;

  public RecoveryPersistedPublicationDiscoveryService(
      RecoveryFlowRepository flowRepository,
      RecoveryPublicationPageService pageService,
      RecoveryPublicationDiscoveryCheckpointRepository checkpoints,
      int pageSize) {

    this.flowRepository = Objects.requireNonNull(flowRepository);
    this.pageService = Objects.requireNonNull(pageService);
    this.checkpoints = Objects.requireNonNull(checkpoints);

    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and 100");
    }

    this.pageSize = pageSize;
  }

  public RecoveryPublicationDiscoveryCheckpoint discover(
      UUID jobId,
      LocalDate day,
      BooleanSupplier shouldContinue,
      Runnable requireOwnership)
      throws SQLException, IOException,
          DataClientException, DataServiceException {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(shouldContinue, "shouldContinue is required");
    Objects.requireNonNull(requireOwnership, "requireOwnership is required");

    var stored = loadOrCreate(jobId, day, requireOwnership);

    while (stored.checkpoint().status()
        == RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERING) {

      requireOwnership.run();

      if (!shouldContinue.getAsBoolean()) {
        return stored.checkpoint();
      }

      var current = stored.checkpoint();

      var page =
          List.copyOf(
              flowRepository.findPage(
                  day, current.lastFlowId(), pageSize));

      requireOwnership.run();

      if (!shouldContinue.getAsBoolean()) {
        return current;
      }

      if (page.isEmpty()) {
        return saveCheckpoint(
                current.completeDiscovery(), stored.etag(), requireOwnership)
            .checkpoint();
      }

      validatePage(page, current.lastFlowId());

      var saved =
          pageService.process(
              jobId, day, page, requireOwnership);

      requireOwnership.run();

      validateSavedPage(page, saved);

      var next = current.afterSavedPage(saved);

      stored =
          saveCheckpoint(next, stored.etag(), requireOwnership);
    }

    return stored.checkpoint();
  }

  private StoredRecoveryPublicationDiscoveryCheckpoint loadOrCreate(
      UUID jobId,
      LocalDate day,
      Runnable requireOwnership) {

    requireOwnership.run();
    var existing = checkpoints.find(jobId, day);
    requireOwnership.run();

    if (existing.isPresent()) {
      return existing.orElseThrow();
    }

    checkpoints.create(
        RecoveryPublicationDiscoveryCheckpoint.start(jobId, day));

    return requireCheckpoint(jobId, day, requireOwnership);
  }

  private StoredRecoveryPublicationDiscoveryCheckpoint saveCheckpoint(
      RecoveryPublicationDiscoveryCheckpoint next,
      String etag,
      Runnable requireOwnership) {

    requireOwnership.run();
    checkpoints.update(next, etag);

    var stored =
        requireCheckpoint(next.jobId(), next.day(), requireOwnership);

    if (!next.equals(stored.checkpoint())) {
      throw new IllegalStateException(
          "Checkpoint changed after the expected update");
    }

    return stored;
  }

  private StoredRecoveryPublicationDiscoveryCheckpoint requireCheckpoint(
      UUID jobId,
      LocalDate day,
      Runnable requireOwnership) {

    requireOwnership.run();

    var stored =
        checkpoints.find(jobId, day)
            .orElseThrow(
                () -> new IllegalStateException(
                    "Expected publication checkpoint was not found"));

    requireOwnership.run();

    return stored;
  }

  private void validatePage(
      List<RecoveryFlowCandidate> page,
      long lastFlowId) {

    if (page.size() > pageSize) {
      throw new IllegalStateException(
          "PostgreSQL returned more candidates than requested");
    }

    long cursor = lastFlowId;

    for (var candidate : page) {
      if (candidate.flowId() <= cursor) {
        throw new IllegalStateException(
            "PostgreSQL candidate IDs must increase after the checkpoint");
      }

      cursor = candidate.flowId();
    }
  }

  private static void validateSavedPage(
      List<RecoveryFlowCandidate> page,
      List<RecoveryPublicationDiscoveryItem> saved) {

    if (saved.size() != page.size()) {
      throw new IllegalStateException(
          "Saved page size differs from the PostgreSQL page");
    }

    for (int index = 0; index < page.size(); index++) {
      if (!page.get(index).equals(saved.get(index).candidate())) {
        throw new IllegalStateException(
            "Saved page does not match PostgreSQL candidate order");
      }
    }
  }
}