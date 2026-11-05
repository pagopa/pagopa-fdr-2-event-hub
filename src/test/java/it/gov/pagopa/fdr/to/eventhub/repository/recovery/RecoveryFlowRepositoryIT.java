package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryPostgresConfiguration;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryPostgresDataSource;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_POSTGRES_INTEGRATION_TESTS",
    matches = "true")
class RecoveryFlowRepositoryIT {

  @Test
  void shouldConnectReadOnlyAndReadCandidatesFromDev() throws Exception {
    String configuredDay = System.getenv("RECOVERY_POSTGRES_TEST_DAY");

    assertNotNull(
        configuredDay,
        "RECOVERY_POSTGRES_TEST_DAY must contain the day to verify");

    LocalDate day = LocalDate.parse(configuredDay);

    try (RecoveryPostgresDataSource resources =
        new RecoveryPostgresDataSource(
            RecoveryPostgresConfiguration.fromEnvironment())) {

      DataSource dataSource = resources.dataSource();

      try (Connection connection = dataSource.getConnection()) {
        assertTrue(
            connection.isReadOnly(),
            "The recovery connection must be read-only");

        try (Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(10);

          try (ResultSet result =
              statement.executeQuery("SHOW transaction_read_only")) {

            assertTrue(result.next());
            assertTrue(
                "on".equalsIgnoreCase(result.getString(1)),
                "PostgreSQL must enforce a read-only transaction");
          }
        }
      }

      RecoveryFlowRepository repository =
          new RecoveryFlowRepository(dataSource, 60);

      List<RecoveryFlowCandidate> candidates =
          repository.findPage(day, 0L, 10);

      assertNotNull(candidates);
      assertTrue(candidates.size() <= 10);

      long previousFlowId = 0L;

      for (RecoveryFlowCandidate candidate : candidates) {
        assertTrue(
            candidate.flowId() > previousFlowId,
            "Candidates must have distinct, increasing flow IDs");

        previousFlowId = candidate.flowId();
      }

      System.out.printf(
          "DEV recovery discovery completed: day=%s, candidates=%d%n",
          day,
          candidates.size());
    }
  }
}