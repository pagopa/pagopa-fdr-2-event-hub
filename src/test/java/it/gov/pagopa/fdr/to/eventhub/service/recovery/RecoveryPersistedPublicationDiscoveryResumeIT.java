package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryCheckpointTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryItemRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryItemTableMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_PUBLICATION_PERSISTENCE_INTEGRATION_TESTS",
    matches = "true")
class RecoveryPersistedPublicationDiscoveryResumeIT {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final int PAGE_SIZE = 2;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldResumeWithoutRecomputingSavedPublications(
      boolean partialPage) throws Exception {

    TableClient client =
        RecoveryTableClients.fromEnvironment().items();

    // Validate the destination before any storage operation.
    assertEquals("pagopadweufdrsa", client.getAccountName());
    assertEquals("fdrrecoveryitems", client.getTableName());

    UUID jobId = UUID.randomUUID();

    System.out.printf(
        "DEV publication resume test started: "
            + "jobId=%s, partialPage=%s%n",
        jobId, partialPage);

    var itemMapper =
        new RecoveryPublicationDiscoveryItemTableMapper();

    var items =
        new RecoveryPublicationDiscoveryItemRepository(
            client, itemMapper);

    var checkpoints =
        new RecoveryPublicationDiscoveryCheckpointRepository(
            client,
            new RecoveryPublicationDiscoveryCheckpointTableMapper());

    var flows = mock(RecoveryFlowRepository.class);
    var batch = mock(RecoveryPublicationBatchService.class);
    var ownership = mock(Runnable.class);

    var first = candidate(10L);
    var second = candidate(20L);
    var third = candidate(30L);

    var firstPage = List.of(first, second);
    var secondPage = List.of(third);

    when(flows.findPage(DAY, 0L, PAGE_SIZE))
        .thenReturn(firstPage);

    when(flows.findPage(DAY, 20L, PAGE_SIZE))
        .thenReturn(secondPage);

    when(flows.findPage(DAY, 30L, PAGE_SIZE))
        .thenReturn(List.of());

    var firstResult =
        compared(
            first,
            RecoveryPublicationSource.SourceType.FDR3_JSON,
            RecoveryPublicationPresenceIndex.Presence.ABSENT,
            0L);

    var secondResult =
        compared(
            second,
            RecoveryPublicationSource.SourceType.FDR1_XML,
            RecoveryPublicationPresenceIndex.Presence.PRESENT_UNVERIFIED,
            4L);

    var thirdResult = unresolved(third);

    when(batch.compare(DAY, firstPage))
        .thenReturn(List.of(firstResult, secondResult));

    if (partialPage) {
      when(batch.compare(DAY, List.of(second)))
          .thenReturn(List.of(secondResult));
    }

    when(batch.compare(DAY, secondPage))
        .thenReturn(List.of(thirdResult));

    var interruptedItems = spy(items);
    var interruptedCheckpoints = spy(checkpoints);

    var failure =
        new IllegalStateException(
            partialPage
                ? "Simulated failure before second item save"
                : "Simulated failure before checkpoint update");

    if (partialPage) {
      doThrow(failure)
          .when(interruptedItems)
          .createOrRead(
              argThat(item -> item.candidate().flowId() == 20L));
    } else {
      doThrow(failure)
          .when(interruptedCheckpoints)
          .update(
              any(RecoveryPublicationDiscoveryCheckpoint.class),
              anyString());
    }

    var interruptedPageService =
        new RecoveryPublicationPageService(
            interruptedItems,
            batch,
            new RecoveryPublicationDiscoveryItemFactory());

    var interruptedService =
        new RecoveryPersistedPublicationDiscoveryService(
            flows,
            interruptedPageService,
            interruptedCheckpoints,
            PAGE_SIZE);

    var actual =
        assertThrows(
            IllegalStateException.class,
            () ->
                interruptedService.discover(
                    jobId, DAY, () -> true, ownership));

    assertSame(failure, actual);

    var beforeCheckpoint =
        checkpoints.find(jobId, DAY).orElseThrow();

    assertEquals(
        RecoveryPublicationDiscoveryCheckpoint.start(jobId, DAY),
        beforeCheckpoint.checkpoint());

    var beforeEntities = readFlowEntities(client, jobId);

    assertEquals(partialPage ? 1 : 2, beforeEntities.size());

    Map<String, String> originalEtags =
        beforeEntities.stream()
            .collect(
                Collectors.toMap(
                    TableEntity::getRowKey,
                    TableEntity::getETag));

    verify(flows, never()).findPage(DAY, 20L, PAGE_SIZE);

    // Resume with the real repositories, without fault injection.
    var resumedPageService =
        new RecoveryPublicationPageService(
            items,
            batch,
            new RecoveryPublicationDiscoveryItemFactory());

    var resumedService =
        new RecoveryPersistedPublicationDiscoveryService(
            flows, resumedPageService, checkpoints, PAGE_SIZE);

    var completed =
        resumedService.discover(
            jobId, DAY, () -> true, ownership);

    assertEquals(
        RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED,
        completed.status());

    assertEquals(30L, completed.lastFlowId());
    assertEquals(3L, completed.candidateCount());
    assertEquals(1L, completed.absentCandidateCount());
    assertEquals(1L, completed.presentCandidateCount());
    assertEquals(1L, completed.unresolvedCandidateCount());
    assertEquals(2L, completed.pageCount());

    assertEquals(
        completed,
        checkpoints.find(jobId, DAY).orElseThrow().checkpoint());

    var afterEntities = readFlowEntities(client, jobId);

    assertEquals(3, afterEntities.size());

    var factory = new RecoveryPublicationDiscoveryItemFactory();

    Map<Long, RecoveryPublicationDiscoveryItem> expected =
        Map.of(
            10L, factory.create(jobId, DAY, firstResult),
            20L, factory.create(jobId, DAY, secondResult),
            30L, factory.create(jobId, DAY, thirdResult));

    for (var entity : afterEntities) {
      var stored = itemMapper.toItem(entity);

      assertEquals(
          expected.get(stored.candidate().flowId()),
          stored);

      assertNotNull(entity.getETag());

      String originalEtag = originalEtags.get(entity.getRowKey());

      if (originalEtag != null) {
        assertEquals(
            originalEtag,
            entity.getETag(),
            "An already saved item must not be overwritten");
      }
    }

    // The already saved first item is never rediscovered.
    verify(batch, times(1)).compare(DAY, firstPage);
    verify(batch, never()).compare(DAY, List.of(first));

    if (partialPage) {
      verify(batch, times(1)).compare(DAY, List.of(second));
    } else {
      verify(batch, never()).compare(DAY, List.of(second));
    }

    verify(batch, times(1)).compare(DAY, secondPage);
    verify(flows, times(2)).findPage(DAY, 0L, PAGE_SIZE);

    System.out.printf(
        "DEV publication resume verified: "
            + "jobId=%s, partialPage=%s, "
            + "itemsBefore=%d, itemsAfter=%d, "
            + "absent=%d, present=%d, unresolved=%d, pages=%d, "
            + "savedItemsUnchanged=true%n",
        jobId,
        partialPage,
        beforeEntities.size(),
        afterEntities.size(),
        completed.absentCandidateCount(),
        completed.presentCandidateCount(),
        completed.unresolvedCandidateCount(),
        completed.pageCount());
  }

