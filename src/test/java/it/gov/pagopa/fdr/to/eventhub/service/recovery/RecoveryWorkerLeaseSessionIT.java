package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryWorkerLockBlobInitializer;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryWorkerLeaseRepository;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_LEASE_INTEGRATION_TESTS",
    matches = "true")
class RecoveryWorkerLeaseSessionIT {

  @Test
  void shouldRenewAutomaticallyBeyondInitialLeaseDuration()
      throws InterruptedException {

    String connectionString =
        System.getenv("FDR_SA_CONNECTION_STRING");

    String blobName =
        "it-session-" + UUID.randomUUID() + ".lock";

    BlobClient blob =
        new BlobClientBuilder()
            .connectionString(connectionString)
            .containerName("fdr-recovery-locks")
            .blobName(blobName)
            .buildClient();

    // Validate the destination before performing network operations.
    assertEquals("pagopadweufdrsa", blob.getAccountName());
    assertEquals("fdr-recovery-locks", blob.getContainerName());

    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor();

    try {
      new RecoveryWorkerLockBlobInitializer(blob).ensureExists();

      RecoveryWorkerLeaseRepository repository =
          spy(new RecoveryWorkerLeaseRepository(blob));

      RecoveryWorkerLeaseRepository competitor =
          new RecoveryWorkerLeaseRepository(blob);

      try (RecoveryWorkerLeaseSession session =
          RecoveryWorkerLeaseSession.tryAcquire(repository, scheduler)
              .orElseThrow(
                  () -> new IllegalStateException(
                      "Could not acquire the isolated test lease"))) {

        System.out.println(
            "DEV lease session test: waiting 65 seconds "
                + "to exceed the initial lease duration");

        TimeUnit.SECONDS.sleep(65);

        session.requireHeld();

        verify(repository, atLeast(2)).renew(anyString());

        assertTrue(
            competitor.tryAcquire().isEmpty(),
            "Another client must remain excluded after 60 seconds");
      }

      String acquiredAfterClose =
          competitor.tryAcquire()
              .orElseThrow(
                  () -> new IllegalStateException(
                      "Closing the session must release the lease"));

      try {
        competitor.renew(acquiredAfterClose);
      } finally {
        competitor.release(acquiredAfterClose);
      }

      System.out.println(
          "DEV lease session test completed: "
              + "automaticRenewal=true, "
              + "exclusiveBeyond60Seconds=true, "
              + "releasedOnClose=true");

    } finally {
      scheduler.shutdownNow();
      blob.deleteIfExists();
    }
  }
}