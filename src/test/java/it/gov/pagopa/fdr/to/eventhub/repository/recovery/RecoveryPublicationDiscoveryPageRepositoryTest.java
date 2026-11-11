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
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class RecoveryPublicationDiscoveryPageRepositoryTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 5);

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final TableClient client = mock(TableClient.class);

  private final RecoveryPublicationDiscoveryItemTableMapper mapper =
      new RecoveryPublicationDiscoveryItemTableMapper();

  private final RecoveryPublicationDiscoveryPageRepository repository =
      new RecoveryPublicationDiscoveryPageRepository(client, mapper);

  @Test
  void shouldReadPageUsingPartitionAndExclusiveCursor() {
    var first = item(11L, RecoveryPublicationDiscoveryItem.Status.ABSENT);
    var second =
        item(12L, RecoveryPublicationDiscoveryItem.Status.UNRESOLVED);

    stubRows(List.of(mapper.toEntity(first), mapper.toEntity(second)));

    var result = repository.findPage(JOB_ID, DAY, 10L, 2);

    assertEquals(List.of(first, second), result);

    var captured = ArgumentCaptor.forClass(ListEntitiesOptions.class);

    verify(client).listEntities(
        captured.capture(),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(
        "PartitionKey eq '" + JOB_ID + "_2026-10-05'"
            + " and RowKey gt 'FLOW_00000000000000000010'"
            + " and RowKey le 'FLOW_09223372036854775807'",
        captured.getValue().getFilter());

    assertEquals(Integer.valueOf(2), captured.getValue().getTop());

    assertThrows(UnsupportedOperationException.class, result::clear);
  }

  @Test
  void shouldReturnAllDiscoveryCategories() {
    var absent =
        item(1L, RecoveryPublicationDiscoveryItem.Status.ABSENT);

    var present =
        item(
            2L,
            RecoveryPublicationDiscoveryItem.Status.PRESENT_UNVERIFIED);

    var unresolved =
        item(3L, RecoveryPublicationDiscoveryItem.Status.UNRESOLVED);

    stubRows(
        List.of(
            mapper.toEntity(absent),
            mapper.toEntity(present),
            mapper.toEntity(unresolved)));

    assertEquals(
        List.of(absent, present, unresolved),
        repository.findPage(JOB_ID, DAY, 0L, 10));
  }

  @Test
  void shouldFollowSdkPagesUntilApplicationPageIsFull() {
    var first = item(1L, RecoveryPublicationDiscoveryItem.Status.ABSENT);
    var second = item(2L, RecoveryPublicationDiscoveryItem.Status.ABSENT);
    var third = item(3L, RecoveryPublicationDiscoveryItem.Status.ABSENT);

    var nextCalls = new AtomicInteger();

    PagedIterable<TableEntity> entities =
        new PagedIterable<>(
            () -> page(List.of(mapper.toEntity(first)), "next"),
            token -> {
              nextCalls.incrementAndGet();

              return page(
                  List.of(
                      mapper.toEntity(second),
                      mapper.toEntity(third)),
                  null);
            });

    stubIterable(entities);

    assertEquals(
        List.of(first, second),
        repository.findPage(JOB_ID, DAY, 0L, 2));

    assertEquals(1, nextCalls.get());
  }

  @Test
  void shouldNotFetchAnotherSdkPageWhenLimitIsReached() {
    var first = item(1L, RecoveryPublicationDiscoveryItem.Status.ABSENT);
    var nextCalls = new AtomicInteger();

    PagedIterable<TableEntity> entities =
        new PagedIterable<>(
            () -> page(List.of(mapper.toEntity(first)), "next"),
            token -> {
              nextCalls.incrementAndGet();
              return page(List.of(), null);
            });

    stubIterable(entities);

    assertEquals(
        List.of(first),
        repository.findPage(JOB_ID, DAY, 0L, 1));

    assertEquals(0, nextCalls.get());
  }

  @Test
  void shouldFollowContinuationAfterEmptySdkPage() {
    var item = item(1L, RecoveryPublicationDiscoveryItem.Status.ABSENT);

    PagedIterable<TableEntity> entities =
        new PagedIterable<>(
            () -> page(List.of(), "next"),
            token -> page(List.of(mapper.toEntity(item)), null));

    stubIterable(entities);

    assertEquals(
        List.of(item),
        repository.findPage(JOB_ID, DAY, 0L, 10));
  }

  @Test
  void shouldReturnEmptyWhenNoRowsRemain() {
    stubRows(List.of());

    assertTrue(
        repository.findPage(JOB_ID, DAY, 100L, 10).isEmpty());
  }

  @ParameterizedTest
  @CsvSource({
      "-1,10",
      "0,0",
      "0,101"
  })
  void shouldRejectInvalidRequestBeforeStorageAccess(
      long cursor,
      int pageSize) {

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findPage(JOB_ID, DAY, cursor, pageSize));

    verifyNoInteractions(client);
  }

  @ParameterizedTest
  @CsvSource({
      "2,1",
      "1,1"
  })
  void shouldRejectUnorderedOrDuplicateStoredIds(
      long firstId,
      long secondId) {

    stubRows(
        List.of(
            mapper.toEntity(
                item(firstId, RecoveryPublicationDiscoveryItem.Status.ABSENT)),
            mapper.toEntity(
                item(secondId, RecoveryPublicationDiscoveryItem.Status.ABSENT))));

    assertThrows(
        IllegalStateException.class,
        () -> repository.findPage(JOB_ID, DAY, 0L, 10));
  }

  @Test
  void shouldRejectItemAtOrBeforeCursor() {
    stubRows(
        List.of(
            mapper.toEntity(
                item(10L, RecoveryPublicationDiscoveryItem.Status.ABSENT))));

    assertThrows(
        IllegalStateException.class,
        () -> repository.findPage(JOB_ID, DAY, 10L, 10));
  }

  @Test
  void shouldRejectPreviousDiscoverySchema() {
    var entity =
        mapper.toEntity(
            item(1L, RecoveryPublicationDiscoveryItem.Status.ABSENT));

    entity.addProperty("SchemaVersion", "2");
    stubRows(List.of(entity));

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findPage(JOB_ID, DAY, 0L, 10));
  }

  @Test
  void shouldRejectItemOfAnotherJob() {
    var original =
        item(1L, RecoveryPublicationDiscoveryItem.Status.ABSENT);

    var another =
        new RecoveryPublicationDiscoveryItem(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            DAY,
            original.candidate(),
            original.outcome());

    stubRows(List.of(mapper.toEntity(another)));

    assertThrows(
        IllegalStateException.class,
        () -> repository.findPage(JOB_ID, DAY, 0L, 10));
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

    var actual =
        assertThrows(
            TableServiceException.class,
            () -> repository.findPage(JOB_ID, DAY, 0L, 10));

    assertSame(failure, actual);
  }

  private void stubRows(List<TableEntity> rows) {
    stubIterable(new PagedIterable<>(() -> page(rows, null)));
  }

  private void stubIterable(PagedIterable<TableEntity> entities) {
    when(client.listEntities(
            any(ListEntitiesOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenReturn(entities);
  }

  private static PagedResponseBase<Void, TableEntity> page(
      List<TableEntity> rows,
      String continuationToken) {

    return new PagedResponseBase<>(
        null,
        200,
        new HttpHeaders(),
        rows,
        continuationToken,
        null);
  }

  private static RecoveryPublicationDiscoveryItem item(
      long flowId,
      RecoveryPublicationDiscoveryItem.Status status) {

    var candidate =
        new RecoveryFlowCandidate(
            flowId,
            "flow-" + flowId,
            1L,
            "88888888888",
            "15376371009",
            LocalDateTime.parse("2026-10-05T12:50:26.001454"));

    RecoveryPublicationDiscoveryItem.Outcome outcome;

    if (status == RecoveryPublicationDiscoveryItem.Status.UNRESOLVED) {
      outcome =
          new RecoveryPublicationDiscoveryItem.Unresolved(
              "ORIGIN_UNDETERMINED", Map.of());
    } else {
      String sessionId = new UUID(0L, flowId).toString();

      var source =
          new RecoveryPublicationSource(
              candidate,
              new RecoveryBlobSnapshot(
                  "fdr3-flows",
                  "original-" + flowId,
                  "\"original-etag\"",
                  100L,
                  Map.of("sessionId", sessionId)),
              RecoveryPublicationSource.SourceType.FDR3_JSON,
              sessionId,
              Instant.parse("2026-10-05T12:50:26.001454Z"));

      outcome =
          new RecoveryPublicationDiscoveryItem.Compared(
              source,
              status,
              status == RecoveryPublicationDiscoveryItem.Status.ABSENT
                  ? 0L
                  : 1L);
    }

    return new RecoveryPublicationDiscoveryItem(
        JOB_ID, DAY, candidate, outcome);
  }
}