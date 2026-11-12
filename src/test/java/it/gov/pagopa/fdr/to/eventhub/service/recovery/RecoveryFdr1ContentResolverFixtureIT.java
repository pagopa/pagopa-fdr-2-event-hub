package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryBlobContentRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFdr1BlobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFdr3BlobContentRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFdr3BlobRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_CONTENT_FIXTURE_TESTS",
    matches = "true")
class RecoveryFdr1ContentResolverFixtureIT {

  private static final String FLOW =
      "2026-10-14ABI88515-B041S00000123456";

  private static final String ORIGINAL_SESSION =
      "1486cdfe-0fe0-44c6-b618-0e67d77eebe2";

  private static final String COPY_SESSION =
      "aeb6bf74-483c-4893-824d-7d7a4fec692b";

  private static final String JSON_SESSION =
      "30a6bdb2-78aa-458a-ab50-eb6a632e30e8";

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void shouldSelectOriginalXmlRegardlessOfListingOrder(
      boolean reverseOrder) throws IOException {

    var candidate = new RecoveryFlowCandidate(
        155850L,
        FLOW,
        2L,
        "ABI88515",
        "00493410583",
        LocalDateTime.parse("2026-10-15T12:00:37"));

    byte[] jsonBytes = fixture(FLOW + "_ABI88515_2.json.zip");
    byte[] originalBytes = fixture(
        FLOW + "_d55c2f82-9b33-4dcc-a065-af4aa5607ecf.xml.zip");
    byte[] copyBytes = fixture(
        FLOW + "_cd8f23b4-5fdc-451e-b3db-6c25773e7048.xml.zip");

    var jsonBlob = new RecoveryBlobSnapshot(
        "fdr3-flows",
        candidate.blobName(),
        "fixture-json-etag",
        jsonBytes.length,
        metadata(
            JSON_SESSION,
            false,
            "FDR03",
            // Synthetic fixture timestamp; not used for content matching.
            "2026-10-15T12:00:37Z"));

    var originalBlob = new RecoveryBlobSnapshot(
        "fdr1-flows",
        FLOW + "_d55c2f82-9b33-4dcc-a065-af4aa5607ecf.xml.zip",
        "0x8DF2AB3FBB49507",
        originalBytes.length,
        metadata(
            ORIGINAL_SESSION,
            true,
            "FDR001",
            "2026-10-15T14:01:01.942940"));

    var copyBlob = new RecoveryBlobSnapshot(
        "fdr1-flows",
        FLOW + "_cd8f23b4-5fdc-451e-b3db-6c25773e7048.xml.zip",
        "0x8DF2AB4AC3338C6",
        copyBytes.length,
        metadata(
            COPY_SESSION,
            false,
            "FDR001",
            "2026-10-15T14:05:57.851192"));

    var jsonRepository = mock(RecoveryFdr3BlobRepository.class);
    var xmlRepository = mock(RecoveryFdr1BlobRepository.class);
    var jsonDownload = mock(RecoveryFdr3BlobContentRepository.class);
    var content = mock(RecoveryBlobContentRepository.class);

    when(jsonRepository.find(candidate))
        .thenReturn(Optional.of(jsonBlob));

    when(xmlRepository.listCandidates(candidate))
        .thenReturn(
            reverseOrder
                ? List.of(originalBlob, copyBlob)
                : List.of(copyBlob, originalBlob));

    when(jsonDownload.download(jsonBlob)).thenReturn(jsonBytes);
    when(content.download(jsonBlob)).thenReturn(jsonBytes);
    when(content.download(originalBlob)).thenReturn(originalBytes);
    when(content.download(copyBlob)).thenReturn(copyBytes);

    var decompressor = new RecoveryBlobDecompressor(8 * 1024 * 1024);
    var payloadDecoder = new RecoveryFdr1PayloadDecoder(8 * 1024 * 1024);
    var dateNormalizer = new RecoveryFdr1DateNormalizer();
    var paymentSelector = new RecoveryPaymentEventSelector();
    var sourceMapper = new RecoveryPublicationSourceMapper(dateNormalizer);

    var jsonVerifier = new RecoveryFdr3SourceVerifier(
        new RecoveryFdr3SourceInspector(
            jsonRepository,
            new RecoveryFdr3BlobMetadataInspector()),
        jsonDownload,
        decompressor,
        new RecoveryFdr3IdentityReader(),
        new RecoveryFdr3IdentityMatcher());

    var xmlInspector = new RecoveryFdr1SourceInspector(
        content,
        decompressor,
        new RecoveryFdr1EnvelopeReader(),
        payloadDecoder,
        new RecoveryFdr1ReportReader(),
        new RecoveryFdr1IdentityMatcher(dateNormalizer));

    var xmlResolver = new RecoveryFdr1SourceResolver(
        xmlRepository,
        new RecoveryFdr1SourceVerifier(
            new RecoveryFdr1BlobMetadataInspector(),
            xmlInspector));

    var jsonReader = new RecoveryFdr3PaymentEventReader(
        content, decompressor, paymentSelector);

    var xmlReader = new RecoveryFdr1PaymentEventReader(
        content, decompressor, payloadDecoder, paymentSelector);

    var before = assertInstanceOf(
        RecoveryFdr1SourceResolver.Unresolved.class,
        xmlResolver.resolve(candidate));

    assertEquals(
        RecoveryFdr1SourceResolver.UnresolvedReason.AMBIGUOUS_MATCH,
        before.reason());

    var contentResolver = new RecoveryFdr1ContentResolver(
        jsonReader,
        xmlReader,
        sourceMapper,
        new RecoveryPaymentContentMatcher());

    var inspector = new RecoveryPublicationSourceInspector(
        jsonVerifier,
        xmlResolver,
        contentResolver);

    var evidence = inspector.inspect(candidate);

    var resolved = assertInstanceOf(
        RecoveryFdr1SourceResolver.Resolved.class,
        evidence.fdr1());

    assertEquals(
        originalBlob,
        resolved.source().source().blob());

    var selector = new RecoveryPublicationSourceSelector(sourceMapper);

    var selected = assertInstanceOf(
        RecoveryPublicationSourceSelector.Selected.class,
        selector.select(evidence));

    var source = selected.source();

    assertEquals(candidate, source.candidate());
    assertEquals(
        RecoveryPublicationSource.SourceType.FDR1_XML,
        source.sourceType());
    assertEquals(ORIGINAL_SESSION, source.sessionId());
    assertEquals(originalBlob, source.blob());
    assertEquals(
        Instant.parse("2026-10-15T12:00:37Z"),
        source.eventFlowDate());

    try (var events = xmlReader.readAll(source)) {
      var payments = events.toList();

      assertEquals(1, payments.size());

      var payment = payments.get(0);

      assertEquals("96000000018127144", payment.getIuv());
      assertEquals(
          "bf9c86fe81b84c7f9c5d73a8799a6f18",
          payment.getIur());
      assertEquals(
          0,
          new BigDecimal("230.73").compareTo(payment.getAmount()));
      assertEquals(ORIGINAL_SESSION, payment.getUniqueId());
    }

    System.out.printf(
        "UAT local fixture verified: reverseOrder=%s, "
            + "flowId=%d, revision=%d, sourceType=%s, "
            + "sessionId=%s, network=none, sends=none%n",
        reverseOrder,
        candidate.flowId(),
        candidate.revision(),
        source.sourceType(),
        source.sessionId());
  }

  private static byte[] fixture(String name) throws IOException {
    String directory = System.getenv("RECOVERY_CONTENT_FIXTURE_DIR");

    if (directory == null || directory.isBlank()) {
      throw new IllegalArgumentException(
          "RECOVERY_CONTENT_FIXTURE_DIR is required");
    }

    return Files.readAllBytes(Path.of(directory).resolve(name));
  }

  private static Map<String, String> metadata(
      String sessionId,
      boolean elaborate,
      String serviceIdentifier,
      String insertedTimestamp) {

    return Map.of(
        "sessionId", sessionId,
        "elaborate", Boolean.toString(elaborate),
        "serviceIdentifier", serviceIdentifier,
        "insertedTimestamp", insertedTimestamp);
  }
}