package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.ListBlobsOptions;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RecoveryFdr1BlobRepository {

  private static final String CONTAINER_NAME = "fdr1-flows";
  private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(30);

  private final BlobContainerClient container;
  private final int maxCandidates;

  public RecoveryFdr1BlobRepository(
      BlobContainerClient container,
      int maxCandidates) {

    this.container =
        Objects.requireNonNull(container, "container is required");

    if (!CONTAINER_NAME.equals(container.getBlobContainerName())) {
      throw new IllegalArgumentException(
          "The FDR1 source container must be " + CONTAINER_NAME);
    }

    if (maxCandidates < 1 || maxCandidates > 1000) {
      throw new IllegalArgumentException(
          "maxCandidates must be between 1 and 1000");
    }

    this.maxCandidates = maxCandidates;
  }

  public List<RecoveryBlobSnapshot> listCandidates(
      RecoveryFlowCandidate candidate) {

    Objects.requireNonNull(candidate, "candidate is required");

    ListBlobsOptions options =
        new ListBlobsOptions()
            .setPrefix(candidate.name() + "_")
            .setMaxResultsPerPage(
                Math.min(100, maxCandidates + 1))
            .setDetails(
                new BlobListDetails().setRetrieveMetadata(true));

    List<RecoveryBlobSnapshot> candidates = new ArrayList<>();

    for (BlobItem item : container.listBlobs(options, PAGE_TIMEOUT)) {
      if (candidates.size() >= maxCandidates) {
        throw new IllegalStateException(
            "FDR1 source candidate limit exceeded for flowId="
                + candidate.flowId());
      }

      candidates.add(toSnapshot(item));
    }

    return List.copyOf(candidates);
  }

  private static RecoveryBlobSnapshot toSnapshot(BlobItem item) {
    BlobItemProperties properties = item.getProperties();

    if (properties == null || properties.getContentLength() == null) {
      throw new IllegalStateException(
          "Missing FDR1 blob properties: " + item.getName());
    }

    Map<String, String> metadata = item.getMetadata();

    return new RecoveryBlobSnapshot(
        CONTAINER_NAME,
        item.getName(),
        properties.getETag(),
        properties.getContentLength(),
        metadata == null ? Map.of() : metadata);
  }
}