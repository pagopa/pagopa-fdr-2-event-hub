package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.zaxxer.hikari.HikariConfig;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxClientFactory;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxConfiguration;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryPostgresConfiguration;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryPostgresDataSource;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryTableClients;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryBatchRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryItemTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryDiscoveryCheckpoint;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_PERSISTENCE_INTEGRATION_TESTS",
    matches = "true")
class RecoveryPersistedDiscoveryServiceIT {

  @Test
  void shouldPersistDailyDiscoveryAndSkipCompletedRun() throws Exception {
    String expectedAccount =
        System.getenv("RECOVERY_TEST_STORAGE_ACCOUNT");

    String configuredDay =
        System.getenv("RECOVERY_DISCOVERY_TEST_DAY");

    assertNotNull(
        expectedAccount,
        "RECOVERY_TEST_STORAGE_ACCOUNT must identify the DEV storage account");

    assertNotNull(
        configuredDay,
        "RECOVERY_DISCOVERY_TEST_DAY must contain the test day");

    LocalDate day = LocalDate.parse(configuredDay);

    RecoveryAdxConfiguration adxConfiguration =
        RecoveryAdxConfiguration.fromEnvironment();

    HikariConfig postgresConfiguration =
        RecoveryPostgresConfiguration.fromEnvironment();

    assertEquals(
        "https://pagopaddataexplorer.westeurope.kusto.windows.net",
        adxConfiguration.clusterUrl());

    assertEquals("re", adxConfiguration.database());

    assertEquals(
        RecoveryAdxConfiguration.AuthMode.AZURE_CLI,
        adxConfiguration.authMode());

    assertEquals(
        "jdbc:postgresql://fdr-db.d.internal.postgresql.pagopa.it:5432/fdr3",
        postgresConfiguration.getJdbcUrl());

    RecoveryTableClients tableClients =
        RecoveryTableClients.fromEnvironment();

    TableClient itemsClient = tableClients.items();

    assertEquals(expectedAccount, itemsClient.getAccountName());
    assertEquals("fdrrecoveryitems", itemsClient.getTableName());

    RecoveryDiscoveryItemTableMapper itemMapper =
        new RecoveryDiscoveryItemTableMapper();

    RecoveryDiscoveryCheckpointRepository checkpoints =
        new RecoveryDiscoveryCheckpointRepository(
            itemsClient,
            new RecoveryDiscoveryCheckpointTableMapper());

    RecoveryDiscoveryBatchRepository batches =
        new RecoveryDiscoveryBatchRepository(itemsClient, itemMapper);

    RecoveryAdxClientFactory adxFactory =
        new RecoveryAdxClientFactory(adxConfiguration);

    RecoveryAdxRepository adx =
        new RecoveryAdxRepository(
            adxFactory.client(),
            adxConfiguration.database(),
            adxConfiguration.queryTimeoutSeconds());

    UUID jobId = UUID.randomUUID();

    System.out.printf(
        "DEV persistence test started: jobId=%s, day=%s, account=%s%n",
        jobId,
        day,
        expectedAccount);

    try (RecoveryPostgresDataSource postgres =
        new RecoveryPostgresDataSource(postgresConfiguration)) {

      RecoveryFlowRepository flows =
          new RecoveryFlowRepository(postgres.dataSource(), 60);

      RecoveryPersistedDiscoveryService service =
          new RecoveryPersistedDiscoveryService(
              adx, flows, batches, checkpoints, 100);

      RecoveryDiscoveryCheckpoint completed =
          service.discover(jobId, day);

      assertEquals(
          RecoveryDiscoveryCheckpoint.Status.DISCOVERED,
          completed.status());

      StoredRecoveryDiscoveryCheckpoint persisted =
          checkpoints.find(jobId, day).orElseThrow();

      assertEquals(completed, persisted.checkpoint());

      ListEntitiesOptions options =
          new ListEntitiesOptions()
              .setFilter(
                  "PartitionKey eq '" + completed.partitionKey() + "'");

      long savedCandidates = 0L;
      long savedAbsent = 0L;
      long savedPresent = 0L;

      for (TableEntity entity :
          itemsClient.listEntities(
              options,
              Duration.ofSeconds(30),
              Context.NONE)) {

        if (completed.rowKey().equals(entity.getRowKey())) {
          continue;
        }

        RecoveryDiscoveryItem item = itemMapper.toItem(entity);

        assertEquals(jobId, item.jobId());
        assertEquals(day, item.day());

        savedCandidates++;

        switch (item.presence()) {
          case ABSENT -> savedAbsent++;
          case PRESENT_UNVERIFIED -> savedPresent++;
        }
      }

      assertEquals(completed.candidateCount(), savedCandidates);
      assertEquals(completed.absentCandidateCount(), savedAbsent);
      assertEquals(completed.presentCandidateCount(), savedPresent);

      // The second execution must use only the persisted checkpoint.
      RecoveryAdxRepository unusedAdx =
          mock(RecoveryAdxRepository.class);

      RecoveryFlowRepository unusedFlows =
          mock(RecoveryFlowRepository.class);

      RecoveryDiscoveryBatchRepository unusedBatches =
          mock(RecoveryDiscoveryBatchRepository.class);

      RecoveryPersistedDiscoveryService secondService =
          new RecoveryPersistedDiscoveryService(
              unusedAdx,
              unusedFlows,
              unusedBatches,
              checkpoints,
              100);

      RecoveryDiscoveryCheckpoint repeated =
          secondService.discover(jobId, day);

      assertEquals(completed, repeated);

      verifyNoInteractions(unusedAdx, unusedFlows, unusedBatches);

      StoredRecoveryDiscoveryCheckpoint afterRepeat =
          checkpoints.find(jobId, day).orElseThrow();

      assertEquals(persisted.etag(), afterRepeat.etag());

      System.out.printf(
          "DEV persistence test completed: jobId=%s, day=%s, "
              + "candidates=%d, absent=%d, presentUnverified=%d, "
              + "pages=%d, repeatedRunSkipped=true%n",
          jobId,
          day,
          completed.candidateCount(),
          completed.absentCandidateCount(),
          completed.presentCandidateCount(),
          completed.pageCount());
    }
  }
}