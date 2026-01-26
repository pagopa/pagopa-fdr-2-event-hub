package it.gov.pagopa.fdr.to.eventhub.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.rest.PagedIterable;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;

import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.wrapper.BlobServiceClientWrapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

@ExtendWith(MockitoExtension.class)
class CommonUtilTest {

  private static final String STORAGE_ENV_VAR = "STORAGE_ENV_VAR";
  private static final String CONTAINER_NAME = "test-container";
  private static final String BLOB_NAME = "test-blob.xml";

  @Mock private BlobServiceClientWrapper mockBlobServiceClientWrapper;
  @Mock private BlobServiceClient mockBlobServiceClient;
  @Mock private BlobContainerClient mockBlobContainerClient;
  @Mock private BlobClient mockBlobClient;
  @Mock private BlobProperties mockBlobProperties;
  @Mock private ExecutionContext mockContext;
  @Mock private Logger mockLogger;
  @Mock private BlobServiceClientBuilder mockBuilder;

  @BeforeEach
  void setUp() {

    CommonUtil.setBlobServiceClientWrapper(mockBlobServiceClientWrapper);
    lenient()
        .when(mockBlobServiceClientWrapper.getBlobContainerClient(anyString(), anyString()))
        .thenReturn(mockBlobContainerClient);
    lenient().when(mockBlobContainerClient.getBlobClient(anyString())).thenReturn(mockBlobClient);
  }

  @Test
  void testBlobFileNotFound() {
    when(mockBlobClient.exists()).thenReturn(false);

    BlobFileData result =
        CommonUtil.getBlobFile(STORAGE_ENV_VAR, CONTAINER_NAME, BLOB_NAME, mockLogger);

    assertNull(result);
    ArgumentCaptor<String> logCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockLogger, atLeastOnce()).error(logCaptor.capture(), anyString());

