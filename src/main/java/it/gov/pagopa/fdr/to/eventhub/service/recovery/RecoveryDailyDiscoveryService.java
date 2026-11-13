package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class RecoveryDailyDiscoveryService {

  public record Summary(
      LocalDate day,
      long candidateCount,
      long absentCandidateCount,
      long presentCandidateCount,
      long pageCount) {
  }

  private final RecoveryAdxRepository adxRepository;
  private final RecoveryFlowRepository flowRepository;
  private final int pageSize;

  public RecoveryDailyDiscoveryService(
      RecoveryAdxRepository adxRepository,
      RecoveryFlowRepository flowRepository,
      int pageSize) {

    this.adxRepository =
        Objects.requireNonNull(adxRepository, "adxRepository is required");

    this.flowRepository =
        Objects.requireNonNull(flowRepository, "flowRepository is required");

    if (pageSize < 1 || pageSize > 1000) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and 1000");
    }

    this.pageSize = pageSize;
  }

  public Summary discover(
      LocalDate day,
      Consumer<List<RecoveryFlowPresenceIndex.Comparison>> pageConsumer)
      throws SQLException, DataClientException, DataServiceException {

    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(pageConsumer, "pageConsumer is required");

    RecoveryFlowPresenceIndex index =
        new RecoveryFlowPresenceIndex(adxRepository.findByDay(day));

    long afterFlowId = 0L;
    long candidateCount = 0L;
    long absentCount = 0L;
    long presentCount = 0L;
    long pageCount = 0L;

    while (true) {
      List<RecoveryFlowCandidate> page =
          List.copyOf(
              flowRepository.findPage(day, afterFlowId, pageSize));

      if (page.isEmpty()) {
        return new Summary(
            day,
            candidateCount,
            absentCount,
            presentCount,
            pageCount);
      }

      long nextCursor = validatePage(page, afterFlowId);

      List<RecoveryFlowPresenceIndex.Comparison> comparisons =
          page.stream()
              .map(index::compare)
              .toList();

      pageConsumer.accept(comparisons);

      long pageAbsentCount =
          comparisons.stream()
              .filter(
                  comparison ->
                      comparison.presence()
                          == RecoveryFlowPresenceIndex.Presence.ABSENT)
              .count();

      candidateCount += comparisons.size();
      absentCount += pageAbsentCount;
      presentCount += comparisons.size() - pageAbsentCount;
      pageCount++;
      afterFlowId = nextCursor;
    }
  }

  private long validatePage(
      List<RecoveryFlowCandidate> page,
      long afterFlowId) {

    if (page.size() > pageSize) {
      throw new IllegalStateException(
          "PostgreSQL returned more candidates than requested");
    }

    long previousId = afterFlowId;

    for (RecoveryFlowCandidate candidate : page) {
      if (candidate.flowId() <= previousId) {
        throw new IllegalStateException(
            "PostgreSQL candidate IDs must be strictly increasing");
      }

      previousId = candidate.flowId();
    }

    return previousId;
  }
}