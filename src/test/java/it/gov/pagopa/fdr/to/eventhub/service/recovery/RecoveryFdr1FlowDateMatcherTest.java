package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecoveryFdr1FlowDateMatcherTest {

  private final RecoveryFdr1FlowDateMatcher matcher =
      new RecoveryFdr1FlowDateMatcher();

  @ParameterizedTest
  @CsvSource({
      "16:39:37.208,16:39:37.208,EXACT",
      "16:39:37,16:39:37,EXACT",
      "16:39:37.208475,16:39:37.208475,EXACT",
      "16:39:37.208,16:39:37.208475,MILLISECOND_PROJECTION",
      "16:39:37.208,16:39:37.208999999,MILLISECOND_PROJECTION",
      "16:39:37.208,16:39:37.209,DIFFERENT",
      "16:39:37.810,16:39:37,DIFFERENT",
      "16:39:37.209,16:39:37.208999999,DIFFERENT",
      "16:39:37.208400,16:39:37.208475,DIFFERENT",
      "16:39:37.999,16:39:38,DIFFERENT"
  })
  void shouldCompareDatesWithExplicitPrecisionRule(
      String postgresTime,
      String xmlTime,
      RecoveryFdr1FlowDateMatcher.Match expected) {

    Instant postgresDate =
        Instant.parse("2026-10-29T" + postgresTime + "Z");

    Instant xmlDate =
        Instant.parse("2026-10-29T" + xmlTime + "Z");

    assertEquals(
        expected,
        matcher.compare(postgresDate, xmlDate));
  }

  @Test
  void shouldRejectNullPostgresDate() {
    Instant xmlDate = Instant.parse("2026-10-29T16:39:37Z");

    assertThrows(
        NullPointerException.class,
        () -> matcher.compare(null, xmlDate));
  }

  @Test
  void shouldRejectNullXmlDate() {
    Instant postgresDate = Instant.parse("2026-10-29T16:39:37Z");

    assertThrows(
        NullPointerException.class,
        () -> matcher.compare(postgresDate, null));
  }
}