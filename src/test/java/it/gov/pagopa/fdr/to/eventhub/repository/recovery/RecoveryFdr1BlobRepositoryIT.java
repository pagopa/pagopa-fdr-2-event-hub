package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryBlobDecompressor;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1BlobMetadataInspector;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1DateNormalizer;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1EnvelopeReader;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1IdentityMatcher;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1PayloadDecoder;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1ReportReader;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1SourceInspector;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1SourceResolver;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr1SourceVerifier;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3BlobMetadataInspector;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3IdentityMatcher;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3IdentityReader;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3SourceInspector;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryFdr3SourceVerifier;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryPublicationSourceInspector;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryPublicationSourceMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_FDR1_BLOB_INTEGRATION_TESTS",
    matches = "true")
class RecoveryFdr1BlobRepositoryIT {

    // Limits for DEV fixture
    private static final int MAX_COMPRESSED_BYTES = 1024 * 1024;
    private static final int MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_INNER_XML_BYTES = 8 * 1024 * 1024;

    private static final String EXPECTED_ACCOUNT =
      "pagopadweufdrsa";

  private static final String CONTAINER_NAME =
      "fdr1-flows";

  private static final int MAX_CANDIDATES = 20;

  private static final String KNOWN_BLOB_NAME =
      "2026-10-1660000000001-1741953872446"
          + "_377137b2-6193-455a-8ea5-b693e3bb21cd.xml.zip";

  private static final String KNOWN_SESSION_ID =
      "6ecc100f-cd56-422d-815b-8243edc81a84";

  private static final RecoveryFlowCandidate CANDIDATE =
      new RecoveryFlowCandidate(
          592L,
          "2026-10-1660000000001-1741953872446",
          1L,
          "60000000001",
          "15376371009",
          LocalDateTime.of(
              2026, 10, 29, 16, 39, 37, 810_000_000));

  private BlobContainerClient devContainer() {
    String connectionString =
        System.getenv("FDR_SA_CONNECTION_STRING");

    assertNotNull(
        connectionString,
        "FDR_SA_CONNECTION_STRING must target DEV");

    assertTrue(
        !connectionString.isBlank(),
        "FDR_SA_CONNECTION_STRING must not be blank");

    BlobServiceClient service =
        new BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();

    // Validate the destination before performing network operations.
    assertEquals(
        EXPECTED_ACCOUNT,
        service.getAccountName(),
        "This integration test must target DEV");

    return service.getBlobContainerClient(CONTAINER_NAME);
  }

  @Test
  void shouldListKnownXmlCandidateWithoutFilteringElaborate() {
    RecoveryFdr1BlobRepository repository =
        new RecoveryFdr1BlobRepository(
            devContainer(), MAX_CANDIDATES);

    List<RecoveryBlobSnapshot> candidates =
        repository.listCandidates(CANDIDATE);

    assertTrue(!candidates.isEmpty());
    assertTrue(candidates.size() <= MAX_CANDIDATES);

    RecoveryBlobSnapshot known = requireKnownCandidate(candidates);

    assertEquals(CONTAINER_NAME, known.containerName());
    assertTrue(known.contentLength() > 0);
    assertTrue(!known.etag().isBlank());

    assertEquals(
        KNOWN_SESSION_ID,
        requiredMetadata(known, "sessionId"));

    assertEquals(
        "FDR001",
        requiredMetadata(known, "serviceIdentifier"));

    assertEquals(
        "false",
        requiredMetadata(known, "elaborate"));

    System.out.printf(
        "DEV FDR1 candidate listing verified: "
            + "flow=%s, candidates=%d, "
            + "knownSessionId=%s, elaborate=false, writes=none%n",
        CANDIDATE.name(),
        candidates.size(),
        KNOWN_SESSION_ID);
  }

  @Test
  void shouldReturnEmptyForUnknownFlowPrefix() {
    RecoveryFdr1BlobRepository repository =
        new RecoveryFdr1BlobRepository(
            devContainer(), MAX_CANDIDATES);

    RecoveryFlowCandidate missing =
        new RecoveryFlowCandidate(
            1L,
            "it-recovery-missing-" + UUID.randomUUID(),
            1L,
            "60000000001",
            "15376371009",
            LocalDateTime.of(2026, 10, 1, 0, 0));

    assertTrue(repository.listCandidates(missing).isEmpty());

    System.out.println(
        "DEV FDR1 unknown prefix verified: candidates=0, writes=none");
  }

