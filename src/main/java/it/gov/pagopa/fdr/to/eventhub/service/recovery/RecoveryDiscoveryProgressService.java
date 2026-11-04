package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RecoveryDiscoveryProgressService {

  public enum DiscoveryStatus {
    NOT_STARTED,
    DISCOVERING,
    DISCOVERED
  }

  public record DailyProgress(
      UUID jobId,
      LocalDate day,
      DiscoveryStatus status,
      long candidateCount,
      long absentCandidateCount,
      long presentCandidateCount,
      long pageCount,
      long lastFlowId) {}

  private final RecoveryJobRepository jobs;
  private final RecoveryDiscoveryCheckpointRepository checkpoints;

  public RecoveryDiscoveryProgressService(
      RecoveryJobRepository jobs,
      RecoveryDiscoveryCheckpointRepository checkpoints) {

    this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    this.checkpoints =
        Objects.requireNonNull(checkpoints, "checkpoints is required");
  }

  public Optional<DailyProgress> find(UUID jobId, LocalDate day) {
    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");

    var storedJob = jobs.find(jobId);

    if (storedJob.isEmpty()) {
      return Optional.empty();
    }

    var job = storedJob.orElseThrow().job();

    if (day.isBefore(job.dateFrom()) || day.isAfter(job.dateTo())) {
      throw new IllegalArgumentException(
          "day must belong to the recovery job date range");
    }

    DailyProgress progress =
        checkpoints.find(jobId, day)
            .map(stored -> {
              var checkpoint = stored.checkpoint();

              return new DailyProgress(
                  jobId,
                  day,
                  DiscoveryStatus.valueOf(checkpoint.status().name()),
                  checkpoint.candidateCount(),
                  checkpoint.absentCandidateCount(),
                  checkpoint.presentCandidateCount(),
                  checkpoint.pageCount(),
                  checkpoint.lastFlowId());
            })
            .orElseGet(() -> new DailyProgress(
                jobId,
                day,
                DiscoveryStatus.NOT_STARTED,
                0L,
                0L,
                0L,
                0L,
                0L));

    return Optional.of(progress);
  }
}