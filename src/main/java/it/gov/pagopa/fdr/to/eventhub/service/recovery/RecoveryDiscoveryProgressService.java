package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryCheckpointRepository;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

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
      long lastFlowId,
      long unresolvedCandidateCount) {

    public DailyProgress(
        UUID jobId,
        LocalDate day,
        DiscoveryStatus status,
        long candidateCount,
        long absentCandidateCount,
        long presentCandidateCount,
        long pageCount,
        long lastFlowId) {

      this(
          jobId,
          day,
          status,
          candidateCount,
          absentCandidateCount,
          presentCandidateCount,
          pageCount,
          lastFlowId,
          0L);
    }
  }

  public static final class DayOutsideJobRangeException
      extends IllegalArgumentException {

    private static final long serialVersionUID = 2704319422762995466L;

    public DayOutsideJobRangeException() {
      super("day must belong to the recovery job date range");
    }
  }

  private final RecoveryJobRepository jobs;

  private final BiFunction<
      UUID, LocalDate, Optional<DailyProgress>> checkpointReader;

  public RecoveryDiscoveryProgressService(
      RecoveryJobRepository jobs,
      RecoveryDiscoveryCheckpointRepository checkpoints) {

    this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    Objects.requireNonNull(checkpoints, "checkpoints is required");

    this.checkpointReader =
        (jobId, day) ->
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
                });
  }

  public RecoveryDiscoveryProgressService(
      RecoveryJobRepository jobs,
      RecoveryPublicationDiscoveryCheckpointRepository checkpoints) {

    this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    Objects.requireNonNull(checkpoints, "checkpoints is required");

    this.checkpointReader =
        (jobId, day) ->
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
                      checkpoint.lastFlowId(),
                      checkpoint.unresolvedCandidateCount());
                });
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
      throw new DayOutsideJobRangeException();
    }

    var progress =
        checkpointReader.apply(jobId, day)
            .orElseGet(
                () ->
                    new DailyProgress(
                        jobId,
                        day,
                        DiscoveryStatus.NOT_STARTED,
                        0L, 0L, 0L, 0L, 0L, 0L));

    return Optional.of(progress);
  }
}