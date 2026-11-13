package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryDiscoveryCheckpointTableMapper {

  private static final String SCHEMA_VERSION = "SchemaVersion";
  private static final String CURRENT_SCHEMA_VERSION = "1";

  private static final String JOB_ID = "JobId";
  private static final String DAY = "Day";
  private static final String LAST_FLOW_ID = "LastFlowId";
  private static final String CANDIDATE_COUNT = "CandidateCount";
  private static final String ABSENT_COUNT = "AbsentCandidateCount";
  private static final String PRESENT_COUNT = "PresentCandidateCount";
  private static final String PAGE_COUNT = "PageCount";
  private static final String STATUS = "Status";

  public TableEntity toEntity(
      RecoveryDiscoveryCheckpoint checkpoint) {

    Objects.requireNonNull(checkpoint, "checkpoint is required");

    return new TableEntity(
            checkpoint.partitionKey(),
            checkpoint.rowKey())
        .addProperty(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        .addProperty(JOB_ID, checkpoint.jobId().toString())
        .addProperty(DAY, checkpoint.day().toString())
        .addProperty(LAST_FLOW_ID, checkpoint.lastFlowId())
        .addProperty(CANDIDATE_COUNT, checkpoint.candidateCount())
        .addProperty(ABSENT_COUNT, checkpoint.absentCandidateCount())
        .addProperty(PRESENT_COUNT, checkpoint.presentCandidateCount())
        .addProperty(PAGE_COUNT, checkpoint.pageCount())
        .addProperty(STATUS, checkpoint.status().name());
  }

  public RecoveryDiscoveryCheckpoint toCheckpoint(
      TableEntity entity) {

    Objects.requireNonNull(entity, "entity is required");

    if (!CURRENT_SCHEMA_VERSION.equals(
        entity.getProperties().get(SCHEMA_VERSION))) {
      throw new IllegalArgumentException(
          "Unsupported discovery checkpoint schema version");
    }

    RecoveryDiscoveryCheckpoint checkpoint =
        new RecoveryDiscoveryCheckpoint(
            UUID.fromString(requiredString(entity, JOB_ID)),
            LocalDate.parse(requiredString(entity, DAY)),
            requiredLong(entity, LAST_FLOW_ID),
            requiredLong(entity, CANDIDATE_COUNT),
            requiredLong(entity, ABSENT_COUNT),
            requiredLong(entity, PRESENT_COUNT),
            requiredLong(entity, PAGE_COUNT),
            RecoveryDiscoveryCheckpoint.Status.valueOf(
                requiredString(entity, STATUS)));

    if (!checkpoint.partitionKey().equals(entity.getPartitionKey())
        || !checkpoint.rowKey().equals(entity.getRowKey())) {
      throw new IllegalArgumentException(
          "Discovery checkpoint storage keys do not match its content");
    }

    return checkpoint;
  }

  private static String requiredString(
      TableEntity entity,
      String property) {

    Object value = entity.getProperties().get(property);

    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(
          property + " must be a non-blank string");
    }

    return text;
  }

  private static long requiredLong(
      TableEntity entity,
      String property) {

    Object value = entity.getProperties().get(property);

    if (!(value instanceof Long number)) {
      throw new IllegalArgumentException(
          property + " must be an Int64 value");
    }

    return number;
  }
}