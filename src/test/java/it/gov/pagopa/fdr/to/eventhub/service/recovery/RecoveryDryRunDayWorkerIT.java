package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.azure.storage.blob.BlobClient;
import com.zaxxer.hikari.HikariConfig;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxClientFactory;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxConfiguration;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryPostgresConfiguration;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryPostgresDataSource;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryTableClients;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryWorkerLockBlobInitializer;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryWorkerLockClientFactory;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobRequest;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryBatchRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryItemTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryWorkerLeaseRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryJob;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_WORKER_INTEGRATION_TESTS",
    matches = "true")
class RecoveryDryRunDayWorkerIT {

  @Test
  void shouldCompleteOneDayInDevAndSkipCompletedJob()
      throws Exception {

    String configuredDay =
        System.getenv("RECOVERY_WORKER_TEST_DAY");

    assertNotNull(
        configuredDay,
        "RECOVERY_WORKER_TEST_DAY must contain the test day");

    LocalDate day = LocalDate.parse(configuredDay);

    HikariConfig postgresConfiguration =
        RecoveryPostgresConfiguration.fromEnvironment();

    RecoveryAdxConfiguration adxConfiguration =
        RecoveryAdxConfiguration.fromEnvironment();

    RecoveryTableClients tables =
        RecoveryTableClients.fromEnvironment();

    BlobClient lockBlob =
        RecoveryWorkerLockClientFactory.fromEnvironment();

    // Validate all destinations before connecting or writing.
    assertDevConnections(
        postgresConfiguration,
        adxConfiguration,
        tables,
        lockBlob);

    RecoveryJobRepository jobs =
        new RecoveryJobRepository(
            tables.jobs(),
            new RecoveryJobTableMapper());

    RecoveryDiscoveryCheckpointRepository checkpoints =
        new RecoveryDiscoveryCheckpointRepository(
            tables.items(),
            new RecoveryDiscoveryCheckpointTableMapper());

    RecoveryDiscoveryBatchRepository batches =
        new RecoveryDiscoveryBatchRepository(
            tables.items(),
            new RecoveryDiscoveryItemTableMapper());

    RecoveryAdxClientFactory adxFactory =
        new RecoveryAdxClientFactory(adxConfiguration);

    RecoveryAdxRepository adx =
        new RecoveryAdxRepository(
            adxFactory.client(),
            adxConfiguration.database(),
            adxConfiguration.queryTimeoutSeconds());

    RecoveryWorkerLockBlobInitializer initializer =
        new RecoveryWorkerLockBlobInitializer(lockBlob);

    RecoveryWorkerLeaseRepository leases =
        new RecoveryWorkerLeaseRepository(lockBlob);

    ScheduledThreadPoolExecutor renewals = createRenewalScheduler();

    try (RecoveryPostgresDataSource postgres =
        new RecoveryPostgresDataSource(postgresConfiguration)) {

      RecoveryFlowRepository flows =
          new RecoveryFlowRepository(postgres.dataSource(), 60);

      RecoveryPersistedDiscoveryService persisted =
          new RecoveryPersistedDiscoveryService(
              adx,
              flows,
              batches,
              checkpoints,
              100);

      RecoveryDiscoveryJobService discovery =
          spy(new RecoveryDiscoveryJobService(jobs, persisted));

      Clock clock = Clock.systemUTC();

      RecoveryJobWorkerStateService states =
          new RecoveryJobWorkerStateService(jobs, clock);

      RecoveryDryRunDayWorker worker =
          new RecoveryDryRunDayWorker(
              jobs,
              states,
              discovery,
              () -> {
                initializer.ensureExists();
                return RecoveryWorkerLeaseSession.tryAcquire(
                    leases,
                    renewals);
              });

      RecoveryJobRequest request =
          new RecoveryJobRequest(
              day.toString(),
              day.toString(),
              true);

      RecoveryJob job = RecoveryJob.create(request, clock);
      jobs.create(job);

      System.out.printf(
          "DEV dry-run worker test started: jobId=%s, day=%s%n",
          job.jobId(),
          day);

      RecoveryDryRunDayWorker.Outcome first =
          worker.runOneDay(job.jobId());

      assertEquals(
          RecoveryDryRunDayWorker.Outcome.JOB_COMPLETED,
          first,
          "The DEV worker lease must be available for this test");

      StoredRecoveryJob completed =
          jobs.find(job.jobId()).orElseThrow();

      assertCompletedJob(job, completed.job(), day);

      StoredRecoveryDiscoveryCheckpoint checkpoint =
          checkpoints.find(job.jobId(), day).orElseThrow();

      assertEquals(
          RecoveryDiscoveryCheckpoint.Status.DISCOVERED,
          checkpoint.checkpoint().status());

      RecoveryDryRunDayWorker.Outcome repeated =
          worker.runOneDay(job.jobId());

      assertEquals(
          RecoveryDryRunDayWorker.Outcome.NOT_RUN,
          repeated);

      StoredRecoveryJob afterRepeat =
          jobs.find(job.jobId()).orElseThrow();

      StoredRecoveryDiscoveryCheckpoint checkpointAfterRepeat =
          checkpoints.find(job.jobId(), day).orElseThrow();

      assertEquals(completed.job(), afterRepeat.job());
      assertEquals(completed.etag(), afterRepeat.etag());

      assertEquals(
          checkpoint.checkpoint(),
          checkpointAfterRepeat.checkpoint());

      assertEquals(
          checkpoint.etag(),
          checkpointAfterRepeat.etag());

      // Exactly one discovery invocation across both worker executions.
      verify(discovery).discover(
          eq(job.jobId()),
          eq(day),
          any(RecoveryWorkerLeaseSession.class));

      RecoveryDiscoveryCheckpoint summary =
          checkpoint.checkpoint();

      System.out.printf(
          "DEV dry-run worker test completed: jobId=%s, day=%s, "
              + "status=%s, nextDate=%s, candidates=%d, "
              + "absent=%d, presentUnverified=%d, pages=%d, "
              + "repeatedRunSkipped=true%n",
          job.jobId(),
          day,
          completed.job().status(),
          completed.job().nextDate(),
          summary.candidateCount(),
          summary.absentCandidateCount(),
          summary.presentCandidateCount(),
          summary.pageCount());

    } finally {
      renewals.shutdownNow();
    }
  }

