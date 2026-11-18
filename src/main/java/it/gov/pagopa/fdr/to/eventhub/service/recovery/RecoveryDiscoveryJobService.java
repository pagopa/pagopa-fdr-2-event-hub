package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryDiscoveryCheckpoint;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryDiscoveryJobService {

  private final RecoveryJobRepository jobRepository;
  private final RecoveryPersistedDiscoveryService discoveryService;

  public RecoveryDiscoveryJobService(
      RecoveryJobRepository jobRepository,
      RecoveryPersistedDiscoveryService discoveryService) {

    this.jobRepository =
        Objects.requireNonNull(
            jobRepository,
            "jobRepository is required");

    this.discoveryService =
        Objects.requireNonNull(
            discoveryService,
            "discoveryService is required");
  }

  public RecoveryDiscoveryCheckpoint discover(
      UUID jobId,
      LocalDate day)
      throws SQLException, DataClientException, DataServiceException {

    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(day, "day is required");

    RecoveryJob job = requireJob(jobId);

    if (day.isBefore(job.dateFrom()) || day.isAfter(job.dateTo())) {
      throw new IllegalArgumentException(
          "Discovery day must be within the job date range");
    }

    return discoveryService.discover(
        jobId,
        day,
        () -> requireJob(jobId).status() == RecoveryJobStatus.RUNNING);
  }

  public RecoveryDiscoveryCheckpoint discover(UUID jobId, LocalDate day, RecoveryWorkerLeaseSession lease)
          throws SQLException, DataClientException, DataServiceException {

      Objects.requireNonNull(jobId, "jobId is required");
      Objects.requireNonNull(day, "day is required");
      Objects.requireNonNull(lease, "lease is required");

      lease.requireHeld();

      RecoveryJob job = requireJob(jobId);

      if (day.isBefore(job.dateFrom()) || day.isAfter(job.dateTo())) {
          throw new IllegalArgumentException("Discovery day must be within the job date range");
      }

      return discoveryService.discover(jobId, day, () -> {
          lease.requireHeld();
          return requireJob(jobId).status() == RecoveryJobStatus.RUNNING;
      }, lease::requireHeld);
  }

  private RecoveryJob requireJob(UUID jobId) {
    return jobRepository.find(jobId)
        .orElseThrow(
            () -> new IllegalStateException(
                "Recovery job was not found"))
        .job();
  }
}