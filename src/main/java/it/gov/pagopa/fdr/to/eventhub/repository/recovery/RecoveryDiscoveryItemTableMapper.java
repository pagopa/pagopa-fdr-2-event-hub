package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFlowPresenceIndex;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryDiscoveryItemTableMapper {

  private static final String SCHEMA_VERSION = "SchemaVersion";
  private static final String CURRENT_SCHEMA_VERSION = "1";

  private static final String JOB_ID = "JobId";
  private static final String DAY = "Day";
  private static final String FLOW_ID = "FlowId";
  private static final String FLOW_NAME = "FlowName";
  private static final String REVISION = "Revision";
  private static final String PSP_DOMAIN_ID = "PspDomainId";
  private static final String ORG_DOMAIN_ID = "OrgDomainId";
  private static final String PRESENCE = "Presence";
  private static final String PRESENT_ROWS = "PresentRows";

  public TableEntity toEntity(RecoveryDiscoveryItem item) {
    Objects.requireNonNull(item, "item is required");

    RecoveryFlowCandidate candidate = item.candidate();

    requireText(candidate.name(), FLOW_NAME);
    requireText(candidate.pspDomainId(), PSP_DOMAIN_ID);
    requireText(candidate.orgDomainId(), ORG_DOMAIN_ID);

    return new TableEntity(item.partitionKey(), item.rowKey())
        .addProperty(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        .addProperty(JOB_ID, item.jobId().toString())
        .addProperty(DAY, item.day().toString())
        .addProperty(FLOW_ID, candidate.flowId())
        .addProperty(FLOW_NAME, candidate.name())
        .addProperty(REVISION, candidate.revision())
        .addProperty(PSP_DOMAIN_ID, candidate.pspDomainId())
        .addProperty(ORG_DOMAIN_ID, candidate.orgDomainId())
        .addProperty(PRESENCE, item.presence().name())
        .addProperty(PRESENT_ROWS, item.presentRows());
  }

  public RecoveryDiscoveryItem toItem(TableEntity entity) {
    Objects.requireNonNull(entity, "entity is required");

    if (!CURRENT_SCHEMA_VERSION.equals(
        entity.getProperties().get(SCHEMA_VERSION))) {
      throw new IllegalArgumentException(
          "Unsupported discovery item schema version");
    }

    UUID jobId = UUID.fromString(requiredString(entity, JOB_ID));
    LocalDate day = LocalDate.parse(requiredString(entity, DAY));

    RecoveryFlowCandidate candidate =
        new RecoveryFlowCandidate(
            requiredLong(entity, FLOW_ID),
            requiredString(entity, FLOW_NAME),
            requiredLong(entity, REVISION),
            requiredString(entity, PSP_DOMAIN_ID),
            requiredString(entity, ORG_DOMAIN_ID));

    RecoveryFlowPresenceIndex.Presence presence =
        RecoveryFlowPresenceIndex.Presence.valueOf(
            requiredString(entity, PRESENCE));

    RecoveryDiscoveryItem item =
        new RecoveryDiscoveryItem(
            jobId,
            day,
            candidate,
            presence,
            requiredLong(entity, PRESENT_ROWS));

    if (!item.partitionKey().equals(entity.getPartitionKey())
        || !item.rowKey().equals(entity.getRowKey())) {
      throw new IllegalArgumentException(
          "Discovery item storage keys do not match its content");
    }

    return item;
  }

  private static String requiredString(
      TableEntity entity,
      String property) {

    Object value = entity.getProperties().get(property);

    if (!(value instanceof String text)) {
      throw new IllegalArgumentException(
          property + " must be a non-blank string");
    }

    requireText(text, property);
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

  private static void requireText(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          property + " must be a non-blank string");
    }
  }
}