  private static List<TableEntity> readFlowEntities(
      TableClient client, UUID jobId) {

    var options =
        new ListEntitiesOptions()
            .setFilter(
                "PartitionKey eq '" + jobId + "_" + DAY + "'");

    List<TableEntity> entities = new ArrayList<>();

    for (var entity :
        client.listEntities(
            options, Duration.ofSeconds(30), Context.NONE)) {

      if (entity.getRowKey().startsWith("FLOW_")) {
        entities.add(entity);
      }
    }

    return List.copyOf(entities);
  }

  private static RecoveryPublicationBatchService.Compared compared(
      RecoveryFlowCandidate candidate,
      RecoveryPublicationSource.SourceType type,
      RecoveryPublicationPresenceIndex.Presence presence,
      long rows) {

    var source =
        new RecoveryPublicationSource(
            candidate,
            new RecoveryBlobSnapshot(
                type.containerName(),
                "fixture-original-" + candidate.flowId(),
                "\"fixture-etag\"",
                100L,
                Map.of("elaborate", "true")),
            type,
            new UUID(0L, candidate.flowId()).toString(),
            Instant.parse("2026-10-05T10:31:35.208475643Z"));

    return new RecoveryPublicationBatchService.Compared(
        new RecoveryPublicationPresenceIndex.Comparison(
            DAY, source, presence, rows));
  }

  private static RecoveryPublicationBatchService.Unresolved unresolved(
      RecoveryFlowCandidate candidate) {

    var evidence =
        new RecoveryPublicationSourceInspector.Inspection(
            candidate,
            new RecoveryFdr3SourceVerifier.BlobMissing(candidate),
            new RecoveryFdr1SourceResolver.Unresolved(
                RecoveryFdr1SourceResolver.UnresolvedReason.NO_CANDIDATES,
                List.of()));

    return new RecoveryPublicationBatchService.Unresolved(
        new RecoveryPublicationSourceSelector.Unresolved(
            RecoveryPublicationSourceSelector.UnresolvedReason
                .ORIGIN_UNDETERMINED,
            evidence));
  }

  private static RecoveryFlowCandidate candidate(long id) {
    return new RecoveryFlowCandidate(
        id,
        "TEST-PIDM2543-PUBLICATION-RESUME-" + id,
        1L,
        "88888888888",
        "77777777777",
        LocalDateTime.parse("2026-10-05T10:31:35"));
  }
}