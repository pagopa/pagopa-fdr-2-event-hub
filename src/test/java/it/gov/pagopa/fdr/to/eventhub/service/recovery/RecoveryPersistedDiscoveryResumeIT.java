package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryTableClients;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxFlowPresence;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryBatchRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryItemTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryDiscoveryCheckpoint;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_PERSISTENCE_INTEGRATION_TESTS",
    matches = "true")
class RecoveryPersistedDiscoveryResumeIT {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final String PSP = "88888888888";
  private static final String ORGANIZATION = "77777777777";
  private static final int PAGE_SIZE = 2;

  @Test
  void shouldResumeAfterPageWasSavedButCheckpointUpdateFailed()
      throws Exception {

    TableClient itemsClient =
        RecoveryTableClients.fromEnvironment().items();

    // Restrict actual writes to the expected DEV destination.
    assertEquals("pagopadweufdrsa", itemsClient.getAccountName());
    assertEquals("fdrrecoveryitems", itemsClient.getTableName());

    UUID jobId = UUID.randomUUID();

    System.out.printf(
        "DEV resume test started: jobId=%s, day=%s%n",
        jobId,
        DAY);

    RecoveryDiscoveryItemTableMapper itemMapper =
        new RecoveryDiscoveryItemTableMapper();

    RecoveryDiscoveryBatchRepository batches =
        new RecoveryDiscoveryBatchRepository(itemsClient, itemMapper);

    RecoveryDiscoveryCheckpointRepository checkpoints =
        new RecoveryDiscoveryCheckpointRepository(
            itemsClient,
            new RecoveryDiscoveryCheckpointTableMapper());

    RecoveryFlowRepository flows =
        mock(RecoveryFlowRepository.class);

    RecoveryAdxRepository adx =
        mock(RecoveryAdxRepository.class);

    RecoveryFlowCandidate first = candidate(10L);
    RecoveryFlowCandidate second = candidate(20L);
    RecoveryFlowCandidate third = candidate(30L);

    when(flows.findPage(DAY, 0L, PAGE_SIZE))
        .thenReturn(List.of(first, second));

    when(flows.findPage(DAY, 20L, PAGE_SIZE))
        .thenReturn(List.of(third));

    when(flows.findPage(DAY, 30L, PAGE_SIZE))
        .thenReturn(List.of());

    when(adx.findByDay(DAY))
        .thenReturn(
            List.of(
                new RecoveryAdxFlowPresence(
                    second.name(),
                    PSP,
                    ORGANIZATION,
                    1L)));

    // Creation and reads are real; only checkpoint updates are interrupted.
    RecoveryDiscoveryCheckpointRepository interruptedCheckpoints =
        spy(checkpoints);

    IllegalStateException simulatedFailure =
        new IllegalStateException(
            "Simulated interruption before checkpoint update");

    doThrow(simulatedFailure)
        .when(interruptedCheckpoints)
        .update(
            any(RecoveryDiscoveryCheckpoint.class),
            anyString());

    RecoveryPersistedDiscoveryService interruptedService =
        new RecoveryPersistedDiscoveryService(
            adx,
            flows,
            batches,
            interruptedCheckpoints,
            PAGE_SIZE);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> interruptedService.discover(jobId, DAY));

    assertSame(simulatedFailure, thrown);

    StoredRecoveryDiscoveryCheckpoint beforeResume =
        checkpoints.find(jobId, DAY).orElseThrow();

    assertEquals(
        RecoveryDiscoveryCheckpoint.start(jobId, DAY),
        beforeResume.checkpoint());

    List<TableEntity> savedBeforeResume =
        readFlowEntities(itemsClient, jobId);

    assertEquals(2, savedBeforeResume.size());

    Map<String, String> originalEtags =
        savedBeforeResume.stream()
            .collect(
                Collectors.toMap(
                    TableEntity::getRowKey,
                    TableEntity::getETag));

    verify(flows, never()).findPage(DAY, 20L, PAGE_SIZE);

    // Resume using the real checkpoint repository.
    RecoveryPersistedDiscoveryService resumedService =
        new RecoveryPersistedDiscoveryService(
            adx,
            flows,
            batches,
            checkpoints,
            PAGE_SIZE);

    RecoveryDiscoveryCheckpoint completed =
        resumedService.discover(jobId, DAY);

    assertCompletedCheckpoint(completed);

    StoredRecoveryDiscoveryCheckpoint persisted =
        checkpoints.find(jobId, DAY).orElseThrow();

    assertEquals(completed, persisted.checkpoint());

    List<TableEntity> savedAfterResume =
        readFlowEntities(itemsClient, jobId);

    assertEquals(3, savedAfterResume.size());

    long savedAbsent = 0L;
    long savedPresent = 0L;

    for (TableEntity entity : savedAfterResume) {
      var item = itemMapper.toItem(entity);

      assertEquals(jobId, item.jobId());
      assertEquals(DAY, item.day());

      switch (item.presence()) {
        case ABSENT -> savedAbsent++;
        case PRESENT_UNVERIFIED -> savedPresent++;
      }

      String originalEtag = originalEtags.get(entity.getRowKey());

      if (originalEtag != null) {
        assertEquals(
            originalEtag,
            entity.getETag(),
            "An already saved discovery item must not be overwritten");
      }
    }

    assertEquals(2L, savedAbsent);
    assertEquals(1L, savedPresent);

    verify(flows, times(2)).findPage(DAY, 0L, PAGE_SIZE);
    verify(flows, times(1)).findPage(DAY, 20L, PAGE_SIZE);
    verify(flows, times(1)).findPage(DAY, 30L, PAGE_SIZE);

    // One ADX extraction for each execution, including the resumed one.
    verify(adx, times(2)).findByDay(DAY);

    System.out.printf(
        "DEV resume test completed: jobId=%s, "
            + "itemsBeforeResume=%d, itemsAfterResume=%d, "
            + "candidates=%d, absent=%d, presentUnverified=%d, "
            + "pages=%d, existingItemsUnchanged=true%n",
        jobId,
        savedBeforeResume.size(),
        savedAfterResume.size(),
        completed.candidateCount(),
        completed.absentCandidateCount(),
        completed.presentCandidateCount(),
        completed.pageCount());
  }

  private RecoveryFlowCandidate candidate(long id) {
    return new RecoveryFlowCandidate(
        id,
        "TEST-PIDM2543-RESUME-" + id,
        1L,
        PSP,
        ORGANIZATION);
  }

  private List<TableEntity> readFlowEntities(
      TableClient client,
      UUID jobId) {

    String partitionKey = jobId + "_" + DAY;

    ListEntitiesOptions options =
        new ListEntitiesOptions()
            .setFilter("PartitionKey eq '" + partitionKey + "'");

    List<TableEntity> entities = new ArrayList<>();

    for (TableEntity entity :
        client.listEntities(
            options,
            Duration.ofSeconds(30),
            Context.NONE)) {

      if (!"DISCOVERY".equals(entity.getRowKey())) {
        entities.add(entity);
      }
    }

    return List.copyOf(entities);
  }

  private void assertCompletedCheckpoint(RecoveryDiscoveryCheckpoint completed) {

      assertEquals(RecoveryDiscoveryCheckpoint.Status.DISCOVERED, completed.status());

      assertEquals(30L, completed.lastFlowId());
      assertEquals(3L, completed.candidateCount());
      assertEquals(2L, completed.absentCandidateCount());
      assertEquals(1L, completed.presentCandidateCount());
      assertEquals(2L, completed.pageCount());
  }
}