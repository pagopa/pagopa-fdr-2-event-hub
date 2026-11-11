package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDispatcher;
import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class RecoveryExecutionRuntimeResourcesTest {

  @Test
  void shouldCloseOwnedProducerOnlyOnce() {
    var dispatcher = mock(RecoveryDryRunDispatcher.class);
    var postgres = mock(RecoveryPostgresDataSource.class);
    var renewals = mock(ScheduledExecutorService.class);
    var producer = mock(EventHubProducerClient.class);

    var runtime =
        new RecoveryDryRunRuntime(
            dispatcher, postgres, renewals, producer);

    runtime.close();
    runtime.close();

    verify(producer).close();
    verify(postgres).close();
    verify(renewals).shutdownNow();
  }

  @Test
  void shouldClosePostgresEvenWhenProducerCloseFails() {
    var dispatcher = mock(RecoveryDryRunDispatcher.class);
    var postgres = mock(RecoveryPostgresDataSource.class);
    var renewals = mock(ScheduledExecutorService.class);
    var producer = mock(EventHubProducerClient.class);

    var failure = new IllegalStateException("Producer close failed");
    doThrow(failure).when(producer).close();

    var runtime =
        new RecoveryDryRunRuntime(
            dispatcher, postgres, renewals, producer);

    var actual =
        assertThrows(IllegalStateException.class, runtime::close);

    assertSame(failure, actual);
    verify(postgres).close();
    verify(renewals).shutdownNow();
  }

  @Test
  void shouldValidateMissingEventHubSettingsBeforeOtherClients() {
    var environment = new HashMap<String, String>();

    environment.put(
        "RECOVERY_MAX_COMPRESSED_BLOB_BYTES", "1048576");
    environment.put(
        "RECOVERY_MAX_DECOMPRESSED_BLOB_BYTES", "8388608");
    environment.put(
        "RECOVERY_MAX_INNER_XML_BYTES", "8388608");
    environment.put(
        "RECOVERY_MAX_FDR1_CANDIDATES", "20");

    environment.put(
        RecoveryExecutionConfiguration.ENABLED_SETTING, "true");

    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> RecoveryDryRunRuntime.fromEnvironment(environment));

    assertTrue(
        exception.getMessage().contains(
            RecoveryExecutionConfiguration.CONNECTION_STRING_SETTING));
  }
}