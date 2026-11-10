package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DateTimeException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr1DateNormalizerTest {

  private final RecoveryFdr1DateNormalizer normalizer =
      new RecoveryFdr1DateNormalizer();

  @ParameterizedTest
  @CsvSource({
      "2026-10-29T16:39:37Z,2026-10-29T16:39:37Z",
      "2026-10-09T01:00:06.208475643+02:00,"
          + "2026-10-08T23:00:06.208475643Z",
      "2026-01-10T12:00:00+01:00,2026-01-10T11:00:00Z",
      "2026-07-10T12:00:00-05:00,2026-07-10T17:00:00Z",
      "2026-10-09T01:00:06.208475643,"
          + "2026-10-08T23:00:06.208475643Z",
      "2026-01-10T12:00:00,2026-01-10T11:00:00Z",
      "2026-10-25T02:30:00+02:00,2026-10-25T00:30:00Z",
      "2026-10-25T02:30:00+01:00,2026-10-25T01:30:00Z"
  })
  void shouldNormalizeValidDates(String input, String expected) {
    Instant expectedInstant = Instant.parse(expected);

    assertEquals(expectedInstant, normalizer.normalize(input));
  }

  @Test
  void shouldStripSurroundingWhitespace() {
    assertEquals(
        Instant.parse("2026-10-29T16:39:37Z"),
        normalizer.normalize(" 2026-10-29T16:39:37Z "));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "2026-03-29T02:30:00",
      "2026-10-25T02:30:00"
  })
  void shouldRejectNonUniqueLocalTimes(String input) {
    assertThrows(
        DateTimeException.class,
        () -> normalizer.normalize(input));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "",
      "invalid",
      "2026-02-30T12:00:00Z",
      "2026-10-29",
      "2026-10-29T16:39:37.1234567891Z",
      "2026-10-29T16:39:37Z trailing"
  })
  void shouldRejectInvalidDates(String input) {
    assertThrows(
        DateTimeException.class,
        () -> normalizer.normalize(input));
  }

  @Test
  void shouldRejectNull() {
    assertThrows(
        NullPointerException.class,
        () -> normalizer.normalize(null));
  }
}