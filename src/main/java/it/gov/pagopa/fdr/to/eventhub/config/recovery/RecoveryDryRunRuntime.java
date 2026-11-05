package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.azure.storage.blob.BlobClient;
import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryBatchRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryItemTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryRunnableJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryWorkerLeaseRepository;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDiscoveryJobService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDayWorker;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDispatcher;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobWorkerStateService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryPersistedDiscoveryService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryWorkerLeaseSession;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class RecoveryDryRunRuntime implements AutoCloseable {

  private final RecoveryDryRunDispatcher dispatcher;
  private final RecoveryPostgresDataSource postgres;
  private final ScheduledExecutorService renewals;

  private final ReentrantReadWriteLock lifecycle =
      new ReentrantReadWriteLock(true);

  private boolean closed;

  RecoveryDryRunRuntime(
      RecoveryDryRunDispatcher dispatcher,
      RecoveryPostgresDataSource postgres,
      ScheduledExecutorService renewals) {

    this.dispatcher =
        Objects.requireNonNull(dispatcher, "dispatcher is required");

    this.postgres =
        Objects.requireNonNull(postgres, "postgres is required");

    this.renewals =
        Objects.requireNonNull(renewals, "renewals is required");
  }

  public static RecoveryDryRunRuntime fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  public static RecoveryDryRunRuntime fromEnvironment(
      Map<String, String> environment) {

    Objects.requireNonNull(environment, "environment is required");

    Settings settings =
        new Settings(
            integerSetting(
                environment, "RECOVERY_DISCOVERY_PAGE_SIZE", 100, 100),
            integerSetting(
                environment, "RECOVERY_JOB_PAGE_SIZE", 10, 100),
            integerSetting(
                environment,
                "RECOVERY_POSTGRES_QUERY_TIMEOUT_SECONDS",
                60,
                60));

    var postgresConfiguration =
        RecoveryPostgresConfiguration.fromEnvironment(environment);

    RecoveryAdxConfiguration adxConfiguration =
        RecoveryAdxConfiguration.fromEnvironment(environment);

    RecoveryTableClients tables =
        RecoveryTableClients.fromEnvironment(environment);

    BlobClient lockBlob =
        RecoveryWorkerLockClientFactory.fromEnvironment(environment);

    RecoveryPostgresDataSource postgres =
        new RecoveryPostgresDataSource(postgresConfiguration);

    ScheduledThreadPoolExecutor renewals = createRenewalScheduler();

    try {
      RecoveryDryRunDispatcher dispatcher =
          createDispatcher(
              tables,
              adxConfiguration,
              lockBlob,
              postgres,
              renewals,
              settings);

      return new RecoveryDryRunRuntime(
          dispatcher,
          postgres,
          renewals);

    } catch (RuntimeException failure) {
      try {
        closeResources(postgres, renewals);
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }

      throw failure;
    }
  }

  public Optional<RecoveryDryRunDispatcher.Attempt> runNext()
      throws SQLException, DataClientException, DataServiceException {

    lifecycle.readLock().lock();

    try {
      if (closed) {
        throw new IllegalStateException(
            "Recovery runtime has been closed");
      }

      return dispatcher.runNext();

    } finally {
      lifecycle.readLock().unlock();
    }
  }

  @Override
  public void close() {
    lifecycle.writeLock().lock();

    try {
      if (closed) {
        return;
      }

      closed = true;
      closeResources(postgres, renewals);

    } finally {
      lifecycle.writeLock().unlock();
    }
  }

  private static RecoveryDryRunDispatcher createDispatcher(
      RecoveryTableClients tables,
      RecoveryAdxConfiguration adxConfiguration,
      BlobClient lockBlob,
      RecoveryPostgresDataSource postgres,
      ScheduledExecutorService renewals,
      Settings settings) {

    RecoveryJobTableMapper jobMapper =
        new RecoveryJobTableMapper();

    RecoveryJobRepository jobs =
        new RecoveryJobRepository(tables.jobs(), jobMapper);

    RecoveryRunnableJobRepository runnableJobs =
        new RecoveryRunnableJobRepository(tables.jobs(), jobMapper);

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

    RecoveryFlowRepository flows =
        new RecoveryFlowRepository(
            postgres.dataSource(),
            settings.postgresQueryTimeoutSeconds());

    RecoveryPersistedDiscoveryService persisted =
        new RecoveryPersistedDiscoveryService(
            adx,
            flows,
            batches,
            checkpoints,
            settings.discoveryPageSize());

    RecoveryDiscoveryJobService discovery =
        new RecoveryDiscoveryJobService(jobs, persisted);

    RecoveryJobWorkerStateService states =
        new RecoveryJobWorkerStateService(jobs, Clock.systemUTC());

    RecoveryWorkerLockBlobInitializer initializer =
        new RecoveryWorkerLockBlobInitializer(lockBlob);

    RecoveryWorkerLeaseRepository leases =
        new RecoveryWorkerLeaseRepository(lockBlob);

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

    return new RecoveryDryRunDispatcher(
        runnableJobs,
        worker,
        settings.jobPageSize());
  }

  private static ScheduledThreadPoolExecutor createRenewalScheduler() {
    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread =
                  new Thread(runnable, "fdr-recovery-lease-renewal");

              thread.setDaemon(true);
              return thread;
            });

    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

    return scheduler;
  }

  private static void closeResources(
      RecoveryPostgresDataSource postgres,
      ScheduledExecutorService renewals) {

    try {
      renewals.shutdownNow();
    } finally {
      postgres.close();
    }
  }

  private static int integerSetting(
      Map<String, String> environment,
      String setting,
      int defaultValue,
      int maximum) {

    String message =
        setting + " must be an integer between 1 and " + maximum;

    final int value;

    try {
      value =
          Integer.parseInt(
              environment.getOrDefault(
                  setting,
                  Integer.toString(defaultValue)));

    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(message, exception);
    }

    if (value < 1 || value > maximum) {
      throw new IllegalArgumentException(message);
    }

    return value;
  }

  private record Settings(
      int discoveryPageSize,
      int jobPageSize,
      int postgresQueryTimeoutSeconds) {
  }
}