package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryAdxConfigurationTest {

  private static final String CLUSTER_KEY = "RECOVERY_ADX_CLUSTER_URL";
  private static final String DATABASE_KEY = "RECOVERY_ADX_DATABASE";
  private static final String MODE_KEY = "RECOVERY_ADX_AUTH_MODE";
  private static final String TIMEOUT_KEY =
      "RECOVERY_ADX_QUERY_TIMEOUT_SECONDS";

  private static final String CLUSTER =
      "https://recovery-test.westeurope.kusto.windows.net";

  private Map<String, String> environment() {
    Map<String, String> values = new HashMap<>();
    values.put(CLUSTER_KEY, CLUSTER);
    values.put(DATABASE_KEY, "recovery-test");
    return values;
  }

  @Test
  void shouldUseWorkloadIdentityAndSixtySecondsByDefault() {
    RecoveryAdxConfiguration configuration =
        RecoveryAdxConfiguration.fromEnvironment(environment());

    assertEquals(CLUSTER, configuration.clusterUrl());
    assertEquals("recovery-test", configuration.database());
    assertEquals(
        RecoveryAdxConfiguration.AuthMode.WORKLOAD_IDENTITY,
        configuration.authMode());
    assertEquals(60, configuration.queryTimeoutSeconds());
  }

  @Test
  void shouldAllowExplicitAzureCliAuthentication() {
    Map<String, String> values = environment();
    values.put(MODE_KEY, "AZURE_CLI");

    RecoveryAdxConfiguration configuration =
        RecoveryAdxConfiguration.fromEnvironment(values);

    assertEquals(
        RecoveryAdxConfiguration.AuthMode.AZURE_CLI,
        configuration.authMode());
  }

  @Test
  void shouldReadCustomTimeout() {
    Map<String, String> values = environment();
    values.put(TIMEOUT_KEY, "120");

    RecoveryAdxConfiguration configuration =
        RecoveryAdxConfiguration.fromEnvironment(values);

    assertEquals(120, configuration.queryTimeoutSeconds());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "", " "})
  void shouldRejectMissingCluster(String value) {
    Map<String, String> values = environment();

    if ("missing".equals(value)) {
      values.remove(CLUSTER_KEY);
    } else {
      values.put(CLUSTER_KEY, value);
    }

    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryAdxConfiguration.fromEnvironment(values));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://example.com",
      "https://example.com/v2/rest/query",
      "https://example.com?token=value",
      "https://user:password@example.com",
      "https://example.com#fragment"
  })
  void shouldRejectInvalidClusterUrl(String url) {
    Map<String, String> values = environment();
    values.put(CLUSTER_KEY, url);

    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryAdxConfiguration.fromEnvironment(values));
  }

  @Test
  void shouldRejectUnknownAuthenticationMode() {
    Map<String, String> values = environment();
    values.put(MODE_KEY, "UNKNOWN");

    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryAdxConfiguration.fromEnvironment(values));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "601", "invalid"})
  void shouldRejectInvalidTimeout(String value) {
    Map<String, String> values = environment();
    values.put(TIMEOUT_KEY, value);

    assertThrows(
        IllegalArgumentException.class,
        () -> RecoveryAdxConfiguration.fromEnvironment(values));
  }
}