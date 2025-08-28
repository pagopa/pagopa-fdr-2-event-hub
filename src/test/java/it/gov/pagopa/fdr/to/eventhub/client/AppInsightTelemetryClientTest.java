package it.gov.pagopa.fdr.to.eventhub.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.microsoft.applicationinsights.TelemetryClient;
import it.gov.pagopa.fdr.to.eventhub.util.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class})
class AppInsightTelemetryClientTest {

  @Mock private TelemetryClient telemetryClientMock;

  @InjectMocks private AppInsightTelemetryClient sut;

  @Test
  void createCustomEventWithSuccess() {
    assertDoesNotThrow(
        () -> sut.createCustomEvent(ErrorCodes.FDR1_E1, "error detail", new Exception("test")));

    verify(telemetryClientMock).trackEvent(eq("FDR_TO_EVH_ALERT"), anyMap(), eq(null));
  }
}
