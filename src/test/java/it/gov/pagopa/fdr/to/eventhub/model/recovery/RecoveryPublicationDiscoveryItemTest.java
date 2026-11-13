package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryPublicationDiscoveryItemTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  @ParameterizedTest
  @CsvSource({
      "ABSENT,0",
      "PRESENT_UNVERIFIED,4"
  })
  void shouldPreserveComparedSource(
      RecoveryPublicationDiscoveryItem.Status status,
      long presentRows) {

    var candidate = candidate(155333L);
    var source = source(candidate);

    var outcome =
        new RecoveryPublicationDiscoveryItem.Compared(
            source, status, presentRows);

    var item =
        new RecoveryPublicationDiscoveryItem(
            JOB_ID, DAY, candidate, outcome);

    assertSame(outcome, item.outcome());
    assertSame(source, outcome.source());
    assertEquals(status, item.outcome().status());
    assertEquals(presentRows, outcome.presentRows());
  }

  @ParameterizedTest
  @CsvSource({
      "ABSENT,1",
      "ABSENT,-1",
      "PRESENT_UNVERIFIED,0",
      "PRESENT_UNVERIFIED,-1",
      "UNRESOLVED,0"
  })
  void shouldRejectInvalidComparedState(
      RecoveryPublicationDiscoveryItem.Status status,
      long presentRows) {

    var source = source(candidate(155333L));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryPublicationDiscoveryItem.Compared(
                source, status, presentRows));
  }

  @Test
  void shouldRepresentUnresolvedSeparatelyFromAbsent() {
    var outcome =
        new RecoveryPublicationDiscoveryItem.Unresolved(
            "XML_UNRESOLVED",
            Map.of("xmlReason", "METADATA_UNRESOLVED"));

    var item =
        new RecoveryPublicationDiscoveryItem(
            JOB_ID, DAY, candidate(155333L), outcome);

    assertEquals(
        RecoveryPublicationDiscoveryItem.Status.UNRESOLVED,
        item.outcome().status());

    assertEquals("XML_UNRESOLVED", outcome.reason());
    assertEquals(
        "METADATA_UNRESOLVED",
        outcome.details().get("xmlReason"));
  }

  @Test
  void shouldBuildStableStorageKeys() {
    var item =
        new RecoveryPublicationDiscoveryItem(
            JOB_ID,
            DAY,
            candidate(155333L),
            new RecoveryPublicationDiscoveryItem.Unresolved(
                "ORIGIN_UNDETERMINED", Map.of()));

    assertEquals(
        "11111111-1111-4111-8111-111111111111_2026-10-01",
        item.partitionKey());

    assertEquals("FLOW_00000000000000155333", item.rowKey());
  }

  @Test
  void shouldRejectSourceOfAnotherCandidate() {
    var candidate = candidate(155333L);

    var outcome =
        new RecoveryPublicationDiscoveryItem.Compared(
            source(candidate(155336L)),
            RecoveryPublicationDiscoveryItem.Status.ABSENT,
            0L);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryPublicationDiscoveryItem(
                JOB_ID, DAY, candidate, outcome));
  }

  @Test
  void shouldSnapshotAndProtectDiagnosticDetails() {
    var details = new HashMap<String, String>();
    details.put("xmlReason", "NO_MATCH");

    var outcome =
        new RecoveryPublicationDiscoveryItem.Unresolved(
            "ORIGIN_UNDETERMINED", details);

    details.put("xmlReason", "CHANGED");

    assertEquals("NO_MATCH", outcome.details().get("xmlReason"));

    var storedDetails = outcome.details();

    assertThrows(
        UnsupportedOperationException.class,
        () -> storedDetails.put("another", "value"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void shouldRejectBlankReason(String reason) {
    Map<String, String> details = Map.of();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryPublicationDiscoveryItem.Unresolved(
                reason, details));
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L})
  void shouldRejectNonPositiveFlowId(long flowId) {
    var candidate = candidate(flowId);

    var outcome =
        new RecoveryPublicationDiscoveryItem.Unresolved(
            "ORIGIN_UNDETERMINED", Map.of());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryPublicationDiscoveryItem(
                JOB_ID, DAY, candidate, outcome));
  }

  @Test
  void shouldRejectMissingSourceForComparedOutcome() {
    assertThrows(
        NullPointerException.class,
        () ->
            new RecoveryPublicationDiscoveryItem.Compared(
                null,
                RecoveryPublicationDiscoveryItem.Status.ABSENT,
                0L));
  }

  private static RecoveryFlowCandidate candidate(long flowId) {
    return new RecoveryFlowCandidate(
        flowId,
        "flow-1",
        1L,
        "psp-1",
        "org-1",
        LocalDateTime.parse("2026-10-05T10:31:35"));
  }

  private static RecoveryPublicationSource source(
      RecoveryFlowCandidate candidate) {

    return new RecoveryPublicationSource(
        candidate,
        new RecoveryBlobSnapshot(
            "fdr3-flows",
            candidate.blobName(),
            "\"original-etag\"",
            100L,
            Map.of(
                "serviceIdentifier", "FDR03",
                "elaborate", "true")),
        RecoveryPublicationSource.SourceType.FDR3_JSON,
        "6fd3e2eb-1d1d-4068-ba74-fa9866141524",
        Instant.parse("2026-10-05T10:31:35Z"));
  }
}