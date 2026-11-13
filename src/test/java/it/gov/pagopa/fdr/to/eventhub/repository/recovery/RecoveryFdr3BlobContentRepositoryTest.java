package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryFdr3BlobContentRepositoryTest {

  private static final String BLOB_NAME = "flow_PSP_1.json.zip";
  private static final String ETAG = "\"source-etag\"";
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final BlobContainerClient container =
      mock(BlobContainerClient.class);

  private final BlobClient blob = mock(BlobClient.class);

  private RecoveryFdr3BlobContentRepository repository;

  @BeforeEach
  void setUp() {
    when(container.getBlobContainerName())
        .thenReturn("fdr3-flows");

    when(container.getBlobClient(BLOB_NAME))
        .thenReturn(blob);

    repository =
        new RecoveryFdr3BlobContentRepository(container, 8);
  }

  private RecoveryBlobSnapshot snapshot(long length) {
    return new RecoveryBlobSnapshot(
        "fdr3-flows",
        BLOB_NAME,
        ETAG,
        length,
        Map.of());
  }

  private void givenContent(byte[] content) {
    doAnswer(invocation -> {
      OutputStream output = invocation.getArgument(0);
      output.write(content);
      return null;
    }).when(blob).downloadStreamWithResponse(
        any(OutputStream.class),
        isNull(),
        isNull(),
        any(BlobRequestConditions.class),
        eq(false),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @Test
  void shouldDownloadExactContentWithSnapshotETag() throws RecoveryContentSizeException {
    doAnswer(invocation -> {
      OutputStream output = invocation.getArgument(0);
      output.write(new byte[] {10, 20});
      output.write(30);
      output.write(new byte[] {0, 40, 0}, 1, 1);
      return null;
    }).when(blob).downloadStreamWithResponse(
        any(OutputStream.class),
        isNull(),
        isNull(),
        any(BlobRequestConditions.class),
        eq(false),
        eq(TIMEOUT),
        eq(Context.NONE));

    byte[] content = repository.download(snapshot(4));

    assertArrayEquals(new byte[] {10, 20, 30, 40}, content);

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
  void shouldReadEmptyBlobWithoutInventingContent() throws RecoveryContentSizeException {
    givenContent(new byte[0]);

    assertArrayEquals(
        new byte[0],
        repository.download(snapshot(0)));
  }

  @Test
  void shouldRejectOversizedSnapshotBeforeDownload() {
    RecoveryBlobSnapshot oversized = snapshot(9);

    var failure =
        assertThrows(
            RecoveryContentSizeException.class,
            () -> repository.download(oversized));

    assertEquals(
        RecoveryContentSizeException.Stage.COMPRESSED_BLOB,
        failure.stage());

    assertEquals(8, failure.limitBytes());
    assertEquals(9, failure.observedBytesAtLeast());

    verify(container, never()).getBlobClient(anyString());
    verifyNoInteractions(blob);
  }

  @Test
  void shouldRejectMoreBytesThanDeclared() {
    givenContent(new byte[] {1, 2, 3, 4, 5});
    RecoveryBlobSnapshot source = snapshot(4);

    assertThrows(
        IllegalStateException.class,
        () -> repository.download(source));
  }

  @Test
  void shouldRejectIncompleteContent() {
    givenContent(new byte[] {1, 2, 3});
    RecoveryBlobSnapshot source = snapshot(4);

    assertThrows(
        IllegalStateException.class,
        () -> repository.download(source));
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 404, 412, 500})
  void shouldPropagateStorageFailureWithoutRefreshingSnapshot(
      int status) {

    BlobStorageException failure =
        mock(BlobStorageException.class);

    when(failure.getStatusCode()).thenReturn(status);

    when(blob.downloadStreamWithResponse(
            any(OutputStream.class),
            isNull(),
            isNull(),
            any(BlobRequestConditions.class),
            eq(false),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    RecoveryBlobSnapshot source = snapshot(4);

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            () -> repository.download(source));

    assertSame(failure, thrown);
    assertEquals(status, thrown.getStatusCode());

    verify(blob).downloadStreamWithResponse(
        any(OutputStream.class),
        isNull(),
        isNull(),
        any(BlobRequestConditions.class),
        eq(false),
        eq(TIMEOUT),
        eq(Context.NONE));

    verifyNoMoreInteractions(blob);
  }

  @Test
  void shouldRejectSnapshotFromAnotherContainer() {
    RecoveryBlobSnapshot source =
        new RecoveryBlobSnapshot(
            "fdr1-flows",
            BLOB_NAME,
            ETAG,
            4L,
            Map.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.download(source));

    verifyNoInteractions(blob);
  }

  @Test
  void shouldRejectWildcardETag() {
    RecoveryBlobSnapshot source =
        new RecoveryBlobSnapshot(
            "fdr3-flows",
            BLOB_NAME,
            "*",
            4L,
            Map.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.download(source));

    verifyNoInteractions(blob);
  }

  @Test
  void shouldRejectNullSnapshot() {
    assertThrows(
        NullPointerException.class,
        () -> repository.download(null));

    verifyNoInteractions(blob);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void shouldRejectInvalidSizeLimit(int limit) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryFdr3BlobContentRepository(
            container, limit));
  }
}