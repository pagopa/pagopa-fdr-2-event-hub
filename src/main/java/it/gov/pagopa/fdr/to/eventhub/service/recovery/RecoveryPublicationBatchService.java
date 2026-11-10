package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxPublicationQuery;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RecoveryPublicationBatchService {

  public sealed interface Result permits Compared, Unresolved {
    RecoveryFlowCandidate candidate();
  }

  public record Compared(
      RecoveryPublicationPresenceIndex.Comparison comparison)
      implements Result {

    @Override
    public RecoveryFlowCandidate candidate() {
      return comparison.source().candidate();
    }
  }

  public record Unresolved(
      RecoveryPublicationSourceSelector.Unresolved issue)
      implements Result {

    @Override
    public RecoveryFlowCandidate candidate() {
      return issue.evidence().candidate();
    }
  }

  private final RecoveryPublicationSourceInspector sourceInspector;
  private final RecoveryPublicationSourceSelector sourceSelector;
  private final RecoveryPublicationPresenceService presenceService;

  public RecoveryPublicationBatchService(
      RecoveryPublicationSourceInspector sourceInspector,
      RecoveryPublicationSourceSelector sourceSelector,
      RecoveryPublicationPresenceService presenceService) {

    this.sourceInspector = Objects.requireNonNull(sourceInspector);
    this.sourceSelector = Objects.requireNonNull(sourceSelector);
    this.presenceService = Objects.requireNonNull(presenceService);
  }

  public List<Result> compare(
      LocalDate paymentDay,
      List<RecoveryFlowCandidate> candidates)
      throws IOException, DataClientException, DataServiceException {

    Objects.requireNonNull(paymentDay, "paymentDay is required");
    Objects.requireNonNull(candidates, "candidates is required");

    if (candidates.size()
        > RecoveryAdxPublicationQuery.MAX_PUBLICATIONS) {
      throw new IllegalArgumentException(
          "Candidate batch exceeds the supported size");
    }

    var batch = List.copyOf(candidates);

    List<RecoveryPublicationSourceSelector.Selection> selections =
        new ArrayList<>();

    List<RecoveryPublicationSource> sources = new ArrayList<>();

    for (var candidate : batch) {
      var evidence = sourceInspector.inspect(candidate);
      var selection = sourceSelector.select(evidence);

      selections.add(selection);

      if (selection
          instanceof RecoveryPublicationSourceSelector.Selected selected) {
        sources.add(selected.source());
      }
    }

    List<RecoveryPublicationPresenceIndex.Comparison> comparisons =
        sources.isEmpty()
            ? List.of()
            : presenceService.compare(paymentDay, List.copyOf(sources));

    if (comparisons.size() != sources.size()) {
      throw new IllegalStateException(
          "Presence comparison count differs from selected source count");
    }

    return merge(paymentDay, selections, comparisons);
  }

  private static List<Result> merge(
      LocalDate paymentDay,
      List<RecoveryPublicationSourceSelector.Selection> selections,
      List<RecoveryPublicationPresenceIndex.Comparison> comparisons) {

    var iterator = comparisons.iterator();
    List<Result> results = new ArrayList<>();

    for (var selection : selections) {
      if (selection
          instanceof RecoveryPublicationSourceSelector.Selected selected) {

        var comparison = iterator.next();

        if (!paymentDay.equals(comparison.paymentDay())
            || !selected.source().equals(comparison.source())) {
          throw new IllegalStateException(
              "Presence comparison does not match the requested source");
        }

        results.add(new Compared(comparison));
      } else {
        results.add(
            new Unresolved(
                (RecoveryPublicationSourceSelector.Unresolved) selection));
      }
    }

    return List.copyOf(results);
  }
}