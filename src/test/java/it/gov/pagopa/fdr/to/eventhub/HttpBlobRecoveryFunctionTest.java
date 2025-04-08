package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlStAXParser;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import it.gov.pagopa.fdr.to.eventhub.util.SampleContentFileUtil;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith({MockitoExtension.class, SystemStubsExtension.class})
class HttpBlobRecoveryFunctionTest {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicReference<HttpStatus> statusToReturn = new AtomicReference<>();
  @SystemStub private final EnvironmentVariables environmentVariables = new EnvironmentVariables();
  @Mock private EventHubProducerClient mockEventHubClientFlowTx;
  @Mock private EventHubProducerClient mockEventHubClientReportedIUV;
  @Mock private ExecutionContext mockContext;
  @Mock private HttpRequestMessage<Optional<String>> mockRequest;
  @Mock private FDR1XmlStAXParser mockFDR1XmlParser;
  private HttpBlobRecoveryFunction function;
  private HttpResponseMessage.Builder mockResponseBuilder;
  private HttpResponseMessage mockResponse;

  @BeforeEach
  void setUp() {
    function =
        new HttpBlobRecoveryFunction(mockEventHubClientFlowTx, mockEventHubClientReportedIUV,mockFDR1XmlParser);
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

  @Test
  void testMissingRequestBody() {

    statusToReturn.set(HttpStatus.BAD_REQUEST);

    when(mockRequest.getBody()).thenReturn(Optional.empty());
    HttpResponseMessage response = function.run(mockRequest, mockContext);
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
  }

  @Test
  void testInvalidJsonFormat() {

    statusToReturn.set(HttpStatus.BAD_REQUEST);

    when(mockRequest.getBody()).thenReturn(Optional.of("invalid-json"));
    HttpResponseMessage response = function.run(mockRequest, mockContext);
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
  }

  @Test
  void testFileNotFound() throws Exception {

    when(mockResponse.getStatus()).thenReturn(HttpStatus.NOT_FOUND);

    String requestBody =
        objectMapper.writeValueAsString(Map.of("fileName", "test.xml", "container", "fdr1-flows"));
    when(mockRequest.getBody()).thenReturn(Optional.of(requestBody));

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil
          .when(() -> CommonUtil.getBlobFile(anyString(), anyString(), anyString(), any()))
          .thenReturn(null);
      mockedUtil
          .when(() -> CommonUtil.notFound(any(HttpRequestMessage.class), anyString()))
          .thenReturn(mockResponse);
      mockedUtil
          .when(() -> CommonUtil.getJsonField(any(JsonNode.class), eq("fileName")))
          .thenReturn("test.xml");
      mockedUtil
          .when(() -> CommonUtil.getJsonField(any(JsonNode.class), eq("container")))
          .thenReturn("fdr1-flows");

      HttpResponseMessage response = function.run(mockRequest, mockContext);
      assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
    }
  }

