package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.data.tables.models.TableEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.UnsupportedRecoveryJobFormatException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import java.util.Objects;
import java.util.Optional;

public final class RecoveryJobTableMapper {

  public static final String ROW_KEY = "JOB";

  private static final String SCHEMA_VERSION = "SchemaVersion";
  private static final String DISCOVERY_MODE = "DiscoveryMode";
  private static final String STATUS = "Status";
  private static final String JOB_JSON = "JobJson";

  private static final String LEGACY_SCHEMA_VERSION = "1";
  private static final String PUBLICATION_SCHEMA_VERSION = "2";
  private static final String PUBLICATION_MODE = "PUBLICATION";

  private static final JsonMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final boolean publicationDiscovery;

  public RecoveryJobTableMapper() {
    this(false);
  }

  private RecoveryJobTableMapper(boolean publicationDiscovery) {
    this.publicationDiscovery = publicationDiscovery;
  }

  public static RecoveryJobTableMapper forPublicationDiscovery() {
    return new RecoveryJobTableMapper(true);
  }

  public String schemaVersion() {
    return publicationDiscovery
        ? PUBLICATION_SCHEMA_VERSION
        : LEGACY_SCHEMA_VERSION;
  }

  public Optional<String> discoveryMode() {
    return publicationDiscovery
        ? Optional.of(PUBLICATION_MODE)
        : Optional.empty();
  }

  public TableEntity toEntity(RecoveryJob job) {
    Objects.requireNonNull(job, "job is required");

    try {
      var entity =
          new TableEntity(job.jobId().toString(), ROW_KEY)
              .addProperty(SCHEMA_VERSION, schemaVersion())
              .addProperty(STATUS, job.status().name())
              .addProperty(
                  JOB_JSON,
                  OBJECT_MAPPER.writeValueAsString(job));

      if (publicationDiscovery) {
        entity.addProperty(DISCOVERY_MODE, PUBLICATION_MODE);
      }

      return entity;
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Cannot serialize recovery job", exception);
    }
  }

  public RecoveryJob toJob(TableEntity entity) {
    Objects.requireNonNull(entity, "entity is required");

    if (!ROW_KEY.equals(entity.getRowKey())) {
      throw new IllegalArgumentException(
          "Invalid recovery job row key");
    }

    if (!schemaVersion().equals(entity.getProperty(SCHEMA_VERSION))) {
        throw new UnsupportedRecoveryJobFormatException("Unsupported recovery job schema version");
    }

    validateDiscoveryMode(entity);

    Object payload = entity.getProperty(JOB_JSON);

    if (!(payload instanceof String json) || json.isBlank()) {
      throw new IllegalArgumentException(
          "Missing recovery job JSON");
    }

    final RecoveryJob job;

    try {
      job = OBJECT_MAPPER.readValue(json, RecoveryJob.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Invalid stored recovery job JSON", exception);
    }

    if (job == null) {
      throw new IllegalArgumentException(
          "Stored recovery job must not be null");
    }

    if (!job.jobId().toString().equals(entity.getPartitionKey())) {
      throw new IllegalArgumentException(
          "Stored recovery job ID does not match partition key");
    }

    if (!job.status().name().equals(entity.getProperty(STATUS))) {
      throw new IllegalArgumentException(
          "Stored recovery job status does not match entity status");
    }

    return job;
  }

  private void validateDiscoveryMode(TableEntity entity) {
    Object mode = entity.getProperty(DISCOVERY_MODE);

    if (publicationDiscovery) {
      if (!PUBLICATION_MODE.equals(mode)) {
          throw new UnsupportedRecoveryJobFormatException("Unsupported recovery job discovery mode");
      }
    } else if (mode != null) {
        throw new UnsupportedRecoveryJobFormatException("Legacy recovery job must not declare a discovery mode");
    }
  }
}