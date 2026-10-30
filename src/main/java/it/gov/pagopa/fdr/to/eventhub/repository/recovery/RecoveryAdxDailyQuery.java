package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

public final class RecoveryAdxDailyQuery {

  private static final String QUERY = """
      declare query_parameters(recoveryDay:datetime);
      IUV_RENDICONTATI
      | where DATA_ESITO_SINGOLO_PAGAMENTO == recoveryDay
      | summarize PresentRows = count() by ID_FLUSSO, PSP, ID_DOMINIO
      """;

  private final LocalDate day;

  public RecoveryAdxDailyQuery(LocalDate day) {
    this.day = Objects.requireNonNull(day, "day is required");
  }

  public String text() {
    return QUERY;
  }

  public Map<String, String> parameters() {
    return Map.of("recoveryDay", day + "T00:00:00Z");
  }
}