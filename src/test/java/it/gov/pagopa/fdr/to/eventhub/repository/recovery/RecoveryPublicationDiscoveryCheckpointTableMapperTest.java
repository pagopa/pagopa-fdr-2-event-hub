package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryPublicationDiscoveryCheckpointTableMapperTest {

  private static final UUID JOB_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryPublicationDiscoveryCheckpointTableMapper mapper =
      new RecoveryPublicationDiscoveryCheckpointTableMapper();

  @ParameterizedTest
  @EnumSource(RecoveryPublicationDiscoveryCheckpoint.Status.class)
  void shouldRoundTripCheckpoint(
      RecoveryPublicationDiscoveryCheckpoint.Status status) {

    var checkpoint = checkpoint(status);
    var entity = mapper.toEntity(checkpoint);

    assertEquals("2", entity.getProperties().get("SchemaVersion"));
    assertEquals(
        "PUBLICATION",
        entity.getProperties().get("DiscoveryMode"));

    assertEquals(checkpoint, mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRoundTripInitialCheckpoint() {
    var checkpoint =
        RecoveryPublicationDiscoveryCheckpoint.start(JOB_ID, DAY);

    assertEquals(
        checkpoint,
        mapper.toCheckpoint(mapper.toEntity(checkpoint)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "3"})
  void shouldRejectUnsupportedSchemaVersion(String version) {
    var entity = entity();
    entity.addProperty("SchemaVersion", version);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "UnresolvedCandidateCount",
      "LastFlowId",
      "CandidateCount"
  })
  void shouldRejectMissingCounter(String property) {
    var entity = entity();
    entity.getProperties().remove(property);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRejectInt32Counter() {
    var entity = entity();
    entity.addProperty("UnresolvedCandidateCount", Integer.valueOf(1));

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRejectNullCounter() {
    var entity = entity();
    entity.addProperty("PresentCandidateCount", null);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRejectUnknownStatus() {
    var entity = entity();
    entity.addProperty("Status", "UNKNOWN");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRejectWrongPartitionKey() {
    var entity = entity();
    entity.addProperty("PartitionKey", "wrong-partition");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRejectWrongRowKey() {
    var entity = entity();
    entity.addProperty("RowKey", "wrong-row");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRejectUnbalancedCounters() {
    var entity = entity();
    entity.addProperty("UnresolvedCandidateCount", 2L);

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  @Test
  void shouldRejectAnotherDiscoveryMode() {
    var entity = entity();
    entity.addProperty("DiscoveryMode", "FLOW");

    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.toCheckpoint(entity));
  }

  private TableEntity entity() {
    return mapper.toEntity(
        checkpoint(
            RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERING));
  }

  private static RecoveryPublicationDiscoveryCheckpoint checkpoint(
      RecoveryPublicationDiscoveryCheckpoint.Status status) {

    return new RecoveryPublicationDiscoveryCheckpoint(
        JOB_ID,
        DAY,
        155735L,
        3L,
        1L,
        1L,
        1L,
        1L,
        status);
  }
}