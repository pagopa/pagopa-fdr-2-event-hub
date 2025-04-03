package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import it.gov.pagopa.fdr.to.eventhub.util.SampleContentFileUtil;
import java.time.LocalDate;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HttpMassiveBlobRecoveryFunctionTest {

  private static final ObjectMapper objectMapper = new ObjectMapper();
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
    function =
        new HttpMassiveBlobRecoveryFunction(
            mockEventHubClientFlowTx, mockEventHubClientReportedIUV);
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
  @ValueSource(
      strings = {
        "", // Empty body
        "invalid json", // Invalid JSON format
        "{\"fileName\": \"test.xml\"}", // Missing 'container' field
        "{\"fileName\": \"test.xml\", \"container\": \"fdr1-flows\", \"dateFrom\": \"2025-02-20\","
            + " \"dateTo\": \"2025-02-20\"}" // Mutually exclusive filename and data fields
      })
  void testInvalidRequests(String requestBody) {
    statusToReturn.set(HttpStatus.BAD_REQUEST);
    when(mockRequest.getBody())
        .thenReturn(Optional.ofNullable(requestBody.isEmpty() ? null : requestBody));

    HttpResponseMessage response = function.run(mockRequest, mockContext);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
  }

  @Test
  void testMissingMetadata() throws Exception {

    when(mockResponse.getStatus()).thenReturn(HttpStatus.UNPROCESSABLE_ENTITY);

    String requestBody =
        objectMapper.writeValueAsString(Map.of("fileName", "test.xml", "container", "fdr1-flows"));
    when(mockRequest.getBody()).thenReturn(Optional.of(requestBody));

    // empty metadata
    Map<String, String> metadata = new HashMap<>();
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

      mockedUtil
          .when(() -> CommonUtil.unprocessableEntity(any(HttpRequestMessage.class), anyString()))
          .thenReturn(mockResponse);

      HttpResponseMessage response = function.run(mockRequest, mockContext);

      assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatus());
    }
  }

  @Test
  void testFDR1FilenameRequestOK() throws Exception {

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

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil
          .when(() -> CommonUtil.getBlobFile(anyString(), anyString(), anyString(), any()))
          .thenReturn(mockBlobFileData);
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.parseXml(any())).thenReturn(mockFlusso);
      mockedUtil
          .when(
              () ->
                  CommonUtil.processXmlBlobAndSendToEventHub(
                      any(), any(), any(), any(), anyBoolean(), anyBoolean()))
          .thenReturn(true);
      mockedUtil
          .when(() -> CommonUtil.ok(any(HttpRequestMessage.class), anyString()))
          .thenReturn(mockResponse);

      HttpResponseMessage response = function.run(mockRequest, mockContext);

      assertEquals(HttpStatus.OK, response.getStatus());
    }
  }

  @Test
  void testFDR1DateRangeRequestOK() throws Exception {

    when(mockResponse.getStatus()).thenReturn(HttpStatus.OK);

    String requestBody =
        objectMapper.writeValueAsString(
            Map.of("dateFrom", "2025-04-02", "dateTo", "2025-04-05", "container", "fdr1-flows"));
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

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil
          .when(
              () ->
                  CommonUtil.getBlobFilesInDateRange(
                      anyString(),
                      anyString(),
                      anyString(),
                      any(LocalDate.class),
                      any(LocalDate.class),
                      any(ExecutionContext.class)))
          .thenReturn(Arrays.asList(mockBlobFileData));
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.parseXml(any())).thenReturn(mockFlusso);
      mockedUtil
          .when(
              () ->
                  CommonUtil.processXmlBlobAndSendToEventHub(
                      any(), any(), any(), any(), anyBoolean(), anyBoolean()))
          .thenReturn(true);
      mockedUtil
          .when(() -> CommonUtil.ok(any(HttpRequestMessage.class), anyString()))
          .thenReturn(mockResponse);

      HttpResponseMessage response = function.run(mockRequest, mockContext);

      assertEquals(HttpStatus.OK, response.getStatus());
    }
  }

  @Test
  void testFDR3FilenameRequestOK() throws Exception {

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

      HttpResponseMessage response = function.run(mockRequest, mockContext);

      assertEquals(HttpStatus.OK, response.getStatus());
    }
  }

  @Test
  void testFDR3DateRangeRequestOK() throws Exception {

    when(mockResponse.getStatus()).thenReturn(HttpStatus.OK);

    String requestBody =
        objectMapper.writeValueAsString(
            Map.of("dateFrom", "2025-04-02", "dateTo", "2025-04-05", "container", "fdr3-flows"));
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
          .when(
              () ->
                  CommonUtil.getBlobFilesInDateRange(
                      anyString(),
                      anyString(),
                      anyString(),
                      any(LocalDate.class),
                      any(LocalDate.class),
                      any(ExecutionContext.class)))
          .thenReturn(Arrays.asList(mockBlobFileData));
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

      HttpResponseMessage response = function.run(mockRequest, mockContext);

      assertEquals(HttpStatus.OK, response.getStatus());
    }
  }

  @Test
  void testEventHubProcessingFailure() throws Exception {

    when(mockResponse.getStatus()).thenReturn(HttpStatus.MULTI_STATUS);

    String requestBody =
        objectMapper.writeValueAsString(
            Map.of("dateFrom", "2025-04-02", "dateTo", "2025-04-05", "container", "fdr1-flows"));
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

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil
          .when(
              () ->
                  CommonUtil.getBlobFilesInDateRange(
                      anyString(),
                      anyString(),
                      anyString(),
                      any(LocalDate.class),
                      any(LocalDate.class),
                      any(ExecutionContext.class)))
          .thenReturn(Arrays.asList(mockBlobFileData));
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.parseXml(any())).thenReturn(mockFlusso);
      mockedUtil
          .when(
              () ->
                  CommonUtil.processXmlBlobAndSendToEventHub(
                      any(), any(), any(), any(), anyBoolean(), anyBoolean()))
          .thenReturn(false);
      mockedUtil
          .when(() -> CommonUtil.multiStatus(any(HttpRequestMessage.class), anyString()))
          .thenReturn(mockResponse);

      HttpResponseMessage response = function.run(mockRequest, mockContext);

      assertEquals(HttpStatus.MULTI_STATUS, response.getStatus());
    }
  }
}
