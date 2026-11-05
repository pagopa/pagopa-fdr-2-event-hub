package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponse;
import com.azure.core.http.rest.PagedResponseBase;
import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryRunnableJobRepositoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static final LocalDate DAY =
      LocalDate.of(2026, 10, 1);

  private static final Instant CREATED_AT =
      Instant.parse("2026-11-04T09:00:00Z");

  private final TableClient client = mock(TableClient.class);

  @SuppressWarnings("unchecked")
  private final PagedIterable<TableEntity> results =
      mock(PagedIterable.class);

  private final RecoveryJobTableMapper mapper =
      new RecoveryJobTableMapper();

  private final RecoveryRunnableJobRepository repository =
      new RecoveryRunnableJobRepository(client, mapper);

  @BeforeEach
  void setUp() {
    when(client.listEntities(
            any(ListEntitiesOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenReturn(results);
  }

  private RecoveryJob job(
      RecoveryJobStatus status,
      boolean dryRun) {

    LocalDate nextDate =
        status == RecoveryJobStatus.COMPLETED
            ? DAY.plusDays(1)
            : DAY;

    return new RecoveryJob(
        UUID.randomUUID(),
        DAY,
        DAY,
        dryRun,
        status,
        nextDate,
        CREATED_AT,
        CREATED_AT);
  }

  private PagedResponse<TableEntity> page(
      List<TableEntity> entities,
      String nextToken) {

    return new PagedResponseBase<Void, TableEntity>(
        null,
        200,
        new HttpHeaders(),
        entities,
        nextToken,
        null);
  }

  private void stubPage(
      String token,
      int size,
      List<TableEntity> entities,
      String nextToken) {

    when(results.iterableByPage(token, size))
        .thenReturn(List.of(page(entities, nextToken)));
  }

  @Test
  void shouldReturnRunnableDryRunJobsAndApplyServerFilter() {
    RecoveryJob pending = job(RecoveryJobStatus.PENDING, true);
    RecoveryJob running = job(RecoveryJobStatus.RUNNING, true);
    RecoveryJob pausing = job(RecoveryJobStatus.PAUSING, true);

    stubPage(
        null,
        10,
        List.of(
            mapper.toEntity(pending),
            mapper.toEntity(running),
            mapper.toEntity(pausing)),
        null);

    RecoveryRunnableJobRepository.JobPage result =
        repository.findPage(null, 10);

    assertEquals(
        List.of(pending.jobId(), running.jobId(), pausing.jobId()),
        result.jobIds());

    assertNull(result.continuationToken());

    ArgumentCaptor<ListEntitiesOptions> captor =
        ArgumentCaptor.forClass(ListEntitiesOptions.class);

    verify(client).listEntities(
        captor.capture(),
        eq(TIMEOUT),
        eq(Context.NONE));

    assertEquals(
        "RowKey eq 'JOB' and "
            + "(Status eq 'PENDING' or Status eq 'RUNNING' "
            + "or Status eq 'PAUSING')",
        captor.getValue().getFilter());

    assertEquals(Integer.valueOf(10), captor.getValue().getTop());
  }

  @Test
  void shouldExcludeExecutionJobsWithoutLosingContinuationToken() {
    RecoveryJob executionJob =
        job(RecoveryJobStatus.PENDING, false);

    stubPage(
        null,
        2,
        List.of(mapper.toEntity(executionJob)),
        "next-page");

    RecoveryRunnableJobRepository.JobPage result =
        repository.findPage(null, 2);

    assertTrue(result.jobIds().isEmpty());
    assertEquals("next-page", result.continuationToken());
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = {"PAUSED", "COMPLETED", "FAILED"})
  void shouldDefensivelyExcludeInactiveJobs(
      RecoveryJobStatus status) {

    RecoveryJob inactive = job(status, true);

    stubPage(
        null,
        2,
        List.of(mapper.toEntity(inactive)),
        null);

    RecoveryRunnableJobRepository.JobPage result =
        repository.findPage(null, 2);

    assertTrue(result.jobIds().isEmpty());
  }

  @Test
  void shouldForwardContinuationTokenUnchanged() {
    stubPage("opaque-token", 2, List.of(), "following-token");

    RecoveryRunnableJobRepository.JobPage result =
        repository.findPage("opaque-token", 2);

    assertEquals("following-token", result.continuationToken());

    verify(results).iterableByPage("opaque-token", 2);
  }

  @Test
  void shouldPreserveTokenForEmptyStoragePage() {
    stubPage(null, 2, List.of(), "next-page");

    RecoveryRunnableJobRepository.JobPage result =
        repository.findPage(null, 2);

    assertTrue(result.jobIds().isEmpty());
    assertEquals("next-page", result.continuationToken());
  }

  @Test
  void shouldReturnTerminalPageWhenNoPagesAreAvailable() {
    List<PagedResponse<TableEntity>> noPages = List.of();

    when(results.iterableByPage(null, 2))
        .thenReturn(noPages);

    RecoveryRunnableJobRepository.JobPage result =
        repository.findPage(null, 2);

    assertTrue(result.jobIds().isEmpty());
    assertNull(result.continuationToken());
  }

  @Test
  void shouldReadOnlyOneStoragePage() {
    RecoveryJob firstJob = job(RecoveryJobStatus.PENDING, true);

    TableEntity corruptedSecondPage =
        new TableEntity("invalid", "JOB");

    List<PagedResponse<TableEntity>> pages =
        List.of(
            page(List.of(mapper.toEntity(firstJob)), "next-page"),
            page(List.of(corruptedSecondPage), null));

    when(results.iterableByPage(null, 2))
        .thenReturn(pages);

    RecoveryRunnableJobRepository.JobPage result =
        repository.findPage(null, 2);

    assertEquals(List.of(firstJob.jobId()), result.jobIds());
    assertEquals("next-page", result.continuationToken());
  }

  @Test
  void shouldRejectCorruptedStoredJob() {
    RecoveryJob job = job(RecoveryJobStatus.PENDING, true);

    TableEntity corrupted =
        mapper.toEntity(job).addProperty("JobJson", "{}");

    stubPage(null, 2, List.of(corrupted), null);

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findPage(null, 2));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 101})
  void shouldRejectInvalidPageSizeBeforeAccessingStorage(int size) {
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findPage(null, size));

    verifyNoInteractions(client, results);
  }

  @Test
  void shouldPropagateStorageFailure() {
    IllegalStateException failure =
        new IllegalStateException("Simulated storage failure");

    when(client.listEntities(
            any(ListEntitiesOptions.class),
            eq(TIMEOUT),
            eq(Context.NONE)))
        .thenThrow(failure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> repository.findPage(null, 2));

    assertSame(failure, thrown);
  }

  @Test
  void shouldRejectPageLargerThanRequested() {
    RecoveryJob first = job(RecoveryJobStatus.PENDING, true);
    RecoveryJob second = job(RecoveryJobStatus.RUNNING, true);

    stubPage(
        null,
        1,
        List.of(mapper.toEntity(first), mapper.toEntity(second)),
        null);

    assertThrows(
        IllegalStateException.class,
        () -> repository.findPage(null, 1));
  }
}