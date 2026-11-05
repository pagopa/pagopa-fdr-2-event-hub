package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.microsoft.azure.kusto.data.Client;
import com.microsoft.azure.kusto.data.ClientRequestProperties;
import com.microsoft.azure.kusto.data.KustoOperationResult;
import com.microsoft.azure.kusto.data.exceptions.KustoServiceQueryError;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxFlowPresence;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryAdxRepositoryTest {

  private static final String DATABASE = "recovery-test";
  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private static final String RESPONSE = """
      [
        {
          "FrameType": "DataTable",
          "TableId": 0,
          "TableKind": "PrimaryResult",
          "TableName": "PrimaryResult",
          "Columns": [
            {"ColumnName": "ID_FLUSSO", "ColumnType": "string"},
            {"ColumnName": "PSP", "ColumnType": "string"},
            {"ColumnName": "ID_DOMINIO", "ColumnType": "string"},
            {"ColumnName": "PresentRows", "ColumnType": "long"}
          ],
          "Rows": %s
        },
        {
          "FrameType": "DataSetCompletion",
          "HasErrors": false,
          "Cancelled": false
        }
      ]
      """;

  private final Client client = mock(Client.class);

  private final RecoveryAdxRepository repository =
      new RecoveryAdxRepository(client, DATABASE, 60);

  private void stubResponse(String rows) throws Exception {
    KustoOperationResult result =
        new KustoOperationResult(RESPONSE.formatted(rows), "v2");

    when(client.executeQuery(
            eq(DATABASE),
            any(String.class),
            any(ClientRequestProperties.class)))
        .thenReturn(result);
  }

  @Test
  void shouldReadPresenceAndBindDayAndTimeout() throws Exception {
    stubResponse("""
        [["flow-1", "88888888888", "77777777777", 25]]
        """);

    List<RecoveryAdxFlowPresence> result = repository.findByDay(DAY);

    assertEquals(
        List.of(
            new RecoveryAdxFlowPresence(
                "flow-1", "88888888888", "77777777777", 25)),
        result);

    ArgumentCaptor<ClientRequestProperties> captor =
        ArgumentCaptor.forClass(ClientRequestProperties.class);

    RecoveryAdxDailyQuery query = new RecoveryAdxDailyQuery(DAY);

    verify(client).executeQuery(
        eq(DATABASE),
        eq(query.text()),
        captor.capture());

    ClientRequestProperties properties = captor.getValue();

    assertEquals(
        "2026-10-01T00:00:00Z",
        properties.getParameter("recoveryDay"));

    assertEquals(
        Long.valueOf(60_000L),
        properties.getTimeoutInMilliSec());

    assertEquals(
        Boolean.FALSE,
        properties.getOption("deferpartialqueryfailures"));
  }

  @Test
  void shouldAcceptSuccessfullyCompletedEmptyResult() throws Exception {
    stubResponse("[]");

    assertTrue(repository.findByDay(DAY).isEmpty());
  }

  @Test
  void shouldRejectMissingPrimaryResult() throws Exception {
    KustoOperationResult result =
        new KustoOperationResult("""
            [
              {
                "FrameType": "DataSetCompletion",
                "HasErrors": false,
                "Cancelled": false
              }
            ]
            """, "v2");

    when(client.executeQuery(
            eq(DATABASE),
            any(String.class),
            any(ClientRequestProperties.class)))
        .thenReturn(result);

    assertThrows(
        IllegalStateException.class,
        () -> repository.findByDay(DAY));
  }

  @Test
  void shouldRejectNullResponse() {
    // An unstubbed Mockito invocation returns null.
    assertThrows(
        IllegalStateException.class,
        () -> repository.findByDay(DAY));
  }

  @Test
  void shouldRejectNullCount() throws Exception {
    stubResponse("""
        [["flow-1", "88888888888", "77777777777", null]]
        """);

    assertThrows(
        IllegalStateException.class,
        () -> repository.findByDay(DAY));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "[[\"\", \"88888888888\", \"77777777777\", 25]]",
      "[[\"flow-1\", \"\", \"77777777777\", 25]]",
      "[[\"flow-1\", \"88888888888\", \"\", 25]]"
  })
  void shouldRejectMissingFlowIdentifiers(String rows) throws Exception {
    stubResponse(rows);

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findByDay(DAY));
  }

  @Test
  void shouldPropagateQueryFailure() throws Exception {
    KustoServiceQueryError failure =
        new KustoServiceQueryError("Simulated query failure");

    when(client.executeQuery(
            eq(DATABASE),
            any(String.class),
            any(ClientRequestProperties.class)))
        .thenThrow(failure);

    KustoServiceQueryError thrown =
        assertThrows(
            KustoServiceQueryError.class,
            () -> repository.findByDay(DAY));

    assertSame(failure, thrown);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "E_QUERY_RESULT_SET_TOO_LARGE",
      "E_RUNAWAY_QUERY"
  })
  void shouldRejectSdkResponseWithPartialFailure(String code) {
    String response = """
        [
          {
            "FrameType": "DataTable",
            "TableId": 0,
            "TableKind": "PrimaryResult",
            "TableName": "PrimaryResult",
            "Columns": [
              {"ColumnName": "ID_FLUSSO", "ColumnType": "string"}
            ],
            "Rows": [["flow-1"]]
          },
          {
            "FrameType": "DataSetCompletion",
            "HasErrors": true,
            "Cancelled": false,
            "OneApiErrors": [
              {
                "error": {
                  "code": "%s",
                  "message": "Simulated partial query failure"
                }
              }
            ]
          }
        ]
        """.formatted(code);

    KustoServiceQueryError failure =
        assertThrows(
            KustoServiceQueryError.class,
            () -> new KustoOperationResult(response, "v2"));

    assertTrue(
        failure.getMessage().contains(code),
        "The exception must identify the simulated ADX failure");
  }

  @Test
  void shouldRejectMissingDayBeforeCallingAdx() {
    assertThrows(
        NullPointerException.class,
        () -> repository.findByDay(null));

    verifyNoInteractions(client);
  }
}