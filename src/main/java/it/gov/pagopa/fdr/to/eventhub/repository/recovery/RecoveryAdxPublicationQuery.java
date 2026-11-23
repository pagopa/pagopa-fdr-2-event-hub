package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class RecoveryAdxPublicationQuery {

  public static final int MAX_PUBLICATIONS = 100;
  public static final int MAX_RESULT_GROUPS = 10_000;

  private static final String QUERY = """
      declare query_parameters(
          recoveryDay:datetime,
          recoveryPublicationIds:string);
      let requestedIds = parse_json(recoveryPublicationIds);
      IUV_RENDICONTATI
      | where DATA_ESITO_SINGOLO_PAGAMENTO == recoveryDay
      | where tostring(UNIQUE_ID) in~ (requestedIds)
      | summarize PresentRows = count()
          by ID_FLUSSO, PSP, ID_DOMINIO,
             PublicationId = tolower(tostring(UNIQUE_ID))
      | take %d
      """.formatted(MAX_RESULT_GROUPS + 1);

  private final Map<String, String> parameters;

  public RecoveryAdxPublicationQuery(
      LocalDate paymentDay,
      Collection<UUID> publicationIds) {

    Objects.requireNonNull(paymentDay, "paymentDay is required");
    Objects.requireNonNull(
        publicationIds, "publicationIds is required");

    if (publicationIds.isEmpty()
        || publicationIds.size() > MAX_PUBLICATIONS) {
      throw new IllegalArgumentException(
          "publicationIds must contain between 1 and "
              + MAX_PUBLICATIONS + " entries");
    }

    String idsJson =
        publicationIds.stream()
            .map(id -> Objects.requireNonNull(
                id, "publicationIds must not contain null"))
            .map(UUID::toString)
            .distinct()
            .sorted()
            .collect(Collectors.joining("\",\"", "[\"", "\"]"));

    this.parameters =
        Map.of(
            "recoveryDay", paymentDay + "T00:00:00Z",
            "recoveryPublicationIds", idsJson);
  }

  public String text() {
    return QUERY;
  }

  public Map<String, String> parameters() {
    return parameters;
  }
}