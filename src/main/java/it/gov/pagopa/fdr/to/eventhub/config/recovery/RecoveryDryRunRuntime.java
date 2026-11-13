package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryRunnableJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryWorkerLeaseRepository;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDayWorker;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDryRunDispatcher;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobWorkerStateService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryWorkerLeaseSession;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

public final class RecoveryDryRunRuntime implements AutoCloseable {

  private final RecoveryDryRunDispatcher dispatcher;
  private final RecoveryPostgresDataSource postgres;
  private final ScheduledExecutorService renewals;
  private final EventHubProducerClient producer;

  private final ReentrantReadWriteLock lifecycle =
      new ReentrantReadWriteLock(true);

  private boolean closed;

  RecoveryDryRunRuntime(
      RecoveryDryRunDispatcher dispatcher,
      RecoveryPostgresDataSource postgres,
      ScheduledExecutorService renewals) {

    this(dispatcher, postgres, renewals, null);
  }

  RecoveryDryRunRuntime(
      RecoveryDryRunDispatcher dispatcher,
      RecoveryPostgresDataSource postgres,
      ScheduledExecutorService renewals,
      EventHubProducerClient producer) {

    this.dispatcher =
        Objects.requireNonNull(dispatcher, "dispatcher is required");

    this.postgres =
        Objects.requireNonNull(postgres, "postgres is required");

    this.renewals =
        Objects.requireNonNull(renewals, "renewals is required");

    this.producer = producer;
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
            RecoveryPublicationConfiguration.fromEnvironment(environment),
            RecoveryExecutionConfiguration.fromEnvironment(environment));

    var postgresConfiguration =
        RecoveryPostgresConfiguration.fromEnvironment(environment);

    var adxConfiguration =
        RecoveryAdxConfiguration.fromEnvironment(environment);

    var tables = RecoveryTableClients.fromEnvironment(environment);

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

    EventHubProducerClient producer = null;

    try {
      if (settings.execution().enabled()) {
        producer =
            CommonUtil.createEventHubClient(
                environment.get(
                    RecoveryExecutionConfiguration
                        .CONNECTION_STRING_SETTING),
                environment.get(
                    RecoveryExecutionConfiguration.EVENT_HUB_SETTING));
      }

      var resources =
          new OwnedResources(postgres, renewals, producer);

      var dispatcher =
          createDispatcher(
              tables,
              adxConfiguration,
              storage,
              lockBlob,
              resources,
              settings);

      return new RecoveryDryRunRuntime(
          dispatcher, postgres, renewals, producer);

    } catch (RuntimeException failure) {
      try {
        closeResources(postgres, renewals, producer);

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
      closeResources(postgres, renewals, producer);

    } finally {
      lifecycle.writeLock().unlock();
    }
  }

  private static RecoveryDryRunDispatcher createDispatcher(
      RecoveryTableClients tables,
      RecoveryAdxConfiguration adxConfiguration,
      BlobServiceClient storage,
      BlobClient lockBlob,
      OwnedResources resources,
      Settings settings) {

    var jobMapper =
        RecoveryJobTableMapper.forPublicationDiscovery();

    var adxFactory =
        new RecoveryAdxClientFactory(adxConfiguration);

    var adx =
        new RecoveryAdxRepository(
            adxFactory.client(),
            adxConfiguration.database(),
            adxConfiguration.queryTimeoutSeconds());

    var flows =
        new RecoveryFlowRepository(
            resources.postgres().dataSource(),
            settings.postgresQueryTimeoutSeconds());

    var components =
        RecoveryPublicationComponentsFactory.createComponents(
            tables,
            storage,
            flows,
            adx,
            settings.publication(),
            settings.discoveryPageSize());

    var jobs = components.jobs();

    var runnableJobs =
        new RecoveryRunnableJobRepository(
            tables.jobs(),
            jobMapper,
            settings.execution().enabled());

    var states =
        new RecoveryJobWorkerStateService(
            jobs, Clock.systemUTC());

    var initializer =
        new RecoveryWorkerLockBlobInitializer(lockBlob);

    var leases =
        new RecoveryWorkerLeaseRepository(lockBlob);

    Supplier<Optional<RecoveryWorkerLeaseSession>> leaseAcquirer =
        () -> {
          initializer.ensureExists();

          return RecoveryWorkerLeaseSession.tryAcquire(
              leases, resources.renewals());
        };

    final RecoveryDryRunDayWorker worker;

    if (settings.execution().enabled()) {
      var execution =
          RecoveryExecutionComponentsFactory.create(
              tables,
              storage,
              adx,
              settings.publication(),
              settings.discoveryPageSize(),
              components,
              resources.producer());

      worker =
          new RecoveryDryRunDayWorker(
              jobs,
              states,
              components.discovery(),
              execution,
              leaseAcquirer);

    } else {
      worker =
          new RecoveryDryRunDayWorker(
              jobs,
              states,
              components.discovery(),
              leaseAcquirer);
    }

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
      ScheduledExecutorService renewals,
      EventHubProducerClient producer) {

    try {
      renewals.shutdownNow();

    } finally {
      try {
        if (producer != null) {
          producer.close();
        }

      } finally {
        postgres.close();
      }
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
      RecoveryPublicationConfiguration publication,
      RecoveryExecutionConfiguration execution) {
  }

  private record OwnedResources(
      RecoveryPostgresDataSource postgres,
      ScheduledExecutorService renewals,
      EventHubProducerClient producer) {
  }
}