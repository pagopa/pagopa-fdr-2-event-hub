package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import java.text.ParsePosition;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

final class RecoveryFdr1DateSyntax {

  private RecoveryFdr1DateSyntax() {
  }

  static String validate(String value) {
    Objects.requireNonNull(value, "value is required");

    ParsePosition position = new ParsePosition(0);

    var parsed =
        DateTimeFormatter.ISO_DATE_TIME.parse(value, position);

    if (parsed == null
        || position.getErrorIndex() >= 0
        || position.getIndex() != value.length()) {

      int errorIndex =
          position.getErrorIndex() >= 0
              ? position.getErrorIndex()
              : position.getIndex();

      throw new DateTimeParseException(
          "Invalid FDR1 date-time", value, errorIndex);
    }

    return value;
  }
}