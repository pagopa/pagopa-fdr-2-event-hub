package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public final class RecoveryJobRequest {

  private final LocalDate dateFrom;
  private final LocalDate dateTo;
  private final boolean dryRun;

  @JsonCreator
  public RecoveryJobRequest(
      @JsonProperty("dateFrom") String dateFrom,
      @JsonProperty("dateTo") String dateTo,
      @JsonProperty("dryRun") Boolean dryRun) {

    this.dateFrom = parseRequiredDate("dateFrom", dateFrom);
    this.dateTo = parseRequiredDate("dateTo", dateTo);

    if (this.dateFrom.isAfter(this.dateTo)) {
      throw new IllegalArgumentException(
          "'dateFrom' must be before or equal to 'dateTo'");
    }

    this.dryRun = dryRun == null || dryRun;
  }

  private static LocalDate parseRequiredDate(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("'" + field + "' is required");
    }

    if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
      throw new IllegalArgumentException(
          "'" + field + "' must use yyyy-MM-dd format");
    }

    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "'" + field + "' must be a valid calendar date", e);
    }
  }

  public LocalDate getDateFrom() {
    return dateFrom;
  }

  public LocalDate getDateTo() {
    return dateTo;
  }

  public boolean isDryRun() {
    return dryRun;
  }
}