package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import java.util.Map;
import java.util.Objects;

public final class RecoveryTableClients {

  private final TableClient jobs;
  private final TableClient items;

  private RecoveryTableClients(TableClient jobs, TableClient items) {
    this.jobs = jobs;
    this.items = items;
  }

  public static RecoveryTableClients fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  public static RecoveryTableClients fromEnvironment(
      Map<String, String> environment) {

    Objects.requireNonNull(environment, "environment is required");

    String connectionString =
        required(environment, "FDR_SA_CONNECTION_STRING");
    String jobsTable =
        required(environment, "RECOVERY_JOBS_TABLE_NAME");
    String itemsTable =
        required(environment, "RECOVERY_ITEMS_TABLE_NAME");

    validateTableName("RECOVERY_JOBS_TABLE_NAME", jobsTable);
    validateTableName("RECOVERY_ITEMS_TABLE_NAME", itemsTable);

    if (jobsTable.equalsIgnoreCase(itemsTable)) {
      throw new IllegalArgumentException(
          "Recovery jobs and items must use different tables");
    }

    TableServiceClient serviceClient =
        new TableServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();

    return new RecoveryTableClients(
        serviceClient.getTableClient(jobsTable),
        serviceClient.getTableClient(itemsTable));
  }

  private static String required(
      Map<String, String> environment, String name) {

    String value = environment.get(name);

    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "Missing required configuration: " + name);
    }

    return value;
  }

  private static void validateTableName(String setting, String tableName) {
    if (!tableName.matches("[A-Za-z][A-Za-z0-9]{2,62}")) {
      throw new IllegalArgumentException(
          setting + " must contain 3-63 alphanumeric characters "
              + "and start with a letter");
    }
  }

  public TableClient jobs() {
    return jobs;
  }

  public TableClient items() {
    return items;
  }
}