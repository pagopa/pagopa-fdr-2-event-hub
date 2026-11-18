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
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryAdxPublicationPresence;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryAdxPublicationRepositoryTest {

  private static final String DATABASE = "recovery-test";
  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private static final UUID PUBLICATION_ID =
      UUID.fromString("6fd3e2eb-1d1d-4068-ba74-fa9866141524");

  private static final List<UUID> IDS = List.of(PUBLICATION_ID);

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
            {"ColumnName": "PublicationId", "ColumnType": "string"},
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

  @Test
  void shouldReadPublicationAndBindParameters() throws Exception {
    stubRows("[" + row(PUBLICATION_ID.toString(), "4") + "]");

    var result = repository.findPublicationsByDay(DAY, IDS);

    assertEquals(
        List.of(
            new RecoveryAdxPublicationPresence(
                DAY,
                "flow-1",
                "psp-1",
                "org-1",
                PUBLICATION_ID,
                4L)),
        result);

    var captor =
        ArgumentCaptor.forClass(ClientRequestProperties.class);

    var query = new RecoveryAdxPublicationQuery(DAY, IDS);

    verify(client).executeQuery(
        eq(DATABASE),
        eq(query.text()),
        captor.capture());

    var properties = captor.getValue();

    assertEquals(
        query.parameters().get("recoveryDay"),
        properties.getParameter("recoveryDay"));

    assertEquals(
        query.parameters().get("recoveryPublicationIds"),
        properties.getParameter("recoveryPublicationIds"));

    assertEquals(
        Long.valueOf(60_000L),
        properties.getTimeoutInMilliSec());

    assertEquals(
        Boolean.FALSE,
        properties.getOption("deferpartialqueryfailures"));
  }

  @Test
  void shouldAcceptSuccessfullyCompletedEmptyResult() throws Exception {
    stubRows("[]");

    assertTrue(
        repository.findPublicationsByDay(DAY, IDS).isEmpty());
  }

  @Test
  void shouldRejectNullResponse() {
    assertThrows(
        IllegalStateException.class,
        () -> repository.findPublicationsByDay(DAY, IDS));
  }

  @Test
  void shouldRejectMissingPrimaryResult() throws Exception {
    stubResponse("""
        [
          {
            "FrameType": "DataSetCompletion",
            "HasErrors": false,
            "Cancelled": false
          }
        ]
        """);

    assertThrows(
        IllegalStateException.class,
        () -> repository.findPublicationsByDay(DAY, IDS));
  }

  @Test
  void shouldValidatePublicationColumnEvenForEmptyResult()
      throws Exception {

    String response =
        RESPONSE.formatted("[]")
            .replace("\"PublicationId\"", "\"OtherColumn\"");

    stubResponse(response);

    assertThrows(
        RuntimeException.class,
        () -> repository.findPublicationsByDay(DAY, IDS));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "invalid", "1-1-1-1-1"})
  void shouldRejectInvalidPublicationId(String value) throws Exception {
    stubRows("[" + row(value, "4") + "]");

    assertThrows(
        IllegalStateException.class,
        () -> repository.findPublicationsByDay(DAY, IDS));
  }

  @Test
  void shouldRejectNullPublicationId() throws Exception {
    stubRows("""
        [["flow-1", "psp-1", "org-1", null, 4]]
        """);

    assertThrows(
        IllegalStateException.class,
        () -> repository.findPublicationsByDay(DAY, IDS));
  }

  @Test
  void shouldRejectPublicationOutsideRequestedBatch() throws Exception {
    stubRows(
        "[" + row(
            "24fea4e9-c5d3-4d21-b74b-d51be0774d11",
            "4") + "]");

    assertThrows(
        IllegalStateException.class,
        () -> repository.findPublicationsByDay(DAY, IDS));
  }

  @Test
  void shouldRejectNullCount() throws Exception {
    stubRows("[" + row(PUBLICATION_ID.toString(), "null") + "]");

    assertThrows(
        IllegalStateException.class,
        () -> repository.findPublicationsByDay(DAY, IDS));
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L})
  void shouldRejectNonPositiveCount(long count) throws Exception {
    stubRows(
        "[" + row(
            PUBLICATION_ID.toString(),
            Long.toString(count)) + "]");

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findPublicationsByDay(DAY, IDS));
  }

  @Test
  void shouldRejectResultBeyondGroupLimit() throws Exception {
    String repeatedRow = row(PUBLICATION_ID.toString(), "1");

    String rows =
        "[" + String.join(
            ",",
            Collections.nCopies(
                RecoveryAdxPublicationQuery.MAX_RESULT_GROUPS + 1,
                repeatedRow)) + "]";

    stubRows(rows);

    assertThrows(
        IllegalStateException.class,
        () -> repository.findPublicationsByDay(DAY, IDS));
  }

  @Test
  void shouldPropagateQueryFailure() throws Exception {
    var failure =
        new KustoServiceQueryError("Simulated query failure");

    when(client.executeQuery(
            eq(DATABASE),
            any(String.class),
            any(ClientRequestProperties.class)))
        .thenThrow(failure);

    var actual =
        assertThrows(
            KustoServiceQueryError.class,
            () -> repository.findPublicationsByDay(DAY, IDS));

    assertSame(failure, actual);
  }

  @Test
  void shouldRejectEmptyBatchBeforeCallingAdx() {
    List<UUID> ids = List.of();

    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findPublicationsByDay(DAY, ids));

    verifyNoInteractions(client);
  }

  @Test
  void shouldRejectNullDayBeforeCallingAdx() {
    assertThrows(
        NullPointerException.class,
        () -> repository.findPublicationsByDay(null, IDS));

    verifyNoInteractions(client);
  }

  private void stubRows(String rows) throws Exception {
    stubResponse(RESPONSE.formatted(rows));
  }

  private void stubResponse(String response) throws Exception {
    var result = new KustoOperationResult(response, "v2");

    when(client.executeQuery(
            eq(DATABASE),
            any(String.class),
            any(ClientRequestProperties.class)))
        .thenReturn(result);
  }

  private static String row(String publicationId, String count) {
    return """
        ["flow-1","psp-1","org-1","%s",%s]
        """.formatted(publicationId, count).strip();
  }
}