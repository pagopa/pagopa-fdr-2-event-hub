package it.gov.pagopa.fdr.to.eventhub.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.microsoft.applicationinsights.TelemetryClient;
import it.gov.pagopa.fdr.to.eventhub.util.ErrorCodes;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.isNull;

@ExtendWith({MockitoExtension.class})
class AppInsightTelemetryClientTest {

  @Mock private TelemetryClient telemetryClientMock;

  @InjectMocks private AppInsightTelemetryClient sut;

  @Test
  void createCustomEventForAlertWithSuccess() {
    assertDoesNotThrow(
        () ->
            sut.createCustomEventForAlert(
                ErrorCodes.FDR1_E1, "error detail", new Exception("test")));

    verify(telemetryClientMock).trackEvent(eq("FDR_TO_EVH_ALERT"), anyMap(), eq(null));
  }

  @Test
  void createCustomEventForAlertWithExceptionWithoutMessage() {

    Exception exception = new IllegalArgumentException();

    sut.createCustomEventForAlert(
        ErrorCodes.FDR1_E1,
        "error detail",
        exception
    );

    ArgumentCaptor<Map<String, String>> propertiesCaptor =
        ArgumentCaptor.forClass(Map.class);

    verify(telemetryClientMock).trackEvent(
        eq("FDR_TO_EVH_ALERT"),
        propertiesCaptor.capture(),
        isNull()
    );

    Map<String, String> properties = propertiesCaptor.getValue();

    assertEquals(
        IllegalArgumentException.class.getName(),
        properties.get("cause")
    );
  }

}
