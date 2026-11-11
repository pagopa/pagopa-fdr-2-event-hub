package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryRunnableJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryWorkerLeaseRepository;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDayWorker;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDispatcher;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobWorkerStateService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryWorkerLeaseSession;
import java.io.IOException;
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

    var settings =
        new Settings(
            integerSetting(
                environment, "RECOVERY_DISCOVERY_PAGE_SIZE", 100, 100),
            integerSetting(
                environment, "RECOVERY_JOB_PAGE_SIZE", 10, 100),
            integerSetting(
                environment,
                "RECOVERY_POSTGRES_QUERY_TIMEOUT_SECONDS",
                60,
                60),
            RecoveryPublicationConfiguration.fromEnvironment(environment));

    var postgresConfiguration =
        RecoveryPostgresConfiguration.fromEnvironment(environment);

    var adxConfiguration =
        RecoveryAdxConfiguration.fromEnvironment(environment);

    var tables = RecoveryTableClients.fromEnvironment(environment);

    // RecoveryTableClients has already validated this setting.
    var storage =
        new BlobServiceClientBuilder()
            .connectionString(
                environment.get("FDR_SA_CONNECTION_STRING"))
            .buildClient();

    var lockBlob =
        RecoveryWorkerLockClientFactory.fromEnvironment(environment);

    var postgres =
        new RecoveryPostgresDataSource(postgresConfiguration);

    var renewals = createRenewalScheduler();

    try {
      var dispatcher =
          createDispatcher(
              tables,
              adxConfiguration,
              storage,
              lockBlob,
              postgres,
              renewals,
              settings);

      return new RecoveryDryRunRuntime(
          dispatcher, postgres, renewals);

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
      throws SQLException, IOException,
          DataClientException, DataServiceException {

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
      BlobServiceClient storage,
      BlobClient lockBlob,
      RecoveryPostgresDataSource postgres,
      ScheduledExecutorService renewals,
      Settings settings) {

    var jobMapper =
        RecoveryJobTableMapper.forPublicationDiscovery();

    var jobs =
        new RecoveryJobRepository(tables.jobs(), jobMapper);

    var runnableJobs =
        new RecoveryRunnableJobRepository(tables.jobs(), jobMapper);

    var adxFactory =
        new RecoveryAdxClientFactory(adxConfiguration);

    var adx =
        new RecoveryAdxRepository(
            adxFactory.client(),
            adxConfiguration.database(),
            adxConfiguration.queryTimeoutSeconds());

    var flows =
        new RecoveryFlowRepository(
            postgres.dataSource(),
            settings.postgresQueryTimeoutSeconds());

    var discovery =
        RecoveryPublicationComponentsFactory.create(
            tables,
            storage,
            flows,
            adx,
            settings.publication(),
            settings.discoveryPageSize());

    var states =
        new RecoveryJobWorkerStateService(
            jobs, Clock.systemUTC());

    var initializer =
        new RecoveryWorkerLockBlobInitializer(lockBlob);

    var leases =
        new RecoveryWorkerLeaseRepository(lockBlob);

    var worker =
        new RecoveryDryRunDayWorker(
            jobs,
            states,
            discovery,
            () -> {
              initializer.ensureExists();

              return RecoveryWorkerLeaseSession.tryAcquire(
                  leases, renewals);
            });

    return new RecoveryDryRunDispatcher(
        runnableJobs, worker, settings.jobPageSize());
  }

  private static ScheduledThreadPoolExecutor createRenewalScheduler() {
    var scheduler =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              var thread =
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
                  setting, Integer.toString(defaultValue)));
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
      int postgresQueryTimeoutSeconds,
      RecoveryPublicationConfiguration publication) {
  }
}