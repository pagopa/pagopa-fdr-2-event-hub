package it.gov.pagopa.fdr.to.eventhub;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

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
import org.mockito.junit.jupiter.MockitoExtension;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;

import it.gov.pagopa.fdr.to.eventhub.model.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import it.gov.pagopa.fdr.to.eventhub.util.SampleContentFileUtil;

@ExtendWith(MockitoExtension.class)
class HttpBlobRecoveryFunctionTest {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private EventHubProducerClient mockEventHubClientFlowTx;
	@Mock
	private EventHubProducerClient mockEventHubClientReportedIUV;
	@Mock
	private ExecutionContext mockContext;
	@Mock
	private HttpRequestMessage<Optional<String>> mockRequest;

	private HttpBlobRecoveryFunction function;

	private HttpResponseMessage.Builder mockResponseBuilder;
	private HttpResponseMessage mockResponse;
	private final AtomicReference<HttpStatus> statusToReturn = new AtomicReference<>();

	@BeforeEach
	void setUp() {
		function = new HttpBlobRecoveryFunction(mockEventHubClientFlowTx,
				mockEventHubClientReportedIUV);
		Logger logger = mock(Logger.class);
		lenient().when(mockContext.getLogger()).thenReturn(logger);

		mockResponseBuilder = mock(HttpResponseMessage.Builder.class);
		mockResponse = mock(HttpResponseMessage.class);

		when(mockResponseBuilder.header(anyString(), anyString()))
				.thenReturn(mockResponseBuilder);
		when(mockResponseBuilder.body(any())).thenReturn(mockResponseBuilder);
		when(mockResponseBuilder.build()).thenAnswer(invocation -> {
			when(mockResponse.getStatus()).thenReturn(statusToReturn.get());
			return mockResponse;
		});

		when(mockRequest.createResponseBuilder(any(HttpStatus.class)))
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

		statusToReturn.set(HttpStatus.NOT_FOUND);

		String requestBody = objectMapper.writeValueAsString(
				Map.of("fileName", "test.xml", "container", "test-container"));
		when(mockRequest.getBody()).thenReturn(Optional.of(requestBody));

		try (MockedStatic<CommonUtil> mockedUtil = mockStatic(
				CommonUtil.class)) {
			mockedUtil
					.when(() -> CommonUtil.getBlobFile(anyString(), anyString(),
					anyString(), any())).thenReturn(null);

			HttpResponseMessage response = function.run(mockRequest,
					mockContext);
			assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
		}
	}

	@Test
	void testMissingMetadata() throws Exception {

		statusToReturn.set(HttpStatus.UNPROCESSABLE_ENTITY);

		String requestBody = objectMapper.writeValueAsString(
				Map.of("fileName", "test.xml", "container", "test-container"));
		when(mockRequest.getBody()).thenReturn(Optional.of(requestBody));

		BlobFileData mockBlobFileData = new BlobFileData(new byte[]{},
				new HashMap<>());

		try (MockedStatic<CommonUtil> mockedUtil = mockStatic(
				CommonUtil.class)) {
			mockedUtil
					.when(() -> CommonUtil.getBlobFile(anyString(), anyString(),
					anyString(), any())).thenReturn(mockBlobFileData);
			mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any()))
					.thenReturn(false);

			HttpResponseMessage response = function.run(mockRequest,
					mockContext);
			assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatus());
		}
	}

	@Test
	void testSuccessfulProcessing() throws Exception {

		statusToReturn.set(HttpStatus.OK);

		String requestBody = objectMapper.writeValueAsString(
				Map.of("fileName", "test.xml", "container", "test-container"));
		when(mockRequest.getBody()).thenReturn(Optional.of(requestBody));

		Map<String, String> metadata = new HashMap<>();
		metadata.put("key", "value");
		BlobFileData mockBlobFileData = new BlobFileData(SampleContentFileUtil
				.createGzipCompressedData(new byte[]{1, 2, 3}.toString()),
				metadata);
		FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);

		try (MockedStatic<CommonUtil> mockedUtil = mockStatic(
				CommonUtil.class)) {
			mockedUtil
					.when(() -> CommonUtil.getBlobFile(anyString(), anyString(),
					anyString(), any())).thenReturn(mockBlobFileData);
			mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any()))
					.thenReturn(true);
			mockedUtil.when(() -> CommonUtil.parseXml(any()))
					.thenReturn(mockFlusso);
			mockedUtil.when(() -> CommonUtil.processXmlBlobAndSendToEventHub(
					any(), any(), any(), any())).thenReturn(true);

			HttpResponseMessage response = function.run(mockRequest,
					mockContext);
			assertEquals(HttpStatus.OK, response.getStatus());
		}
	}

	@Test
	void testEventHubProcessingFailure() throws Exception {

		statusToReturn.set(HttpStatus.SERVICE_UNAVAILABLE);

		String requestBody = objectMapper.writeValueAsString(
				Map.of("fileName", "test.xml", "container", "test-container"));
		when(mockRequest.getBody()).thenReturn(Optional.of(requestBody));

		Map<String, String> metadata = new HashMap<>();
		metadata.put("key", "value");
		BlobFileData mockBlobFileData = new BlobFileData(SampleContentFileUtil
				.createGzipCompressedData(new byte[]{1, 2, 3}.toString()),
				metadata);
		FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);

		try (MockedStatic<CommonUtil> mockedUtil = mockStatic(
				CommonUtil.class)) {
			mockedUtil
					.when(() -> CommonUtil.getBlobFile(anyString(), anyString(),
					anyString(), any())).thenReturn(mockBlobFileData);
			mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any()))
					.thenReturn(true);
			mockedUtil.when(() -> CommonUtil.parseXml(any()))
					.thenReturn(mockFlusso);
			mockedUtil.when(() -> CommonUtil.processXmlBlobAndSendToEventHub(
					any(), any(), any(), any())).thenReturn(false);

			HttpResponseMessage response = function.run(mockRequest,
					mockContext);
			assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatus());
		}
	}
}
