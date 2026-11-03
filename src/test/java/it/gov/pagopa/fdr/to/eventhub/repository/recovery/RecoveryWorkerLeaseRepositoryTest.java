package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.util.Context;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobAcquireLeaseOptions;
import com.azure.storage.blob.options.BlobReleaseLeaseOptions;
import com.azure.storage.blob.options.BlobRenewLeaseOptions;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryWorkerLeaseRepositoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static final String LEASE_ID =
      "11111111-1111-1111-1111-111111111111";

  private final BlobLeaseClient client =
      mock(BlobLeaseClient.class);

  @SuppressWarnings("unchecked")
  private final Function<String, BlobLeaseClient> clientFactory =
      mock(Function.class);

  private final RecoveryWorkerLeaseRepository repository =
      new RecoveryWorkerLeaseRepository(clientFactory);

  @BeforeEach
  void setUp() {
    when(clientFactory.apply(anyString())).thenReturn(client);
  }

  private BlobStorageException storageError(int status, String code) {
    BlobStorageException exception =
        mock(BlobStorageException.class);

    when(exception.getStatusCode()).thenReturn(status);
    when(exception.getErrorCode())
        .thenReturn(BlobErrorCode.fromString(code));

    return exception;
  }

  @Test
  void shouldAcquireFiniteLeaseUsingFreshIdentifier() {
    String acquired = repository.tryAcquire().orElseThrow();

    ArgumentCaptor<String> idCaptor =
        ArgumentCaptor.forClass(String.class);

    verify(clientFactory).apply(idCaptor.capture());

    assertEquals(acquired, idCaptor.getValue());
    assertEquals(acquired, UUID.fromString(acquired).toString());

    ArgumentCaptor<BlobAcquireLeaseOptions> optionsCaptor =
        ArgumentCaptor.forClass(BlobAcquireLeaseOptions.class);

    verify(client).acquireLeaseWithResponse(
        optionsCaptor.capture(),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(60, optionsCaptor.getValue().getDuration());
  }

  @Test
  void shouldReturnEmptyWhenAnotherWorkerOwnsLease() {
    BlobStorageException failure =
        storageError(409, "LeaseAlreadyPresent");

    when(client.acquireLeaseWithResponse(
            any(BlobAcquireLeaseOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    Optional<String> result = repository.tryAcquire();

    assertTrue(result.isEmpty());

    verify(client).acquireLeaseWithResponse(
        any(BlobAcquireLeaseOptions.class),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @ParameterizedTest
  @CsvSource({
      "404, BlobNotFound",
      "404, ContainerNotFound",
      "403, AuthorizationPermissionMismatch",
      "409, LeaseIsBreakingAndCannotBeAcquired",
      "409, UnknownError"
  })
  void shouldPropagateErrorsOtherThanAlreadyPresent(
      int status,
      String code) {

    BlobStorageException failure =
        storageError(status, code);

    when(client.acquireLeaseWithResponse(
            any(BlobAcquireLeaseOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            () -> repository.tryAcquire());

    assertSame(failure, thrown);

    verify(client).acquireLeaseWithResponse(
        any(BlobAcquireLeaseOptions.class),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @Test
  void shouldUseDifferentIdentifiersForDifferentAcquisitionAttempts() {
    String first = repository.tryAcquire().orElseThrow();
    String second = repository.tryAcquire().orElseThrow();

    assertNotEquals(first, second);

    ArgumentCaptor<String> captor =
        ArgumentCaptor.forClass(String.class);

    verify(clientFactory, times(2)).apply(captor.capture());

    assertEquals(List.of(first, second), captor.getAllValues());
  }

  @Test
  void shouldRenewUsingExistingLeaseIdentifier() {
    repository.renew(LEASE_ID);

    verify(clientFactory).apply(LEASE_ID);

    verify(client).renewLeaseWithResponse(
        any(BlobRenewLeaseOptions.class),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @Test
  void shouldReleaseUsingExistingLeaseIdentifier() {
    repository.release(LEASE_ID);

    verify(clientFactory).apply(LEASE_ID);

    verify(client).releaseLeaseWithResponse(
        any(BlobReleaseLeaseOptions.class),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @Test
  void shouldPropagateRenewalFailureWithoutApplicationRetry() {
    BlobStorageException failure =
        storageError(412, "LeaseIdMismatchWithLeaseOperation");

    when(client.renewLeaseWithResponse(
            any(BlobRenewLeaseOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            () -> repository.renew(LEASE_ID));

    assertSame(failure, thrown);

    verify(client).renewLeaseWithResponse(
        any(BlobRenewLeaseOptions.class),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @Test
  void shouldPropagateReleaseFailureWithoutApplicationRetry() {
    BlobStorageException failure =
        storageError(412, "LeaseIdMismatchWithLeaseOperation");

    when(client.releaseLeaseWithResponse(
            any(BlobReleaseLeaseOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    BlobStorageException thrown =
        assertThrows(
            BlobStorageException.class,
            () -> repository.release(LEASE_ID));

    assertSame(failure, thrown);

    verify(client).releaseLeaseWithResponse(
        any(BlobReleaseLeaseOptions.class),
        eq(TIMEOUT),
        eq(Context.NONE));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = " ")
  void shouldRejectMissingIdentifierBeforeAccessingStorage(
      String leaseId) {

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.renew(leaseId));

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.release(leaseId));

    verifyNoInteractions(clientFactory, client);
  }
}