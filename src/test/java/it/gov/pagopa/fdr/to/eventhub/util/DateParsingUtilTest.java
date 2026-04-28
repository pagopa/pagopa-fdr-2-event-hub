package it.gov.pagopa.fdr.to.eventhub.util;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DateParsingUtilTest {

  @Test
  void testParseDateTimeToUtcLocal_withTimezone_normalizedToUtcSameInstant() {
    // All these strings represent the SAME INSTANT: 2025-03-06T10:01:36Z
    String[] dates =
        new String[] {
          "2025-03-06T11:01:36+01:00",
          "2025-03-06T05:01:36-05:00",
          "2025-03-06T10:01:36Z",
        };

    for (String date : dates) {
      LocalDateTime ldt = DateParsingUtil.parseDateTimeToUtcLocal(date);
      assertNotNull(ldt);
      assertEquals(LocalDateTime.of(2025, 3, 6, 10, 1, 36), ldt);
    }
  }

  @Test
  void testParseDateTimeToUtcLocal_withoutTimezone_interpretedAsItalyAndConvertedToUtc() {
    String date = "2025-03-06T11:01:36";
    LocalDateTime ldt = DateParsingUtil.parseDateTimeToUtcLocal(date);
    assertNotNull(ldt);
    assertEquals(LocalDateTime.of(2025, 3, 6, 10, 1, 36), ldt);
  }

  @Test
  void testParseDateTimeToUtcLocal_dateOnly_midnight() {
    String date = "2025-03-06";
    LocalDateTime ldt = DateParsingUtil.parseDateTimeToUtcLocal(date);
    assertNotNull(ldt);
    assertEquals(LocalDateTime.of(2025, 3, 6, 0, 0), ldt);
  }

  @Test
  void testParseToLocalDate_acceptsDateTimeAndCutsToDate() {
    assertEquals(LocalDate.of(2025, 3, 6), DateParsingUtil.parseToLocalDate("2025-03-06T11:01:36+01:00"));
    assertEquals(LocalDate.of(2025, 3, 6), DateParsingUtil.parseToLocalDate("2025-03-06T11:01:36"));
    assertEquals(LocalDate.of(2025, 3, 6), DateParsingUtil.parseToLocalDate("2025-03-06"));
  }

  @Test
  void testParseToLocalDate_invalidTooShort_throws() {
    assertThrows(IllegalArgumentException.class, () -> DateParsingUtil.parseToLocalDate("2025-03"));
  }
  
  @Test
  void testParseDateTimeToUtcLocal_withExplicitPositiveOffset_convertedToUtc() {
    String date = "2026-04-10T12:59:12.989+06:00";
    LocalDateTime ldt = DateParsingUtil.parseDateTimeToUtcLocal(date);
    assertNotNull(ldt);
    assertEquals(LocalDateTime.of(2026, 4, 10, 6, 59, 12, 989_000_000), ldt);
  }
  
  @Test
  void testParseDateTimeToUtcLocal_withoutTimezone_interpretedAsRomeAndConvertedToUtc() {
    String date = "2026-04-10T12:59:12.989";
    LocalDateTime ldt = DateParsingUtil.parseDateTimeToUtcLocal(date);
    assertNotNull(ldt);
    assertEquals(LocalDateTime.of(2026, 4, 10, 10, 59, 12, 989_000_000), ldt);
  }
  
  @Test
  void testParseDateTimeToUtcLocal_null_returnsNull() {
    assertNull(DateParsingUtil.parseDateTimeToUtcLocal(null));
  }
  
  @Test
  void testParseDateTimeToUtcLocal_blank_returnsNull() {
    assertNull(DateParsingUtil.parseDateTimeToUtcLocal("   "));
  }
  
  @Test
  void testParseDateTimeToUtcLocal_trimsInputBeforeParsing() {
    String date = "  2025-03-06T11:01:36+01:00  ";
    LocalDateTime ldt = DateParsingUtil.parseDateTimeToUtcLocal(date);
    assertNotNull(ldt);
    assertEquals(LocalDateTime.of(2025, 3, 6, 10, 1, 36), ldt);
  }
  
  @Test
  void testParseDateTimeToUtcLocal_invalidDateTimeWithParsableDatePrefix_fallsBackToMidnight() {
    String date = "2025-03-06abc";
    LocalDateTime ldt = DateParsingUtil.parseDateTimeToUtcLocal(date);
    assertNotNull(ldt);
    assertEquals(LocalDateTime.of(2025, 3, 6, 0, 0), ldt);
  }
  
  @Test
  void testParseDateTimeToUtcLocal_invalidTooShort_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DateParsingUtil.parseDateTimeToUtcLocal("2025-03"));
  }
}