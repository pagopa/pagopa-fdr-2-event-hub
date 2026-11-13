package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import java.util.Map;
import java.util.Objects;

public record RecoveryExecutionConfiguration(boolean enabled) {

  public static final String ENABLED_SETTING =
      "RECOVERY_EXECUTION_ENABLED";

  public static final String CONNECTION_STRING_SETTING =
      "EVENT_HUB_REPORTEDIUV_CONNECTION_STRING";

  public static final String EVENT_HUB_SETTING =
      "EVENT_HUB_REPORTEDIUV_NAME";

  public static RecoveryExecutionConfiguration fromEnvironment(
      Map<String, String> environment) {

    Objects.requireNonNull(environment, "environment is required");

    String value =
        environment.getOrDefault(ENABLED_SETTING, "false");

    final boolean enabled;

    if ("true".equalsIgnoreCase(value)) {
      enabled = true;
    } else if ("false".equalsIgnoreCase(value)) {
      enabled = false;
    } else {
      throw new IllegalArgumentException(
          ENABLED_SETTING + " must be true or false");
    }

    if (enabled) {
      requireSetting(environment, CONNECTION_STRING_SETTING);
      requireSetting(environment, EVENT_HUB_SETTING);
    }

    return new RecoveryExecutionConfiguration(enabled);
  }

  private static void requireSetting(
      Map<String, String> environment,
      String name) {

    String value = environment.get(name);

    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          name + " is required when " + ENABLED_SETTING + "=true");
    }
  }
}