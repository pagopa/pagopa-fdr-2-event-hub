package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFlowPresenceIndex;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RecoveryDiscoveryCheckpoint(UUID jobId, LocalDate day, long lastFlowId, long candidateCount,
        long absentCandidateCount, long presentCandidateCount, long pageCount, Status status) {

    private static final String DISCOVERY = "DISCOVERY";

    public enum Status {
        DISCOVERING, DISCOVERED
    }

    public RecoveryDiscoveryCheckpoint {
        Objects.requireNonNull(jobId, "jobId is required");
        Objects.requireNonNull(day, "day is required");
        Objects.requireNonNull(status, "status is required");

        if (lastFlowId < 0 || candidateCount < 0 || absentCandidateCount < 0 || presentCandidateCount < 0
                || pageCount < 0) {
            throw new IllegalArgumentException("Checkpoint cursor and counters must not be negative");
        }

        if (absentCandidateCount > candidateCount || candidateCount - absentCandidateCount != presentCandidateCount) {
            throw new IllegalArgumentException("Checkpoint presence counters do not match candidateCount");
        }

        if (pageCount > candidateCount) {
            throw new IllegalArgumentException("pageCount must not exceed candidateCount");
        }

        if (candidateCount == 0) {
            if (lastFlowId != 0 || pageCount != 0) {
                throw new IllegalArgumentException("An empty checkpoint must have zero cursor and pageCount");
            }
        } else if (lastFlowId == 0 || pageCount == 0) {
            throw new IllegalArgumentException("A non-empty checkpoint must have a positive cursor and pageCount");
        }
    }

    public static RecoveryDiscoveryCheckpoint start(UUID jobId, LocalDate day) {

        return new RecoveryDiscoveryCheckpoint(jobId, day, 0L, 0L, 0L, 0L, 0L, Status.DISCOVERING);
    }

    /**
     * Computes the next checkpoint. The caller must persist the entire page before
     * invoking this operation and persisting the returned checkpoint.
     */
    public RecoveryDiscoveryCheckpoint afterSavedPage(List<RecoveryDiscoveryItem> savedItems) {

        if (status != Status.DISCOVERING) {
            throw new IllegalStateException("Cannot advance a completed discovery");
        }

        List<RecoveryDiscoveryItem> page = List.copyOf(Objects.requireNonNull(savedItems, "savedItems is required"));

        if (page.isEmpty()) {
            throw new IllegalArgumentException("A saved page must contain at least one item");
        }

        long cursor = lastFlowId;
        long pageAbsentCount = 0L;

        for (RecoveryDiscoveryItem item : page) {
            if (!jobId.equals(item.jobId()) || !day.equals(item.day())) {
                throw new IllegalArgumentException("Saved items must belong to the checkpoint job and day");
            }

            if (item.candidate().flowId() <= cursor) {
                throw new IllegalArgumentException("Saved item IDs must be strictly increasing after lastFlowId");
            }

            cursor = item.candidate().flowId();

            if (item.presence() == RecoveryFlowPresenceIndex.Presence.ABSENT) {
                pageAbsentCount++;
            }
        }

        return new RecoveryDiscoveryCheckpoint(jobId, day, cursor, Math.addExact(candidateCount, page.size()),
                Math.addExact(absentCandidateCount, pageAbsentCount),
                Math.addExact(presentCandidateCount, page.size() - pageAbsentCount), Math.addExact(pageCount, 1L),
                Status.DISCOVERING);
    }

    /**
     * Marks discovery as finished. The caller must first verify that PostgreSQL has
     * no further candidates.
     */
    public RecoveryDiscoveryCheckpoint completeDiscovery() {
        if (status == Status.DISCOVERED) {
            return this;
        }

        return new RecoveryDiscoveryCheckpoint(jobId, day, lastFlowId, candidateCount, absentCandidateCount,
                presentCandidateCount, pageCount, Status.DISCOVERED);
    }

    public String partitionKey() {
        return jobId + "_" + day;
    }

    public String rowKey() {
        return DISCOVERY;
    }
}