package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryTableClientsTest {

  private Map<String, String> validConfiguration() {
    String fakeKey =
        Base64.getEncoder().encodeToString(
            "fake-key-for-offline-tests-only"
                .getBytes(StandardCharsets.UTF_8));

    Map<String, String> configuration = new HashMap<>();

    configuration.put(
        "FDR_SA_CONNECTION_STRING",
        "DefaultEndpointsProtocol=https;"
            + "AccountName=recoverytest;"
            + "AccountKey=" + fakeKey + ";"
            + "EndpointSuffix=core.windows.net");

    configuration.put("RECOVERY_JOBS_TABLE_NAME", "fdrrecoveryjobs");
    configuration.put("RECOVERY_ITEMS_TABLE_NAME", "fdrrecoveryitems");

    return configuration;
  }

  @Test
  void shouldBuildClientsForConfiguredTables() {
    Map<String, String> configuration = validConfiguration();

    RecoveryTableClients clients =
        RecoveryTableClients.fromEnvironment(configuration);

    assertEquals("fdrrecoveryjobs", clients.jobs().getTableName());
    assertEquals("fdrrecoveryitems", clients.items().getTableName());
  }

  @Test
  void shouldReturnSameClientInstances() {
    Map<String, String> configuration = validConfiguration();

    RecoveryTableClients clients =
        RecoveryTableClients.fromEnvironment(configuration);

    assertSame(clients.jobs(), clients.jobs());
    assertSame(clients.items(), clients.items());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "FDR_SA_CONNECTION_STRING",
      "RECOVERY_JOBS_TABLE_NAME",
      "RECOVERY_ITEMS_TABLE_NAME"
  })
  void shouldRejectMissingConfiguration(String setting) {
    Map<String, String> configuration = validConfiguration();
    configuration.remove(setting);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> RecoveryTableClients.fromEnvironment(configuration));

    assertEquals(
        "Missing required configuration: " + setting,
        failure.getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void shouldRejectBlankConnectionString(String value) {
    Map<String, String> configuration = validConfiguration();
    configuration.put("FDR_SA_CONNECTION_STRING", value);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> RecoveryTableClients.fromEnvironment(configuration));

    assertEquals(
        "Missing required configuration: FDR_SA_CONNECTION_STRING",
        failure.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "ab",
      "1recovery",
      "recovery-jobs",
      "recovery_jobs",
      " recoveryjobs",
      "recoveryjobs "
  })
  void shouldRejectInvalidTableNames(String name) {
    Map<String, String> jobsConfiguration = validConfiguration();
    jobsConfiguration.put("RECOVERY_JOBS_TABLE_NAME", name);

    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryTableClients.fromEnvironment(jobsConfiguration));

    Map<String, String> itemsConfiguration = validConfiguration();
    itemsConfiguration.put("RECOVERY_ITEMS_TABLE_NAME", name);

    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryTableClients.fromEnvironment(itemsConfiguration));
  }

  @Test
  void shouldRejectSameTableForJobsAndItems() {
    Map<String, String> configuration = validConfiguration();
    configuration.put("RECOVERY_ITEMS_TABLE_NAME", "FDRRECOVERYJOBS");

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> RecoveryTableClients.fromEnvironment(configuration));

    assertEquals(
        "Recovery jobs and items must use different tables",
        failure.getMessage());
  }
}