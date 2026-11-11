package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.azure.storage.blob.BlobServiceClient;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.*;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.*;
import java.util.Objects;

public final class RecoveryPublicationComponentsFactory {

  private RecoveryPublicationComponentsFactory() {
  }

  public static RecoveryPublicationDiscoveryJobService create(
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
        storage.getBlobContainerClient("fdr3-flows");

    var xmlContainer =
        storage.getBlobContainerClient("fdr1-flows");

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
                "fdr1-flows",
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

    var sourceInspector =
        new RecoveryPublicationSourceInspector(
            fdr3Verifier, fdr1Resolver);

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

    return new RecoveryPublicationDiscoveryJobService(
        jobs, persisted);
  }
}