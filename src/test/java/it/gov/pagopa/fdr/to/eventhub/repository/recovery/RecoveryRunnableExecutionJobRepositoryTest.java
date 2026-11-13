package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.rest.PagedIterable;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryRunnableExecutionJobRepositoryTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private final TableClient client = mock(TableClient.class);

  private final RecoveryJobTableMapper mapper =
      RecoveryJobTableMapper.forPublicationDiscovery();

  @ParameterizedTest
  @CsvSource({
      "true,false,true",
      "false,false,false",
      "true,true,true",
      "false,true,true"
  })
  void shouldSelectJobsAccordingToExecutionEnablement(
      boolean dryRun,
      boolean enabled,
      boolean expected) {

    stubJob(job(RecoveryJobStatus.PENDING, dryRun));

    var repository =
        new RecoveryRunnableJobRepository(client, mapper, enabled);

    var result = repository.findPage(null, 10);

    assertEquals(
        expected ? List.of(JOB_ID) : List.of(),
        result.jobIds());
  }

  @ParameterizedTest
  @EnumSource(RecoveryJobStatus.class)
  void shouldSelectOnlyActiveExecutionJobs(
      RecoveryJobStatus status) {

    stubJob(job(status, false));

    var repository =
        new RecoveryRunnableJobRepository(client, mapper, true);

    boolean expected =
        Set.of(
                RecoveryJobStatus.PENDING,
                RecoveryJobStatus.RUNNING,
                RecoveryJobStatus.PAUSING)
            .contains(status);

    assertEquals(
        expected ? List.of(JOB_ID) : List.of(),
        repository.findPage(null, 10).jobIds());
  }

  @Test
  void shouldRejectExecutionForLegacyJobFormat() {
    var legacyMapper = new RecoveryJobTableMapper();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecoveryRunnableJobRepository(
                client, legacyMapper, true));

    verifyNoInteractions(client);
  }

  private void stubJob(RecoveryJob job) {
    TableEntity entity = mapper.toEntity(job);

    var page =
        new PagedResponseBase<Void, TableEntity>(
            null,
            200,
            new HttpHeaders(),
            List.of(entity),
            null,
            null);

    PagedIterable<TableEntity> entities =
        new PagedIterable<>(() -> page);

    when(client.listEntities(
            any(ListEntitiesOptions.class),
            eq(Duration.ofSeconds(30)),
            eq(Context.NONE)))
        .thenReturn(entities);
  }

  private static RecoveryJob job(
      RecoveryJobStatus status,
      boolean dryRun) {

    var day = LocalDate.of(2026, 10, 5);
    var now = Instant.parse("2026-11-11T12:00:00Z");

    return new RecoveryJob(
        JOB_ID,
        day,
        day,
        dryRun,
        status,
        status == RecoveryJobStatus.COMPLETED
            ? day.plusDays(1)
            : day,
        now,
        now);
  }
}