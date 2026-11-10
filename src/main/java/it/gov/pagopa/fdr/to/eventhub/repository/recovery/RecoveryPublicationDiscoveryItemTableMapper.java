package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.data.tables.models.TableEntity;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public final class RecoveryPublicationDiscoveryItemTableMapper {

  private static final String SCHEMA_VERSION = "SchemaVersion";
  private static final String CURRENT_SCHEMA_VERSION = "3";

  private static final String JOB_ID = "JobId";
  private static final String DAY = "Day";
  private static final String FLOW_ID = "FlowId";
  private static final String FLOW_NAME = "FlowName";
  private static final String REVISION = "Revision";
  private static final String PSP_DOMAIN_ID = "PspDomainId";
  private static final String ORG_DOMAIN_ID = "OrgDomainId";
  private static final String FLOW_DATE = "FlowDate";
  private static final String STATUS = "Status";
  private static final String PRESENT_ROWS = "PresentRows";

  private static final String SOURCE_TYPE = "SourceType";
  private static final String SOURCE_CONTAINER = "SourceContainer";
  private static final String SOURCE_BLOB = "SourceBlob";
  private static final String SOURCE_ETAG = "SourceETag";
  private static final String SOURCE_LENGTH = "SourceLength";
  private static final String SOURCE_METADATA = "SourceMetadata";
  private static final String SESSION_ID = "SessionId";
  private static final String EVENT_FLOW_DATE = "EventFlowDate";

  private static final String UNRESOLVED_REASON = "UnresolvedReason";
  private static final String DIAGNOSTIC_DETAILS = "DiagnosticDetails";

  private static final int MAX_STRING_CHARACTERS = 32_768;

  private static final ObjectMapper JSON =
      new ObjectMapper(
          JsonFactory.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public TableEntity toEntity(RecoveryPublicationDiscoveryItem item) {
    Objects.requireNonNull(item, "item is required");

    var candidate = item.candidate();

    var entity =
        new TableEntity(item.partitionKey(), item.rowKey())
            .addProperty(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .addProperty(JOB_ID, item.jobId().toString())
            .addProperty(DAY, item.day().toString())
            .addProperty(FLOW_ID, candidate.flowId())
            .addProperty(FLOW_NAME, candidate.name())
            .addProperty(REVISION, candidate.revision())
            .addProperty(PSP_DOMAIN_ID, candidate.pspDomainId())
            .addProperty(ORG_DOMAIN_ID, candidate.orgDomainId())
            .addProperty(FLOW_DATE, candidate.flowDate().toString())
            .addProperty(STATUS, item.outcome().status().name());

    if (item.outcome()
        instanceof RecoveryPublicationDiscoveryItem.Compared compared) {

      writeCompared(entity, compared);
    } else {
      var unresolved =
          (RecoveryPublicationDiscoveryItem.Unresolved) item.outcome();

      entity
          .addProperty(UNRESOLVED_REASON, unresolved.reason())
          .addProperty(
              DIAGNOSTIC_DETAILS,
              writeMap(unresolved.details()));
    }

    entity.getProperties().forEach((name, value) -> {
      if (value instanceof String text) {
        validateString(text, name);
      }
    });

    return entity;
  }

  public RecoveryPublicationDiscoveryItem toItem(TableEntity entity) {
    Objects.requireNonNull(entity, "entity is required");

    if (!CURRENT_SCHEMA_VERSION.equals(
        entity.getProperties().get(SCHEMA_VERSION))) {
      throw new IllegalArgumentException(
          "Unsupported publication discovery schema version");
    }

    var candidate =
        new RecoveryFlowCandidate(
            requiredLong(entity, FLOW_ID),
            requiredString(entity, FLOW_NAME),
            requiredLong(entity, REVISION),
            requiredString(entity, PSP_DOMAIN_ID),
            requiredString(entity, ORG_DOMAIN_ID),
            LocalDateTime.parse(requiredString(entity, FLOW_DATE)));

    var status =
        RecoveryPublicationDiscoveryItem.Status.valueOf(
            requiredString(entity, STATUS));

    RecoveryPublicationDiscoveryItem.Outcome outcome;

    if (status == RecoveryPublicationDiscoveryItem.Status.UNRESOLVED) {
      rejectProperties(
          entity,
          PRESENT_ROWS,
          SOURCE_TYPE,
          SOURCE_CONTAINER,
          SOURCE_BLOB,
          SOURCE_ETAG,
          SOURCE_LENGTH,
          SOURCE_METADATA,
          SESSION_ID,
          EVENT_FLOW_DATE);

      outcome =
          new RecoveryPublicationDiscoveryItem.Unresolved(
              requiredString(entity, UNRESOLVED_REASON),
              readMap(requiredString(entity, DIAGNOSTIC_DETAILS)));
    } else {
      rejectProperties(entity, UNRESOLVED_REASON, DIAGNOSTIC_DETAILS);
      outcome = readCompared(entity, candidate, status);
    }

    var item =
        new RecoveryPublicationDiscoveryItem(
            UUID.fromString(requiredString(entity, JOB_ID)),
            LocalDate.parse(requiredString(entity, DAY)),
            candidate,
            outcome);

    if (!item.partitionKey().equals(entity.getPartitionKey())
        || !item.rowKey().equals(entity.getRowKey())) {
      throw new IllegalArgumentException(
          "Publication discovery storage keys do not match its content");
    }

    return item;
  }

  private static void writeCompared(
      TableEntity entity,
      RecoveryPublicationDiscoveryItem.Compared compared) {

    var source = compared.source();
    var blob = source.blob();

    entity
        .addProperty(PRESENT_ROWS, compared.presentRows())
        .addProperty(SOURCE_TYPE, source.sourceType().name())
        .addProperty(SOURCE_CONTAINER, blob.containerName())
        .addProperty(SOURCE_BLOB, blob.blobName())
        .addProperty(SOURCE_ETAG, blob.etag())
        .addProperty(SOURCE_LENGTH, blob.contentLength())
        .addProperty(SOURCE_METADATA, writeMap(blob.metadata()))
        .addProperty(SESSION_ID, source.sessionId())
        .addProperty(EVENT_FLOW_DATE, source.eventFlowDate().toString());
  }

  private static RecoveryPublicationDiscoveryItem.Compared readCompared(
      TableEntity entity,
      RecoveryFlowCandidate candidate,
      RecoveryPublicationDiscoveryItem.Status status) {

    var blob =
        new RecoveryBlobSnapshot(
            requiredString(entity, SOURCE_CONTAINER),
            requiredString(entity, SOURCE_BLOB),
            requiredString(entity, SOURCE_ETAG),
            requiredLong(entity, SOURCE_LENGTH),
            readMap(requiredString(entity, SOURCE_METADATA)));

    var source =
        new RecoveryPublicationSource(
            candidate,
            blob,
            RecoveryPublicationSource.SourceType.valueOf(
                requiredString(entity, SOURCE_TYPE)),
            requiredString(entity, SESSION_ID),
            Instant.parse(requiredString(entity, EVENT_FLOW_DATE)));

    return new RecoveryPublicationDiscoveryItem.Compared(
        source, status, requiredLong(entity, PRESENT_ROWS));
  }

  private static String writeMap(Map<String, String> values) {
    try {
      return JSON.writeValueAsString(new TreeMap<>(values));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Cannot serialize discovery properties", exception);
    }
  }

  private static Map<String, String> readMap(String value) {
    try {
      var root = JSON.readTree(value);

      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException(
            "Discovery properties must be a JSON object");
      }

      Map<String, String> values = new HashMap<>();
      var fields = root.fields();

      while (fields.hasNext()) {
        var field = fields.next();

        if (!field.getValue().isTextual()) {
          throw new IllegalArgumentException(
              "Discovery property values must be strings");
        }

        values.put(field.getKey(), field.getValue().textValue());
      }

      return Map.copyOf(values);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Invalid discovery properties JSON", exception);
    }
  }

  private static String requiredString(
      TableEntity entity, String name) {

    Object value = entity.getProperties().get(name);

    if (!(value instanceof String text)) {
      throw new IllegalArgumentException(name + " must be a string");
    }

    validateString(text, name);
    return text;
  }

  private static void validateString(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }

    if (value.length() > MAX_STRING_CHARACTERS) {
      throw new IllegalArgumentException(
          name + " exceeds the supported string size");
    }
  }

  private static long requiredLong(
      TableEntity entity, String name) {

    Object value = entity.getProperties().get(name);

    if (!(value instanceof Long number)) {
      throw new IllegalArgumentException(name + " must be an Int64");
    }

    return number;
  }

  private static void rejectProperties(
      TableEntity entity, String... names) {

    for (String name : names) {
      if (entity.getProperties().containsKey(name)) {
        throw new IllegalArgumentException(
            "Unexpected property for discovery status: " + name);
      }
    }
  }
}