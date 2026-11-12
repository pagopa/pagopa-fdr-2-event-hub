package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class RecoveryPublicationDiscoveryItemFactory {

  public RecoveryPublicationDiscoveryItem create(
      UUID jobId,
      LocalDate day,
      RecoveryPublicationBatchService.Result result) {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(result, "result is required");

    if (result instanceof RecoveryPublicationBatchService.Compared compared) {
      return comparedItem(jobId, day, compared);
    }

    var unresolved =
        (RecoveryPublicationBatchService.Unresolved) result;

    var issue = unresolved.issue();

    return new RecoveryPublicationDiscoveryItem(
        jobId,
        day,
        unresolved.candidate(),
        new RecoveryPublicationDiscoveryItem.Unresolved(
            issue.reason().name(),
            diagnostics(issue.evidence())));
  }

  private static RecoveryPublicationDiscoveryItem comparedItem(
      UUID jobId,
      LocalDate day,
      RecoveryPublicationBatchService.Compared result) {

    var comparison = result.comparison();

    if (!day.equals(comparison.paymentDay())) {
      throw new IllegalArgumentException(
          "Comparison belongs to a different payment day");
    }

    var status =
        switch (comparison.presence()) {
          case ABSENT ->
              RecoveryPublicationDiscoveryItem.Status.ABSENT;
          case PRESENT_UNVERIFIED ->
              RecoveryPublicationDiscoveryItem.Status.PRESENT_UNVERIFIED;
        };

    return new RecoveryPublicationDiscoveryItem(
        jobId,
        day,
        result.candidate(),
        new RecoveryPublicationDiscoveryItem.Compared(
            comparison.source(),
            status,
            comparison.presentRows()));
  }

  private static Map<String, String> diagnostics(
      RecoveryPublicationSourceInspector.Inspection evidence) {

    Map<String, String> details = new HashMap<>();

    details.put("jsonResult", evidence.fdr3().getClass().getSimpleName());
    details.put("xmlResult", evidence.fdr1().getClass().getSimpleName());

    describeJson(evidence.fdr3(), details);
    describeXml(evidence.fdr1(), details);

    return details;
  }

  private static void describeJson(
      RecoveryFdr3SourceVerifier.Result result,
      Map<String, String> details) {

    if (result instanceof RecoveryFdr3SourceVerifier.Verified verified) {
      details.put("jsonSessionId", verified.metadata().sessionId());
      details.put(
          "jsonElaborate",
          Boolean.toString(verified.metadata().elaborate()));
    } else if (result
        instanceof RecoveryFdr3SourceVerifier.MetadataUnresolved unresolved) {

      details.put("jsonReason", unresolved.issue().reason().name());
      details.put("jsonField", unresolved.issue().field());
    } else if (result
        instanceof RecoveryFdr3SourceVerifier.IdentityMismatch mismatch) {

      details.put(
          "jsonDifferences",
          mismatch.comparison().differences().stream()
              .map(Enum::name)
              .sorted()
              .collect(Collectors.joining(",")));
    }
  }

  private static void describeXml(
      RecoveryFdr1SourceResolver.Resolution result,
      Map<String, String> details) {

    if (result instanceof RecoveryFdr1SourceResolver.Resolved resolved) {
      details.put(
          "xmlSessionId",
          resolved.source().metadata().sessionId());

      details.put(
          "xmlElaborate",
          Boolean.toString(resolved.source().metadata().elaborate()));
    } else {
      var unresolved =
          (RecoveryFdr1SourceResolver.Unresolved) result;

      details.put("xmlReason", unresolved.reason().name());
      details.put(
          "xmlInspectionCount",
          Integer.toString(unresolved.inspections().size()));
      describeInconsistentReport(unresolved, details);
    }
  }

  private static void describeInconsistentReport(RecoveryFdr1SourceResolver.Unresolved unresolved,
          Map<String, String> details) {

      if (unresolved.reason() != RecoveryFdr1SourceResolver.UnresolvedReason.INCONSISTENT_REPORT) {
          return;
      }

      for (var result : unresolved.inspections()) {
          if (result instanceof RecoveryFdr1SourceVerifier.Inspected inspected) {
              var source = inspected.source();
              String issues = reportIssues(source);

              if (!issues.isEmpty()) {
                  details.put("xmlBlob", source.blob().blobName());
                  details.put("xmlReportIssues", issues);
                  details.put("xmlOuterFlowName", source.envelope().name());
                  details.put("xmlInnerFlowName", source.report().name());
                  details.put("xmlDeclaredPayments", Long.toString(source.report().declaredPaymentCount()));
                  details.put("xmlObservedPayments", Long.toString(source.report().observedPaymentCount()));

                  return;
              }
          }
      }
  }

  private static String reportIssues(RecoveryFdr1SourceInspector.Inspection source) {

      var issues = new java.util.ArrayList<String>();

      if (!Objects.equals(source.envelope().name(), source.report().name())) {
          issues.add("FLOW_NAME_MISMATCH");
      }

      if (source.report().declaredPaymentCount() != source.report().observedPaymentCount()) {
          issues.add("PAYMENT_COUNT_MISMATCH");
      }

      return String.join(",", issues);
  }
}