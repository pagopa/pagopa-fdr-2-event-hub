package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.data.tables.models.TableEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import java.util.Objects;

public final class RecoveryJobTableMapper {

  public static final String ROW_KEY = "JOB";

  private static final String SCHEMA_VERSION = "1";

  private static final JsonMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public TableEntity toEntity(RecoveryJob job) {
    Objects.requireNonNull(job, "job is required");

    try {
      return new TableEntity(job.jobId().toString(), ROW_KEY)
          .addProperty("SchemaVersion", SCHEMA_VERSION)
          .addProperty("Status", job.status().name())
          .addProperty("JobJson", OBJECT_MAPPER.writeValueAsString(job));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize recovery job", e);
    }
  }

  public RecoveryJob toJob(TableEntity entity) {
    Objects.requireNonNull(entity, "entity is required");

    if (!ROW_KEY.equals(entity.getRowKey())) {
      throw new IllegalArgumentException("Invalid recovery job row key");
    }

    if (!SCHEMA_VERSION.equals(entity.getProperty("SchemaVersion"))) {
      throw new IllegalArgumentException("Unsupported recovery job schema version");
    }

    Object payload = entity.getProperty("JobJson");

    if (!(payload instanceof String json) || json.isBlank()) {
      throw new IllegalArgumentException("Missing recovery job JSON");
    }

    final RecoveryJob job;
    try {
      job = OBJECT_MAPPER.readValue(json, RecoveryJob.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid stored recovery job JSON", e);
    }

    if (job == null) {
      throw new IllegalArgumentException("Stored recovery job must not be null");
    }

    if (!job.jobId().toString().equals(entity.getPartitionKey())) {
      throw new IllegalArgumentException(
          "Stored recovery job ID does not match partition key");
    }

    if (!job.status().name().equals(entity.getProperty("Status"))) {
      throw new IllegalArgumentException(
          "Stored recovery job status does not match entity status");
    }

    return job;
  }
}