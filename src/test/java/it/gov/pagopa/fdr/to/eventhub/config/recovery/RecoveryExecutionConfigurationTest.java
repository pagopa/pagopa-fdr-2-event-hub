package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryExecutionConfigurationTest {

  @Test
  void shouldBeDisabledByDefaultWithoutEventHubSettings() {
    assertFalse(
        RecoveryExecutionConfiguration.fromEnvironment(Map.of())
            .enabled());
  }

  @Test
  void shouldIgnoreEventHubSettingsWhenDisabled() {
    var environment =
        Map.of(
            RecoveryExecutionConfiguration.ENABLED_SETTING, "false",
            RecoveryExecutionConfiguration.CONNECTION_STRING_SETTING, "",
            RecoveryExecutionConfiguration.EVENT_HUB_SETTING, "");

    assertFalse(
        RecoveryExecutionConfiguration.fromEnvironment(environment)
            .enabled());
  }

  @Test
  void shouldAcceptEnabledConfigurationWithRequiredSettings() {
    assertTrue(
        RecoveryExecutionConfiguration.fromEnvironment(enabledEnvironment())
            .enabled());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "yes", "1"})
  void shouldRejectInvalidEnablementFlag(String value) {
    var environment =
        Map.of(
            RecoveryExecutionConfiguration.ENABLED_SETTING,
            value);

    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RecoveryExecutionConfiguration.fromEnvironment(
                    environment));

    assertTrue(
        exception.getMessage().contains(
            RecoveryExecutionConfiguration.ENABLED_SETTING));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      RecoveryExecutionConfiguration.CONNECTION_STRING_SETTING,
      RecoveryExecutionConfiguration.EVENT_HUB_SETTING
  })
  void shouldRequireEventHubSettingsOnlyWhenEnabled(String property) {
    var environment = enabledEnvironment();
    environment.remove(property);

    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RecoveryExecutionConfiguration.fromEnvironment(
                    environment));

    assertTrue(exception.getMessage().contains(property));
  }

  private static HashMap<String, String> enabledEnvironment() {
    var environment = new HashMap<String, String>();

    environment.put(
        RecoveryExecutionConfiguration.ENABLED_SETTING, "true");

    environment.put(
        RecoveryExecutionConfiguration.CONNECTION_STRING_SETTING,
        "test-connection-string");

    environment.put(
        RecoveryExecutionConfiguration.EVENT_HUB_SETTING,
        "test-reported-iuv");

    return environment;
  }
}