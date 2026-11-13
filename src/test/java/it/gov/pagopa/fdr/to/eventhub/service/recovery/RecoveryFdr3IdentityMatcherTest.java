package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryFdr3IdentityMatcherTest {

  private static final Instant FLOW_DATE =
      Instant.parse("2026-10-05T12:50:26.001454Z");

  private static final RecoveryFlowCandidate CANDIDATE =
      new RecoveryFlowCandidate(
          299L,
          "flow-1",
          1L,
          "PSP-A",
          "ORG-A",
          LocalDateTime.parse("2026-10-05T12:50:26.001454"));

  private final RecoveryFdr3IdentityMatcher matcher =
      new RecoveryFdr3IdentityMatcher();

  private RecoveryFdr3IdentityReader.Identity identity(
      RecoveryFdr3IdentityMatcher.Field changed) {

    return new RecoveryFdr3IdentityReader.Identity(
        changed == RecoveryFdr3IdentityMatcher.Field.FLOW_NAME
            ? "other-flow" : "flow-1",
        changed == RecoveryFdr3IdentityMatcher.Field.REVISION
            ? 2L : 1L,
        changed == RecoveryFdr3IdentityMatcher.Field.PSP_DOMAIN_ID
            ? "other-psp" : "PSP-A",
        changed == RecoveryFdr3IdentityMatcher.Field.ORG_DOMAIN_ID
            ? "other-org" : "ORG-A",
        changed == RecoveryFdr3IdentityMatcher.Field.FLOW_DATE
            ? FLOW_DATE.plusNanos(1) : FLOW_DATE);
  }

  @Test
  void shouldMatchAllFieldsAtFullPrecision() {
    RecoveryFdr3IdentityMatcher.Comparison result =
        matcher.compare(CANDIDATE, identity(null));

    assertTrue(result.matches());
    assertEquals(Set.of(), result.differences());
  }

  @ParameterizedTest
  @EnumSource(RecoveryFdr3IdentityMatcher.Field.class)
  void shouldReportEachDifferentField(
      RecoveryFdr3IdentityMatcher.Field field) {

    RecoveryFdr3IdentityMatcher.Comparison result =
        matcher.compare(CANDIDATE, identity(field));

    assertFalse(result.matches());
    assertEquals(Set.of(field), result.differences());
  }

  @Test
  void shouldReportAllDifferences() {
    RecoveryFdr3IdentityReader.Identity different =
        new RecoveryFdr3IdentityReader.Identity(
            "other-flow",
            2L,
            "other-psp",
            "other-org",
            FLOW_DATE.plusSeconds(1));

    assertEquals(
        Set.of(RecoveryFdr3IdentityMatcher.Field.values()),
        matcher.compare(CANDIDATE, different).differences());
  }

  @Test
  void shouldNotHideDifferencesByTruncatingToMilliseconds() {
    RecoveryFdr3IdentityReader.Identity lessPrecise =
        new RecoveryFdr3IdentityReader.Identity(
            "flow-1",
            1L,
            "PSP-A",
            "ORG-A",
            Instant.parse("2026-10-05T12:50:26.001Z"));

    assertEquals(
        Set.of(RecoveryFdr3IdentityMatcher.Field.FLOW_DATE),
        matcher.compare(CANDIDATE, lessPrecise).differences());
  }

  @Test
  void shouldReturnImmutableDifferences() {
    Set<RecoveryFdr3IdentityMatcher.Field> differences =
        matcher.compare(CANDIDATE, identity(null)).differences();

    assertThrows(
        UnsupportedOperationException.class,
        () -> differences.add(
            RecoveryFdr3IdentityMatcher.Field.FLOW_DATE));
  }

  @Test
  void shouldRejectNullCandidate() {
    RecoveryFdr3IdentityReader.Identity source = identity(null);

    assertThrows(
        NullPointerException.class,
        () -> matcher.compare(null, source));
  }

  @Test
  void shouldRejectNullIdentity() {
    assertThrows(
        NullPointerException.class,
        () -> matcher.compare(CANDIDATE, null));
  }
}