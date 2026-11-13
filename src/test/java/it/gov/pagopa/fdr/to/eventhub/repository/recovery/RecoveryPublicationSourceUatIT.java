package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxClientFactory;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxConfiguration;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_UAT_SOURCE_INTEGRATION_TESTS",
    matches = "true")
class RecoveryPublicationSourceUatIT {

  private static final String EXPECTED_ACCOUNT = "pagopauweufdrsa";

  // Limits for these integration fixtures, not production defaults.
  private static final int MAX_COMPRESSED_BYTES = 1024 * 1024;
  private static final int MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024;
  private static final int MAX_INNER_XML_BYTES = 8 * 1024 * 1024;
  private static final int MAX_CANDIDATES = 20;

  @ParameterizedTest
  @MethodSource("candidates")
  void shouldInspectUatSources(
      RecoveryFlowCandidate candidate,
      String expectedNativeJsonSession,
      boolean expectedJsonElaborate,
      String expectedOriginalXmlSession) throws Exception {

    var inspector = inspector(uatService());

    var result = inspector.inspect(candidate);

    System.out.printf(
        "UAT source inspection: flowId=%d, revision=%d, "
            + "jsonResult=%s, xmlResult=%s%n",
        candidate.flowId(),
        candidate.revision(),
        result.fdr3().getClass().getSimpleName(),
        result.fdr1().getClass().getSimpleName());

    if (result.fdr3()
        instanceof RecoveryFdr3SourceVerifier.IdentityMismatch mismatch) {

      System.out.printf(
          "UAT JSON differences: flowId=%d, differences=%s%n",
          candidate.flowId(),
          mismatch.comparison().differences());
    }

    var json =
        assertInstanceOf(
            RecoveryFdr3SourceVerifier.Verified.class,
            result.fdr3());

    assertEquals(
        expectedJsonElaborate,
        json.metadata().elaborate());

    if (expectedNativeJsonSession != null) {
      assertEquals(
          expectedNativeJsonSession,
          json.metadata().sessionId());
    }

    System.out.printf(
        "UAT JSON evidence: flowId=%d, sessionId=%s, "
            + "elaborate=%s, flowDate=%s%n",
        candidate.flowId(),
        json.metadata().sessionId(),
        json.metadata().elaborate(),
        json.identity().flowDate());

    var xmlInspections = xmlInspections(result.fdr1(), candidate);

    printXmlEvidence(candidate, xmlInspections);

    if (expectedOriginalXmlSession != null) {
        assertTrue(
                xmlInspections.stream().filter(RecoveryFdr1SourceVerifier.Inspected.class::isInstance)
                        .map(RecoveryFdr1SourceVerifier.Inspected.class::cast)
                        .anyMatch(inspected -> expectedOriginalXmlSession.equals(inspected.metadata().sessionId())),
                "The known original XML must be inspected even if dates differ");
        var resolved = assertInstanceOf(RecoveryFdr1SourceResolver.Resolved.class, result.fdr1());

        var publicationSource = new RecoveryPublicationSourceMapper(new RecoveryFdr1DateNormalizer()).map(resolved);

        assertEquals(expectedOriginalXmlSession, publicationSource.sessionId());

        assertEquals(java.time.Instant.parse("2026-10-08T23:00:06.208475643Z"), publicationSource.eventFlowDate());

        var inspected = resolved.source();

        var outerDate = new RecoveryFdr1DateNormalizer().normalize(inspected.source().envelope().flowDate());

        var postgresDate = candidate.flowDate().toInstant(ZoneOffset.UTC);

        assertEquals(RecoveryFdr1FlowDateMatcher.Match.MILLISECOND_PROJECTION,
                new RecoveryFdr1FlowDateMatcher().compare(postgresDate, outerDate));

        System.out.printf(
                "UAT original XML resolved: flowId=%d, "
                        + "sessionId=%s, dateMatch=%s, eventFlowDate=%s, writes=none%n",
                candidate.flowId(), publicationSource.sessionId(),
                RecoveryFdr1FlowDateMatcher.Match.MILLISECOND_PROJECTION, publicationSource.eventFlowDate());
    }

    var selector = new RecoveryPublicationSourceSelector(
            new RecoveryPublicationSourceMapper(new RecoveryFdr1DateNormalizer()));

    var selection = selector.select(result);

    var selected = assertInstanceOf(RecoveryPublicationSourceSelector.Selected.class, selection);

    boolean expectedXml = expectedOriginalXmlSession != null;

    var expectedType = expectedXml
            ? it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource.SourceType.FDR1_XML
            : it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource.SourceType.FDR3_JSON;

    String expectedSession = expectedXml ? expectedOriginalXmlSession : expectedNativeJsonSession;

    assertEquals(candidate, selected.source().candidate());
    assertEquals(expectedType, selected.source().sourceType());
    assertEquals(expectedSession, selected.source().sessionId());

    if (expectedXml) {
        assertEquals(java.time.Instant.parse("2026-10-08T23:00:06.208475643Z"), selected.source().eventFlowDate());
    } else {
        assertEquals(json.identity().flowDate(), selected.source().eventFlowDate());
    }

    System.out.printf(
            "UAT publication source selected: " + "flowId=%d, revision=%d, sourceType=%s, "
                    + "sessionId=%s, blob=%s, eventFlowDate=%s, writes=none%n",
            candidate.flowId(), candidate.revision(), selected.source().sourceType(), selected.source().sessionId(),
            selected.source().blob().blobName(), selected.source().eventFlowDate());
  }

