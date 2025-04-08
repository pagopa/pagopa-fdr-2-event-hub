package it.gov.pagopa.fdr.to.eventhub.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.rest.PagedIterable;
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
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.wrapper.BlobServiceClientWrapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    lenient().when(mockContext.getLogger()).thenReturn(mockLogger);
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
        CommonUtil.getBlobFile(STORAGE_ENV_VAR, CONTAINER_NAME, BLOB_NAME, mockContext);

    assertNull(result);
    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).severe(logCaptor.capture());

    logCaptor.getAllValues().stream()
        .map(Supplier::get)
        .anyMatch(log -> log.contains("Blob not found"));
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
        CommonUtil.getBlobFile(STORAGE_ENV_VAR, CONTAINER_NAME, BLOB_NAME, mockContext);

    assertNotNull(result);
    assertArrayEquals(mockData, result.getFileContent());
    assertEquals(metadata, result.getMetadata());
  }

  @Test
  void testBlobFileRetrievalFailure() {
    when(mockBlobContainerClient.getBlobClient(anyString()))
        .thenThrow(new RuntimeException("Storage error"));

    BlobFileData result =
        CommonUtil.getBlobFile(STORAGE_ENV_VAR, CONTAINER_NAME, BLOB_NAME, mockContext);

    assertNull(result);
    verify(mockLogger).severe("Error accessing blob: Storage error");
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
            storageEnvVar, containerName, prefixFormat, from, to, mockContext);
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
}
