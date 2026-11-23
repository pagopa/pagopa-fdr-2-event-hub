package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxPublicationPresence;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryPublicationPresenceIndexTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private static final String SESSION_ID =
      "6fd3e2eb-1d1d-4068-ba74-fa9866141524";

  private static final String ANOTHER_SESSION_ID =
      "24fea4e9-c5d3-4d21-b74b-d51be0774d11";

  @Test
  void shouldFindSpecificPublication() {
    var source = source(SESSION_ID);

    var index =
        new RecoveryPublicationPresenceIndex(
            DAY,
            List.of(presence("flow-1", "psp-1", "org-1", SESSION_ID)));

    var result = index.compare(source);

    assertEquals(DAY, result.paymentDay());
    assertEquals(source, result.source());
    assertEquals(
        RecoveryPublicationPresenceIndex.Presence.PRESENT_UNVERIFIED,
        result.presence());
    assertEquals(4L, result.presentRows());
  }

  @Test
  void shouldReportAbsentWhenIndexIsEmpty() {
    var index =
        new RecoveryPublicationPresenceIndex(DAY, List.of());

    var result = index.compare(source(SESSION_ID));

    assertEquals(
        RecoveryPublicationPresenceIndex.Presence.ABSENT,
        result.presence());
    assertEquals(0L, result.presentRows());
  }

  @Test
  void shouldNotTreatAnotherRevisionPublicationAsPresent() {
    var index =
        new RecoveryPublicationPresenceIndex(
            DAY,
            List.of(
                presence(
                    "flow-1", "psp-1", "org-1", ANOTHER_SESSION_ID)));

    var result = index.compare(source(SESSION_ID));

    assertEquals(
        RecoveryPublicationPresenceIndex.Presence.ABSENT,
        result.presence());
  }

  @ParameterizedTest
  @CsvSource({
      "another-flow,psp-1,org-1",
      "flow-1,another-psp,org-1",
      "flow-1,psp-1,another-org"
  })
  void shouldRequireAllIdentityFields(
      String flowName, String psp, String organization) {

    var index =
        new RecoveryPublicationPresenceIndex(
            DAY,
            List.of(
                presence(flowName, psp, organization, SESSION_ID)));

    var result = index.compare(source(SESSION_ID));

    assertEquals(
        RecoveryPublicationPresenceIndex.Presence.ABSENT,
        result.presence());
  }

  @Test
  void shouldCompareUuidWithoutTextCaseDifferences() {
    var index =
        new RecoveryPublicationPresenceIndex(
            DAY,
            List.of(presence("flow-1", "psp-1", "org-1", SESSION_ID)));

    var result =
        index.compare(source(SESSION_ID.toUpperCase(Locale.ROOT)));

    assertEquals(
        RecoveryPublicationPresenceIndex.Presence.PRESENT_UNVERIFIED,
        result.presence());
  }

  @Test
  void shouldRejectAnotherPaymentDay() {
    var presence =
        new RecoveryAdxPublicationPresence(
            DAY.plusDays(1),
            "flow-1",
            "psp-1",
            "org-1",
            UUID.fromString(SESSION_ID),
            4L);

    var rows = List.of(presence);

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryPublicationPresenceIndex(DAY, rows));
  }

  @Test
  void shouldRejectDuplicatePublicationKeys() {
    var presence =
        presence("flow-1", "psp-1", "org-1", SESSION_ID);

    var rows = List.of(presence, presence);

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryPublicationPresenceIndex(DAY, rows));
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L})
  void shouldRejectNonPositiveRowCount(long presentRows) {
    UUID publicationId = UUID.fromString(SESSION_ID);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryAdxPublicationPresence(
                DAY,
                "flow-1",
                "psp-1",
                "org-1",
                publicationId,
                presentRows));
  }

  @Test
  void shouldRejectMissingPublicationId() {
    assertThrows(
        NullPointerException.class,
        () ->
            new RecoveryAdxPublicationPresence(
                DAY,
                "flow-1",
                "psp-1",
                "org-1",
                null,
                4L));
  }

  private static RecoveryAdxPublicationPresence presence(
      String flowName,
      String psp,
      String organization,
      String sessionId) {

    return new RecoveryAdxPublicationPresence(
        DAY,
        flowName,
        psp,
        organization,
        UUID.fromString(sessionId),
        4L);
  }

  private static RecoveryPublicationSource source(String sessionId) {
    var candidate =
        new RecoveryFlowCandidate(
            155333L,
            "flow-1",
            1L,
            "psp-1",
            "org-1",
            LocalDateTime.parse("2026-10-05T10:31:35"));

    var blob =
        new RecoveryBlobSnapshot(
            "fdr3-flows",
            candidate.blobName(),
            "\"etag-1\"",
            100L,
            Map.of());

    return new RecoveryPublicationSource(
        candidate,
        blob,
        RecoveryPublicationSource.SourceType.FDR3_JSON,
        sessionId,
        Instant.parse("2026-10-05T10:31:35Z"));
  }
}