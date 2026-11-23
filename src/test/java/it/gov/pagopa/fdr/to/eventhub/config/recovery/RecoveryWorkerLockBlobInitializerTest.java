package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.rest.Response;
import com.azure.core.http.rest.SimpleResponse;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class RecoveryWorkerLockBlobInitializerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final BlobClient blob = mock(BlobClient.class);

  private final RecoveryWorkerLockBlobInitializer initializer =
      new RecoveryWorkerLockBlobInitializer(blob);

  private Response<Boolean> existenceResponse(boolean exists) {
    return new SimpleResponse<>(
        null,
        200,
        new HttpHeaders(),
        exists);
  }

  private BlobStorageException storageError(int status, String code) {
    BlobStorageException exception =
        mock(BlobStorageException.class);

    when(exception.getStatusCode()).thenReturn(status);
    when(exception.getErrorCode())
        .thenReturn(BlobErrorCode.fromString(code));

    return exception;
  }

  private void failUpload(BlobStorageException failure) {
    when(blob.uploadWithResponse(
            any(BlobParallelUploadOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);
  }

  @Test
  void shouldLeaveExistingBlobUnchanged() {
    when(blob.existsWithResponse(TIMEOUT, Context.NONE))
        .thenReturn(existenceResponse(true));

    initializer.ensureExists();

    verify(blob).existsWithResponse(TIMEOUT, Context.NONE);

    verify(blob, never()).uploadWithResponse(
        any(BlobParallelUploadOptions.class),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @Test
  void shouldCreateMissingBlobWithOverwriteProtection() {
    when(blob.existsWithResponse(TIMEOUT, Context.NONE))
        .thenReturn(existenceResponse(false));

    initializer.ensureExists();

    ArgumentCaptor<BlobParallelUploadOptions> captor =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);

    verify(blob).uploadWithResponse(
        captor.capture(),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(
        "*",
        captor.getValue().getRequestConditions().getIfNoneMatch());
  }

  @ParameterizedTest
  @CsvSource({
      "409, BlobAlreadyExists",
      "412, ConditionNotMet",
      "412, LeaseIdMissing"
  })
  void shouldAcceptConcurrentCreationOnlyAfterConfirmingExistence(
      int status,
      String code) {

    when(blob.existsWithResponse(TIMEOUT, Context.NONE))
        .thenReturn(
            existenceResponse(false),
            existenceResponse(true));

    BlobStorageException failure = storageError(status, code);
    failUpload(failure);

    initializer.ensureExists();

    verify(blob, times(2))
        .existsWithResponse(TIMEOUT, Context.NONE);

    verify(blob).uploadWithResponse(
        any(BlobParallelUploadOptions.class),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @ParameterizedTest
  @CsvSource({
      "409, BlobAlreadyExists",
      "412, ConditionNotMet",
      "412, LeaseIdMissing"
  })
  void shouldPropagateConflictWhenBlobIsStillMissing(
      int status,
      String code) {

    when(blob.existsWithResponse(TIMEOUT, Context.NONE))
        .thenReturn(existenceResponse(false));

    BlobStorageException failure = storageError(status, code);
    failUpload(failure);

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            initializer::ensureExists);

    assertSame(failure, thrown);

    verify(blob, times(2))
        .existsWithResponse(TIMEOUT, Context.NONE);
  }

  @ParameterizedTest
  @CsvSource({
      "403, AuthorizationPermissionMismatch",
      "404, ContainerNotFound",
      "409, UnknownError"
  })
  void shouldPropagateUnexpectedUploadErrors(
      int status,
      String code) {

    when(blob.existsWithResponse(TIMEOUT, Context.NONE))
        .thenReturn(existenceResponse(false));

    BlobStorageException failure = storageError(status, code);
    failUpload(failure);

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            initializer::ensureExists);

    assertSame(failure, thrown);

    verify(blob).existsWithResponse(TIMEOUT, Context.NONE);
  }

  @Test
  void shouldPropagateExistenceCheckFailureWithoutUploading() {
    BlobStorageException failure =
        storageError(403, "AuthorizationPermissionMismatch");

    when(blob.existsWithResponse(TIMEOUT, Context.NONE))
        .thenThrow(failure);

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            initializer::ensureExists);

    assertSame(failure, thrown);

    verify(blob, never()).uploadWithResponse(
        any(BlobParallelUploadOptions.class),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @Test
  void shouldPropagateUploadTimeoutWithoutApplicationRetry() {
    when(blob.existsWithResponse(TIMEOUT, Context.NONE))
        .thenReturn(existenceResponse(false));

    IllegalStateException failure =
        new IllegalStateException("Simulated upload timeout");

    when(blob.uploadWithResponse(
            any(BlobParallelUploadOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            initializer::ensureExists);

    assertSame(failure, thrown);

    verify(blob).uploadWithResponse(
        any(BlobParallelUploadOptions.class),
        eq(TIMEOUT),
        eq(Context.NONE));
  }
}