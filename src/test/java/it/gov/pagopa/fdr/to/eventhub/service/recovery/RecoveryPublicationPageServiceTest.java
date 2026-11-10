package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryItemRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryPublicationPageServiceTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryPublicationDiscoveryItemRepository repository =
      mock(RecoveryPublicationDiscoveryItemRepository.class);

  private final RecoveryPublicationBatchService batchService =
      mock(RecoveryPublicationBatchService.class);

  private final RecoveryPublicationDiscoveryItemFactory factory =
      new RecoveryPublicationDiscoveryItemFactory();

  private final Runnable ownership = mock(Runnable.class);

  private final RecoveryPublicationPageService service =
      new RecoveryPublicationPageService(
          repository, batchService, factory);

  private final RecoveryFlowCandidate first = candidate(1L);
  private final RecoveryFlowCandidate second = candidate(2L);
  private final RecoveryFlowCandidate third = candidate(3L);

  @Test
  void shouldDiscoverOnlyMissingCandidatesAndPreserveOrder()
      throws Exception {

    var page = List.of(first, second, third);
    var stored = storedItem(second);

    when(repository.findForPage(JOB_ID, DAY, page))
        .thenReturn(Map.of(second.flowId(), stored));

    when(batchService.compare(DAY, List.of(first, third)))
        .thenReturn(List.of(batchResult(first), batchResult(third)));

    var firstItem = computedItem(first);
    var thirdItem = computedItem(third);

    when(repository.createOrRead(firstItem))
        .thenReturn(created(firstItem));

    when(repository.createOrRead(thirdItem))
        .thenReturn(created(thirdItem));

    var result = service.process(JOB_ID, DAY, page, ownership);

    assertEquals(List.of(firstItem, stored, thirdItem), result);

    verify(batchService).compare(DAY, List.of(first, third));
  }

  @Test
  void shouldReuseEntireSavedPageWithoutDiscovery() throws Exception {
    var page = List.of(first, second);
    var firstItem = storedItem(first);
    var secondItem = storedItem(second);

    when(repository.findForPage(JOB_ID, DAY, page))
        .thenReturn(
            Map.of(
                first.flowId(), firstItem,
                second.flowId(), secondItem));

    var result = service.process(JOB_ID, DAY, page, ownership);

    assertEquals(List.of(firstItem, secondItem), result);
    verifyNoInteractions(batchService);
  }

  @Test
  void shouldSkipDependenciesForEmptyPage() throws Exception {
    var result =
        service.process(JOB_ID, DAY, List.of(), ownership);

    assertTrue(result.isEmpty());
    verifyNoInteractions(repository, batchService, ownership);
  }

  @Test
  void shouldUseStoredDecisionReturnedAfterConcurrentCreation()
      throws Exception {

    var page = List.of(first);
    var requested = computedItem(first);
    var existing = storedItem(first);

    when(repository.findForPage(JOB_ID, DAY, page))
        .thenReturn(Map.of());

    when(batchService.compare(DAY, page))
        .thenReturn(List.of(batchResult(first)));

    when(repository.createOrRead(requested))
        .thenReturn(
            new RecoveryPublicationDiscoveryItemRepository.SaveResult(
                RecoveryPublicationDiscoveryItemRepository.SaveStatus
                    .ALREADY_PRESENT,
                existing));

    var result = service.process(JOB_ID, DAY, page, ownership);

    assertEquals(List.of(existing), result);
  }

  @Test
  void shouldRejectIncompleteBatchBeforeSavingAnyItem()
      throws Exception {

    var page = List.of(first, second);

    when(repository.findForPage(JOB_ID, DAY, page))
        .thenReturn(Map.of());

    when(batchService.compare(DAY, page))
        .thenReturn(List.of(batchResult(first)));

    assertThrows(
        IllegalStateException.class,
        () -> service.process(JOB_ID, DAY, page, ownership));

    verify(repository, times(0)).createOrRead(computedItem(first));
    verify(repository, times(0)).createOrRead(computedItem(second));
  }

  @Test
  void shouldResumeAfterPartialSaveWithoutRediscoveringSavedItem()
      throws Exception {

    var page = List.of(first, second);
    var firstItem = computedItem(first);
    var secondItem = computedItem(second);

    var failure = new IllegalStateException("Simulated storage failure");

    when(repository.findForPage(JOB_ID, DAY, page))
        .thenReturn(Map.of())
        .thenReturn(Map.of(first.flowId(), firstItem));

    when(batchService.compare(DAY, page))
        .thenReturn(List.of(batchResult(first), batchResult(second)));

    when(batchService.compare(DAY, List.of(second)))
        .thenReturn(List.of(batchResult(second)));

    when(repository.createOrRead(firstItem))
        .thenReturn(created(firstItem));

    when(repository.createOrRead(secondItem))
        .thenThrow(failure)
        .thenReturn(created(secondItem));

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.process(JOB_ID, DAY, page, ownership));

    assertSame(failure, actual);

    var resumed = service.process(JOB_ID, DAY, page, ownership);

    assertEquals(List.of(firstItem, secondItem), resumed);

    verify(batchService).compare(DAY, page);
    verify(batchService).compare(DAY, List.of(second));

    verify(repository, times(1)).createOrRead(firstItem);
    verify(repository, times(2)).createOrRead(secondItem);
  }

  @Test
  void shouldStopWhenOwnershipIsLostAfterReadingSavedItems()
      throws Exception {

    var page = List.of(first);
    var failure = new IllegalStateException("Lease ownership lost");

    when(repository.findForPage(JOB_ID, DAY, page))
        .thenReturn(Map.of());

    doNothing()
        .doThrow(failure)
        .when(ownership)
        .run();

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> service.process(JOB_ID, DAY, page, ownership));

    assertSame(failure, actual);
    verifyNoInteractions(batchService);
  }

  private RecoveryPublicationDiscoveryItem computedItem(
      RecoveryFlowCandidate candidate) {

    return factory.create(JOB_ID, DAY, batchResult(candidate));
  }

  private static RecoveryPublicationDiscoveryItem storedItem(
      RecoveryFlowCandidate candidate) {

    return new RecoveryPublicationDiscoveryItem(
        JOB_ID,
        DAY,
        candidate,
        new RecoveryPublicationDiscoveryItem.Unresolved(
            "ORIGIN_UNDETERMINED",
            Map.of("savedDecision", "true")));
  }

  private static RecoveryPublicationDiscoveryItemRepository.SaveResult created(
      RecoveryPublicationDiscoveryItem item) {

    return new RecoveryPublicationDiscoveryItemRepository.SaveResult(
        RecoveryPublicationDiscoveryItemRepository.SaveStatus.CREATED,
        item);
  }

  private static RecoveryPublicationBatchService.Unresolved batchResult(
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

  private static RecoveryFlowCandidate candidate(long flowId) {
    return new RecoveryFlowCandidate(
        flowId,
        "flow-1",
        1L,
        "psp-1",
        "org-1",
        LocalDateTime.parse("2026-10-05T10:31:35"));
  }
}