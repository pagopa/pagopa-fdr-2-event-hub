package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.microsoft.azure.kusto.data.Client;
import com.microsoft.azure.kusto.data.ClientRequestProperties;
import com.microsoft.azure.kusto.data.KustoOperationResult;
import com.microsoft.azure.kusto.data.KustoResultSetTable;
import com.microsoft.azure.kusto.data.WellKnownDataSet;
import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxFlowPresence;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RecoveryAdxRepository {

  private static final int MAX_GROUPS = 100_000;

  private final Client client;
  private final String database;
  private final long queryTimeoutMillis;

  public RecoveryAdxRepository(
      Client client,
      String database,
      int queryTimeoutSeconds) {

    this.client = Objects.requireNonNull(client, "client is required");

    if (database == null || database.isBlank()) {
      throw new IllegalArgumentException("database is required");
    }

    if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 600) {
      throw new IllegalArgumentException(
          "queryTimeoutSeconds must be between 1 and 600");
    }

    this.database = database;
    this.queryTimeoutMillis = queryTimeoutSeconds * 1000L;
  }

  public List<RecoveryAdxFlowPresence> findByDay(LocalDate day)
      throws DataClientException, DataServiceException {

    RecoveryAdxDailyQuery query = new RecoveryAdxDailyQuery(day);

    ClientRequestProperties properties = new ClientRequestProperties();
    properties.setTimeoutInMilliSec(queryTimeoutMillis);
    properties.setOption("deferpartialqueryfailures", false);

    query.parameters().forEach(properties::setParameter);

    KustoOperationResult result =
        client.executeQuery(database, query.text(), properties);

    if (result == null) {
      throw new IllegalStateException("ADX returned no response");
    }

    List<KustoResultSetTable> primaryTables =
        result.getResultTables().stream()
            .filter(
                table ->
                    table.getTableKind() == WellKnownDataSet.PrimaryResult)
            .toList();

    if (primaryTables.size() != 1) {
      throw new IllegalStateException(
          "ADX must return exactly one primary result table");
    }

    KustoResultSetTable table = primaryTables.get(0);

    // Validate the expected columns even when the result contains no rows.
    table.findColumn("ID_FLUSSO");
    table.findColumn("PSP");
    table.findColumn("ID_DOMINIO");
    table.findColumn("PresentRows");

    List<RecoveryAdxFlowPresence> flows = new ArrayList<>();

    while (table.next()) {
      if (flows.size() >= MAX_GROUPS) {
        throw new IllegalStateException(
            "ADX daily result exceeds the supported number of groups");
      }

      Long presentRows = table.getLongObject("PresentRows");

      if (presentRows == null) {
        throw new IllegalStateException("ADX returned a null PresentRows");
      }

      flows.add(
          new RecoveryAdxFlowPresence(
              table.getString("ID_FLUSSO"),
              table.getString("PSP"),
              table.getString("ID_DOMINIO"),
              presentRows));
    }

    return List.copyOf(flows);
  }
}