  @Test
  void shouldDownloadAndDecompressKnownXmlCandidate()
      throws IOException {

    BlobContainerClient container = devContainer();

    RecoveryFdr1BlobRepository propertiesRepository =
        new RecoveryFdr1BlobRepository(
            container, MAX_CANDIDATES);

    RecoveryBlobSnapshot snapshot =
        requireKnownCandidate(
            propertiesRepository.listCandidates(CANDIDATE));

    RecoveryBlobContentRepository contentRepository =
        new RecoveryBlobContentRepository(
            container,
            CONTAINER_NAME,
            MAX_COMPRESSED_BYTES);

    byte[] compressed = contentRepository.download(snapshot);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES);

    byte[] decompressed = decompressor.decompress(compressed);

    assertEquals(
        snapshot.contentLength(),
        (long) compressed.length);

    assertTrue(
        decompressed.length > 0,
        "The XML fixture must contain a non-empty payload");

    assertEquals(
        KNOWN_SESSION_ID,
        requiredMetadata(snapshot, "sessionId"));

    System.out.printf(
        "DEV XML download and decompression verified: "
            + "blob=%s, sessionId=%s, "
            + "compressedBytes=%d, decompressedBytes=%d, "
            + "etag=%s, writes=none%n",
        snapshot.blobName(),
        KNOWN_SESSION_ID,
        compressed.length,
        decompressed.length,
        snapshot.etag());
  }

  @Test
  void shouldRejectMismatchedXmlETag() {
    BlobContainerClient container = devContainer();

    RecoveryFdr1BlobRepository propertiesRepository =
        new RecoveryFdr1BlobRepository(
            container, MAX_CANDIDATES);

    RecoveryBlobSnapshot current =
        requireKnownCandidate(
            propertiesRepository.listCandidates(CANDIDATE));

    String mismatchedETag = "\"0x0000000000000000\"";

    assertNotEquals(current.etag(), mismatchedETag);

    RecoveryBlobSnapshot mismatched =
        new RecoveryBlobSnapshot(
            current.containerName(),
            current.blobName(),
            mismatchedETag,
            current.contentLength(),
            current.metadata());

    RecoveryBlobContentRepository contentRepository =
        new RecoveryBlobContentRepository(
            container,
            CONTAINER_NAME,
            MAX_COMPRESSED_BYTES);

    BlobStorageException failure =
        assertThrows(
            BlobStorageException.class,
            () -> contentRepository.download(mismatched));

    assertEquals(412, failure.getStatusCode());
    assertEquals(
        BlobErrorCode.CONDITION_NOT_MET,
        failure.getErrorCode());

    System.out.println(
        "DEV XML mismatched ETag verified: "
            + "status=412, code=ConditionNotMet, writes=none");
  }

  @Test
  void shouldReadOuterIdentityFromDevXml() throws IOException {
    BlobContainerClient container = devContainer();

    RecoveryFdr1BlobRepository propertiesRepository =
        new RecoveryFdr1BlobRepository(
            container, MAX_CANDIDATES);

    RecoveryBlobSnapshot snapshot =
        requireKnownCandidate(
            propertiesRepository.listCandidates(CANDIDATE));

    RecoveryBlobContentRepository contentRepository =
        new RecoveryBlobContentRepository(
            container,
            CONTAINER_NAME,
            MAX_COMPRESSED_BYTES);

    byte[] compressed = contentRepository.download(snapshot);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES);

    byte[] xml = decompressor.decompress(compressed);

    RecoveryFdr1EnvelopeReader.RequestIdentity identity =
        new RecoveryFdr1EnvelopeReader().read(xml);

    assertEquals(CANDIDATE.name(), identity.name());
    assertEquals(
        CANDIDATE.pspDomainId(),
        identity.pspDomainId());
    assertEquals(
        CANDIDATE.orgDomainId(),
        identity.orgDomainId());

    assertTrue(!identity.flowDate().isBlank());

    assertEquals(
        KNOWN_SESSION_ID,
        requiredMetadata(snapshot, "sessionId"));

    System.out.printf(
        "DEV FDR1 outer identity verified: "
            + "flow=%s, psp=%s, organization=%s, "
            + "outerFlowDate=%s, sessionId=%s%n",
        identity.name(),
        identity.pspDomainId(),
        identity.orgDomainId(),
        identity.flowDate(),
        KNOWN_SESSION_ID);
  }

  @Test
  void shouldDecodeInnerPayloadFromDevXml() throws IOException {
    BlobContainerClient container = devContainer();

    RecoveryFdr1BlobRepository propertiesRepository =
        new RecoveryFdr1BlobRepository(
            container, MAX_CANDIDATES);

    RecoveryBlobSnapshot snapshot =
        requireKnownCandidate(
            propertiesRepository.listCandidates(CANDIDATE));

    RecoveryBlobContentRepository contentRepository =
        new RecoveryBlobContentRepository(
            container,
            CONTAINER_NAME,
            MAX_COMPRESSED_BYTES);

    byte[] compressed = contentRepository.download(snapshot);

    RecoveryBlobDecompressor decompressor =
        new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES);

    byte[] outerXml = decompressor.decompress(compressed);

    RecoveryFdr1PayloadDecoder decoder =
        new RecoveryFdr1PayloadDecoder(MAX_INNER_XML_BYTES);

    byte[] innerXml = decoder.decode(outerXml);

    assertTrue(
        innerXml.length > 0,
        "The DEV fixture must contain a non-empty inner payload");

    assertTrue(innerXml.length <= MAX_INNER_XML_BYTES);

    assertEquals(
        KNOWN_SESSION_ID,
        requiredMetadata(snapshot, "sessionId"));

    System.out.printf(
        "DEV FDR1 Base64 decoding verified: "
            + "blob=%s, outerXmlBytes=%d, innerXmlBytes=%d, "
            + "sessionId=%s, writes=none%n",
        snapshot.blobName(),
        outerXml.length,
        innerXml.length,
        KNOWN_SESSION_ID);
  }

  @Test
  void shouldReadInnerReportIdentityFromDevXml() throws Exception {
    var container = devContainer();

    var repository =
        new RecoveryFdr1BlobRepository(container, MAX_CANDIDATES);

    var snapshot =
        requireKnownCandidate(repository.listCandidates(CANDIDATE));

    var contentRepository =
        new RecoveryBlobContentRepository(
            container,
            CONTAINER_NAME,
            MAX_COMPRESSED_BYTES);

    byte[] compressed = contentRepository.download(snapshot);

    byte[] outerXml =
        new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES)
            .decompress(compressed);

    var outerIdentity =
        new RecoveryFdr1EnvelopeReader().read(outerXml);

    byte[] innerXml =
        new RecoveryFdr1PayloadDecoder(MAX_INNER_XML_BYTES)
            .decode(outerXml);

    var report =
        new RecoveryFdr1ReportReader().read(innerXml);

    assertEquals(CANDIDATE.name(), outerIdentity.name());
    assertEquals(CANDIDATE.pspDomainId(), outerIdentity.pspDomainId());
    assertEquals(CANDIDATE.orgDomainId(), outerIdentity.orgDomainId());

    assertEquals(
        outerIdentity.name(),
        report.name(),
        "The request and the inner report must identify the expected flow");

    assertTrue(
        report.observedPaymentCount() > 0,
        "The known DEV report must contain payments");

    assertEquals(
        report.declaredPaymentCount(),
        report.observedPaymentCount(),
        "Declared and observed payment counts differ in the DEV fixture");

    String sessionId = requiredMetadata(snapshot, "sessionId");
    assertEquals(KNOWN_SESSION_ID, sessionId);

    var dateNormalizer = new RecoveryFdr1DateNormalizer();

    var comparison =
        new RecoveryFdr1IdentityMatcher(dateNormalizer)
            .compare(CANDIDATE, outerIdentity);

    assertTrue(
        comparison.differences().stream()
            .allMatch(
                field ->
                    field == RecoveryFdr1IdentityMatcher.Field.FLOW_DATE),
        "Unexpected mismatch on flow name, PSP or organization");

    System.out.printf(
        "DEV FDR1 comparison: "
            + "fixtureFlowId=%d, fixtureRevision=%d, "
            + "fixturePostgresDate=%s, "
            + "normalizedOuterDate=%s, normalizedInnerDate=%s, "
            + "matches=%s, differences=%s%n",
        CANDIDATE.flowId(),
        CANDIDATE.revision(),
        CANDIDATE.flowDate(),
        dateNormalizer.normalize(outerIdentity.flowDate()),
        dateNormalizer.normalize(report.flowDate()),
        comparison.matches(),
        comparison.differences());

    System.out.printf(
        "DEV FDR1 inner report verified: "
            + "flow=%s, outerPsp=%s, outerOrganization=%s, "
            + "innerSenderType=%s, innerSenderId=%s, "
            + "innerReceiverType=%s, innerReceiverId=%s, "
            + "outerFlowDate=%s, innerFlowDate=%s, "
            + "declaredPayments=%d, observedPayments=%d, "
            + "sessionId=%s, writes=none%n",
        report.name(),
        outerIdentity.pspDomainId(),
        outerIdentity.orgDomainId(),
        report.senderType(),
        report.senderId(),
        report.receiverType(),
        report.receiverId(),
        outerIdentity.flowDate(),
        report.flowDate(),
        report.declaredPaymentCount(),
        report.observedPaymentCount(),
        sessionId);
  }

  @Test
  void shouldInspectDevSourceAgainstBothPublishedRevisions()
      throws Exception {

    var container = devContainer();

    var blobRepository =
        new RecoveryFdr1BlobRepository(container, MAX_CANDIDATES);

    var snapshot =
        requireKnownCandidate(blobRepository.listCandidates(CANDIDATE));

    var inspector =
        new RecoveryFdr1SourceInspector(
            new RecoveryBlobContentRepository(
                container,
                CONTAINER_NAME,
                MAX_COMPRESSED_BYTES),
            new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES),
            new RecoveryFdr1EnvelopeReader(),
            new RecoveryFdr1PayloadDecoder(MAX_INNER_XML_BYTES),
            new RecoveryFdr1ReportReader(),
            new RecoveryFdr1IdentityMatcher(
                new RecoveryFdr1DateNormalizer()));

    var historicalCandidate =
        new RecoveryFlowCandidate(
            592L,
            CANDIDATE.name(),
            1L,
            CANDIDATE.pspDomainId(),
            CANDIDATE.orgDomainId(),
            LocalDateTime.parse("2026-10-29T16:39:37.810"));

    var latestCandidate =
        new RecoveryFlowCandidate(
            593L,
            CANDIDATE.name(),
            2L,
            CANDIDATE.pspDomainId(),
            CANDIDATE.orgDomainId(),
            LocalDateTime.parse("2026-10-29T16:39:37"));

    var historicalResult =
        inspector.inspect(historicalCandidate, snapshot);

    var latestResult =
        inspector.inspect(latestCandidate, snapshot);

    assertEquals(
        Set.of(RecoveryFdr1IdentityMatcher.Field.FLOW_DATE),
        historicalResult.comparison().differences());

    assertTrue(
        latestResult.comparison().matches(),
        "The XML outer identity must match the verified revision 2 fixture");

    assertEquals(snapshot, historicalResult.blob());
    assertEquals(snapshot, latestResult.blob());

    assertEquals(
        historicalResult.envelope(),
        latestResult.envelope());

    assertEquals(
        historicalResult.report(),
        latestResult.report());

    assertEquals(
        CANDIDATE.name(),
        latestResult.report().name());

    assertEquals(1L, latestResult.report().declaredPaymentCount());
    assertEquals(1L, latestResult.report().observedPaymentCount());

    String sessionId = requiredMetadata(snapshot, "sessionId");
    assertEquals(KNOWN_SESSION_ID, sessionId);

    System.out.printf(
        "DEV composed FDR1 inspection verified: "
            + "blob=%s, historicalFlowId=%d, historicalDifferences=%s, "
            + "latestFlowId=%d, latestMatches=%s, "
            + "outerFlowDate=%s, innerFlowDate=%s, "
            + "sessionId=%s, writes=none%n",
        snapshot.blobName(),
        historicalCandidate.flowId(),
        historicalResult.comparison().differences(),
        latestCandidate.flowId(),
        latestResult.comparison().matches(),
        latestResult.envelope().flowDate(),
        latestResult.report().flowDate(),
        sessionId);
  }

  @Test
  void shouldVerifyDevFdr1SourceIncludingMetadata() throws Exception {
    var container = devContainer();

    var blobRepository =
        new RecoveryFdr1BlobRepository(container, MAX_CANDIDATES);

    var snapshot =
        requireKnownCandidate(blobRepository.listCandidates(CANDIDATE));

    var candidate =
        new RecoveryFlowCandidate(
            593L,
            CANDIDATE.name(),
            2L,
            CANDIDATE.pspDomainId(),
            CANDIDATE.orgDomainId(),
            LocalDateTime.parse("2026-10-29T16:39:37"));

    var sourceInspector =
        new RecoveryFdr1SourceInspector(
            new RecoveryBlobContentRepository(
                container,
                CONTAINER_NAME,
                MAX_COMPRESSED_BYTES),
            new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES),
            new RecoveryFdr1EnvelopeReader(),
            new RecoveryFdr1PayloadDecoder(MAX_INNER_XML_BYTES),
            new RecoveryFdr1ReportReader(),
            new RecoveryFdr1IdentityMatcher(
                new RecoveryFdr1DateNormalizer()));

    var verifier =
        new RecoveryFdr1SourceVerifier(
            new RecoveryFdr1BlobMetadataInspector(),
            sourceInspector);

    var result = verifier.verify(candidate, snapshot);

    var inspected =
        assertInstanceOf(
            RecoveryFdr1SourceVerifier.Inspected.class,
            result);

    assertEquals(KNOWN_SESSION_ID, inspected.metadata().sessionId());
    assertFalse(inspected.metadata().elaborate());

    var source = inspected.source();

    assertEquals(candidate, source.candidate());
    assertEquals(snapshot, source.blob());
    assertTrue(source.comparison().matches());

    assertEquals(candidate.name(), source.envelope().name());
    assertEquals(candidate.pspDomainId(), source.envelope().pspDomainId());
    assertEquals(candidate.orgDomainId(), source.envelope().orgDomainId());

    assertEquals(candidate.name(), source.report().name());
    assertEquals(1L, source.report().declaredPaymentCount());
    assertEquals(1L, source.report().observedPaymentCount());

    System.out.printf(
        "DEV FDR1 source verification completed: "
            + "flowId=%d, revision=%d, blob=%s, "
            + "sessionId=%s, elaborate=%s, identityMatches=%s, "
            + "outerFlowDate=%s, innerFlowDate=%s, "
            + "observedPayments=%d, writes=none%n",
        candidate.flowId(),
        candidate.revision(),
        source.blob().blobName(),
        inspected.metadata().sessionId(),
        inspected.metadata().elaborate(),
        source.comparison().matches(),
        source.envelope().flowDate(),
        source.report().flowDate(),
        source.report().observedPaymentCount());
  }

  @Test
  void shouldResolveDevOriginalAgainstBothPublishedRevisions()
      throws Exception {

    var container = devContainer();

    var sourceInspector =
        new RecoveryFdr1SourceInspector(
            new RecoveryBlobContentRepository(
                container,
                CONTAINER_NAME,
                MAX_COMPRESSED_BYTES),
            new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES),
            new RecoveryFdr1EnvelopeReader(),
            new RecoveryFdr1PayloadDecoder(MAX_INNER_XML_BYTES),
            new RecoveryFdr1ReportReader(),
            new RecoveryFdr1IdentityMatcher(
                new RecoveryFdr1DateNormalizer()));

    var resolver =
        new RecoveryFdr1SourceResolver(
            new RecoveryFdr1BlobRepository(
                container, MAX_CANDIDATES),
            new RecoveryFdr1SourceVerifier(
                new RecoveryFdr1BlobMetadataInspector(),
                sourceInspector));

    var historicalCandidate =
        new RecoveryFlowCandidate(
            592L,
            CANDIDATE.name(),
            1L,
            CANDIDATE.pspDomainId(),
            CANDIDATE.orgDomainId(),
            LocalDateTime.parse("2026-10-29T16:39:37.810"));

    var latestCandidate =
        new RecoveryFlowCandidate(
            593L,
            CANDIDATE.name(),
            2L,
            CANDIDATE.pspDomainId(),
            CANDIDATE.orgDomainId(),
            LocalDateTime.parse("2026-10-29T16:39:37"));

    var historicalResult = resolver.resolve(historicalCandidate);

    var unresolved =
        assertInstanceOf(
            RecoveryFdr1SourceResolver.Unresolved.class,
            historicalResult);

    assertEquals(
        RecoveryFdr1SourceResolver.UnresolvedReason.NO_MATCH,
        unresolved.reason());

    assertEquals(1, unresolved.inspections().size());

    var historicalInspection =
        assertInstanceOf(
            RecoveryFdr1SourceVerifier.Inspected.class,
            unresolved.inspections().get(0));

    assertEquals(
        Set.of(RecoveryFdr1IdentityMatcher.Field.FLOW_DATE),
        historicalInspection.source().comparison().differences());

    var latestResult = resolver.resolve(latestCandidate);

    var resolved =
        assertInstanceOf(
            RecoveryFdr1SourceResolver.Resolved.class,
            latestResult);

    var inspected = resolved.source();
    var source = inspected.source();

    assertEquals(latestCandidate, source.candidate());
    assertEquals(KNOWN_BLOB_NAME, source.blob().blobName());
    assertEquals(KNOWN_SESSION_ID, inspected.metadata().sessionId());
    assertFalse(inspected.metadata().elaborate());
    assertTrue(source.comparison().matches());

    assertEquals(latestCandidate.name(), source.report().name());
    assertEquals(1L, source.report().declaredPaymentCount());
    assertEquals(1L, source.report().observedPaymentCount());

    var publicationSource = new RecoveryPublicationSourceMapper(new RecoveryFdr1DateNormalizer()).map(resolved);

    assertEquals(latestCandidate, publicationSource.candidate());
    assertEquals(source.blob(), publicationSource.blob());

    assertEquals(RecoveryPublicationSource.SourceType.FDR1_XML, publicationSource.sourceType());

    assertEquals(KNOWN_SESSION_ID, publicationSource.sessionId());

    assertEquals(Instant.parse("2026-10-29T16:39:37Z"), publicationSource.eventFlowDate());

    System.out.printf(
            "DEV common publication source verified: " + "flowId=%d, revision=%d, sourceType=%s, "
                    + "blob=%s, etag=%s, sessionId=%s, " + "eventFlowDate=%s, writes=none%n",
            publicationSource.candidate().flowId(), publicationSource.candidate().revision(),
            publicationSource.sourceType(), publicationSource.blob().blobName(), publicationSource.blob().etag(),
            publicationSource.sessionId(), publicationSource.eventFlowDate());

    System.out.printf(
        "DEV FDR1 resolution verified: "
            + "historicalFlowId=%d, historicalReason=%s, "
            + "latestFlowId=%d, resolvedBlob=%s, "
            + "sessionId=%s, elaborate=%s, writes=none%n",
        historicalCandidate.flowId(),
        unresolved.reason(),
        latestCandidate.flowId(),
        source.blob().blobName(),
        inspected.metadata().sessionId(),
        inspected.metadata().elaborate());
  }

  @Test
  void shouldInspectBothDevPublicationSources() throws Exception {
    String connectionString = System.getenv("FDR_SA_CONNECTION_STRING");

    assertNotNull(
        connectionString,
        "FDR_SA_CONNECTION_STRING is required");

    assertFalse(
        connectionString.isBlank(),
        "FDR_SA_CONNECTION_STRING must not be blank");

    var service =
        new BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();

    assertEquals(
        EXPECTED_ACCOUNT,
        service.getAccountName(),
        "This integration test must target the verified DEV account");

    var xmlContainer = service.getBlobContainerClient("fdr1-flows");
    var jsonContainer = service.getBlobContainerClient("fdr3-flows");

    var candidate =
        new RecoveryFlowCandidate(
            593L,
            CANDIDATE.name(),
            2L,
            CANDIDATE.pspDomainId(),
            CANDIDATE.orgDomainId(),
            LocalDateTime.parse("2026-10-29T16:39:37"));

    var fdr3Verifier =
        new RecoveryFdr3SourceVerifier(
            new RecoveryFdr3SourceInspector(
                new RecoveryFdr3BlobRepository(jsonContainer),
                new RecoveryFdr3BlobMetadataInspector()),
            new RecoveryFdr3BlobContentRepository(
                jsonContainer, MAX_COMPRESSED_BYTES),
            new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES),
            new RecoveryFdr3IdentityReader(),
            new RecoveryFdr3IdentityMatcher());

    var fdr1Inspector =
        new RecoveryFdr1SourceInspector(
            new RecoveryBlobContentRepository(
                xmlContainer,
                CONTAINER_NAME,
                MAX_COMPRESSED_BYTES),
            new RecoveryBlobDecompressor(MAX_DECOMPRESSED_BYTES),
            new RecoveryFdr1EnvelopeReader(),
            new RecoveryFdr1PayloadDecoder(MAX_INNER_XML_BYTES),
            new RecoveryFdr1ReportReader(),
            new RecoveryFdr1IdentityMatcher(
                new RecoveryFdr1DateNormalizer()));

    var fdr1Resolver =
        new RecoveryFdr1SourceResolver(
            new RecoveryFdr1BlobRepository(
                xmlContainer, MAX_CANDIDATES),
            new RecoveryFdr1SourceVerifier(
                new RecoveryFdr1BlobMetadataInspector(),
                fdr1Inspector));

    var inspector =
        new RecoveryPublicationSourceInspector(
            fdr3Verifier, fdr1Resolver);

    var result = inspector.inspect(candidate);

    assertEquals(candidate, result.candidate());

    System.out.printf(
        "DEV combined source inspection: jsonResult=%s, xmlResult=%s%n",
        result.fdr3().getClass().getSimpleName(),
        result.fdr1().getClass().getSimpleName());

    if (result.fdr3()
        instanceof RecoveryFdr3SourceVerifier.IdentityMismatch mismatch) {

      System.out.printf(
          "DEV JSON identity differences: %s%n",
          mismatch.comparison().differences());
    }

    var json =
        assertInstanceOf(
            RecoveryFdr3SourceVerifier.Verified.class,
            result.fdr3());

    var xml =
        assertInstanceOf(
            RecoveryFdr1SourceResolver.Resolved.class,
            result.fdr1());

    var mapper =
        new RecoveryPublicationSourceMapper(
            new RecoveryFdr1DateNormalizer());

    var jsonSource = mapper.map(json);
    var xmlSource = mapper.map(xml);

    assertEquals(candidate.blobName(), jsonSource.blob().blobName());
    assertEquals(KNOWN_BLOB_NAME, xmlSource.blob().blobName());
    assertEquals(KNOWN_SESSION_ID, xmlSource.sessionId());

    assertEquals(
        RecoveryPublicationSource.SourceType.FDR3_JSON,
        jsonSource.sourceType());

    assertEquals(
        RecoveryPublicationSource.SourceType.FDR1_XML,
        xmlSource.sourceType());

    System.out.printf(
        "DEV publication source evidence: "
            + "flowId=%d, revision=%d, "
            + "jsonSessionId=%s, jsonElaborate=%s, jsonEventDate=%s, "
            + "xmlSessionId=%s, xmlElaborate=%s, xmlEventDate=%s, "
            + "writes=none%n",
        candidate.flowId(),
        candidate.revision(),
        jsonSource.sessionId(),
        json.metadata().elaborate(),
        jsonSource.eventFlowDate(),
        xmlSource.sessionId(),
        xml.source().metadata().elaborate(),
        xmlSource.eventFlowDate());
  }

  private static String requiredMetadata(
      RecoveryBlobSnapshot snapshot,
      String name) {

    List<String> values =
        snapshot.metadata().entrySet().stream()
            .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
            .map(Map.Entry::getValue)
            .toList();

    assertEquals(
        1,
        values.size(),
        "Expected exactly one metadata entry for " + name);

    String value = values.get(0);

    assertTrue(
        !value.isBlank(),
        "Metadata must not be blank: " + name);

    return value;
  }

  private static RecoveryBlobSnapshot requireKnownCandidate(List<RecoveryBlobSnapshot> candidates) {

      return candidates.stream().filter(blob -> KNOWN_BLOB_NAME.equals(blob.blobName())).findFirst()
              .orElseThrow(() -> new IllegalStateException("The verified DEV XML fixture is missing"));
  }
}