  @Test
  void testMissingMetadata() throws Exception {

    when(mockResponse.getStatus()).thenReturn(HttpStatus.UNPROCESSABLE_ENTITY);

    String requestBody =
        objectMapper.writeValueAsString(Map.of("fileName", "test.xml", "container", "fdr1-flows"));
    when(mockRequest.getBody()).thenReturn(Optional.of(requestBody));

    BlobFileData mockBlobFileData =
        new BlobFileData("", new byte[] {}, new HashMap<>(), new ArrayList<>());

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil
          .when(() -> CommonUtil.getBlobFile(anyString(), anyString(), anyString(), any()))
          .thenReturn(mockBlobFileData);
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(false);
      mockedUtil
          .when(() -> CommonUtil.unprocessableEntity(any(HttpRequestMessage.class), anyString()))
          .thenReturn(mockResponse);
      mockedUtil
          .when(() -> CommonUtil.getJsonField(any(JsonNode.class), eq("fileName")))
          .thenReturn("test.xml");
      mockedUtil
          .when(() -> CommonUtil.getJsonField(any(JsonNode.class), eq("container")))
          .thenReturn("fdr1-flows");

      HttpResponseMessage response = function.run(mockRequest, mockContext);
      assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatus());
    }
  }

  @Test
  void testFDR1SuccessfulProcessing() throws Exception {

    when(mockResponse.getStatus()).thenReturn(HttpStatus.OK);

    String requestBody =
        objectMapper.writeValueAsString(Map.of("fileName", "test.xml", "container", "fdr1-flows"));
    when(mockRequest.getBody()).thenReturn(Optional.of(requestBody));

    Map<String, String> metadata = new HashMap<>();
    metadata.put("key", "value");
    BlobFileData mockBlobFileData =
        new BlobFileData(
            "fileName",
            SampleContentFileUtil.createGzipCompressedData(new byte[] {1, 2, 3}.toString()),
            metadata,
            new ArrayList<>());
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil
          .when(() -> CommonUtil.getBlobFile(anyString(), anyString(), anyString(), any()))
          .thenReturn(mockBlobFileData);
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil
          .when(
              () ->
                  CommonUtil.processXmlBlobAndSendToEventHub(
                      any(), any(), any(), any(), anyBoolean(), anyBoolean()))
          .thenReturn(true);
      mockedUtil
          .when(() -> CommonUtil.ok(any(HttpRequestMessage.class), anyString()))
          .thenReturn(mockResponse);
      mockedUtil
          .when(() -> CommonUtil.getJsonField(any(JsonNode.class), eq("fileName")))
          .thenReturn("test.xml");
      mockedUtil
          .when(() -> CommonUtil.getJsonField(any(JsonNode.class), eq("container")))
          .thenReturn("fdr1-flows");

      HttpResponseMessage response = function.run(mockRequest, mockContext);
      assertEquals(HttpStatus.OK, response.getStatus());
    }
  }

  @Test
  void testFDR3SuccessfulProcessing() throws Exception {

    when(mockResponse.getStatus()).thenReturn(HttpStatus.OK);

    String requestBody =
        objectMapper.writeValueAsString(Map.of("fileName", "test.json", "container", "fdr3-flows"));
    when(mockRequest.getBody()).thenReturn(Optional.of(requestBody));

    Map<String, String> metadata = new HashMap<>();
    metadata.put("key", "value");
    BlobFileData mockBlobFileData =
        new BlobFileData(
            "fileName",
            SampleContentFileUtil.createGzipCompressedData(new byte[] {1, 2, 3}.toString()),
            metadata,
            new ArrayList<>());

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil
          .when(() -> CommonUtil.getBlobFile(anyString(), anyString(), anyString(), any()))
          .thenReturn(mockBlobFileData);
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.parseJSON(any())).thenReturn(mock(Flow.class));
      mockedUtil
          .when(
              () ->
                  CommonUtil.processJsonBlobAndSendToEventHub(
                      any(), any(), any(), any(), anyBoolean(), anyBoolean()))
          .thenReturn(true);
      mockedUtil
          .when(() -> CommonUtil.ok(any(HttpRequestMessage.class), anyString()))
          .thenReturn(mockResponse);
      mockedUtil
          .when(() -> CommonUtil.getJsonField(any(JsonNode.class), eq("fileName")))
          .thenReturn("test.xml");
      mockedUtil
          .when(() -> CommonUtil.getJsonField(any(JsonNode.class), eq("container")))
          .thenReturn("fdr3-flows");

      HttpResponseMessage response = function.run(mockRequest, mockContext);
      assertEquals(HttpStatus.OK, response.getStatus());
    }
  }

  @Test
  void testEventHubProcessingFailure() throws Exception {

    when(mockResponse.getStatus()).thenReturn(HttpStatus.SERVICE_UNAVAILABLE);

    String requestBody =
        objectMapper.writeValueAsString(Map.of("fileName", "test.xml", "container", "fdr1-flows"));
    when(mockRequest.getBody()).thenReturn(Optional.of(requestBody));

    Map<String, String> metadata = new HashMap<>();
    metadata.put("key", "value");
    BlobFileData mockBlobFileData =
        new BlobFileData(
            "fileName",
            SampleContentFileUtil.createGzipCompressedData(new byte[] {1, 2, 3}.toString()),
            metadata,
            Arrays.asList("evh error"));
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil
          .when(() -> CommonUtil.getBlobFile(anyString(), anyString(), anyString(), any()))
          .thenReturn(mockBlobFileData);
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil
          .when(
              () ->
                  CommonUtil.processXmlBlobAndSendToEventHub(
                      any(), any(), any(), any(), anyBoolean(), anyBoolean()))
          .thenReturn(false);
      mockedUtil
          .when(() -> CommonUtil.serviceUnavailable(any(HttpRequestMessage.class), anyString()))
          .thenReturn(mockResponse);
      mockedUtil
          .when(() -> CommonUtil.getJsonField(any(JsonNode.class), eq("fileName")))
          .thenReturn("test.xml");
      mockedUtil
          .when(() -> CommonUtil.getJsonField(any(JsonNode.class), eq("container")))
          .thenReturn("fdr1-flows");

      HttpResponseMessage response = function.run(mockRequest, mockContext);
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatus());
    }
  }

  @Test
  void testConstructorInitializesClients() {

    try (MockedStatic<CommonUtil> mockedCommonUtil = Mockito.mockStatic(CommonUtil.class)) {

      // Simulate environment variables
      environmentVariables.set("EVENT_HUB_FLOWTX_CONNECTION_STRING", "fake-flowtx-conn-string");
      environmentVariables.set("EVENT_HUB_FLOWTX_NAME", "fake-flowtx-name");
      environmentVariables.set(
          "EVENT_HUB_REPORTEDIUV_CONNECTION_STRING", "fake-reportediuv-conn-string");
      environmentVariables.set("EVENT_HUB_REPORTEDIUV_NAME", "fake-reportediuv-name");

      EventHubProducerClient mockClient1 = mock(EventHubProducerClient.class);
      EventHubProducerClient mockClient2 = mock(EventHubProducerClient.class);
      mockedCommonUtil
          .when(
              () -> CommonUtil.createEventHubClient("fake-flowtx-conn-string", "fake-flowtx-name"))
          .thenReturn(mockClient1);
      mockedCommonUtil
          .when(
              () ->
                  CommonUtil.createEventHubClient(
                      "fake-reportediuv-conn-string", "fake-reportediuv-name"))
          .thenReturn(mockClient2);

      // Instantiate the class
      HttpBlobRecoveryFunction httpBlobRecoveryFunction = new HttpBlobRecoveryFunction();

      assertNotNull(httpBlobRecoveryFunction.getEventHubClientFlowTx());
      assertNotNull(httpBlobRecoveryFunction.getEventHubClientReportedIUV());
      assertEquals(mockClient1, httpBlobRecoveryFunction.getEventHubClientFlowTx());
      assertEquals(mockClient2, httpBlobRecoveryFunction.getEventHubClientReportedIUV());
    }
  }
}
