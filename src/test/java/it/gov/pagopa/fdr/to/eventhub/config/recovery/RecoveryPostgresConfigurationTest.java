package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.zaxxer.hikari.HikariConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryPostgresConfigurationTest {

  private Map<String, String> validConfiguration() {
    Map<String, String> configuration = new HashMap<>();

    configuration.put(
        "RECOVERY_POSTGRES_JDBC_URL",
        "jdbc:postgresql://localhost:5432/fdr3");
    configuration.put("RECOVERY_POSTGRES_USERNAME", "recovery_reader");
    configuration.put("RECOVERY_POSTGRES_PASSWORD", "fake-test-password");

    return configuration;
  }

  @Test
  void shouldConfigureDedicatedReadOnlyPoolWithoutConnecting() {
      Map<String, String> environment = validConfiguration();

      HikariConfig configuration = RecoveryPostgresConfiguration.fromEnvironment(environment);

      assertEquals("fdr-recovery-postgres", configuration.getPoolName());
      assertEquals("org.postgresql.Driver", configuration.getDriverClassName());
      assertEquals("jdbc:postgresql://localhost:5432/fdr3", configuration.getJdbcUrl());
      assertEquals("recovery_reader", configuration.getUsername());
      assertEquals("fake-test-password", configuration.getPassword());

      assertEquals(2, configuration.getMaximumPoolSize());
      assertEquals(0, configuration.getMinimumIdle());
      assertEquals(5000L, configuration.getConnectionTimeout());
      assertEquals(2000L, configuration.getValidationTimeout());
      assertTrue(configuration.isReadOnly());

      assertEquals("5", configuration.getDataSourceProperties().getProperty("connectTimeout"));
      assertEquals("90", configuration.getDataSourceProperties().getProperty("socketTimeout"));
      assertEquals("always", configuration.getDataSourceProperties().getProperty("readOnlyMode"));
      assertEquals("require", configuration.getDataSourceProperties().getProperty("sslmode"));

      assertEquals("0", configuration.getDataSourceProperties().getProperty("prepareThreshold"));

      assertFalse(configuration.getDataSourceProperties().containsKey("sslfactory"));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 4, 10})
  void shouldAcceptConfiguredPoolSize(int poolSize) {
    Map<String, String> environment = validConfiguration();
    environment.put(
        "RECOVERY_POSTGRES_MAX_POOL_SIZE",
        Integer.toString(poolSize));

    HikariConfig configuration =
        RecoveryPostgresConfiguration.fromEnvironment(environment);

    assertEquals(poolSize, configuration.getMaximumPoolSize());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "RECOVERY_POSTGRES_JDBC_URL",
      "RECOVERY_POSTGRES_USERNAME",
      "RECOVERY_POSTGRES_PASSWORD"
  })
  void shouldRejectMissingRequiredConfiguration(String setting) {
    Map<String, String> environment = validConfiguration();
    environment.remove(setting);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> RecoveryPostgresConfiguration.fromEnvironment(environment));

    assertEquals(
        "Missing required configuration: " + setting,
        failure.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "",
      " ",
      "0",
      "-1",
      "11",
      "not-a-number"
  })
  void shouldRejectInvalidPoolSize(String value) {
    Map<String, String> environment = validConfiguration();
    environment.put("RECOVERY_POSTGRES_MAX_POOL_SIZE", value);

    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryPostgresConfiguration.fromEnvironment(environment));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "jdbc:mysql://localhost:3306/fdr3",
      "jdbc:postgresql://localhost:5432/fdr3?sslmode=disable",
      "jdbc:postgresql://localhost:5432/fdr3?user=test"
  })
  void shouldRejectUnsupportedJdbcUrl(String jdbcUrl) {
    Map<String, String> environment = validConfiguration();
    environment.put("RECOVERY_POSTGRES_JDBC_URL", jdbcUrl);

    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryPostgresConfiguration.fromEnvironment(environment));
  }
}