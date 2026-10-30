package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

public record RecoveryAdxConfiguration(
    String clusterUrl,
    String database,
    AuthMode authMode,
    int queryTimeoutSeconds) {

  private static final String CLUSTER_URL = "RECOVERY_ADX_CLUSTER_URL";
  private static final String DATABASE = "RECOVERY_ADX_DATABASE";
  private static final String AUTH_MODE = "RECOVERY_ADX_AUTH_MODE";
  private static final String QUERY_TIMEOUT =
      "RECOVERY_ADX_QUERY_TIMEOUT_SECONDS";

  public enum AuthMode {
    AZURE_CLI,
    WORKLOAD_IDENTITY
  }

  public RecoveryAdxConfiguration {
    validateClusterUrl(clusterUrl);

    if (database == null || database.isBlank()) {
      throw new IllegalArgumentException(DATABASE + " is required");
    }

    Objects.requireNonNull(authMode, "authMode is required");

    if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 600) {
      throw new IllegalArgumentException(
          QUERY_TIMEOUT + " must be between 1 and 600");
    }
  }

  public static RecoveryAdxConfiguration fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  public static RecoveryAdxConfiguration fromEnvironment(
      Map<String, String> environment) {

    Objects.requireNonNull(environment, "environment is required");

    String mode =
        environment.getOrDefault(
            AUTH_MODE, AuthMode.WORKLOAD_IDENTITY.name());

    AuthMode authMode;

    try {
      authMode = AuthMode.valueOf(mode);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          AUTH_MODE + " must be AZURE_CLI or WORKLOAD_IDENTITY");
    }

    int timeout;

    try {
      timeout =
          Integer.parseInt(
              environment.getOrDefault(QUERY_TIMEOUT, "60"));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          QUERY_TIMEOUT + " must be an integer between 1 and 600");
    }

    return new RecoveryAdxConfiguration(
        environment.get(CLUSTER_URL),
        environment.get(DATABASE),
        authMode,
        timeout);
  }

  private static void validateClusterUrl(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(CLUSTER_URL + " is required");
    }

    URI uri;

    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(CLUSTER_URL + " is invalid");
    }

    String path = uri.getRawPath();

    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || (path != null && !path.isEmpty() && !"/".equals(path))) {

      throw new IllegalArgumentException(
          CLUSTER_URL
              + " must be an HTTPS cluster URL without credentials, "
              + "query parameters or API paths");
    }
  }
}