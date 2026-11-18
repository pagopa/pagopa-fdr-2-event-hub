package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxPublicationQuery;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryItemRepository;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryPublicationPageService {

  private final RecoveryPublicationDiscoveryItemRepository itemRepository;
  private final RecoveryPublicationBatchService batchService;
  private final RecoveryPublicationDiscoveryItemFactory itemFactory;

  public RecoveryPublicationPageService(
      RecoveryPublicationDiscoveryItemRepository itemRepository,
      RecoveryPublicationBatchService batchService,
      RecoveryPublicationDiscoveryItemFactory itemFactory) {

    this.itemRepository = Objects.requireNonNull(itemRepository);
    this.batchService = Objects.requireNonNull(batchService);
    this.itemFactory = Objects.requireNonNull(itemFactory);
  }

  public List<RecoveryPublicationDiscoveryItem> process(
      UUID jobId,
      LocalDate day,
      List<RecoveryFlowCandidate> candidates,
      Runnable requireOwnership)
      throws IOException, DataClientException, DataServiceException {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(candidates, "candidates is required");
    Objects.requireNonNull(requireOwnership, "requireOwnership is required");

    if (candidates.size() > RecoveryAdxPublicationQuery.MAX_PUBLICATIONS) {
      throw new IllegalArgumentException(
          "Candidate page exceeds the supported size");
    }

    var page = List.copyOf(candidates);

    if (page.isEmpty()) {
      return List.of();
    }

    requireOwnership.run();

    var saved =
        new HashMap<>(
            itemRepository.findForPage(jobId, day, page));

    requireOwnership.run();

    var missing =
        page.stream()
            .filter(candidate -> !saved.containsKey(candidate.flowId()))
            .toList();

    if (!missing.isEmpty()) {
      var items =
          discoverMissing(jobId, day, missing, requireOwnership);

      for (var item : items) {
        requireOwnership.run();

        var persisted = itemRepository.createOrRead(item);

        requireOwnership.run();

        saved.put(
            persisted.item().candidate().flowId(),
            persisted.item());
      }
    }

    return page.stream()
        .map(candidate -> saved.get(candidate.flowId()))
        .toList();
  }

  private List<RecoveryPublicationDiscoveryItem> discoverMissing(
      UUID jobId,
      LocalDate day,
      List<RecoveryFlowCandidate> missing,
      Runnable requireOwnership)
      throws IOException, DataClientException, DataServiceException {

    requireOwnership.run();

    var results = batchService.compare(day, missing);

    requireOwnership.run();

    if (results.size() != missing.size()) {
      throw new IllegalStateException(
          "Discovery result count differs from missing candidate count");
    }

    for (int index = 0; index < missing.size(); index++) {
      if (!missing.get(index).equals(results.get(index).candidate())) {
        throw new IllegalStateException(
            "Discovery results do not match candidate order and identity");
      }
    }

    // Validate and convert every result before starting persistence.
    return results.stream()
        .map(result -> itemFactory.create(jobId, day, result))
        .toList();
  }
}