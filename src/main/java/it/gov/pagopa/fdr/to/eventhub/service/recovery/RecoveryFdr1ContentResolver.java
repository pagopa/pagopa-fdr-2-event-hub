package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RecoveryFdr1ContentResolver {

  private final RecoveryFdr3PaymentEventReader jsonReader;
  private final RecoveryFdr1PaymentEventReader xmlReader;
  private final RecoveryPublicationSourceMapper sourceMapper;
  private final RecoveryPaymentContentMatcher matcher;

  public RecoveryFdr1ContentResolver(
      RecoveryFdr3PaymentEventReader jsonReader,
      RecoveryFdr1PaymentEventReader xmlReader,
      RecoveryPublicationSourceMapper sourceMapper,
      RecoveryPaymentContentMatcher matcher) {

    this.jsonReader = Objects.requireNonNull(jsonReader);
    this.xmlReader = Objects.requireNonNull(xmlReader);
    this.sourceMapper = Objects.requireNonNull(sourceMapper);
    this.matcher = Objects.requireNonNull(matcher);
  }

  public RecoveryFdr1SourceResolver.Resolution resolve(
      RecoveryFdr3SourceVerifier.Verified json,
      RecoveryFdr1SourceResolver.Unresolved xml) throws IOException {

    Objects.requireNonNull(json, "json is required");
    Objects.requireNonNull(xml, "xml is required");

    // Only a verified converted JSON can disambiguate an original XML.
    if (json.metadata().elaborate()
        || xml.reason()
            != RecoveryFdr1SourceResolver.UnresolvedReason.AMBIGUOUS_MATCH) {
      return xml;
    }

    var candidates = compatibleCandidates(json.candidate(), xml);

    if (candidates.size() < 2) {
      return xml;
    }

    List<ReportedIUVEventModel> reference;

    try (var events = jsonReader.readAll(sourceMapper.map(json))) {
      reference = events.toList();
    }

    if (reference.isEmpty()) {
      return xml;
    }

    var matches = findContentMatches(reference, candidates);

    if (matches.size() != 1 || !matches.get(0).metadata().elaborate()) {
      return xml;
    }

    return new RecoveryFdr1SourceResolver.Resolved(matches.get(0));
  }

  private List<RecoveryFdr1SourceVerifier.Inspected> findContentMatches(
      List<ReportedIUVEventModel> reference,
      List<RecoveryFdr1SourceVerifier.Inspected> candidates)
      throws IOException {

    List<RecoveryFdr1SourceVerifier.Inspected> matches =
        new ArrayList<>();

    for (var candidate : candidates) {
      var source = sourceMapper.map(
          new RecoveryFdr1SourceResolver.Resolved(candidate));

      try (var events = xmlReader.readAll(source)) {
        if (matcher.matches(reference.stream(), events)) {
          matches.add(candidate);
        }
      }
    }

    return matches;
  }

  private static List<RecoveryFdr1SourceVerifier.Inspected>
      compatibleCandidates(
          RecoveryFlowCandidate candidate,
          RecoveryFdr1SourceResolver.Unresolved unresolved) {

    List<RecoveryFdr1SourceVerifier.Inspected> candidates =
        new ArrayList<>();

    for (var result : unresolved.inspections()) {
      if (!(result instanceof RecoveryFdr1SourceVerifier.Inspected inspected)) {
        return List.of();
      }

      var source = inspected.source();

      if (!candidate.equals(source.candidate())) {
        throw new IllegalArgumentException(
            "XML inspection belongs to another recovery candidate");
      }

      if (!Objects.equals(
              source.envelope().name(), source.report().name())
          || source.report().declaredPaymentCount()
              != source.report().observedPaymentCount()) {
        return List.of();
      }

      if (source.comparison().matches()) {
        candidates.add(inspected);
      }
    }

    return List.copyOf(candidates);
  }
}