package it.gov.pagopa.fdr.to.eventhub.util;

import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoField;
import java.util.regex.*;

public final class DateParsingUtil {

	private DateParsingUtil() {}

	private static final String TIME_ZONE_REGEX = "([+\\-]\\d{2}:\\d{2}|Z)$";
	private static final Pattern TZ_PATTERN = Pattern.compile(TIME_ZONE_REGEX);
	private static final ZoneId ITALY_ZONE = ZoneId.of("Europe/Rome");
	private static final ZoneId UTC_ZONE = ZoneId.of("UTC");

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

	/**
	 * [PIDM-1734] Parses a datetime string and returns the corresponding UTC LocalDateTime.
	 *
	 * Rules:
	 * - if the input contains an explicit timezone (e.g. Z, +06:00, -05:00), it is converted to UTC
	 * - if the input does not contain a timezone, it is interpreted as Europe/Rome local time
	 *   and then converted to UTC
	 * - if the input is date-only (yyyy-MM-dd), it is returned as start-of-day without UTC normalization,
	 *   because date-only business fields are handled separately through parseToLocalDate()
	 */
	public static LocalDateTime parseDateTimeToUtcLocal(String dateStr) {
	    if (dateStr == null || dateStr.isBlank()) {
	        return null;
	    }

	    String s = dateStr.trim();
	    try {
	        Matcher m = TZ_PATTERN.matcher(s);
	        if (m.find()) {
	            OffsetDateTime odt = OffsetDateTime.parse(s, DATE_TIME_FORMATTER);
	            return odt.atZoneSameInstant(UTC_ZONE).toLocalDateTime();
	        }

	        if (s.length() == 10) { // yyyy-MM-dd
	            return LocalDate.parse(s).atStartOfDay();
	        }

	        return LocalDateTime.parse(s, DATE_TIME_FORMATTER)
	                .atZone(ITALY_ZONE)
	                .withZoneSameInstant(UTC_ZONE)
	                .toLocalDateTime();

	    } catch (DateTimeParseException e) {
	        if (s.length() >= 10) {
	            return LocalDate.parse(s.substring(0, 10)).atStartOfDay();
	        }
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
