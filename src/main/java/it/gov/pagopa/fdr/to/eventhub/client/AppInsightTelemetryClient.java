package it.gov.pagopa.fdr.to.eventhub.client;

import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.TelemetryConfiguration;
import it.gov.pagopa.fdr.to.eventhub.util.ErrorCodes;
import java.util.Map;

/** Azure Application Insight Telemetry client */
public class AppInsightTelemetryClient {

  private final String connectionString = System.getenv("APPLICATIONINSIGHTS_CONNECTION_STRING");

  private final TelemetryClient telemetryClient;

  public AppInsightTelemetryClient() {
    TelemetryConfiguration aDefault = TelemetryConfiguration.createDefault();
    aDefault.setConnectionString(connectionString);
    this.telemetryClient = new TelemetryClient(aDefault);
  }

  AppInsightTelemetryClient(TelemetryClient telemetryClient) {
    this.telemetryClient = telemetryClient;
  }

  /**
   * Create a custom event on Application Insight with the provided information
   *
   * @param errorCode the application error code
   * @param details details of the custom event
   * @param e exception added to the custom event
   */
  public void createCustomEvent(ErrorCodes errorCode, String details, Exception e) {
    Map<String, String> props =
        Map.of(
            "type",
            errorCode.getCode(),
            "title",
            errorCode.getMessage(),
            "details",
            details,
            "cause",
            e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
    this.telemetryClient.trackEvent("FDR_TO_EVH_ALERT", props, null);
  }
}
