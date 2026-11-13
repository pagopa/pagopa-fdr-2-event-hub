package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponseBase;
import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecoveryPublicationDiscoveryItemRepositoryReadTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final TableClient client = mock(TableClient.class);

  private final RecoveryPublicationDiscoveryItemTableMapper mapper =
      new RecoveryPublicationDiscoveryItemTableMapper();

  private final RecoveryPublicationDiscoveryItemRepository repository =
      new RecoveryPublicationDiscoveryItemRepository(client, mapper);

  @Test
  void shouldSkipStorageForEmptyPage() {
    var result = repository.findForPage(JOB_ID, DAY, List.of());

    assertTrue(result.isEmpty());
    verifyNoInteractions(client);
  }

  @Test
  void shouldReadSavedSubsetWithPartitionAndRowRange() {
    var first = item(candidate(1L));
    var third = item(candidate(3L));

    stubRows(List.of(mapper.toEntity(first), mapper.toEntity(third)));

    var result =
        repository.findForPage(
            JOB_ID,
            DAY,
            List.of(candidate(1L), candidate(2L), candidate(3L)));

    assertEquals(Map.of(1L, first, 3L, third), result);

    var captor = ArgumentCaptor.forClass(ListEntitiesOptions.class);

    verify(client).listEntities(
        captor.capture(),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(
        "PartitionKey eq '" + JOB_ID + "_2026-10-01'"
            + " and RowKey ge 'FLOW_00000000000000000001'"
            + " and RowKey le 'FLOW_00000000000000000003'",
        captor.getValue().getFilter());

    assertEquals(Integer.valueOf(4), captor.getValue().getTop());

    verifyNoMoreInteractions(client);
  }

  @Test
  void shouldAcceptNoSavedItems() {
    stubRows(List.of());

    var result =
        repository.findForPage(
            JOB_ID, DAY, List.of(candidate(1L)));

    assertTrue(result.isEmpty());
  }

  @Test
  void shouldRejectDuplicateReturnedFlowIds() {
    var entity = mapper.toEntity(item(candidate(1L)));

    stubRows(List.of(entity, entity));

    var candidates = List.of(candidate(1L), candidate(2L));

    assertThrows(
        IllegalStateException.class,
        () -> repository.findForPage(JOB_ID, DAY, candidates));
  }

  @Test
  void shouldRejectUnexpectedItemInsideRange() {
    stubRows(List.of(mapper.toEntity(item(candidate(2L)))));

    var candidates = List.of(candidate(1L), candidate(3L));

    assertThrows(
        IllegalStateException.class,
        () -> repository.findForPage(JOB_ID, DAY, candidates));
  }

  @Test
  void shouldRejectChangedCandidateIdentity() {
    var changed =
        new RecoveryFlowCandidate(
            1L,
            "flow-1",
            2L,
            "psp-1",
            "org-1",
            LocalDateTime.parse("2026-10-05T10:31:35"));

    stubRows(List.of(mapper.toEntity(item(changed))));

    var candidates = List.of(candidate(1L));

    assertThrows(
        IllegalStateException.class,
        () -> repository.findForPage(JOB_ID, DAY, candidates));
  }

  @Test
  void shouldRejectPreviousSchema() {
    var entity = mapper.toEntity(item(candidate(1L)));
    entity.addProperty("SchemaVersion", "2");

    stubRows(List.of(entity));

    var candidates = List.of(candidate(1L));

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findForPage(JOB_ID, DAY, candidates));
  }

  @Test
  void shouldRejectUnorderedPageBeforeStorageAccess() {
    var candidates = List.of(candidate(2L), candidate(1L));

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findForPage(JOB_ID, DAY, candidates));

    verifyNoInteractions(client);
  }

  @Test
  void shouldRejectOversizedPageBeforeStorageAccess() {
    var candidates = Collections.nCopies(101, candidate(1L));

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findForPage(JOB_ID, DAY, candidates));

    verifyNoInteractions(client);
  }

  @Test
  void shouldPropagateStorageFailure() {
    var response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(403);

    var failure =
        new TableServiceException(
            "Simulated authorization failure",
            response,
            new TableServiceError(
                "AuthorizationFailure",
                "Simulated authorization failure"));

    when(client.listEntities(
            any(ListEntitiesOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    var candidates = List.of(candidate(1L));

    var actual =
        assertThrows(
            TableServiceException.class,
            () -> repository.findForPage(JOB_ID, DAY, candidates));

    assertSame(failure, actual);
  }

  @Test
  void shouldRejectNullCandidateBeforeStorageAccess() {
    var candidates = Arrays.asList(candidate(1L), null);

    assertThrows(
        NullPointerException.class,
        () -> repository.findForPage(JOB_ID, DAY, candidates));

    verifyNoInteractions(client);
  }

  @Test
  void shouldRejectItemOfAnotherJob() {
    var stored =
        new RecoveryPublicationDiscoveryItem(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            DAY,
            candidate(1L),
            new RecoveryPublicationDiscoveryItem.Unresolved(
                "ORIGIN_UNDETERMINED", Map.of()));

    stubRows(List.of(mapper.toEntity(stored)));

    var candidates = List.of(candidate(1L));

    assertThrows(
        IllegalStateException.class,
        () -> repository.findForPage(JOB_ID, DAY, candidates));
  }

  private void stubRows(List<TableEntity> rows) {
    var page =
        new PagedResponseBase<Void, TableEntity>(
            null,
            200,
            new HttpHeaders(),
            rows,
            null,
            null);

    PagedIterable<TableEntity> entities =
        new PagedIterable<>(() -> page);

    when(client.listEntities(
            any(ListEntitiesOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenReturn(entities);
  }

  private static RecoveryPublicationDiscoveryItem item(
      RecoveryFlowCandidate candidate) {

    return new RecoveryPublicationDiscoveryItem(
        JOB_ID,
        DAY,
        candidate,
        new RecoveryPublicationDiscoveryItem.Unresolved(
            "ORIGIN_UNDETERMINED", Map.of()));
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