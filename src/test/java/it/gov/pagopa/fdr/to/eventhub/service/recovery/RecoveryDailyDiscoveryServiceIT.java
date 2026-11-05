package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.zaxxer.hikari.HikariConfig;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxClientFactory;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxConfiguration;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryPostgresConfiguration;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryPostgresDataSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_DISCOVERY_INTEGRATION_TESTS",
    matches = "true")
class RecoveryDailyDiscoveryServiceIT {

  private static final String DEV_ADX_CLUSTER =
      "https://pagopaddataexplorer.westeurope.kusto.windows.net";

  private static final String DEV_POSTGRES_URL =
      "jdbc:postgresql://fdr-db.d.internal.postgresql.pagopa.it:5432/fdr3";

  @Test
  void shouldCompareCompleteDayInDev() throws Exception {
    String configuredDay = System.getenv("RECOVERY_DISCOVERY_TEST_DAY");

    assertNotNull(
        configuredDay,
        "RECOVERY_DISCOVERY_TEST_DAY must contain the day to verify");

    LocalDate day = LocalDate.parse(configuredDay);

    RecoveryAdxConfiguration adxConfiguration =
        RecoveryAdxConfiguration.fromEnvironment();

    HikariConfig postgresConfiguration =
        RecoveryPostgresConfiguration.fromEnvironment();

    // This integration test is intentionally restricted to DEV.
    assertEquals(DEV_ADX_CLUSTER, adxConfiguration.clusterUrl());
    assertEquals("re", adxConfiguration.database());
    assertEquals(DEV_POSTGRES_URL, postgresConfiguration.getJdbcUrl());

    assertEquals(
        RecoveryAdxConfiguration.AuthMode.AZURE_CLI,
        adxConfiguration.authMode());

    RecoveryAdxClientFactory adxFactory =
        new RecoveryAdxClientFactory(adxConfiguration);

    RecoveryAdxRepository adxRepository =
        new RecoveryAdxRepository(
            adxFactory.client(),
            adxConfiguration.database(),
            adxConfiguration.queryTimeoutSeconds());

    try (RecoveryPostgresDataSource postgres =
        new RecoveryPostgresDataSource(postgresConfiguration)) {

      RecoveryFlowRepository flowRepository =
          new RecoveryFlowRepository(postgres.dataSource(), 60);

      RecoveryDailyDiscoveryService service =
          new RecoveryDailyDiscoveryService(
              adxRepository, flowRepository, 100);

      AtomicLong deliveredCandidates = new AtomicLong();
      AtomicLong deliveredPages = new AtomicLong();

      long started = System.nanoTime();

      RecoveryDailyDiscoveryService.Summary summary =
          service.discover(
              day,
              page -> {
                long pageNumber = deliveredPages.incrementAndGet();
                deliveredCandidates.addAndGet(page.size());

                long absent =
                    page.stream()
                        .filter(
                            comparison ->
                                comparison.presence()
                                    == RecoveryFlowPresenceIndex.Presence.ABSENT)
                        .count();

                System.out.printf(
                    "DEV discovery page=%d, candidates=%d, "
                        + "absent=%d, presentUnverified=%d%n",
                    pageNumber,
                    page.size(),
                    absent,
                    page.size() - absent);
              });

      long elapsedMillis =
          (System.nanoTime() - started) / 1_000_000L;

      assertEquals(day, summary.day());

      assertEquals(
          deliveredCandidates.get(),
          summary.candidateCount());

      assertEquals(
          deliveredPages.get(),
          summary.pageCount());

      assertEquals(
          summary.candidateCount(),
          summary.absentCandidateCount()
              + summary.presentCandidateCount());

      System.out.printf(
          "DEV daily discovery completed: day=%s, "
              + "candidates=%d, absent=%d, presentUnverified=%d, "
              + "pages=%d, elapsedMs=%d%n",
          summary.day(),
          summary.candidateCount(),
          summary.absentCandidateCount(),
          summary.presentCandidateCount(),
          summary.pageCount(),
          elapsedMillis);
    }
  }
}