    assertTrue(logCaptor.getAllValues().stream().anyMatch(log -> log.contains("Blob not found")));
  }

  @Test
  void testBlobFileRetrievalSuccess() {
    byte[] mockData = "test data".getBytes();
    ByteArrayInputStream inputStream = new ByteArrayInputStream(mockData);

    Map<String, String> metadata = new HashMap<>();
    metadata.put("key1", "value1");

    when(mockBlobClient.exists()).thenReturn(true);
    when(mockBlobClient.getProperties()).thenReturn(mockBlobProperties);
    when(mockBlobProperties.getMetadata()).thenReturn(metadata);
    doAnswer(
            invocation -> {
              ByteArrayOutputStream actualOutputStream = invocation.getArgument(0);
              inputStream.transferTo(actualOutputStream);
              return null;
            })
        .when(mockBlobClient)
        .downloadStream(any(ByteArrayOutputStream.class));

    BlobFileData result =
        CommonUtil.getBlobFile(STORAGE_ENV_VAR, CONTAINER_NAME, BLOB_NAME, mockLogger);

    assertNotNull(result);
    assertArrayEquals(mockData, result.getFileContent());
    assertEquals(metadata, result.getMetadata());
  }

  @Test
  void testBlobFileRetrievalFailure() {
    when(mockBlobContainerClient.getBlobClient(anyString()))
        .thenThrow(new RuntimeException("Storage error"));

    BlobFileData result =
        CommonUtil.getBlobFile(STORAGE_ENV_VAR, CONTAINER_NAME, BLOB_NAME, mockLogger);

    assertNull(result);
    verify(mockLogger).error(eq("Error accessing blob"), any(Exception.class));
  }

  @Test
  void testGetBlobFilesInDateRange() {
    LocalDate from = LocalDate.of(2024, 4, 1);
    LocalDate to = LocalDate.of(2024, 4, 2);
    String storageEnvVar = "test-env";
    String containerName = "test-container";
    String prefixFormat = "yyyy-MM-dd";

    BlobItem blobItem = mock(BlobItem.class);
    Iterator mockIterator = mock(Iterator.class);
    when(mockIterator.hasNext()).thenReturn(true, false);
    when(mockIterator.next()).thenReturn(blobItem);

    PagedIterable mockIterable = mock(PagedIterable.class);
    when(mockIterable.iterator()).thenReturn(mockIterator);

    when(blobItem.getName()).thenReturn("test-blob");
    when(mockBlobContainerClient.listBlobs(any(ListBlobsOptions.class), isNull()))
        .thenReturn(mockIterable);
    when(mockBlobContainerClient.getBlobClient(anyString())).thenReturn(mockBlobClient);
    when(mockBlobClient.getProperties())
        .thenReturn(mock(com.azure.storage.blob.models.BlobProperties.class));
    when(mockBlobClient.getProperties().getMetadata()).thenReturn(Collections.emptyMap());
    doNothing().when(mockBlobClient).downloadStream(any(ByteArrayOutputStream.class));

    List<BlobFileData> result =
        CommonUtil.getBlobFilesInDateRange(
            storageEnvVar, containerName, prefixFormat, from, to, mockLogger);
    assertNotNull(result);
    assertFalse(result.isEmpty());
  }

  @ParameterizedTest
  @CsvSource({
    "OK, OK response",
    "MULTI_STATUS, Multi-status response",
    "BAD_REQUEST, Bad request response",
    "NOT_FOUND, Not found response",
    "SERVICE_UNAVAILABLE, Service unavailable response",
    "UNPROCESSABLE_ENTITY, Unprocessable entity response",
    "INTERNAL_SERVER_ERROR, Server error response"
  })
  void testHttpResponse(HttpStatus status, String message) {

    HttpRequestMessage<?> request = mock(HttpRequestMessage.class);
    HttpResponseMessage.Builder builder = mock(HttpResponseMessage.Builder.class);
    HttpResponseMessage response = mock(HttpResponseMessage.class);

    when(request.createResponseBuilder(status)).thenReturn(builder);
    when(builder.header(anyString(), anyString())).thenReturn(builder);
    when(builder.body(any(String.class))).thenReturn(builder);
    when(builder.build()).thenReturn(response);

    HttpResponseMessage result;
    switch (status) {
      case OK:
        result = CommonUtil.ok(request, message);
        break;
      case MULTI_STATUS:
        result = CommonUtil.multiStatus(request, message);
        break;
      case BAD_REQUEST:
        result = CommonUtil.badRequest(request, message);
        break;
      case NOT_FOUND:
        result = CommonUtil.notFound(request, message);
        break;
      case SERVICE_UNAVAILABLE:
        result = CommonUtil.serviceUnavailable(request, message);
        break;
      case INTERNAL_SERVER_ERROR:
        result = CommonUtil.serverError(request, message);
        break;
      case UNPROCESSABLE_ENTITY:
        result = CommonUtil.unprocessableEntity(request, message);
        break;
      default:
        throw new IllegalArgumentException("Unsupported HttpStatus");
    }

    assertNotNull(result);
  }
  
  @Test
  void testPrepareAndSendEventsToEventHub() throws Exception {

    // mock EventHub clients & batches
    EventHubProducerClient flowClient = mock(EventHubProducerClient.class);
    EventHubProducerClient reportedClient = mock(EventHubProducerClient.class);

    EventDataBatch flowBatch = mock(EventDataBatch.class);
    EventDataBatch reportedBatch = mock(EventDataBatch.class);

    when(flowClient.createBatch()).thenReturn(flowBatch);
    when(reportedClient.createBatch()).thenReturn(reportedBatch);

    // Capture EventData
    ArgumentCaptor<EventData> flowEventCaptor = ArgumentCaptor.forClass(EventData.class);
    ArgumentCaptor<EventData> paymentEventCaptor = ArgumentCaptor.forClass(EventData.class);

    when(flowBatch.tryAdd(flowEventCaptor.capture())).thenReturn(true);
    when(reportedBatch.tryAdd(paymentEventCaptor.capture())).thenReturn(true);

    lenient().when(flowBatch.getCount()).thenReturn(1);
    lenient().when(reportedBatch.getCount()).thenReturn(1);

    doNothing().when(flowClient).send(any(EventDataBatch.class));
    doNothing().when(reportedClient).send(any(EventDataBatch.class));

    // build models with ALL_DATES date-only string
    FlowTxEventModel flowEvent =
        FlowTxEventModel.builder()
            .flowId("FDR123")
            .flowDateTime(LocalDateTime.of(2026, 1, 26, 15, 55, 44))
            .insertedTimestamp(LocalDateTime.of(2026, 1, 26, 15, 55, 44))
            .regulationDate(LocalDateTime.of(2026, 1, 23, 0, 0))
            .causal("CAUSE")
            .paymentsNum(5)
            .amountPaid(new BigDecimal("50"))
            .domainId("97532760580")
            .psp("SELBIT2B")
            .intPsp("02224410023")
            .uniqueId("8f5ce65b-efd8-4ce2-9593-97b2300b315a")
            .allDates(List.of("2026-01-25")) // date-only
            .build();

    ReportedIUVEventModel paymentEvent =
        ReportedIUVEventModel.builder()
            .iuv("501734800531673")
            .iur("87096853380")
            .amount(new BigDecimal("10"))
            .outcomeCode(0)
            .singlePaymentOutcomeDate(LocalDateTime.of(2026, 1, 25, 0, 0))
            .idsp("1")
            .flowId("FDR123")
            .flowDateTime(LocalDateTime.of(2026, 1, 26, 15, 55, 44))
            .domainId("97532760580")
            .psp("SELBIT2B")
            .intPsp("02224410023")
            .uniqueId("8f5ce65b-efd8-4ce2-9593-97b2300b315a")
            .insertedTimestamp(LocalDateTime.of(2026, 1, 26, 15, 55, 44))
            .idTransfer(1L)
            .build();

    Stream<ReportedIUVEventModel> paymentStream = Stream.of(paymentEvent);

    Map<String, String> metadata = Map.of(
        "sessionId", "8f5ce65b-efd8-4ce2-9593-97b2300b315a",
        "insertedTimestamp", "2026-01-26T15:55:44.7283250Z",
        "serviceIdentifier", "NA"
    );

    // invoke private static prepareAndSendEventsToEventHub via reflection
    Method m =
        CommonUtil.class.getDeclaredMethod(
            "prepareAndSendEventsToEventHub",
            EventHubProducerClient.class,
            EventHubProducerClient.class,
            FlowTxEventModel.class,
            Stream.class,
            String.class,
            Map.class,
            Logger.class,
            boolean.class,
            boolean.class);

    m.setAccessible(true);

    Object result =
        m.invoke(
            null,
            flowClient,
            reportedClient,
            flowEvent,
            paymentStream,
            "FDR123",
            metadata,
            mockLogger,
            true,  // sendFlowEvent
            true   // sendPaymentEvents
        );

    assertEquals(true, result);

    String flowJson = flowEventCaptor.getValue().getBodyAsString();
    assertNotNull(flowJson);

    // contain date-only string
    assertTrue(flowJson.contains("\"ALL_DATES\":[\"2026-01-25\"]"), flowJson);

    // NOT contain midnight timestamp version inside ALL_DATES
    assertFalse(flowJson.contains("2026-01-25T00:00:00"), flowJson);

    String paymentJson = paymentEventCaptor.getValue().getBodyAsString();
    assertNotNull(paymentJson);
    assertTrue(paymentJson.contains("\"DATA_ESITO_SINGOLO_PAGAMENTO\""), paymentJson);

    // Verify send for both hubs
    verify(flowClient, atLeastOnce()).send(any(EventDataBatch.class));
    verify(reportedClient, atLeastOnce()).send(any(EventDataBatch.class));
  }

}
