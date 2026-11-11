package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryRunnableJobRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecoveryDryRunDispatcher {

  private final RecoveryRunnableJobRepository jobs;
  private final RecoveryDryRunDayWorker worker;
  private final int pageSize;

  private final AtomicBoolean dispatching = new AtomicBoolean();
  private final Deque<UUID> pending = new ArrayDeque<>();

  private String continuationToken;

  public RecoveryDryRunDispatcher(
      RecoveryRunnableJobRepository jobs,
      RecoveryDryRunDayWorker worker,
      int pageSize) {

    this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    this.worker = Objects.requireNonNull(worker, "worker is required");

    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and 100");
    }

    this.pageSize = pageSize;
  }

  public Optional<Attempt> runNext()
      throws SQLException, IOException, DataClientException, DataServiceException {

    if (!dispatching.compareAndSet(false, true)) {
      return Optional.empty();
    }

    try {
      return dispatchNext();
    } finally {
      dispatching.set(false);
    }
  }

  private Optional<Attempt> dispatchNext()
      throws SQLException, IOException, DataClientException, DataServiceException {

    if (pending.isEmpty()) {
      RecoveryRunnableJobRepository.JobPage page =
          jobs.findPage(continuationToken, pageSize);

      pending.addAll(page.jobIds());
      continuationToken = page.continuationToken();
    }

    UUID jobId = pending.peekFirst();

    if (jobId == null) {
      return Optional.empty();
    }

    boolean keepCandidate = false;

    try {
      RecoveryDryRunDayWorker.Outcome outcome =
          Objects.requireNonNull(
              worker.runOneDay(jobId),
              "worker must return an outcome");

      keepCandidate =
          outcome == RecoveryDryRunDayWorker.Outcome.LEASE_BUSY;

      return Optional.of(new Attempt(jobId, outcome));

    } finally {
      if (!keepCandidate) {
        pending.removeFirst();
      }
    }
  }

  public record Attempt(
      UUID jobId,
      RecoveryDryRunDayWorker.Outcome outcome) {

    public Attempt {
      Objects.requireNonNull(jobId, "jobId is required");
      Objects.requireNonNull(outcome, "outcome is required");
    }
  }
}