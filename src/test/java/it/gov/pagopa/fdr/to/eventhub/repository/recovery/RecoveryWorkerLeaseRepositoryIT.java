package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryWorkerLockBlobInitializer;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryWorkerLockClientFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_LEASE_INTEGRATION_TESTS",
    matches = "true")
class RecoveryWorkerLeaseRepositoryIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  void shouldCoordinateTwoClientsInDev() {
    BlobClient firstBlob =
        RecoveryWorkerLockClientFactory.fromEnvironment();

    BlobClient secondBlob =
        RecoveryWorkerLockClientFactory.fromEnvironment();

    // Verify the destination before performing any network operation.
    assertDevDestination(firstBlob);
    assertDevDestination(secondBlob);

    RecoveryWorkerLockBlobInitializer initializer =
        new RecoveryWorkerLockBlobInitializer(firstBlob);

    initializer.ensureExists();

    String originalEtag = readEtag(firstBlob);

    initializer.ensureExists();

    assertEquals(
        originalEtag,
        readEtag(firstBlob),
        "Repeated initialization must not overwrite the lock blob");

    RecoveryWorkerLeaseRepository first =
        new RecoveryWorkerLeaseRepository(firstBlob);

    RecoveryWorkerLeaseRepository second =
        new RecoveryWorkerLeaseRepository(secondBlob);

    String firstLeaseId =
        first.tryAcquire()
            .orElseThrow(
                () -> new IllegalStateException(
                    "DEV worker lock is already leased; "
                        + "run this test when no recovery worker is active"));

    try {
      assertTrue(
          second.tryAcquire().isEmpty(),
          "A second client must not acquire an occupied lease");

      // Initialization must also leave an actively leased blob unchanged.
      initializer.ensureExists();

      assertEquals(
          originalEtag,
          readEtag(firstBlob),
          "Initialization must not overwrite an actively leased blob");

      first.renew(firstLeaseId);

      assertTrue(
          second.tryAcquire().isEmpty(),
          "Renewal must preserve exclusive ownership");

    } finally {
      first.release(firstLeaseId);
    }

    String secondLeaseId =
        second.tryAcquire()
            .orElseThrow(
                () -> new IllegalStateException(
                    "Second client could not acquire the released lease"));

    try {
      second.renew(secondLeaseId);

      assertTrue(
          first.tryAcquire().isEmpty(),
          "The first client must respect the second client's lease");

    } finally {
      second.release(secondLeaseId);
    }

    System.out.printf(
        "DEV worker lease test completed: account=%s, "
            + "exclusive=true, renewed=true, released=true, "
            + "existingBlobUnchanged=true%n",
        firstBlob.getAccountName());
  }

  private void assertDevDestination(BlobClient blob) {
    assertEquals(
        "pagopadweufdrsa",
        blob.getAccountName());

    assertEquals(
        "fdr-recovery-locks",
        blob.getContainerName());

    assertEquals(
        "worker.lock",
        blob.getBlobName());

    assertEquals(
        "https://pagopadweufdrsa.blob.core.windows.net/"
            + "fdr-recovery-locks/worker.lock",
        blob.getBlobUrl());
  }

  private String readEtag(BlobClient blob) {
    return blob.getPropertiesWithResponse(
            null,
            TIMEOUT,
            Context.NONE)
        .getValue()
        .getETag();
  }
}