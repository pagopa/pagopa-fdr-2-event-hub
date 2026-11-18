package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.azure.storage.blob.BlobServiceClient;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.*;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.*;
import java.util.Objects;

public final class RecoveryPublicationComponentsFactory {

  private static final String FDR1_CONTAINER_NAME = "fdr1-flows";
  private static final String FDR3_CONTAINER_NAME = "fdr3-flows";

  private RecoveryPublicationComponentsFactory() {
  }

  public record Components(RecoveryJobRepository jobs, RecoveryPersistedPublicationDiscoveryService persisted,
          RecoveryPublicationDiscoveryJobService discovery) {
  }

  public static RecoveryPublicationDiscoveryJobService create(RecoveryTableClients tables, BlobServiceClient storage,
          RecoveryFlowRepository flows, RecoveryAdxRepository adx, RecoveryPublicationConfiguration configuration,
          int pageSize) {

      return createComponents(tables, storage, flows, adx, configuration, pageSize).discovery();
  }

  public static Components createComponents(
          RecoveryTableClients tables,
          BlobServiceClient storage,
          RecoveryFlowRepository flows,
          RecoveryAdxRepository adx,
          RecoveryPublicationConfiguration configuration,
          int pageSize) {

    Objects.requireNonNull(tables, "tables is required");
    Objects.requireNonNull(storage, "storage is required");
    Objects.requireNonNull(flows, "flows is required");
    Objects.requireNonNull(adx, "adx is required");
    Objects.requireNonNull(configuration, "configuration is required");

    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and 100");
    }

    var jsonContainer =
        storage.getBlobContainerClient(FDR3_CONTAINER_NAME);

    var xmlContainer =
        storage.getBlobContainerClient(FDR1_CONTAINER_NAME);

    var decompressor =
        new RecoveryBlobDecompressor(
            configuration.maxDecompressedBytes());

    var dateNormalizer = new RecoveryFdr1DateNormalizer();

    var fdr3Verifier =
        new RecoveryFdr3SourceVerifier(
            new RecoveryFdr3SourceInspector(
                new RecoveryFdr3BlobRepository(jsonContainer),
                new RecoveryFdr3BlobMetadataInspector()),
            new RecoveryFdr3BlobContentRepository(
                jsonContainer,
                configuration.maxCompressedBytes()),
            decompressor,
            new RecoveryFdr3IdentityReader(),
            new RecoveryFdr3IdentityMatcher());

    var fdr1Inspector =
        new RecoveryFdr1SourceInspector(
            new RecoveryBlobContentRepository(
                xmlContainer,
                FDR1_CONTAINER_NAME,
                configuration.maxCompressedBytes()),
            decompressor,
            new RecoveryFdr1EnvelopeReader(),
            new RecoveryFdr1PayloadDecoder(
                configuration.maxInnerXmlBytes()),
            new RecoveryFdr1ReportReader(),
            new RecoveryFdr1IdentityMatcher(dateNormalizer));

    var fdr1Resolver =
        new RecoveryFdr1SourceResolver(
            new RecoveryFdr1BlobRepository(
                xmlContainer,
                configuration.maxFdr1Candidates()),
            new RecoveryFdr1SourceVerifier(
                new RecoveryFdr1BlobMetadataInspector(),
                fdr1Inspector));

    var paymentSelector = new RecoveryPaymentEventSelector();

    var contentResolver =
        new RecoveryFdr1ContentResolver(
            new RecoveryFdr3PaymentEventReader(
                new RecoveryBlobContentRepository(
                    jsonContainer,
                    FDR3_CONTAINER_NAME,
                    configuration.maxCompressedBytes()),
                decompressor,
                paymentSelector),
            new RecoveryFdr1PaymentEventReader(
                new RecoveryBlobContentRepository(
                    xmlContainer,
                    FDR1_CONTAINER_NAME,
                    configuration.maxCompressedBytes()),
                decompressor,
                new RecoveryFdr1PayloadDecoder(
                    configuration.maxInnerXmlBytes()),
                paymentSelector),
            new RecoveryPublicationSourceMapper(dateNormalizer),
            new RecoveryPaymentContentMatcher());

    var sourceInspector =
        new RecoveryPublicationSourceInspector(
            fdr3Verifier,
            fdr1Resolver,
            contentResolver);

    var selector =
        new RecoveryPublicationSourceSelector(
            new RecoveryPublicationSourceMapper(dateNormalizer));

    var batchService =
        new RecoveryPublicationBatchService(
            sourceInspector,
            selector,
            new RecoveryPublicationPresenceService(adx));

    var items =
        new RecoveryPublicationDiscoveryItemRepository(
            tables.items(),
            new RecoveryPublicationDiscoveryItemTableMapper());

    var checkpoints =
        new RecoveryPublicationDiscoveryCheckpointRepository(
            tables.items(),
            new RecoveryPublicationDiscoveryCheckpointTableMapper());

    var pageService =
        new RecoveryPublicationPageService(
            items,
            batchService,
            new RecoveryPublicationDiscoveryItemFactory());

    var persisted =
        new RecoveryPersistedPublicationDiscoveryService(
            flows,
            pageService,
            checkpoints,
            pageSize);

    var jobs = new RecoveryJobRepository(tables.jobs(), RecoveryJobTableMapper.forPublicationDiscovery());

    return new Components(
            jobs,
            persisted,
            new RecoveryPublicationDiscoveryJobService(jobs, persisted));
  }
}