package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDiscoveryProgressService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDiscoveryProgressService.DailyProgress;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDiscoveryProgressService.DiscoveryStatus;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryDiscoveryProgressFunctionTest {

  private static final UUID JOB_ID =
      UUID.fromString("933e5c99-8aaa-4556-9407-48b2b80ab293");

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final RecoveryDiscoveryProgressService service =
      mock(RecoveryDiscoveryProgressService.class);

  private final RecoveryDiscoveryProgressFunction function =
      new RecoveryDiscoveryProgressFunction(service);

  @SuppressWarnings("unchecked")
  private final HttpRequestMessage<Optional<String>> request =
      mock(HttpRequestMessage.class);

  private final ExecutionContext context = mock(ExecutionContext.class);

  private final HttpResponseMessage.Builder builder =
      mock(HttpResponseMessage.Builder.class, RETURNS_SELF);

  private final HttpResponseMessage response =
      mock(HttpResponseMessage.class);

  private final AtomicReference<HttpStatus> status =
      new AtomicReference<>();

  private final AtomicReference<Object> body =
      new AtomicReference<>();

  @BeforeEach
  void setUp() {
    when(request.createResponseBuilder(any(HttpStatus.class)))
        .thenAnswer(invocation -> {
          status.set(invocation.getArgument(0));
          return builder;
        });

    when(builder.body(any())).thenAnswer(invocation -> {
      body.set(invocation.getArgument(0));
      return builder;
    });

    when(builder.build()).thenReturn(response);
    when(response.getStatus()).thenAnswer(invocation -> status.get());
    when(response.getBody()).thenAnswer(invocation -> body.get());
  }

  @ParameterizedTest
  @EnumSource(DiscoveryStatus.class)
  void shouldSerializeDailyProgress(DiscoveryStatus discoveryStatus)
      throws Exception {

    boolean notStarted = discoveryStatus == DiscoveryStatus.NOT_STARTED;

    DailyProgress progress =
        new DailyProgress(
            JOB_ID,
            DAY,
            discoveryStatus,
            notStarted ? 0L : 351L,
            notStarted ? 0L : 223L,
            notStarted ? 0L : 128L,
            notStarted ? 0L : 4L,
            notStarted ? 0L : 581L);

    when(service.find(JOB_ID, DAY))
        .thenReturn(Optional.of(progress));

    HttpResponseMessage result =
        function.get(request, JOB_ID.toString(), DAY.toString(), context);

    assertEquals(HttpStatus.OK, result.getStatus());

    JsonNode payload = payload(result);

    assertEquals(JOB_ID.toString(), payload.get("jobId").asText());
    assertEquals(DAY.toString(), payload.get("day").asText());
    assertEquals(discoveryStatus.name(), payload.get("status").asText());
    assertEquals(progress.candidateCount(), payload.get("candidateCount").asLong());
    assertEquals(progress.absentCandidateCount(), payload.get("absentCandidateCount").asLong());
    assertEquals(progress.presentCandidateCount(), payload.get("presentCandidateCount").asLong());
    assertEquals(progress.pageCount(), payload.get("pageCount").asLong());
    assertEquals(progress.lastFlowId(), payload.get("lastFlowId").asLong());
    assertFalse(payload.has("etag"));

    verify(builder).header("Content-Type", "application/json");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
      "not-a-uuid",
      "1-1-1-1-1",
      " 933e5c99-8aaa-4556-9407-48b2b80ab293"
  })
  void shouldRejectInvalidJobId(String jobId) {
    HttpResponseMessage result =
        function.get(request, jobId, DAY.toString(), context);

    assertEquals(HttpStatus.BAD_REQUEST, result.getStatus());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
      "2026-02-30",
      "2026-13-01",
      "2026-1-01",
      "2026-10-01T00:00:00Z",
      " 2026-10-01"
  })
  void shouldRejectInvalidDay(String day) {
    HttpResponseMessage result =
        function.get(request, JOB_ID.toString(), day, context);

    assertEquals(HttpStatus.BAD_REQUEST, result.getStatus());
    verifyNoInteractions(service);
  }

  @Test
  void shouldReturnNotFoundForUnknownJob() {
    when(service.find(JOB_ID, DAY)).thenReturn(Optional.empty());

    HttpResponseMessage result =
        function.get(request, JOB_ID.toString(), DAY.toString(), context);

    assertEquals(HttpStatus.NOT_FOUND, result.getStatus());
  }

  @Test
  void shouldRejectDayOutsideJobRange() {
    when(service.find(JOB_ID, DAY))
        .thenThrow(
            new RecoveryDiscoveryProgressService.DayOutsideJobRangeException());

    HttpResponseMessage result =
        function.get(request, JOB_ID.toString(), DAY.toString(), context);

    assertEquals(HttpStatus.BAD_REQUEST, result.getStatus());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void shouldReturnSanitizedServerError(boolean corruptedDocument)
      throws Exception {

    RuntimeException failure =
        corruptedDocument
            ? new IllegalArgumentException("Sensitive storage details")
            : new IllegalStateException("Sensitive connection details");

    when(service.find(JOB_ID, DAY)).thenThrow(failure);
    Logger logger = mock(Logger.class);
    when(context.getLogger()).thenReturn(logger);

    HttpResponseMessage result =
        function.get(request, JOB_ID.toString(), DAY.toString(), context);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatus());
    assertEquals(
        "Unable to read recovery discovery progress",
        payload(result).get("message").asText());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-02-30", "2026-13-01"})
  void shouldExplainNonexistentCalendarDate(String day)
      throws Exception {

    HttpResponseMessage result =
        function.get(request, JOB_ID.toString(), day, context);

    assertEquals(HttpStatus.BAD_REQUEST, result.getStatus());
    assertEquals(
        "day must be an existing calendar date",
        payload(result).get("message").asText());

    verifyNoInteractions(service);
  }

  @Test
  void shouldExplainInvalidDateFormat() throws Exception {
    HttpResponseMessage result =
        function.get(request, JOB_ID.toString(), "2026-2-03", context);

    assertEquals(HttpStatus.BAD_REQUEST, result.getStatus());
    assertEquals(
        "day must use YYYY-MM-DD format",
        payload(result).get("message").asText());

    verifyNoInteractions(service);
  }

  private JsonNode payload(HttpResponseMessage result) throws Exception {
    return new ObjectMapper().readTree((String) result.getBody());
  }

  @Test
  void shouldSerializeUnresolvedCount() throws Exception {
    var progress =
        new DailyProgress(
            JOB_ID,
            DAY,
            DiscoveryStatus.DISCOVERED,
            3L, 1L, 1L, 1L, 30L, 1L);

    when(service.find(JOB_ID, DAY))
        .thenReturn(Optional.of(progress));

    var result =
        function.get(
            request, JOB_ID.toString(), DAY.toString(), context);

    var json =
        new ObjectMapper().readTree((String) result.getBody());

    assertEquals(HttpStatus.OK, result.getStatus());
    assertEquals(1L, json.get("unresolvedCandidateCount").asLong());
  }

  @Test
  void shouldReturnConflictForUnsupportedJobFormat() {
    when(service.find(JOB_ID, DAY))
        .thenThrow(
            new it.gov.pagopa.fdr.to.eventhub.repository.recovery
                .UnsupportedRecoveryJobFormatException("Old schema"));

    var result =
        function.get(
            request, JOB_ID.toString(), DAY.toString(), context);

    assertEquals(HttpStatus.CONFLICT, result.getStatus());
  }
}