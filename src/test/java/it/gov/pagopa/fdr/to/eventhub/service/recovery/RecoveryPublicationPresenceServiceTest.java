package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.microsoft.azure.kusto.data.exceptions.KustoServiceQueryError;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxPublicationPresence;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxPublicationQuery;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryPublicationPresenceServiceTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private static final UUID FIRST =
      UUID.fromString("6fd3e2eb-1d1d-4068-ba74-fa9866141524");

  private static final UUID SECOND =
      UUID.fromString("24fea4e9-c5d3-4d21-b74b-d51be0774d11");

  private final RecoveryAdxRepository repository =
      mock(RecoveryAdxRepository.class);

  private final RecoveryPublicationPresenceService service =
      new RecoveryPublicationPresenceService(repository);

  @Test
  void shouldCompareRevisionsSeparatelyWithOneQuery() throws Exception {
    var first =
        source(1L, FIRST, RecoveryPublicationSource.SourceType.FDR3_JSON);

    var second =
        source(2L, SECOND, RecoveryPublicationSource.SourceType.FDR3_JSON);

    var ids = List.of(FIRST, SECOND);

    when(repository.findPublicationsByDay(DAY, ids))
        .thenReturn(List.of(presence(FIRST)));

    var result = service.compare(DAY, List.of(first, second));

    assertEquals(2, result.size());

    assertSame(first, result.get(0).source());
    assertEquals(
        RecoveryPublicationPresenceIndex.Presence.PRESENT_UNVERIFIED,
        result.get(0).presence());
    assertEquals(4L, result.get(0).presentRows());

    assertSame(second, result.get(1).source());
    assertEquals(
        RecoveryPublicationPresenceIndex.Presence.ABSENT,
        result.get(1).presence());
    assertEquals(0L, result.get(1).presentRows());

    verify(repository).findPublicationsByDay(DAY, ids);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void shouldQuerySessionOfSelectedXmlSource() throws Exception {
    var xml =
        source(1L, FIRST, RecoveryPublicationSource.SourceType.FDR1_XML);

    when(repository.findPublicationsByDay(DAY, List.of(FIRST)))
        .thenReturn(List.of(presence(FIRST)));

    var result = service.compare(DAY, List.of(xml));

    assertSame(xml, result.get(0).source());
    assertEquals(
        RecoveryPublicationPresenceIndex.Presence.PRESENT_UNVERIFIED,
        result.get(0).presence());

    verify(repository).findPublicationsByDay(DAY, List.of(FIRST));
  }

  @Test
  void shouldSkipAdxForEmptyBatch() throws Exception {
    var result = service.compare(DAY, List.of());

    assertTrue(result.isEmpty());
    verifyNoInteractions(repository);
  }

  @Test
  void shouldReturnImmutableComparisons() throws Exception {
    var source =
        source(1L, FIRST, RecoveryPublicationSource.SourceType.FDR3_JSON);

    when(repository.findPublicationsByDay(DAY, List.of(FIRST)))
        .thenReturn(List.of());

    var result = service.compare(DAY, List.of(source));

    assertThrows(
        UnsupportedOperationException.class,
        () -> result.clear());
  }

  @Test
  void shouldRejectOversizedBatchBeforeQuery() {
    var source =
        source(1L, FIRST, RecoveryPublicationSource.SourceType.FDR3_JSON);

    var sources =
        Collections.nCopies(
            RecoveryAdxPublicationQuery.MAX_PUBLICATIONS + 1,
            source);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.compare(DAY, sources));

    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectNullSourceBeforeQuery() {
    var sources = new ArrayList<RecoveryPublicationSource>();
    sources.add(null);

    assertThrows(
        NullPointerException.class,
        () -> service.compare(DAY, sources));

    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectNullDayBeforeQuery() {
    List<RecoveryPublicationSource> sources = List.of();

    assertThrows(
        NullPointerException.class,
        () -> service.compare(null, sources));

    verifyNoInteractions(repository);
  }

  @Test
  void shouldRejectNullBatchBeforeQuery() {
    assertThrows(
        NullPointerException.class,
        () -> service.compare(DAY, null));

    verifyNoInteractions(repository);
  }

  @Test
  void shouldPropagateAdxFailure() throws Exception {
    var source =
        source(1L, FIRST, RecoveryPublicationSource.SourceType.FDR3_JSON);

    var sources = List.of(source);
    var failure = new KustoServiceQueryError("Simulated ADX failure");

    when(repository.findPublicationsByDay(DAY, List.of(FIRST)))
        .thenThrow(failure);

    var actual =
        assertThrows(
            KustoServiceQueryError.class,
            () -> service.compare(DAY, sources));

    assertSame(failure, actual);
  }

  private static RecoveryAdxPublicationPresence presence(UUID id) {
    return new RecoveryAdxPublicationPresence(
        DAY, "flow-1", "psp-1", "org-1", id, 4L);
  }

  private static RecoveryPublicationSource source(
      long revision,
      UUID sessionId,
      RecoveryPublicationSource.SourceType type) {

    var candidate =
        new RecoveryFlowCandidate(
            revision,
            "flow-1",
            revision,
            "psp-1",
            "org-1",
            LocalDateTime.parse("2026-10-05T10:31:35"));

    var blob =
        new RecoveryBlobSnapshot(
            type.containerName(),
            "fixture-original",
            "\"fixture-etag\"",
            100L,
            Map.of());

    return new RecoveryPublicationSource(
        candidate,
        blob,
        type,
        sessionId.toString(),
        Instant.parse("2026-10-05T10:31:35Z"));
  }
}