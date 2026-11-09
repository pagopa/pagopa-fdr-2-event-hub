package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryBlobContentRepositoryTest {

  private static final String BLOB_NAME = "flow_uuid.xml.zip";
  private static final String ETAG = "\"xml-source-etag\"";
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final BlobContainerClient container =
      mock(BlobContainerClient.class);

  private final BlobClient blob = mock(BlobClient.class);

  private RecoveryBlobContentRepository repository;

  @BeforeEach
  void setUp() {
    when(container.getBlobContainerName())
        .thenReturn("fdr1-flows");

    when(container.getBlobClient(BLOB_NAME))
        .thenReturn(blob);

    repository =
        new RecoveryBlobContentRepository(
            container, "fdr1-flows", 1024);
  }

  private RecoveryBlobSnapshot snapshot(String containerName) {
    return new RecoveryBlobSnapshot(
        containerName,
        BLOB_NAME,
        ETAG,
        3L,
        Map.of());
  }

  @Test
  void shouldDownloadFdr1ContentWithSnapshotETag() {
    doAnswer(invocation -> {
      OutputStream output = invocation.getArgument(0);
      output.write(new byte[] {1, 2, 3});
      return null;
    }).when(blob).downloadStreamWithResponse(
        any(OutputStream.class),
        isNull(),
        isNull(),
        any(BlobRequestConditions.class),
        eq(false),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertArrayEquals(
        new byte[] {1, 2, 3},
        repository.download(snapshot("fdr1-flows")));

    ArgumentCaptor<BlobRequestConditions> conditions =
        ArgumentCaptor.forClass(BlobRequestConditions.class);

    verify(blob).downloadStreamWithResponse(
        any(OutputStream.class),
        isNull(),
        isNull(),
        conditions.capture(),
        eq(false),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(ETAG, conditions.getValue().getIfMatch());
    verifyNoMoreInteractions(blob);
  }

  @Test
  void shouldRejectSnapshotFromAnotherContainer() {
    RecoveryBlobSnapshot source = snapshot("fdr3-flows");

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.download(source));

    verifyNoInteractions(blob);
  }

  @Test
  void shouldRejectClientForAnotherContainer() {
    BlobContainerClient wrong = mock(BlobContainerClient.class);

    when(wrong.getBlobContainerName())
        .thenReturn("fdr3-flows");

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryBlobContentRepository(
            wrong, "fdr1-flows", 1024));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "other-container", "azure-webjobs-hosts"
  })
  void shouldRejectUnsupportedSourceContainer(String name) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryBlobContentRepository(
            container, name, 1024));
  }

  @Test
  void shouldPropagateFdr1ETagConflict() {
    BlobStorageException failure =
        mock(BlobStorageException.class);

    when(failure.getStatusCode()).thenReturn(412);

    when(blob.downloadStreamWithResponse(
            any(OutputStream.class),
            isNull(),
            isNull(),
            any(BlobRequestConditions.class),
            eq(false),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    RecoveryBlobSnapshot source = snapshot("fdr1-flows");

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            () -> repository.download(source));

    assertSame(failure, thrown);
    assertEquals(412, thrown.getStatusCode());
  }
}