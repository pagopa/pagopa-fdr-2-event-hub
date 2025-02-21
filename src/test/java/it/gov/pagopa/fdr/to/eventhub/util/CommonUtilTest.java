package it.gov.pagopa.fdr.to.eventhub.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobProperties;
import com.microsoft.azure.functions.ExecutionContext;
import it.gov.pagopa.fdr.to.eventhub.model.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.wrapper.BlobServiceClientWrapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    when(mockBlobServiceClientWrapper.getBlobContainerClient(anyString(), anyString()))
        .thenReturn(mockBlobContainerClient);
    when(mockBlobContainerClient.getBlobClient(anyString())).thenReturn(mockBlobClient);
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
}
