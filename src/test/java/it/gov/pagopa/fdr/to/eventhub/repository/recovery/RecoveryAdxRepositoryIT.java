package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxClientFactory;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxConfiguration;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxFlowPresence;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_ADX_INTEGRATION_TESTS",
    matches = "true")
class RecoveryAdxRepositoryIT {

  private record FlowKey(
      String flowName,
      String pspDomainId,
      String orgDomainId) {
  }

  @Test
  void shouldReadDailyPresenceFromDev() throws Exception {
    String configuredDay = System.getenv("RECOVERY_ADX_TEST_DAY");

    assertNotNull(
        configuredDay,
        "RECOVERY_ADX_TEST_DAY must contain the day to verify");

    LocalDate day = LocalDate.parse(configuredDay);

    RecoveryAdxConfiguration configuration =
        RecoveryAdxConfiguration.fromEnvironment();

    assertEquals(
        RecoveryAdxConfiguration.AuthMode.AZURE_CLI,
        configuration.authMode(),
        "This local integration test must use Azure CLI");

    RecoveryAdxClientFactory factory =
        new RecoveryAdxClientFactory(configuration);

    RecoveryAdxRepository repository =
        new RecoveryAdxRepository(
            factory.client(),
            configuration.database(),
            configuration.queryTimeoutSeconds());

    long started = System.nanoTime();

    List<RecoveryAdxFlowPresence> flows = repository.findByDay(day);

    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

    Set<FlowKey> keys = new HashSet<>();
    long totalRows = 0L;

    for (RecoveryAdxFlowPresence flow : flows) {
      FlowKey key =
          new FlowKey(
              flow.flowName(),
              flow.pspDomainId(),
              flow.orgDomainId());

      assertTrue(
          keys.add(key),
          "ADX returned a duplicate flow/PSP/domain group");

      assertTrue(flow.presentRows() > 0);

      totalRows = Math.addExact(totalRows, flow.presentRows());
    }

    System.out.printf(
        "DEV ADX discovery completed: day=%s, groups=%d, "
            + "presentRows=%d, elapsedMs=%d%n",
        day,
        flows.size(),
        totalRows,
        elapsedMillis);
  }
}