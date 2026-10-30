package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFlowPresenceIndex;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryDiscoveryItemTableMapperTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryDiscoveryItemTableMapper mapper =
      new RecoveryDiscoveryItemTableMapper();

  private RecoveryDiscoveryItem item(
      RecoveryFlowPresenceIndex.Presence presence) {

    long presentRows =
        presence == RecoveryFlowPresenceIndex.Presence.ABSENT
            ? 0L
            : 25L;

    return new RecoveryDiscoveryItem(
        JOB_ID,
        DAY,
        new RecoveryFlowCandidate(
            68L,
            "flow-1",
            2L,
            "88888888888",
            "77777777777"),
        presence,
        presentRows);
  }

  private TableEntity absentEntity() {
    return mapper.toEntity(
        item(RecoveryFlowPresenceIndex.Presence.ABSENT));
  }

  @ParameterizedTest
  @EnumSource(RecoveryFlowPresenceIndex.Presence.class)
  void shouldRoundTripBothPresenceStates(
      RecoveryFlowPresenceIndex.Presence presence) {

    RecoveryDiscoveryItem original = item(presence);

    TableEntity entity = mapper.toEntity(original);
    RecoveryDiscoveryItem restored = mapper.toItem(entity);

    assertEquals(original, restored);
    assertEquals(original.partitionKey(), entity.getPartitionKey());
    assertEquals(original.rowKey(), entity.getRowKey());
    assertEquals("1", entity.getProperties().get("SchemaVersion"));
    assertEquals(68L, entity.getProperties().get("FlowId"));
    assertEquals(2L, entity.getProperties().get("Revision"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "2", " "})
  void shouldRejectUnsupportedSchemaVersion(String version) {
    TableEntity entity = absentEntity();
    entity.addProperty("SchemaVersion", version);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "JobId",
      "Day",
      "FlowId",
      "Presence",
      "PresentRows"
  })
  void shouldRejectMissingRequiredProperty(String property) {
    TableEntity entity = absentEntity();
    entity.getProperties().remove(property);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @ParameterizedTest
  @ValueSource(strings = {"partition", "row"})
  void shouldRejectKeysThatDoNotMatchContent(String changedKey) {
    TableEntity corrupted = absentEntity();

    if ("partition".equals(changedKey)) {
      corrupted.addProperty("PartitionKey", "another-partition");
    } else {
      corrupted.addProperty("RowKey", "FLOW_00000000000000000999");
    }

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(corrupted));
  }

  @Test
  void shouldRejectNumericValueStoredAsString() {
    TableEntity entity = absentEntity();
    entity.addProperty("FlowId", "68");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @Test
  void shouldRejectUnknownPresenceState() {
    TableEntity entity = absentEntity();
    entity.addProperty("Presence", "READY_TO_SEND");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }

  @Test
  void shouldRejectPositiveCountForAbsentCandidate() {
    TableEntity entity = absentEntity();
    entity.addProperty("PresentRows", 25L);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toItem(entity));
  }
}