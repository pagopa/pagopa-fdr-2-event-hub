package it.gov.pagopa.fdr.to.eventhub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;

@ExtendWith(MockitoExtension.class)
class HttpMassiveBlobRecoveryFunctionTest {

	private final AtomicReference<HttpStatus> statusToReturn = new AtomicReference<>();
    private HttpMassiveBlobRecoveryFunction function;
    private HttpResponseMessage.Builder mockResponseBuilder;
    private HttpResponseMessage mockResponse;
    
    @Mock private EventHubProducerClient mockEventHubClientFlowTx;
    @Mock private EventHubProducerClient mockEventHubClientReportedIUV;
    @Mock private ExecutionContext mockContext;
    @Mock private HttpRequestMessage<Optional<String>> mockRequest;

    @BeforeEach
    void setUp() {
        function = new HttpMassiveBlobRecoveryFunction(mockEventHubClientFlowTx, mockEventHubClientReportedIUV);
        Logger logger = mock(Logger.class);
        lenient().when(mockContext.getLogger()).thenReturn(logger);

        mockResponseBuilder = mock(HttpResponseMessage.Builder.class);
        mockResponse = mock(HttpResponseMessage.class);

        lenient()
            .when(mockResponseBuilder.header(anyString(), anyString()))
            .thenReturn(mockResponseBuilder);
        lenient().when(mockResponseBuilder.body(any())).thenReturn(mockResponseBuilder);
        lenient()
            .when(mockResponseBuilder.build())
            .thenAnswer(
                invocation -> {
                  when(mockResponse.getStatus()).thenReturn(statusToReturn.get());
                  return mockResponse;
                });

        lenient()
            .when(mockRequest.createResponseBuilder(any(HttpStatus.class)))
            .thenReturn(mockResponseBuilder);
    }
    
    @ParameterizedTest
    @ValueSource(strings = {
        "", // Empty body
        "invalid json", // Invalid JSON format
        "{\"fileName\": \"test.xml\"}", // Missing 'container' field
        "{\"fileName\": \"test.xml\", \"container\": \"fdr1-flows\", \"dateFrom\": \"2025-02-20\", \"dateTo\": \"2025-02-20\"}" // Mutually exclusive filename and data fields
    })
    void testInvalidRequests(String requestBody) {
        statusToReturn.set(HttpStatus.BAD_REQUEST);
        when(mockRequest.getBody()).thenReturn(Optional.ofNullable(requestBody.isEmpty() ? null : requestBody));

        HttpResponseMessage response = function.run(mockRequest, mockContext);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
    }
}