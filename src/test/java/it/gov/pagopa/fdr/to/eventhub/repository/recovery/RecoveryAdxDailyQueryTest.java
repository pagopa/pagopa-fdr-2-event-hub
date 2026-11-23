package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryAdxDailyQueryTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "2026-10-01",
      "2026-10-25",
      "2026-10-31",
      "2026-12-31"
  })
  void shouldBindRequestedDayAtMidnightWithoutShiftingIt(String value) {
    LocalDate day = LocalDate.parse(value);

    RecoveryAdxDailyQuery query = new RecoveryAdxDailyQuery(day);

    assertEquals(
        Map.of("recoveryDay", value + "T00:00:00Z"),
        query.parameters());
  }

  @Test
  void shouldReuseQueryTextAcrossDifferentDays() {
    RecoveryAdxDailyQuery first =
        new RecoveryAdxDailyQuery(LocalDate.of(2026, 10, 1));

    RecoveryAdxDailyQuery second =
        new RecoveryAdxDailyQuery(LocalDate.of(2026, 10, 31));

    assertEquals(first.text(), second.text());
  }

  @Test
  void shouldRejectMissingDay() {
    assertThrows(
        NullPointerException.class,
        () -> new RecoveryAdxDailyQuery(null));
  }
}