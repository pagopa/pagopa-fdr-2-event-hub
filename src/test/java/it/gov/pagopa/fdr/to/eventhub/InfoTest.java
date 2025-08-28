package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import it.gov.pagopa.fdr.to.eventhub.model.AppInfo;
import java.util.Optional;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InfoTest {

  @Mock ExecutionContext context;

  @Spy Info infoFunction;

  @BeforeEach
  void setup() {
    infoFunction = new Info();
  }

  @Test
  void runOK() {
    // test precondition
    final HttpResponseMessage.Builder builder = mock(HttpResponseMessage.Builder.class);
    @SuppressWarnings("unchecked")
    HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage.class);

    HttpResponseMessage responseMock = mock(HttpResponseMessage.class);
    doReturn(HttpStatus.OK).when(responseMock).getStatus();
    doReturn(builder).when(builder).body(any());
    doReturn(responseMock).when(builder).build();
    doReturn(builder).when(request).createResponseBuilder(any(HttpStatus.class));
    doReturn(builder).when(builder).header(anyString(), anyString());

    // test execution
    HttpResponseMessage response = infoFunction.run(request, context);

    // test assertion
    assertEquals(HttpStatus.OK, response.getStatus());
  }

  @SneakyThrows
  @Test
  void getInfoOk() {

    // Execute function
    AppInfo response = infoFunction.getInfo();

    // Checking assertions
    assertNotNull(response.getName());
    assertNotNull(response.getVersion());
    assertNotNull(response.getEnvironment());
  }
}
