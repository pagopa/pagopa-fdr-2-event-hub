package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxPublicationQuery;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryPublicationPresenceService {

  private final RecoveryAdxRepository repository;

  public RecoveryPublicationPresenceService(
      RecoveryAdxRepository repository) {

    this.repository =
        Objects.requireNonNull(repository, "repository is required");
  }

  public List<RecoveryPublicationPresenceIndex.Comparison> compare(
      LocalDate paymentDay,
      List<RecoveryPublicationSource> sources)
      throws DataClientException, DataServiceException {

    Objects.requireNonNull(paymentDay, "paymentDay is required");
    Objects.requireNonNull(sources, "sources is required");

    if (sources.size() > RecoveryAdxPublicationQuery.MAX_PUBLICATIONS) {
      throw new IllegalArgumentException(
          "sources must contain at most "
              + RecoveryAdxPublicationQuery.MAX_PUBLICATIONS
              + " entries");
    }

    var batch = List.copyOf(sources);

    if (batch.isEmpty()) {
      return List.of();
    }

    var publicationIds =
        batch.stream()
            .map(source -> UUID.fromString(source.sessionId()))
            .distinct()
            .toList();

    var presences =
        repository.findPublicationsByDay(
            paymentDay, publicationIds);

    var index =
        new RecoveryPublicationPresenceIndex(
            paymentDay, presences);

    return batch.stream()
        .map(index::compare)
        .toList();
  }
}