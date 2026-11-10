package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr1IdentityMatcherTest {

  private static final String FLOW = "flow-1";
  private static final String PSP = "psp-1";
  private static final String ORGANIZATION = "organization-1";
  private static final String DATE = "2026-10-29T16:39:37.810Z";

  private final RecoveryFdr1IdentityMatcher matcher =
      new RecoveryFdr1IdentityMatcher(
          new RecoveryFdr1DateNormalizer());

  @ParameterizedTest
  @ValueSource(strings = {
      "2026-10-29T16:39:37.810Z",
      "2026-10-29T17:39:37.810+01:00",
      "2026-10-29T17:39:37.810"
  })
  void shouldMatchEquivalentDates(String date) {
    var identity = identity(FLOW, PSP, ORGANIZATION, date);

    var comparison = matcher.compare(candidate(), identity);

    assertTrue(comparison.matches());
    assertEquals(Set.of(), comparison.differences());
  }

  @ParameterizedTest
  @EnumSource(RecoveryFdr1IdentityMatcher.Field.class)
  void shouldIdentifyEachDifferentField(
      RecoveryFdr1IdentityMatcher.Field field) {

    String name =
        field == RecoveryFdr1IdentityMatcher.Field.FLOW_NAME
            ? "another-flow" : FLOW;

    String psp =
        field == RecoveryFdr1IdentityMatcher.Field.PSP_DOMAIN_ID
            ? "another-psp" : PSP;

    String organization =
        field == RecoveryFdr1IdentityMatcher.Field.ORG_DOMAIN_ID
            ? "another-organization" : ORGANIZATION;

    String date =
        field == RecoveryFdr1IdentityMatcher.Field.FLOW_DATE
            ? "2026-10-29T16:39:38.810Z" : DATE;

    var identity = identity(name, psp, organization, date);

    var comparison = matcher.compare(candidate(), identity);

    assertFalse(comparison.matches());
    assertEquals(Set.of(field), comparison.differences());
  }

  @Test
  void shouldReportAllDifferences() {
    var identity =
        identity(
            "another-flow",
            "another-psp",
            "another-organization",
            "2026-10-29T16:39:38Z");

    var comparison = matcher.compare(candidate(), identity);

    assertFalse(comparison.matches());
    assertEquals(
        EnumSet.allOf(RecoveryFdr1IdentityMatcher.Field.class),
        comparison.differences());
  }

  @Test
  void shouldExposeFractionalSecondDifference() {
    var identity =
        identity(FLOW, PSP, ORGANIZATION, "2026-10-29T16:39:37Z");

    var comparison = matcher.compare(candidate(), identity);

    assertEquals(
        Set.of(RecoveryFdr1IdentityMatcher.Field.FLOW_DATE),
        comparison.differences());
  }

  @Test
  void shouldPropagateInvalidDate() {
    var candidate = candidate();
    var identity = identity(FLOW, PSP, ORGANIZATION, "invalid");

    assertThrows(
        DateTimeException.class,
        () -> matcher.compare(candidate, identity));
  }

  @Test
  void shouldRejectNullCandidate() {
    var identity = identity(FLOW, PSP, ORGANIZATION, DATE);

    assertThrows(
        NullPointerException.class,
        () -> matcher.compare(null, identity));
  }

  @Test
  void shouldRejectNullIdentity() {
    var candidate = candidate();

    assertThrows(
        NullPointerException.class,
        () -> matcher.compare(candidate, null));
  }

  private static RecoveryFlowCandidate candidate() {
    return new RecoveryFlowCandidate(
        592L,
        FLOW,
        1L,
        PSP,
        ORGANIZATION,
        LocalDateTime.parse("2026-10-29T16:39:37.810"));
  }

  @Test
  void shouldMatchXmlDateProjectedToPostgresMilliseconds() {
    var candidate = candidate();

    var identity =
        identity(
            FLOW,
            PSP,
            ORGANIZATION,
            "2026-10-29T16:39:37.810475Z");

    var comparison = matcher.compare(candidate, identity);

    assertTrue(comparison.matches());
    assertEquals(Set.of(), comparison.differences());
  }

  private static RecoveryFdr1EnvelopeReader.RequestIdentity identity(
      String name,
      String psp,
      String organization,
      String date) {

    return new RecoveryFdr1EnvelopeReader.RequestIdentity(
        name, psp, organization, date);
  }
}