  @Test
  @EnabledIfEnvironmentVariable(
      named = "RECOVERY_ADX_UAT_INTEGRATION_TESTS",
      matches = "true")
  void shouldCompareSelectedUatPublicationsAgainstAdx()
      throws Exception {

    String clusterUrl =
        System.getenv("RECOVERY_ADX_UAT_CLUSTER_URL");

    assertNotNull(
        clusterUrl, "RECOVERY_ADX_UAT_CLUSTER_URL is required");

    assertEquals(
        "https://pagopaudataexplorer.westeurope.kusto.windows.net",
        clusterUrl,
        "This integration test must target the verified UAT cluster");

    var configuration =
        new RecoveryAdxConfiguration(
            clusterUrl,
            "re",
            RecoveryAdxConfiguration.AuthMode.AZURE_CLI,
            60);

    var factory = new RecoveryAdxClientFactory(configuration);

    var repository =
        new RecoveryAdxRepository(factory.client(), "re", 60);

    var batchService =
        new RecoveryPublicationBatchService(
            inspector(uatService()),
            new RecoveryPublicationSourceSelector(
                new RecoveryPublicationSourceMapper(
                    new RecoveryFdr1DateNormalizer())),
            new RecoveryPublicationPresenceService(repository));

    var nativeCandidates =
        List.of(
            nativeCandidate(
                155333L, 1L, "2026-10-05T10:31:35"),
            nativeCandidate(
                155336L, 2L, "2026-10-05T10:32:35"),
            nativeCandidate(
                155735L, 3L, "2026-10-05T15:32:35"));

    var expectedNativeSessions =
        Map.of(
            155333L, "6fd3e2eb-1d1d-4068-ba74-fa9866141524",
            155336L, "24fea4e9-c5d3-4d21-b74b-d51be0774d11",
            155735L, "6456ec11-a040-4129-97de-b75369990280");

    LocalDate nativeDay = LocalDate.of(2026, 10, 1);

    var nativeResults =
        batchService.compare(nativeDay, nativeCandidates);

    assertBatchPresent(
        nativeDay,
        nativeResults,
        expectedNativeSessions,
        RecoveryPublicationSource.SourceType.FDR3_JSON);

    var xmlCandidate =
        new RecoveryFlowCandidate(
            155487L,
            "2026-10-09ABI06270-PPA7777801000620",
            1L,
            "ABI06270",
            "77777777777",
            LocalDateTime.parse("2026-10-08T23:00:06.208"));

    LocalDate xmlDay = LocalDate.of(2026, 10, 8);

    var xmlResults =
        batchService.compare(xmlDay, List.of(xmlCandidate));

    assertBatchPresent(
        xmlDay,
        xmlResults,
        Map.of(
            155487L, "ec92305b-47b2-4553-9599-ff086c5dfd93"),
        RecoveryPublicationSource.SourceType.FDR1_XML);

    var xmlCompared =
        assertInstanceOf(
            RecoveryPublicationBatchService.Compared.class,
            xmlResults.get(0));

    assertEquals(
        java.time.Instant.parse(
            "2026-10-08T23:00:06.208475643Z"),
        xmlCompared.comparison().source().eventFlowDate());
  }

  private static List<RecoveryFdr1SourceVerifier.Result> xmlInspections(
      RecoveryFdr1SourceResolver.Resolution resolution,
      RecoveryFlowCandidate candidate) {

    if (resolution
        instanceof RecoveryFdr1SourceResolver.Resolved resolved) {

      return List.of(resolved.source());
    }

    var unresolved =
        (RecoveryFdr1SourceResolver.Unresolved) resolution;

    System.out.printf(
        "UAT XML unresolved: flowId=%d, reason=%s, inspected=%d%n",
        candidate.flowId(),
        unresolved.reason(),
        unresolved.inspections().size());

    return unresolved.inspections();
  }

