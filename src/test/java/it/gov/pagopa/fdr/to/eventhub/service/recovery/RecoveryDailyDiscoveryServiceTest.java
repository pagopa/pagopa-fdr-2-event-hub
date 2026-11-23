package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.microsoft.azure.kusto.data.exceptions.KustoServiceQueryError;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxFlowPresence;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecoveryDailyDiscoveryServiceTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
  private static final String PSP = "88888888888";
  private static final String ORGANIZATION = "77777777777";

  private final RecoveryAdxRepository adxRepository =
      mock(RecoveryAdxRepository.class);

  private final RecoveryFlowRepository flowRepository =
      mock(RecoveryFlowRepository.class);

  private final RecoveryDailyDiscoveryService service =
      new RecoveryDailyDiscoveryService(
          adxRepository, flowRepository, 2);

  @BeforeEach
  void setUp() throws Exception {
    when(adxRepository.findByDay(DAY)).thenReturn(List.of());
  }

  private RecoveryFlowCandidate candidate(long id, String name) {
    return new RecoveryFlowCandidate(
        id, name, 1L, PSP, ORGANIZATION, LocalDateTime.of(2026, 10, 2, 12, 43, 18, 448_000_000));
  }

  @Test
  void shouldReadAdxOnceAndCompareMultiplePostgresPages()
      throws Exception {

    RecoveryAdxFlowPresence presence =
        new RecoveryAdxFlowPresence(
            "flow-1", PSP, ORGANIZATION, 25L);

    when(adxRepository.findByDay(DAY))
        .thenReturn(List.of(presence));

    when(flowRepository.findPage(DAY, 0L, 2))
        .thenReturn(
            List.of(
                candidate(1L, "flow-1"),
                candidate(2L, "flow-2")));

    when(flowRepository.findPage(DAY, 2L, 2))
        .thenReturn(List.of(candidate(3L, "flow-3")));

    when(flowRepository.findPage(DAY, 3L, 2))
        .thenReturn(List.of());

    List<List<RecoveryFlowPresenceIndex.Comparison>> pages =
        new ArrayList<>();

    RecoveryDailyDiscoveryService.Summary summary =
        service.discover(DAY, pages::add);

    assertEquals(DAY, summary.day());
    assertEquals(3L, summary.candidateCount());
    assertEquals(2L, summary.absentCandidateCount());
    assertEquals(1L, summary.presentCandidateCount());
    assertEquals(2L, summary.pageCount());

    assertEquals(2, pages.size());

    assertEquals(
        RecoveryFlowPresenceIndex.Presence.PRESENT_UNVERIFIED,
        pages.get(0).get(0).presence());

    assertEquals(
        RecoveryFlowPresenceIndex.Presence.ABSENT,
        pages.get(0).get(1).presence());

    verify(adxRepository, times(1)).findByDay(DAY);
    verify(flowRepository).findPage(DAY, 0L, 2);
    verify(flowRepository).findPage(DAY, 2L, 2);
    verify(flowRepository).findPage(DAY, 3L, 2);
  }

  @Test
  void shouldReturnZeroCountersForEmptyPostgresResult()
      throws Exception {

    when(flowRepository.findPage(DAY, 0L, 2))
        .thenReturn(List.of());

    List<List<RecoveryFlowPresenceIndex.Comparison>> pages =
        new ArrayList<>();

    RecoveryDailyDiscoveryService.Summary summary =
        service.discover(DAY, pages::add);

    assertEquals(0L, summary.candidateCount());
    assertEquals(0L, summary.absentCandidateCount());
    assertEquals(0L, summary.presentCandidateCount());
    assertEquals(0L, summary.pageCount());
    assertTrue(pages.isEmpty());
  }

  @Test
  void shouldContinueAfterPageSmallerThanRequestedSize()
      throws Exception {

    when(flowRepository.findPage(DAY, 0L, 2))
        .thenReturn(List.of(candidate(1L, "flow-1")));

    when(flowRepository.findPage(DAY, 1L, 2))
        .thenReturn(List.of(candidate(2L, "flow-2")));

    when(flowRepository.findPage(DAY, 2L, 2))
        .thenReturn(List.of());

    RecoveryDailyDiscoveryService.Summary summary =
        service.discover(DAY, page -> { });

    assertEquals(2L, summary.candidateCount());
    assertEquals(2L, summary.pageCount());

    verify(flowRepository).findPage(DAY, 2L, 2);
  }

  @Test
  void shouldStopBeforePostgresWhenAdxFails() throws Exception {
    KustoServiceQueryError failure =
        new KustoServiceQueryError("Simulated ADX failure");

    when(adxRepository.findByDay(DAY)).thenThrow(failure);

    List<List<RecoveryFlowPresenceIndex.Comparison>> pages =
        new ArrayList<>();

    KustoServiceQueryError thrown =
        assertThrows(
            KustoServiceQueryError.class,
            () -> service.discover(DAY, pages::add));

    assertSame(failure, thrown);
    assertTrue(pages.isEmpty());
    verifyNoInteractions(flowRepository);
  }

  @Test
  void shouldPropagatePostgresFailureWithoutDeliveringPage()
      throws Exception {

    SQLException failure =
        new SQLException("Simulated PostgreSQL failure");

    when(flowRepository.findPage(DAY, 0L, 2))
        .thenThrow(failure);

    List<List<RecoveryFlowPresenceIndex.Comparison>> pages =
        new ArrayList<>();

    SQLException thrown =
        assertThrows(
            SQLException.class,
            () -> service.discover(DAY, pages::add));

    assertSame(failure, thrown);
    assertTrue(pages.isEmpty());
  }

  @Test
  void shouldRejectUnorderedPageBeforeDeliveringIt()
      throws Exception {

    when(flowRepository.findPage(DAY, 0L, 2))
        .thenReturn(
            List.of(
                candidate(2L, "flow-2"),
                candidate(1L, "flow-1")));

    List<List<RecoveryFlowPresenceIndex.Comparison>> pages =
        new ArrayList<>();

    assertThrows(
        IllegalStateException.class,
        () -> service.discover(DAY, pages::add));

    assertTrue(pages.isEmpty());
  }

  @Test
  void shouldRejectCursorThatDoesNotAdvance() throws Exception {
    when(flowRepository.findPage(DAY, 0L, 2))
        .thenReturn(List.of(candidate(1L, "flow-1")));

    when(flowRepository.findPage(DAY, 1L, 2))
        .thenReturn(List.of(candidate(1L, "flow-1")));

    List<List<RecoveryFlowPresenceIndex.Comparison>> pages =
        new ArrayList<>();

    assertThrows(
        IllegalStateException.class,
        () -> service.discover(DAY, pages::add));

    assertEquals(1, pages.size());
  }

  @Test
  void shouldStopWhenPageConsumerFails() throws Exception {
    when(flowRepository.findPage(DAY, 0L, 2))
        .thenReturn(List.of(candidate(1L, "flow-1")));

    IllegalStateException failure =
        new IllegalStateException("Simulated consumer failure");

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> service.discover(
                DAY,
                page -> {
                  throw failure;
                }));

    assertSame(failure, thrown);

    verify(flowRepository, times(1)).findPage(DAY, 0L, 2);
    verify(flowRepository, times(0)).findPage(DAY, 1L, 2);
  }
}