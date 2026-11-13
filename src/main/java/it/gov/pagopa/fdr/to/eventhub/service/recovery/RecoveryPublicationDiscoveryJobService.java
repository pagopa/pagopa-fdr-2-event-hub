package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryPublicationDiscoveryJobService {

  private final RecoveryJobRepository jobs;
  private final RecoveryPersistedPublicationDiscoveryService discovery;

  public RecoveryPublicationDiscoveryJobService(
      RecoveryJobRepository jobs,
      RecoveryPersistedPublicationDiscoveryService discovery) {

    this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    this.discovery =
        Objects.requireNonNull(discovery, "discovery is required");
  }

  public RecoveryPublicationDiscoveryCheckpoint discover(
      UUID jobId,
      LocalDate day,
      RecoveryWorkerLeaseSession lease)
      throws SQLException, IOException,
          DataClientException, DataServiceException {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");
    Objects.requireNonNull(lease, "lease is required");

    lease.requireHeld();

    var job = requireJob(jobId);

    lease.requireHeld();

    if (!job.dryRun()) {
      throw new IllegalArgumentException(
          "This discovery path supports only dryRun jobs");
    }

    if (day.isBefore(job.dateFrom()) || day.isAfter(job.dateTo())) {
      throw new IllegalArgumentException(
          "Discovery day must be within the job date range");
    }

    if (!day.equals(job.nextDate())) {
      throw new IllegalArgumentException(
          "Discovery day must match the job nextDate");
    }

    return discovery.discover(
        jobId,
        day,
        () -> shouldContinue(jobId, day, lease),
        lease::requireHeld);
  }

  private boolean shouldContinue(
      UUID jobId,
      LocalDate day,
      RecoveryWorkerLeaseSession lease) {

    lease.requireHeld();

    var current = requireJob(jobId);

    lease.requireHeld();

    return current.status() == RecoveryJobStatus.RUNNING
        && current.dryRun()
        && day.equals(current.nextDate());
  }

  private RecoveryJob requireJob(UUID jobId) {
    return jobs.find(jobId)
        .orElseThrow(
            () -> new IllegalStateException(
                "Recovery job was not found"))
        .job();
  }
}