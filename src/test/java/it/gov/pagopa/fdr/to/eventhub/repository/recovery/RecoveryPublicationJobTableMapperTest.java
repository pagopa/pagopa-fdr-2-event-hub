package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobRequest;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RecoveryPublicationJobTableMapperTest {

  private final RecoveryJobTableMapper publicationMapper =
      RecoveryJobTableMapper.forPublicationDiscovery();

  private final RecoveryJobTableMapper legacyMapper =
      new RecoveryJobTableMapper();

  @Test
  void shouldWritePublicationModeAndRoundTripJob() {
    var job = job();
    var entity = publicationMapper.toEntity(job);

    assertEquals("2", entity.getProperty("SchemaVersion"));
    assertEquals("PUBLICATION", entity.getProperty("DiscoveryMode"));
    assertEquals(job, publicationMapper.toJob(entity));
  }

  @Test
  void shouldRejectLegacyJobInPublicationPath() {
    var entity = legacyMapper.toEntity(job());

    assertThrows(
        IllegalArgumentException.class,
        () -> publicationMapper.toJob(entity));
  }

  @Test
  void shouldRejectPublicationJobInLegacyPath() {
    var entity = publicationMapper.toEntity(job());

    assertThrows(
        IllegalArgumentException.class,
        () -> legacyMapper.toJob(entity));
  }

  @Test
  void shouldRejectMissingPublicationMode() {
    var entity = publicationMapper.toEntity(job());
    entity.getProperties().remove("DiscoveryMode");

    assertThrows(
        IllegalArgumentException.class,
        () -> publicationMapper.toJob(entity));
  }

  @Test
  void shouldRejectUnexpectedPublicationMode() {
    var entity = publicationMapper.toEntity(job());
    entity.addProperty("DiscoveryMode", "FLOW");

    assertThrows(
        IllegalArgumentException.class,
        () -> publicationMapper.toJob(entity));
  }

  @Test
  void shouldRejectModeOnLegacyJob() {
    var entity = legacyMapper.toEntity(job());
    entity.addProperty("DiscoveryMode", "PUBLICATION");

    assertThrows(
        IllegalArgumentException.class,
        () -> legacyMapper.toJob(entity));
  }

  @Test
  void shouldKeepPublicationModeWhenWritingUpdatedJob() {
    var initial = publicationMapper.toJob(
        publicationMapper.toEntity(job()));

    var updated =
        new RecoveryJob(
            initial.jobId(),
            initial.dateFrom(),
            initial.dateTo(),
            initial.dryRun(),
            RecoveryJobStatus.RUNNING,
            initial.nextDate(),
            initial.createdAt(),
            initial.updatedAt().plusSeconds(1));

    var entity = publicationMapper.toEntity(updated);

    assertEquals("2", entity.getProperty("SchemaVersion"));
    assertEquals("PUBLICATION", entity.getProperty("DiscoveryMode"));
    assertEquals(updated, publicationMapper.toJob(entity));
  }

  private static RecoveryJob job() {
    return RecoveryJob.create(
        new RecoveryJobRequest(
            "2026-10-01", "2026-10-31", true),
        Clock.fixed(
            Instant.parse("2026-11-10T15:00:00Z"),
            ZoneOffset.UTC));
  }
}