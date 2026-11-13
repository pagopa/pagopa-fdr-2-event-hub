package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.storage.blob.BlobServiceClient;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryBlobContentRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryCheckpointTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryItemTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationDiscoveryPageRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationExecutionRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryPublicationExecutionTableMapper;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryBlobDecompressor;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryExecutionJobWorkerStateService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1PaymentEventReader;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1PayloadDecoder;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3PaymentEventReader;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobWorkerStateService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryPaymentEventPublisher;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryPaymentEventSelector;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryPublicationExecutionDayService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryPublicationExecutionJobService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryPublicationExecutionService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryPublicationPresenceService;
import java.time.Clock;
import java.util.Objects;

public final class RecoveryExecutionComponentsFactory {

  private RecoveryExecutionComponentsFactory() {
  }

  public static RecoveryPublicationExecutionJobService create(
      RecoveryTableClients tables,
      BlobServiceClient storage,
      RecoveryAdxRepository adx,
      RecoveryPublicationConfiguration configuration,
      int pageSize,
      RecoveryPublicationComponentsFactory.Components discovery,
      EventHubProducerClient producer) {

    Objects.requireNonNull(tables, "tables is required");
    Objects.requireNonNull(storage, "storage is required");
    Objects.requireNonNull(adx, "adx is required");
    Objects.requireNonNull(configuration, "configuration is required");
    Objects.requireNonNull(discovery, "discovery is required");
    Objects.requireNonNull(producer, "producer is required");

    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and 100");
    }

    var clock = Clock.systemUTC();

    var decompressor =
        new RecoveryBlobDecompressor(
            configuration.maxDecompressedBytes());

    var selector = new RecoveryPaymentEventSelector();

    var jsonReader =
        new RecoveryFdr3PaymentEventReader(
            new RecoveryBlobContentRepository(
                storage.getBlobContainerClient("fdr3-flows"),
                "fdr3-flows",
                configuration.maxCompressedBytes()),
            decompressor,
            selector);

    var xmlReader =
        new RecoveryFdr1PaymentEventReader(
            new RecoveryBlobContentRepository(
                storage.getBlobContainerClient("fdr1-flows"),
                "fdr1-flows",
                configuration.maxCompressedBytes()),
            decompressor,
            new RecoveryFdr1PayloadDecoder(
                configuration.maxInnerXmlBytes()),
            selector);

    var executions =
        new RecoveryPublicationExecutionService(
            new RecoveryPublicationExecutionRepository(
                tables.items(),
                new RecoveryPublicationExecutionTableMapper()),
            new RecoveryPublicationPresenceService(adx),
            jsonReader,
            xmlReader,
            new RecoveryPaymentEventPublisher(producer),
            clock);

    var dayExecution =
        new RecoveryPublicationExecutionDayService(
            new RecoveryPublicationDiscoveryCheckpointRepository(
                tables.items(),
                new RecoveryPublicationDiscoveryCheckpointTableMapper()),
            new RecoveryPublicationDiscoveryPageRepository(
                tables.items(),
                new RecoveryPublicationDiscoveryItemTableMapper()),
            executions,
            pageSize);

    return new RecoveryPublicationExecutionJobService(
        discovery.jobs(),
        discovery.persisted(),
        dayExecution,
        new RecoveryJobWorkerStateService(
            discovery.jobs(), clock),
        new RecoveryExecutionJobWorkerStateService(
            discovery.jobs(), clock));
  }
}