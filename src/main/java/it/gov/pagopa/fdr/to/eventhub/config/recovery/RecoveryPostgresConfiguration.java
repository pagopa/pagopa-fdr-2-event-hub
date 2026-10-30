package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.zaxxer.hikari.HikariConfig;
import java.util.Map;
import java.util.Objects;

public final class RecoveryPostgresConfiguration {

  private static final String MAX_POOL_SIZE_SETTING =
      "RECOVERY_POSTGRES_MAX_POOL_SIZE";

  private RecoveryPostgresConfiguration() {
    // Utility class.
  }

  public static HikariConfig fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  public static HikariConfig fromEnvironment(
      Map<String, String> environment) {

    Objects.requireNonNull(environment, "environment is required");

    String jdbcUrl =
        required(environment, "RECOVERY_POSTGRES_JDBC_URL");
    String username =
        required(environment, "RECOVERY_POSTGRES_USERNAME");
    String password =
        required(environment, "RECOVERY_POSTGRES_PASSWORD");

    if (!jdbcUrl.startsWith("jdbc:postgresql://")
        || jdbcUrl.contains("?")) {
      throw new IllegalArgumentException(
          "RECOVERY_POSTGRES_JDBC_URL must be a PostgreSQL URL "
              + "without query parameters");
    }

    int maximumPoolSize = maximumPoolSize(environment);

    HikariConfig configuration = new HikariConfig();

    configuration.setPoolName("fdr-recovery-postgres");
    configuration.setDriverClassName("org.postgresql.Driver");
    configuration.setJdbcUrl(jdbcUrl);
    configuration.setUsername(username);
    configuration.setPassword(password);

    configuration.setMaximumPoolSize(maximumPoolSize);
    configuration.setMinimumIdle(0);
    configuration.setConnectionTimeout(5000);
    configuration.setValidationTimeout(2000);
    configuration.setIdleTimeout(60000);
    configuration.setMaxLifetime(600000);
    configuration.setReadOnly(true);

    configuration.addDataSourceProperty("connectTimeout", "5");
    configuration.addDataSourceProperty("socketTimeout", "90");
    configuration.addDataSourceProperty("tcpKeepAlive", "true");
    configuration.addDataSourceProperty("readOnlyMode", "always");

    configuration.addDataSourceProperty("sslmode", "require");
    configuration.addDataSourceProperty("prepareThreshold", "0");

    return configuration;
  }

  private static String required(
      Map<String, String> environment, String setting) {

    String value = environment.get(setting);

    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "Missing required configuration: " + setting);
    }

    return value;
  }

  private static int maximumPoolSize(Map<String, String> environment) {
    String value = environment.getOrDefault(MAX_POOL_SIZE_SETTING, "2");

    final int parsed;

    try {
      parsed = Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          MAX_POOL_SIZE_SETTING + " must be an integer between 1 and 10");
    }

    if (parsed < 1 || parsed > 10) {
      throw new IllegalArgumentException(
          MAX_POOL_SIZE_SETTING + " must be between 1 and 10");
    }

    return parsed;
  }
}