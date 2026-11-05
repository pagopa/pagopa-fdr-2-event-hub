package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryJobTableMapperTest {

  private final RecoveryJobTableMapper mapper = new RecoveryJobTableMapper();

  private RecoveryJob newJob() {
    return RecoveryJob.create(
        new RecoveryJobRequest("2026-10-01", "2026-10-31", true),
        Clock.fixed(
            Instant.parse("2026-10-29T15:00:00Z"),
            ZoneOffset.UTC));
  }

  @Test
  void shouldMapJobToEntityAndReadItBack() {
    RecoveryJob job = newJob();

    TableEntity entity = mapper.toEntity(job);

    assertEquals(job.jobId().toString(), entity.getPartitionKey());
    assertEquals("JOB", entity.getRowKey());
    assertEquals("1", entity.getProperty("SchemaVersion"));
    assertEquals("PENDING", entity.getProperty("Status"));
    assertEquals(job, mapper.toJob(entity));
  }

  @Test
  void shouldRejectUnsupportedSchema() {
    TableEntity entity =
        mapper.toEntity(newJob()).addProperty("SchemaVersion", "99");

    assertThrows(IllegalArgumentException.class, () -> mapper.toJob(entity));
  }

  @Test
  void shouldRejectWrongRowKey() {
    TableEntity original = mapper.toEntity(newJob());

    TableEntity entity =
        new TableEntity(original.getPartitionKey(), "OTHER")
            .addProperty("SchemaVersion", "1")
            .addProperty("Status", original.getProperty("Status"))
            .addProperty("JobJson", original.getProperty("JobJson"));

    assertThrows(IllegalArgumentException.class, () -> mapper.toJob(entity));
  }

  @Test
  void shouldRejectMismatchedJobId() {
    TableEntity original = mapper.toEntity(newJob());

    TableEntity entity =
        new TableEntity(UUID.randomUUID().toString(), "JOB")
            .addProperty("SchemaVersion", "1")
            .addProperty("Status", original.getProperty("Status"))
            .addProperty("JobJson", original.getProperty("JobJson"));

    assertThrows(IllegalArgumentException.class, () -> mapper.toJob(entity));
  }

  @Test
  void shouldRejectMismatchedStatus() {
    TableEntity entity =
        mapper.toEntity(newJob()).addProperty("Status", "COMPLETED");

    assertThrows(IllegalArgumentException.class, () -> mapper.toJob(entity));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "invalid-json", "{}", "null"})
  void shouldRejectInvalidPayload(String payload) {
    TableEntity entity =
        mapper.toEntity(newJob()).addProperty("JobJson", payload);

    assertThrows(IllegalArgumentException.class, () -> mapper.toJob(entity));
  }

  @Test
  void shouldRejectMissingPayload() {
    TableEntity entity =
        new TableEntity(UUID.randomUUID().toString(), "JOB")
            .addProperty("SchemaVersion", "1")
            .addProperty("Status", "PENDING");

    assertThrows(IllegalArgumentException.class, () -> mapper.toJob(entity));
  }
}