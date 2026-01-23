package it.gov.pagopa.fdr.to.eventhub.util;

import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoField;
import java.util.regex.*;

public final class DateParsingUtil {

	private DateParsingUtil() {}

	private static final String TIME_ZONE_REGEX = "([+\\-]\\d{2}:\\d{2}|Z)$";
	private static final Pattern TZ_PATTERN = Pattern.compile(TIME_ZONE_REGEX);

	private static final DateTimeFormatter DATE_TIME_FORMATTER =
			new DateTimeFormatterBuilder()
			.appendPattern("yyyy-MM-dd'T'HH:mm:ss")
			.optionalStart()
			.appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
			.optionalEnd()
			.optionalStart()
			.appendPattern("XXX")
			.optionalEnd()
			.toFormatter();

	/** Parse strings that may or may not have an offset. If they have an offset/Z => normalize to the same UTC time. */
	public static LocalDateTime parseDateTimeToUtcLocal(String dateStr) {
		if (dateStr == null || dateStr.isBlank()) return null;

		String s = dateStr.trim();
		try {
			Matcher m = TZ_PATTERN.matcher(s);
			if (m.find()) {
				ZonedDateTime zdt = ZonedDateTime.parse(s, DATE_TIME_FORMATTER);
				return zdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
			}

			if (s.length() == 10) { // yyyy-MM-dd
				return LocalDate.parse(s).atStartOfDay();
			}

			return LocalDateTime.parse(s, DATE_TIME_FORMATTER);

		} catch (DateTimeParseException e) {
			try {
				if (s.length() >= 10) {
					// fallback: yyyy-MM-dd + midnight
					return LocalDate.parse(s.substring(0, 10)).atStartOfDay();
				}
			} catch (Exception ignored) {}
			throw new IllegalArgumentException("Date format not supported: " + dateStr, e);
		}

	}

	/** Converts a string to LocalDate (first 10 chars) */
	public static LocalDate parseToLocalDate(String dateStr) {
		if (dateStr == null || dateStr.isBlank()) return null;
		String s = dateStr.trim();
		if (s.length() < 10) {
			throw new IllegalArgumentException("Date format not supported: " + dateStr);
		}
		return LocalDate.parse(s.substring(0, 10));
	}

}
