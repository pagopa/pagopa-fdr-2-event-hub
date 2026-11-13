package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Objects;

public final class RecoveryFdr1DateNormalizer {

  private static final ZoneId LOCAL_ZONE = ZoneId.of("Europe/Rome");

  private static final DateTimeFormatter FORMATTER =
      new DateTimeFormatterBuilder()
          .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          .optionalStart()
          .appendOffsetId()
          .optionalEnd()
          .toFormatter()
          .withResolverStyle(ResolverStyle.STRICT);

  public Instant normalize(String value) {
    Objects.requireNonNull(value, "value is required");

    TemporalAccessor parsed = FORMATTER.parse(value.strip());
    LocalDateTime localDateTime = LocalDateTime.from(parsed);

    if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) {
      return localDateTime.toInstant(ZoneOffset.from(parsed));
    }

    List<ZoneOffset> validOffsets =
        LOCAL_ZONE.getRules().getValidOffsets(localDateTime);

    if (validOffsets.isEmpty()) {
      throw new DateTimeException(
          "FDR1 local date falls in a Europe/Rome clock gap: " + value);
    }

    if (validOffsets.size() != 1) {
      throw new DateTimeException(
          "FDR1 local date is ambiguous in Europe/Rome: " + value);
    }

    return localDateTime.toInstant(validOffsets.get(0));
  }
}