  private static void printXmlEvidence(
      RecoveryFlowCandidate candidate,
      List<RecoveryFdr1SourceVerifier.Result> inspections) {

    var normalizer = new RecoveryFdr1DateNormalizer();
    var postgresDate = candidate.flowDate().toInstant(ZoneOffset.UTC);

    for (var result : inspections) {
      if (result
          instanceof RecoveryFdr1SourceVerifier.MetadataUnresolved issue) {

        System.out.printf(
            "UAT XML metadata issue: blob=%s, reason=%s, field=%s%n",
            issue.blob().blobName(),
            issue.issue().reason(),
            issue.issue().field());

        continue;
      }

      var inspected = (RecoveryFdr1SourceVerifier.Inspected) result;
      var source = inspected.source();

      var outerDate =
          normalizer.normalize(source.envelope().flowDate());

      var innerDate =
          normalizer.normalize(source.report().flowDate());

      System.out.printf(
          "UAT XML evidence: flowId=%d, blob=%s, sessionId=%s, "
              + "elaborate=%s, postgresDate=%s, "
              + "outerDate=%s, innerDate=%s, "
              + "outerMinusPostgres=%s, differences=%s, "
              + "declaredPayments=%d, observedPayments=%d, writes=none%n",
          candidate.flowId(),
          source.blob().blobName(),
          inspected.metadata().sessionId(),
          inspected.metadata().elaborate(),
          postgresDate,
          outerDate,
          innerDate,
          Duration.between(postgresDate, outerDate),
          source.comparison().differences(),
          source.report().declaredPaymentCount(),
          source.report().observedPaymentCount());
    }
  }

  private static BlobServiceClient uatService() {
    String connectionString =
        System.getenv("RECOVERY_UAT_STORAGE_CONNECTION_STRING");

    assertNotNull(
        connectionString,
        "RECOVERY_UAT_STORAGE_CONNECTION_STRING is required");

    assertFalse(connectionString.isBlank());

    var service =
        new BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();

    assertEquals(
        EXPECTED_ACCOUNT,
        service.getAccountName(),
        "This integration test must target the verified UAT account");

    return service;
  }

  private static RecoveryPublicationSourceInspector inspector(
      BlobServiceClient service) {

    var jsonContainer = service.getBlobContainerClient("fdr3-flows");
    var xmlContainer = service.getBlobContainerClient("fdr1-flows");

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
                "fdr1-flows",
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

    return new RecoveryPublicationSourceInspector(
        fdr3Verifier, fdr1Resolver);
  }

  private static Stream<Arguments> candidates() {
    return Stream.of(
        Arguments.of(
            nativeCandidate(
                155333L, 1L, "2026-10-05T10:31:35"),
            "6fd3e2eb-1d1d-4068-ba74-fa9866141524",
            true,
            null),
        Arguments.of(
            nativeCandidate(
                155336L, 2L, "2026-10-05T10:32:35"),
            "24fea4e9-c5d3-4d21-b74b-d51be0774d11",
            true,
            null),
        Arguments.of(
            nativeCandidate(
                155735L, 3L, "2026-10-05T15:32:35"),
            "6456ec11-a040-4129-97de-b75369990280",
            true,
            null),
        Arguments.of(
            new RecoveryFlowCandidate(
                155487L,
                "2026-10-09ABI06270-PPA7777801000620",
                1L,
                "ABI06270",
                "77777777777",
                LocalDateTime.parse("2026-10-08T23:00:06.208")),
            null,
            false,
            "ec92305b-47b2-4553-9599-ff086c5dfd93"));
  }

  private static RecoveryFlowCandidate nativeCandidate(
      long id, long revision, String date) {

    return new RecoveryFlowCandidate(
        id,
        "2026-10-05CIPBITMM-00002",
        revision,
        "CIPBITMM",
        "81000730077",
        LocalDateTime.parse(date));
  }

  private static void assertBatchPresent(LocalDate day, List<RecoveryPublicationBatchService.Result> results,
          Map<Long, String> expectedSessions, RecoveryPublicationSource.SourceType expectedType) {

      assertEquals(expectedSessions.size(), results.size());

      var actualFlowIds = results.stream().map(result -> result.candidate().flowId()).collect(Collectors.toSet());

      assertEquals(expectedSessions.keySet(), actualFlowIds);

      for (var result : results) {
          var compared = assertInstanceOf(RecoveryPublicationBatchService.Compared.class, result);

          var comparison = compared.comparison();
          var source = comparison.source();

          assertEquals(day, comparison.paymentDay());
          assertEquals(expectedType, source.sourceType());

          assertEquals(expectedSessions.get(source.candidate().flowId()), source.sessionId());

          assertEquals(RecoveryPublicationPresenceIndex.Presence.PRESENT_UNVERIFIED, comparison.presence());

          System.out.printf(
                  "UAT full publication comparison verified: " + "day=%s, flowId=%d, revision=%d, "
                          + "sourceType=%s, sessionId=%s, " + "presence=%s, rows=%d, writes=none%n",
                  day, source.candidate().flowId(), source.candidate().revision(), source.sourceType(),
                  source.sessionId(), comparison.presence(), comparison.presentRows());
      }
  }
}