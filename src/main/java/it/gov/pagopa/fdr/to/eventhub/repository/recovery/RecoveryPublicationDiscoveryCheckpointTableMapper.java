package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryPublicationDiscoveryCheckpointTableMapper {

  private static final String SCHEMA_VERSION = "SchemaVersion";
  private static final String CURRENT_SCHEMA_VERSION = "2";

  private static final String DISCOVERY_MODE = "DiscoveryMode";
  private static final String PUBLICATION_MODE = "PUBLICATION";

  private static final String JOB_ID = "JobId";
  private static final String DAY = "Day";
  private static final String LAST_FLOW_ID = "LastFlowId";
  private static final String CANDIDATE_COUNT = "CandidateCount";
  private static final String ABSENT_COUNT = "AbsentCandidateCount";
  private static final String PRESENT_COUNT = "PresentCandidateCount";
  private static final String UNRESOLVED_COUNT = "UnresolvedCandidateCount";
  private static final String PAGE_COUNT = "PageCount";
  private static final String STATUS = "Status";

  public TableEntity toEntity(
      RecoveryPublicationDiscoveryCheckpoint checkpoint) {

    Objects.requireNonNull(checkpoint, "checkpoint is required");

    return new TableEntity(
            checkpoint.partitionKey(),
            checkpoint.rowKey())
        .addProperty(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        .addProperty(DISCOVERY_MODE, PUBLICATION_MODE)
        .addProperty(JOB_ID, checkpoint.jobId().toString())
        .addProperty(DAY, checkpoint.day().toString())
        .addProperty(LAST_FLOW_ID, checkpoint.lastFlowId())
        .addProperty(CANDIDATE_COUNT, checkpoint.candidateCount())
        .addProperty(ABSENT_COUNT, checkpoint.absentCandidateCount())
        .addProperty(PRESENT_COUNT, checkpoint.presentCandidateCount())
        .addProperty(UNRESOLVED_COUNT, checkpoint.unresolvedCandidateCount())
        .addProperty(PAGE_COUNT, checkpoint.pageCount())
        .addProperty(STATUS, checkpoint.status().name());
  }

  public RecoveryPublicationDiscoveryCheckpoint toCheckpoint(
      TableEntity entity) {

    Objects.requireNonNull(entity, "entity is required");

    if (!CURRENT_SCHEMA_VERSION.equals(
        entity.getProperties().get(SCHEMA_VERSION))) {
      throw new IllegalArgumentException(
          "Unsupported publication checkpoint schema version");
    }

    if (!PUBLICATION_MODE.equals(
        entity.getProperties().get(DISCOVERY_MODE))) {
      throw new IllegalArgumentException(
          "Unsupported checkpoint discovery mode");
    }

    var checkpoint =
        new RecoveryPublicationDiscoveryCheckpoint(
            UUID.fromString(requiredString(entity, JOB_ID)),
            LocalDate.parse(requiredString(entity, DAY)),
            requiredLong(entity, LAST_FLOW_ID),
            requiredLong(entity, CANDIDATE_COUNT),
            requiredLong(entity, ABSENT_COUNT),
            requiredLong(entity, PRESENT_COUNT),
            requiredLong(entity, UNRESOLVED_COUNT),
            requiredLong(entity, PAGE_COUNT),
            RecoveryPublicationDiscoveryCheckpoint.Status.valueOf(
                requiredString(entity, STATUS)));

    if (!checkpoint.partitionKey().equals(entity.getPartitionKey())
        || !checkpoint.rowKey().equals(entity.getRowKey())) {
      throw new IllegalArgumentException(
          "Publication checkpoint storage keys do not match its content");
    }

    return checkpoint;
  }

  private static String requiredString(
      TableEntity entity, String name) {

    Object value = entity.getProperties().get(name);

    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(
          name + " must be a non-blank string");
    }

    return text;
  }

  private static long requiredLong(
      TableEntity entity, String name) {

    Object value = entity.getProperties().get(name);

    if (!(value instanceof Long number)) {
      throw new IllegalArgumentException(
          name + " must be an Int64");
    }

    return number;
  }
}