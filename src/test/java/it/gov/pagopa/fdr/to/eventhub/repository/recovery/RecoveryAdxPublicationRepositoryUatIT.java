package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxClientFactory;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryAdxConfiguration;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryPublicationPresenceIndex;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "RECOVERY_ADX_UAT_INTEGRATION_TESTS", matches = "true")
class RecoveryAdxPublicationRepositoryUatIT {

    @Test
    void shouldFindEachNativeRevisionWithoutMatchingXmlCopies() throws Exception {

        LocalDate day = LocalDate.of(2026, 10, 1);

        var sources = List.of(nativeSource(155333L, 1L, "2026-10-05T10:31:35", "6fd3e2eb-1d1d-4068-ba74-fa9866141524"),
                nativeSource(155336L, 2L, "2026-10-05T10:32:35", "24fea4e9-c5d3-4d21-b74b-d51be0774d11"),
                nativeSource(155735L, 3L, "2026-10-05T15:32:35", "6456ec11-a040-4129-97de-b75369990280"));

        var copyIds = List.of(UUID.fromString("edc9f140-b098-4164-a048-8a3309baead3"),
                UUID.fromString("00e65a5a-75fc-486a-97a1-1d4631554ad1"),
                UUID.fromString("fda0b1e1-46ce-4b8d-a598-c5b5b5319672"));

        List<UUID> requestedIds = new ArrayList<>();

        for (var source : sources) {
            requestedIds.add(UUID.fromString(source.sessionId()));
        }

        requestedIds.addAll(copyIds);

        var factory = new RecoveryAdxClientFactory(configuration());

        var client = factory.client();

        var repository = new RecoveryAdxRepository(client, "re", 60);

        long started = System.nanoTime();

        var presences = repository.findPublicationsByDay(day, requestedIds);

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        var index = new RecoveryPublicationPresenceIndex(day, presences);

        for (var source : sources) {
            var comparison = index.compare(source);

            assertEquals(RecoveryPublicationPresenceIndex.Presence.PRESENT_UNVERIFIED, comparison.presence(),
                    "Missing native publication: " + source.sessionId());

            System.out.printf("UAT ADX native publication: revision=%d, " + "sessionId=%s, presence=%s, rows=%d%n",
                    source.candidate().revision(), source.sessionId(), comparison.presence(), comparison.presentRows());
        }

        assertFalse(presences.stream().anyMatch(presence -> copyIds.contains(presence.publicationId())),
                "An XML copy publication is unexpectedly present in ADX");

        System.out.printf(
                "UAT ADX native batch verified: day=%s, requested=%d, " + "groups=%d, elapsedMs=%d, writes=none%n", day,
                requestedIds.size(), presences.size(), elapsedMillis);
    }

    @Test
    void shouldFindOriginalXmlWithoutMatchingJsonCopy() throws Exception {

        LocalDate day = LocalDate.of(2026, 10, 8);

        String originalSession = "ec92305b-47b2-4553-9599-ff086c5dfd93";

        UUID copyId = UUID.fromString("f77ab46c-0da9-448c-a9c0-9ce132e7c1a5");

        var candidate = new RecoveryFlowCandidate(155487L, "2026-10-09ABI06270-PPA7777801000620", 1L, "ABI06270",
                "77777777777", LocalDateTime.parse("2026-10-08T23:00:06.208"));

        var source = source(candidate, originalSession, RecoveryPublicationSource.SourceType.FDR1_XML,
                Instant.parse("2026-10-08T23:00:06.208475643Z"));

        var requestedIds = List.of(UUID.fromString(originalSession), copyId);

        var factory = new RecoveryAdxClientFactory(configuration());

        var client = factory.client();

        var repository = new RecoveryAdxRepository(client, "re", 60);

        long started = System.nanoTime();

        var presences = repository.findPublicationsByDay(day, requestedIds);

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        var index = new RecoveryPublicationPresenceIndex(day, presences);

        var comparison = index.compare(source);

        assertEquals(RecoveryPublicationPresenceIndex.Presence.PRESENT_UNVERIFIED, comparison.presence());

        assertFalse(presences.stream().anyMatch(presence -> copyId.equals(presence.publicationId())),
                "The JSON copy publication is unexpectedly present in ADX");

        System.out.printf(
                "UAT ADX original XML verified: day=%s, sessionId=%s, "
                        + "presence=%s, rows=%d, elapsedMs=%d, writes=none%n",
                day, originalSession, comparison.presence(), comparison.presentRows(), elapsedMillis);
    }

    private static RecoveryAdxConfiguration configuration() {
        String clusterUrl = System.getenv("RECOVERY_ADX_UAT_CLUSTER_URL");

        assertNotNull(clusterUrl, "RECOVERY_ADX_UAT_CLUSTER_URL is required");

        assertFalse(clusterUrl.isBlank());

        return new RecoveryAdxConfiguration(clusterUrl, "re", RecoveryAdxConfiguration.AuthMode.AZURE_CLI, 60);
    }

    private static RecoveryPublicationSource nativeSource(long id, long revision, String date, String sessionId) {

        var candidate = new RecoveryFlowCandidate(id, "2026-10-05CIPBITMM-00002", revision, "CIPBITMM", "81000730077",
                LocalDateTime.parse(date));

        return source(candidate, sessionId, RecoveryPublicationSource.SourceType.FDR3_JSON, Instant.parse(date + "Z"));
    }

    private static RecoveryPublicationSource source(RecoveryFlowCandidate candidate, String sessionId,
            RecoveryPublicationSource.SourceType type, Instant eventDate) {

        // This test queries ADX only. Blob details are fixture values.
        var blob = new RecoveryBlobSnapshot(type.containerName(), "integration-test-fixture", "\"fixture-etag\"", 0L,
                Map.of());

        return new RecoveryPublicationSource(candidate, blob, type, sessionId, eventDate);
    }
}