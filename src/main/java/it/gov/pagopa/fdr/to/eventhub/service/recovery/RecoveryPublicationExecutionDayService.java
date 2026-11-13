package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationExecution;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryPageRepository;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class RecoveryPublicationExecutionDayService {

  public enum Status {
    COMPLETED,
    PAUSED,
    BLOCKED_UNRESOLVED,
    BLOCKED_UNCERTAIN
  }

  public record DayResult(
      UUID jobId,
      LocalDate day,
      Status status,
      long examinedPublications,
      long sentPublications,
      long presentPublications,
      long unresolvedPublications,
      long uncertainPublications,
      long lastExaminedFlowId) {
  }

  private final RecoveryPublicationDiscoveryCheckpointRepository checkpoints;
  private final RecoveryPublicationDiscoveryPageRepository pages;
  private final RecoveryPublicationExecutionService executions;
  private final int pageSize;

  public RecoveryPublicationExecutionDayService(
      RecoveryPublicationDiscoveryCheckpointRepository checkpoints,
      RecoveryPublicationDiscoveryPageRepository pages,
      RecoveryPublicationExecutionService executions,
      int pageSize) {

    this.checkpoints =
        Objects.requireNonNull(checkpoints, "checkpoints is required");

    this.pages =
        Objects.requireNonNull(pages, "pages is required");

    this.executions =
        Objects.requireNonNull(executions, "executions is required");

    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and 100");
    }

    this.pageSize = pageSize;
  }

  public DayResult execute(
      UUID jobId,
      LocalDate day,
      BooleanSupplier shouldContinue,
      Runnable requireOwnership)
      throws IOException, DataClientException, DataServiceException {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(shouldContinue, "shouldContinue is required");
    Objects.requireNonNull(requireOwnership, "requireOwnership is required");

    var checkpoint =
        loadCheckpoint(jobId, day, requireOwnership);

    var progress = new Progress(checkpoint);

    while (canContinue(shouldContinue, requireOwnership)) {
      var page =
          List.copyOf(
              pages.findPage(
                  jobId,
                  day,
                  progress.lastFlowId,
                  pageSize));

      requireOwnership.run();

      if (page.isEmpty()) {
        return progress.finish();
      }

      validatePage(page, progress);

      for (var item : page) {
        if (!canContinue(shouldContinue, requireOwnership)) {
          return progress.result(Status.PAUSED);
        }

        boolean uncertain =
            processItem(item, progress, requireOwnership);

        if (uncertain) {
          return progress.result(Status.BLOCKED_UNCERTAIN);
        }
      }
    }

    return progress.result(Status.PAUSED);
  }

  private RecoveryPublicationDiscoveryCheckpoint loadCheckpoint(
      UUID jobId,
      LocalDate day,
      Runnable requireOwnership) {

    requireOwnership.run();

    var found = checkpoints.find(jobId, day);

    requireOwnership.run();

    var checkpoint =
        found.orElseThrow(
                () -> new IllegalStateException(
                    "Publication discovery checkpoint is missing"))
            .checkpoint();

    if (!jobId.equals(checkpoint.jobId())
        || !day.equals(checkpoint.day())
        || checkpoint.status()
            != RecoveryPublicationDiscoveryCheckpoint.Status.DISCOVERED) {

      throw new IllegalStateException(
          "Execution requires completed discovery for the requested day");
    }

    return checkpoint;
  }

  private static boolean canContinue(
      BooleanSupplier shouldContinue,
      Runnable requireOwnership) {

    requireOwnership.run();
    boolean allowed = shouldContinue.getAsBoolean();
    requireOwnership.run();

    return allowed;
  }

  private void validatePage(
      List<RecoveryPublicationDiscoveryItem> page,
      Progress progress) {

    var checkpoint = progress.checkpoint;

    if (page.size() > pageSize
        || page.size()
            > checkpoint.candidateCount() - progress.examined) {

      throw new IllegalStateException(
          "Discovery page exceeds the expected execution range");
    }

    long previous = progress.lastFlowId;

    for (var item : page) {
      long flowId = item.candidate().flowId();

      if (!checkpoint.jobId().equals(item.jobId())
          || !checkpoint.day().equals(item.day())
          || flowId <= previous
          || flowId > checkpoint.lastFlowId()) {

        throw new IllegalStateException(
            "Discovery page contains an unexpected publication");
      }

      previous = flowId;
    }
  }

  private boolean processItem(
      RecoveryPublicationDiscoveryItem item,
      Progress progress,
      Runnable requireOwnership)
      throws IOException, DataClientException, DataServiceException {

    boolean uncertain = false;

    switch (item.outcome().status()) {
      case ABSENT -> {
        var execution = executions.execute(item, requireOwnership);

        requireOwnership.run();
        validateExecution(item, execution);

        progress.absentSeen++;

        switch (execution.status()) {
          case SENT -> progress.sent++;
          case SKIPPED_PRESENT -> progress.present++;
          case UNCERTAIN -> {
            progress.uncertain++;
            uncertain = true;
          }
          default -> throw new IllegalStateException(
              "Publication execution returned a non-final outcome");
        }
      }

      case PRESENT_UNVERIFIED -> {
        progress.presentSeen++;
        progress.present++;
      }

      case UNRESOLVED -> progress.unresolved++;
    }

    progress.examined++;
    progress.lastFlowId = item.candidate().flowId();

    return uncertain;
  }

  private static void validateExecution(
      RecoveryPublicationDiscoveryItem item,
      RecoveryPublicationExecution execution) {

    var compared =
        (RecoveryPublicationDiscoveryItem.Compared) item.outcome();

    if (!item.jobId().equals(execution.jobId())
        || !item.day().equals(execution.day())
        || !compared.source().equals(execution.source())) {

      throw new IllegalStateException(
          "Execution result belongs to a different publication");
    }
  }

  private static final class Progress {

    private final RecoveryPublicationDiscoveryCheckpoint checkpoint;

    private long examined;
    private long absentSeen;
    private long presentSeen;
    private long sent;
    private long present;
    private long unresolved;
    private long uncertain;
    private long lastFlowId;

    private Progress(
        RecoveryPublicationDiscoveryCheckpoint checkpoint) {

      this.checkpoint = checkpoint;
    }

    private DayResult finish() {
      if (examined != checkpoint.candidateCount()
          || absentSeen != checkpoint.absentCandidateCount()
          || presentSeen != checkpoint.presentCandidateCount()
          || unresolved != checkpoint.unresolvedCandidateCount()
          || lastFlowId != checkpoint.lastFlowId()) {

        throw new IllegalStateException(
            "Stored discovery items do not match the completed checkpoint");
      }

      return result(
          unresolved == 0
              ? Status.COMPLETED
              : Status.BLOCKED_UNRESOLVED);
    }

    private DayResult result(Status status) {
      return new DayResult(
          checkpoint.jobId(),
          checkpoint.day(),
          status,
          examined,
          sent,
          present,
          unresolved,
          uncertain,
          lastFlowId);
    }
  }
}