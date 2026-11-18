package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.data.tables.models.TableEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationExecution;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryPublicationExecutionTableMapper {

  private static final String SCHEMA_VERSION = "SchemaVersion";
  private static final String RECORD_TYPE = "RecordType";
  private static final String CURRENT_VERSION = "1";
  private static final String EXECUTION_TYPE = "PUBLICATION_EXECUTION";

  private static final String JOB_ID = "JobId";
  private static final String DAY = "Day";
  private static final String SOURCE_JSON = "SourceJson";
  private static final String STATUS = "Status";
  private static final String ATTEMPT_ID = "AttemptId";
  private static final String EVENTS = "AcknowledgedEvents";
  private static final String BATCHES = "AcknowledgedBatches";
  private static final String CREATED_AT = "CreatedAt";
  private static final String UPDATED_AT = "UpdatedAt";

  private static final int MAX_STRING_LENGTH = 32_768;

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .build();

  public TableEntity toEntity(
      RecoveryPublicationExecution execution) {

    Objects.requireNonNull(execution, "execution is required");

    var entity =
        new TableEntity(
            execution.partitionKey(),
            execution.rowKey())
            .addProperty(SCHEMA_VERSION, CURRENT_VERSION)
            .addProperty(RECORD_TYPE, EXECUTION_TYPE)
            .addProperty(JOB_ID, execution.jobId().toString())
            .addProperty(DAY, execution.day().toString())
            .addProperty(SOURCE_JSON, serializeSource(execution.source()))
            .addProperty(STATUS, execution.status().name())
            .addProperty(CREATED_AT, execution.createdAt().toString())
            .addProperty(UPDATED_AT, execution.updatedAt().toString());

    if (execution.attemptId() != null) {
      entity.addProperty(
          ATTEMPT_ID, execution.attemptId().toString());
    }

    if (execution.receipt() != null) {
      entity.addProperty(
          EVENTS, execution.receipt().acknowledgedEvents());

      entity.addProperty(
          BATCHES, execution.receipt().acknowledgedBatches());
    }

    return entity;
  }

  public RecoveryPublicationExecution toExecution(
      TableEntity entity) {

    Objects.requireNonNull(entity, "entity is required");

    if (!CURRENT_VERSION.equals(
        entity.getProperties().get(SCHEMA_VERSION))
        || !EXECUTION_TYPE.equals(
            entity.getProperties().get(RECORD_TYPE))) {

      throw new IllegalArgumentException(
          "Unsupported publication execution format");
    }

    var status =
        RecoveryPublicationExecution.Status.valueOf(
            requiredString(entity, STATUS));

    UUID attemptId =
        entity.getProperties().containsKey(ATTEMPT_ID)
            ? UUID.fromString(requiredString(entity, ATTEMPT_ID))
            : null;

    var execution =
        new RecoveryPublicationExecution(
            UUID.fromString(requiredString(entity, JOB_ID)),
            LocalDate.parse(requiredString(entity, DAY)),
            deserializeSource(requiredString(entity, SOURCE_JSON)),
            status,
            attemptId,
            readReceipt(entity, status),
            Instant.parse(requiredString(entity, CREATED_AT)),
            Instant.parse(requiredString(entity, UPDATED_AT)));

    if (!execution.partitionKey().equals(entity.getPartitionKey())
        || !execution.rowKey().equals(entity.getRowKey())) {

      throw new IllegalArgumentException(
          "Execution storage keys do not match its content");
    }

    return execution;
  }

  private static RecoveryPublicationExecution.SendReceipt readReceipt(
      TableEntity entity,
      RecoveryPublicationExecution.Status status) {

    if (status == RecoveryPublicationExecution.Status.SENT) {
      return new RecoveryPublicationExecution.SendReceipt(
          requiredLong(entity, EVENTS),
          requiredLong(entity, BATCHES));
    }

    if (entity.getProperties().containsKey(EVENTS)
        || entity.getProperties().containsKey(BATCHES)) {

      throw new IllegalArgumentException(
          "Only SENT executions may contain acknowledgement counters");
    }

    return null;
  }

  private static String serializeSource(
      RecoveryPublicationSource source) {

    try {
      String json = MAPPER.writeValueAsString(source);
      validateLength(json);
      return json;

    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Cannot serialize publication execution source", exception);
    }
  }

  private static RecoveryPublicationSource deserializeSource(
      String json) {

    try {
      var source =
          MAPPER.readValue(json, RecoveryPublicationSource.class);

      if (source == null) {
        throw new IllegalArgumentException(
            "Publication execution source must not be null");
      }

      return source;

    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Invalid publication execution source", exception);
    }
  }

  private static String requiredString(
      TableEntity entity,
      String property) {

    Object value = entity.getProperties().get(property);

    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(
          property + " must be a non-blank string");
    }

    validateLength(text);
    return text;
  }

  private static long requiredLong(
      TableEntity entity,
      String property) {

    Object value = entity.getProperties().get(property);

    if (!(value instanceof Long number)) {
      throw new IllegalArgumentException(
          property + " must be an Int64");
    }

    return number;
  }

  private static void validateLength(String text) {
    if (text.length() > MAX_STRING_LENGTH) {
      throw new IllegalArgumentException(
          "Execution property exceeds the supported string length");
    }
  }
}