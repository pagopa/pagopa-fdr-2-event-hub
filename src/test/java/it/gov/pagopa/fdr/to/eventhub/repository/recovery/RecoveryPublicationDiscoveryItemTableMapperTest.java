package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryPublicationDiscoveryItemTableMapperTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryPublicationDiscoveryItemTableMapper mapper =
      new RecoveryPublicationDiscoveryItemTableMapper();

  @ParameterizedTest
  @CsvSource({
      "FDR3_JSON,ABSENT,0",
      "FDR3_JSON,PRESENT_UNVERIFIED,4",
      "FDR1_XML,ABSENT,0",
      "FDR1_XML,PRESENT_UNVERIFIED,4"
  })
  void shouldRoundTripComparedItem(
      RecoveryPublicationSource.SourceType type,
      RecoveryPublicationDiscoveryItem.Status status,
      long rows) {

    var item = comparedItem(type, status, rows);
    var entity = mapper.toEntity(item);

    assertEquals("3", entity.getProperties().get("SchemaVersion"));
    assertEquals(item, mapper.toItem(entity));
  }

  @Test
  void shouldRoundTripUnresolvedItem() {
    var item = unresolvedItem();

    assertEquals(item, mapper.toItem(mapper.toEntity(item)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "2"})
  void shouldRejectPreviousSchema(String version) {
    var entity = comparedEntity();
    entity.addProperty("SchemaVersion", version);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @Test
  void shouldRejectInvalidSessionId() {
    var entity = comparedEntity();
    entity.addProperty("SessionId", "invalid");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "[]",
      "{\"number\":1}",
      "{\"duplicate\":\"one\",\"duplicate\":\"two\"}"
  })
  void shouldRejectInvalidMetadataJson(String json) {
    var entity = comparedEntity();
    entity.addProperty("SourceMetadata", json);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @Test
  void shouldRejectWrongPartitionKey() {
    var entity = comparedEntity();
    entity.addProperty("PartitionKey", "wrong-partition");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @Test
  void shouldRejectWrongRowKey() {
    var entity = comparedEntity();
    entity.addProperty("RowKey", "wrong-row");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @Test
  void shouldRejectMissingCount() {
    var entity = comparedEntity();
    entity.getProperties().remove("PresentRows");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @Test
  void shouldRejectDiagnosticsOnComparedItem() {
    var entity = comparedEntity();
    entity.addProperty("DiagnosticDetails", "{}");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @Test
  void shouldRejectOversizedDiagnosticProperty() {
    var item =
        new RecoveryPublicationDiscoveryItem(
            JOB_ID,
            DAY,
            candidate(),
            new RecoveryPublicationDiscoveryItem.Unresolved(
                "ORIGIN_UNDETERMINED",
                Map.of("detail", "x".repeat(32_768))));

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toEntity(item));
  }

  @Test
  void shouldRejectInt32WhereInt64IsRequired() {
    var entity = comparedEntity();
    entity.addProperty("FlowId", Integer.valueOf(155333));

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @Test
  void shouldRejectMissingDiagnosticDetails() {
    var entity = mapper.toEntity(unresolvedItem());
    entity.getProperties().remove("DiagnosticDetails");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  private TableEntity comparedEntity() {
    return mapper.toEntity(
        comparedItem(
            RecoveryPublicationSource.SourceType.FDR3_JSON,
            RecoveryPublicationDiscoveryItem.Status.PRESENT_UNVERIFIED,
            4L));
  }

  private static RecoveryPublicationDiscoveryItem comparedItem(
      RecoveryPublicationSource.SourceType type,
      RecoveryPublicationDiscoveryItem.Status status,
      long rows) {

    var candidate = candidate();

    var source =
        new RecoveryPublicationSource(
            candidate,
            new RecoveryBlobSnapshot(
                type.containerName(),
                "original-blob",
                "\"original-etag\"",
                100L,
                Map.of(
                    "sessionId",
                    "6fd3e2eb-1d1d-4068-ba74-fa9866141524",
                    "elaborate",
                    "true")),
            type,
            "6fd3e2eb-1d1d-4068-ba74-fa9866141524",
            Instant.parse("2026-10-05T10:31:35.208475643Z"));

    return new RecoveryPublicationDiscoveryItem(
        JOB_ID,
        DAY,
        candidate,
        new RecoveryPublicationDiscoveryItem.Compared(
            source, status, rows));
  }

  private static RecoveryPublicationDiscoveryItem unresolvedItem() {
    return new RecoveryPublicationDiscoveryItem(
        JOB_ID,
        DAY,
        candidate(),
        new RecoveryPublicationDiscoveryItem.Unresolved(
            "XML_UNRESOLVED",
            Map.of("xmlReason", "AMBIGUOUS_MATCH")));
  }

  private static RecoveryFlowCandidate candidate() {
    return new RecoveryFlowCandidate(
        155333L,
        "flow-1",
        1L,
        "psp-1",
        "org-1",
        LocalDateTime.parse("2026-10-05T10:31:35.208475"));
  }
}