  private ScheduledThreadPoolExecutor createRenewalScheduler() {
    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread =
                  new Thread(runnable, "fdr-recovery-lease-it");

              thread.setDaemon(true);
              return thread;
            });

    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

    return scheduler;
  }

  private void assertCompletedJob(
      RecoveryJob original,
      RecoveryJob completed,
      LocalDate day) {

    assertEquals(original.jobId(), completed.jobId());
    assertEquals(original.createdAt(), completed.createdAt());

    assertEquals(true, completed.dryRun());
    assertEquals(day, completed.dateFrom());
    assertEquals(day, completed.dateTo());

    assertEquals(
        RecoveryJobStatus.COMPLETED,
        completed.status());

    assertEquals(day.plusDays(1), completed.nextDate());
  }

  private void assertDevConnections(
      HikariConfig postgres,
      RecoveryAdxConfiguration adx,
      RecoveryTableClients tables,
      BlobClient lockBlob) {

    assertEquals(
        "jdbc:postgresql://fdr-db.d.internal.postgresql.pagopa.it:5432/fdr3",
        postgres.getJdbcUrl());

    assertEquals(
        "https://pagopaddataexplorer.westeurope.kusto.windows.net",
        adx.clusterUrl());

    assertEquals("re", adx.database());

    assertEquals(
        RecoveryAdxConfiguration.AuthMode.AZURE_CLI,
        adx.authMode());

    assertEquals("pagopadweufdrsa", tables.jobs().getAccountName());
    assertEquals("fdrrecoveryjobs", tables.jobs().getTableName());

    assertEquals("pagopadweufdrsa", tables.items().getAccountName());
    assertEquals("fdrrecoveryitems", tables.items().getTableName());

    assertEquals(
        "https://pagopadweufdrsa.blob.core.windows.net/"
            + "fdr-recovery-locks/worker.lock",
        lockBlob.getBlobUrl());
  }
}