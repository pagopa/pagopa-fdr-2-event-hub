package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.microsoft.azure.kusto.data.exceptions.DataClientException;
import com.microsoft.azure.kusto.data.exceptions.DataServiceException;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationDiscoveryItem;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationExecution;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationExecutionRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.StoredRecoveryPublicationExecution;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class RecoveryPublicationExecutionService {

  private final RecoveryPublicationExecutionRepository repository;
  private final RecoveryPublicationPresenceService presence;
  private final RecoveryFdr3PaymentEventReader jsonReader;
  private final RecoveryFdr1PaymentEventReader xmlReader;
  private final RecoveryPaymentEventPublisher publisher;
  private final Clock clock;

  public RecoveryPublicationExecutionService(
      RecoveryPublicationExecutionRepository repository,
      RecoveryPublicationPresenceService presence,
      RecoveryFdr3PaymentEventReader jsonReader,
      RecoveryFdr1PaymentEventReader xmlReader,
      RecoveryPaymentEventPublisher publisher,
      Clock clock) {

    this.repository =
        Objects.requireNonNull(repository, "repository is required");

    this.presence =
        Objects.requireNonNull(presence, "presence is required");

    this.jsonReader =
        Objects.requireNonNull(jsonReader, "jsonReader is required");

    this.xmlReader =
        Objects.requireNonNull(xmlReader, "xmlReader is required");

    this.publisher =
        Objects.requireNonNull(publisher, "publisher is required");

    this.clock =
        Objects.requireNonNull(clock, "clock is required");
  }

  public RecoveryPublicationExecution execute(
      RecoveryPublicationDiscoveryItem item,
      Runnable requireOwnership)
      throws IOException, DataClientException, DataServiceException {

    Objects.requireNonNull(
        requireOwnership, "requireOwnership is required");

    // Also rejects PRESENT_UNVERIFIED and UNRESOLVED discovery items.
    var requested =
        RecoveryPublicationExecution.pending(item, clock.instant());

    requireOwnership.run();

    var stored = repository.createOrRead(requested);

    requireOwnership.run();

    return switch (stored.execution().status()) {
      case PENDING ->
          executePending(stored, requireOwnership);

      case SENDING ->
          recoverInterruptedAttempt(stored, requireOwnership);

      case SENT, SKIPPED_PRESENT, UNCERTAIN ->
          stored.execution();
    };
  }

  private RecoveryPublicationExecution executePending(
      StoredRecoveryPublicationExecution stored,
      Runnable requireOwnership)
      throws IOException, DataClientException, DataServiceException {

    var execution = stored.execution();

    var comparison =
        comparePresence(execution, requireOwnership);

    if (comparison.presence()
        == RecoveryPublicationPresenceIndex.Presence
            .PRESENT_UNVERIFIED) {

      var skipped =
          execution.skipAlreadyPresent(clock.instant());

      requireOwnership.run();
      repository.update(skipped, stored.etag());
      requireOwnership.run();

      return skipped;
    }

    requireOwnership.run();

    try (var events =
        readEvents(execution.day(), execution.source())) {

      requireOwnership.run();

      var iterator = events.iterator();

      // Validate the first selected event before recording an attempt.
      if (!iterator.hasNext()) {
        throw new IOException(
            "Selected publication has no payments for the recovery day");
      }

      requireOwnership.run();

      var sending =
          execution.startSending(
              UUID.randomUUID(), clock.instant());

      repository.update(sending, stored.etag());

      // No Event Hub call occurs before the SENDING update succeeds.
      return sendAttempt(sending, iterator, requireOwnership);
    }
  }

  private RecoveryPublicationPresenceIndex.Comparison comparePresence(
      RecoveryPublicationExecution execution,
      Runnable requireOwnership)
      throws DataClientException, DataServiceException {

    requireOwnership.run();

    var comparisons =
        presence.compare(
            execution.day(),
            List.of(execution.source()));

    requireOwnership.run();

    if (comparisons.size() != 1) {
      throw new IllegalStateException(
          "Expected one publication presence comparison");
    }

    var comparison = comparisons.get(0);

    if (!execution.day().equals(comparison.paymentDay())
        || !execution.source().equals(comparison.source())
        || comparison.presence() == null) {

      throw new IllegalStateException(
          "Presence comparison does not match the execution");
    }

    boolean absent =
        comparison.presence()
            == RecoveryPublicationPresenceIndex.Presence.ABSENT;

    if ((absent && comparison.presentRows() != 0)
        || (!absent && comparison.presentRows() <= 0)) {

      throw new IllegalStateException(
          "Presence comparison contains inconsistent counters");
    }

    return comparison;
  }

  private Stream<ReportedIUVEventModel> readEvents(
      LocalDate day,
      RecoveryPublicationSource source) throws IOException {

    return switch (source.sourceType()) {
      case FDR3_JSON -> jsonReader.read(day, source);
      case FDR1_XML -> xmlReader.read(day, source);
    };
  }

  private RecoveryPublicationExecution sendAttempt(
      RecoveryPublicationExecution sending,
      Iterator<ReportedIUVEventModel> iterator,
      Runnable requireOwnership) throws IOException {

    try {
      requireOwnership.run();

      var stored =
          repository.find(
                  sending.jobId(),
                  sending.day(),
                  sending.source().candidate().flowId())
              .orElseThrow(
                  () -> new IOException(
                      "Persisted sending attempt was not returned"));

      requireOwnership.run();

      var current = stored.execution();

      if (current.status()
              != RecoveryPublicationExecution.Status.SENDING
          || !sending.attemptId().equals(current.attemptId())
          || !sending.source().equals(current.source())) {

        throw new IOException(
            "Persisted sending attempt does not match this worker");
      }

      var selectedEvents =
          StreamSupport.stream(
              Spliterators.spliteratorUnknownSize(
                  iterator, Spliterator.ORDERED),
              false);

      var receipt =
          publisher.publish(selectedEvents, requireOwnership);

      requireOwnership.run();

      var sent =
          current.markSent(
              receipt.acknowledgedEvents(),
              receipt.acknowledgedBatches(),
              clock.instant());

      repository.update(sent, stored.etag());

      requireOwnership.run();

      return sent;

    } catch (IOException | RuntimeException failure) {
      preserveUncertainAttempt(
          sending, requireOwnership, failure);

      throw failure;
    }
  }

  private RecoveryPublicationExecution recoverInterruptedAttempt(
      StoredRecoveryPublicationExecution stored,
      Runnable requireOwnership) {

    var uncertain =
        stored.execution().markUncertain(clock.instant());

    requireOwnership.run();
    repository.update(uncertain, stored.etag());
    requireOwnership.run();

    return uncertain;
  }

  private void preserveUncertainAttempt(
      RecoveryPublicationExecution sending,
      Runnable requireOwnership,
      Exception originalFailure) {

    try {
      requireOwnership.run();

      var found =
          repository.find(
              sending.jobId(),
              sending.day(),
              sending.source().candidate().flowId());

      requireOwnership.run();

      if (found.isEmpty()) {
        return;
      }

      var stored = found.orElseThrow();
      var current = stored.execution();

      // A SENT update may have succeeded even if its response was lost.
      if (current.status()
              != RecoveryPublicationExecution.Status.SENDING
          || !sending.attemptId().equals(current.attemptId())
          || !sending.source().equals(current.source())) {

        return;
      }

      var uncertain = current.markUncertain(clock.instant());

      requireOwnership.run();
      repository.update(uncertain, stored.etag());
      requireOwnership.run();

    } catch (RuntimeException cleanupFailure) {
      if (cleanupFailure != originalFailure) {
        originalFailure.addSuppressed(cleanupFailure);
      }
    }
  }
}