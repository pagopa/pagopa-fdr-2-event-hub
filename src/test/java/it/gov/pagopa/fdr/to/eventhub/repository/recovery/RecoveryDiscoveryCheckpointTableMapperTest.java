package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFlowPresenceIndex;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryDiscoveryCheckpointTableMapperTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryDiscoveryCheckpointTableMapper mapper =
      new RecoveryDiscoveryCheckpointTableMapper();

  private RecoveryDiscoveryCheckpoint checkpointWithProgress() {
    RecoveryDiscoveryItem item =
        new RecoveryDiscoveryItem(
            JOB_ID,
            DAY,
            new RecoveryFlowCandidate(
                68L,
                "flow-1",
                1L,
                "88888888888",
                "77777777777",
                LocalDateTime.of(2026, 10, 2, 12, 43, 18, 448_000_000)),
            RecoveryFlowPresenceIndex.Presence.ABSENT,
            0L);

    return RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY)
        .afterSavedPage(List.of(item));
  }

  private TableEntity entityWithProgress() {
    return mapper.toEntity(checkpointWithProgress());
  }

  @ParameterizedTest
  @EnumSource(RecoveryDiscoveryCheckpoint.Status.class)
  void shouldRoundTripCheckpointWithProgress(
      RecoveryDiscoveryCheckpoint.Status status) {

    RecoveryDiscoveryCheckpoint original = checkpointWithProgress();

    if (status == RecoveryDiscoveryCheckpoint.Status.DISCOVERED) {
      original = original.completeDiscovery();
    }

    TableEntity entity = mapper.toEntity(original);

    assertEquals(original, mapper.toCheckpoint(entity));
    assertEquals(original.partitionKey(), entity.getPartitionKey());
    assertEquals("DISCOVERY", entity.getRowKey());
    assertEquals("1", entity.getProperties().get("SchemaVersion"));
    assertEquals(68L, entity.getProperties().get("LastFlowId"));
    assertEquals(1L, entity.getProperties().get("CandidateCount"));
  }

  @ParameterizedTest
  @EnumSource(RecoveryDiscoveryCheckpoint.Status.class)
  void shouldRoundTripEmptyCheckpoint(
      RecoveryDiscoveryCheckpoint.Status status) {

    RecoveryDiscoveryCheckpoint original =
        RecoveryDiscoveryCheckpoint.start(JOB_ID, DAY);

    if (status == RecoveryDiscoveryCheckpoint.Status.DISCOVERED) {
      original = original.completeDiscovery();
    }

    TableEntity entity = mapper.toEntity(original);

    assertEquals(original, mapper.toCheckpoint(entity));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "2", " "})
  void shouldRejectUnsupportedSchemaVersion(String version) {
    TableEntity entity = entityWithProgress();
    entity.addProperty("SchemaVersion", version);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "JobId",
      "Day",
      "LastFlowId",
      "Status"
  })
  void shouldRejectMissingRequiredProperty(String property) {
    TableEntity entity = entityWithProgress();
    entity.getProperties().remove(property);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PartitionKey", "RowKey"})
  void shouldRejectKeysThatDoNotMatchContent(String property) {
    TableEntity entity = entityWithProgress();
    entity.addProperty(property, "incorrect-key");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRejectCounterStoredAsString() {
    TableEntity entity = entityWithProgress();
    entity.addProperty("CandidateCount", "1");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRejectInconsistentPresenceCounters() {
    TableEntity entity = entityWithProgress();

    // There is one absent candidate and no present candidates.
    entity.addProperty("PresentCandidateCount", 1L);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRejectUnknownStatus() {
    TableEntity entity = entityWithProgress();
    entity.addProperty("Status", "UNKNOWN");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }
}