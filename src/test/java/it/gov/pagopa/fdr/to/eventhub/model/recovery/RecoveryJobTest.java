package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryJobTest {

  private static final Instant NOW =
      Instant.parse("2026-10-29T15:00:00Z");

  private static final Clock CLOCK =
      Clock.fixed(NOW, ZoneOffset.UTC);

  private final ObjectMapper objectMapper =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();

  private RecoveryJob newJob() {
    return RecoveryJob.create(
        new RecoveryJobRequest("2026-10-01", "2026-10-31", false),
        CLOCK);
  }

  private RecoveryJob snapshot(
      RecoveryJob original,
      RecoveryJobStatus status,
      LocalDate nextDate,
      Instant updatedAt) {

    return new RecoveryJob(
        original.jobId(),
        original.dateFrom(),
        original.dateTo(),
        original.dryRun(),
        status,
        nextDate,
        original.createdAt(),
        updatedAt);
  }

  @Test
  void shouldCreatePendingJobFromRequest() {
    RecoveryJob job = newJob();

    assertNotNull(job.jobId());
    assertEquals(LocalDate.of(2026, 10, 1), job.dateFrom());
    assertEquals(LocalDate.of(2026, 10, 31), job.dateTo());
    assertFalse(job.dryRun());
    assertEquals(RecoveryJobStatus.PENDING, job.status());
    assertEquals(job.dateFrom(), job.nextDate());
    assertEquals(NOW, job.createdAt());
    assertEquals(NOW, job.updatedAt());
  }

  @Test
  void shouldPreserveDryRunDefault() {
    RecoveryJob job =
        RecoveryJob.create(
            new RecoveryJobRequest("2026-10-01", "2026-10-01", null),
            CLOCK);

    assertEquals(true, job.dryRun());
  }

  @Test
  void shouldSerializeDatesAsIsoStringsAndReadJobBack() throws Exception {
    RecoveryJob job = newJob();

    String json = objectMapper.writeValueAsString(job);

    assertEquals(
        "2026-10-01",
        objectMapper.readTree(json).get("dateFrom").asText());
    assertEquals(
        "2026-10-29T15:00:00Z",
        objectMapper.readTree(json).get("createdAt").asText());
    assertEquals(job, objectMapper.readValue(json, RecoveryJob.class));
  }

  @Test
  void shouldRepresentProgressWithoutChangingOriginalSnapshot() {
    RecoveryJob original = newJob();

    RecoveryJob updated =
        snapshot(
            original,
            RecoveryJobStatus.RUNNING,
            LocalDate.of(2026, 10, 2),
            NOW.plusSeconds(60));

    assertEquals(LocalDate.of(2026, 10, 1), original.nextDate());
    assertEquals(LocalDate.of(2026, 10, 2), updated.nextDate());
    assertEquals(original.jobId(), updated.jobId());
    assertEquals(original.createdAt(), updated.createdAt());
  }

  @Test
  void shouldRepresentCompletedRange() {
    RecoveryJob job =
        snapshot(
            newJob(),
            RecoveryJobStatus.COMPLETED,
            LocalDate.of(2026, 11, 1),
            NOW.plusSeconds(60));

    assertEquals(RecoveryJobStatus.COMPLETED, job.status());
    assertEquals(job.dateTo().plusDays(1), job.nextDate());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-09-30", "2026-11-02"})
  void shouldRejectNextDateOutsideRange(String nextDate) {
    RecoveryJob original = newJob();
    LocalDate invalidNextDate = LocalDate.parse(nextDate);

    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(
            original,
            RecoveryJobStatus.RUNNING,
            invalidNextDate,
            NOW));
  }

  @Test
  void shouldRejectCompletedJobWithUnfinishedRange() {
    RecoveryJob original = newJob();
    LocalDate unfinishedNextDate = LocalDate.of(2026, 10, 31);

    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(
            original,
            RecoveryJobStatus.COMPLETED,
            unfinishedNextDate,
            NOW));
  }

  @Test
  void shouldRejectUpdatedAtBeforeCreatedAt() {
    RecoveryJob original = newJob();
    RecoveryJobStatus status = original.status();
    LocalDate nextDate = original.nextDate();
    Instant invalidUpdatedAt = NOW.minusSeconds(1);

    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(
            original,
            status,
            nextDate,
            invalidUpdatedAt));
  }

  @Test
  void shouldRejectNullRequest() {
    assertThrows(
        NullPointerException.class,
        () -> RecoveryJob.create(null, CLOCK));
  }

  @Test
  void shouldRejectNullClock() {
    RecoveryJobRequest request =
        new RecoveryJobRequest("2026-10-01", "2026-10-31", true);

    assertThrows(
        NullPointerException.class,
        () -> RecoveryJob.create(request, null));
  }
}