package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpResponse;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobStateConflictException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class RecoveryJobsControlFunctionTest {

  private enum Action {
    PAUSE,
    RESUME
  }

  private static final UUID JOB_ID =
      UUID.fromString("a14c9701-8d81-4a50-9f55-9a531e1257c3");

  private final RecoveryJobService service =
      mock(RecoveryJobService.class);

  private final RecoveryJobsFunction function =
      new RecoveryJobsFunction(service);

  @SuppressWarnings("unchecked")
  private final HttpRequestMessage<Optional<String>> request =
      mock(HttpRequestMessage.class);

  private final ExecutionContext context =
      mock(ExecutionContext.class);

  private final HttpResponseMessage.Builder builder =
      mock(HttpResponseMessage.Builder.class);

  private final HttpResponseMessage response =
      mock(HttpResponseMessage.class);

  private final AtomicReference<HttpStatus> status =
      new AtomicReference<>();

  private final AtomicReference<Object> responseBody =
      new AtomicReference<>();

  @BeforeEach
  void setUp() {
    when(request.createResponseBuilder(any(HttpStatus.class)))
        .thenAnswer(invocation -> {
          status.set(invocation.getArgument(0));
          return builder;
        });

    when(builder.header(anyString(), anyString())).thenReturn(builder);

    when(builder.body(any()))
        .thenAnswer(invocation -> {
          responseBody.set(invocation.getArgument(0));
          return builder;
        });

    when(builder.build()).thenReturn(response);
    when(response.getStatus()).thenAnswer(invocation -> status.get());
    when(response.getBody()).thenAnswer(invocation -> responseBody.get());
  }

  private RecoveryJob jobInStatus(RecoveryJobStatus jobStatus) {
    Instant timestamp = Instant.parse("2026-10-30T08:30:00Z");

    return new RecoveryJob(
        JOB_ID,
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 31),
        true,
        jobStatus,
        LocalDate.of(2026, 10, 3),
        timestamp,
        timestamp);
  }

  private HttpResponseMessage invoke(Action action, String jobId) {
    return switch (action) {
      case PAUSE -> function.pause(request, jobId, context);
      case RESUME -> function.resume(request, jobId, context);
    };
  }

  private void stubResult(Action action, Optional<RecoveryJob> result) {
    switch (action) {
      case PAUSE -> when(service.pause(JOB_ID)).thenReturn(result);
      case RESUME -> when(service.resume(JOB_ID)).thenReturn(result);
    }
  }

  private void stubFailure(Action action, RuntimeException failure) {
    switch (action) {
      case PAUSE -> when(service.pause(JOB_ID)).thenThrow(failure);
      case RESUME -> when(service.resume(JOB_ID)).thenThrow(failure);
    }
  }

  private void verifyServiceCall(Action action) {
    switch (action) {
      case PAUSE -> verify(service).pause(JOB_ID);
      case RESUME -> verify(service).resume(JOB_ID);
    }

    verifyNoMoreInteractions(service);
  }

  static Stream<Arguments> invalidIds() {
    return Stream.of(Action.values())
        .flatMap(
            action ->
                Stream.of(
                        (String) null,
                        "",
                        " ",
                        "not-a-uuid",
                        "1-1-1-1-1")
                    .map(id -> Arguments.of(action, id)));
  }

  @ParameterizedTest
  @CsvSource({
      "PAUSE, PAUSED, OK",
      "PAUSE, PAUSING, ACCEPTED",
      "RESUME, PENDING, ACCEPTED",
      "RESUME, RUNNING, OK"
  })
  void shouldReturnControlledJob(
      Action action,
      RecoveryJobStatus resultingStatus,
      HttpStatus expectedHttpStatus) throws Exception {

    RecoveryJob job = jobInStatus(resultingStatus);
    stubResult(action, Optional.of(job));

    HttpResponseMessage result = invoke(action, JOB_ID.toString());

    assertEquals(expectedHttpStatus, result.getStatus());

    JsonNode payload =
        new ObjectMapper().readTree((String) result.getBody());

    assertEquals(JOB_ID.toString(), payload.get("jobId").asText());
    assertEquals(resultingStatus.name(), payload.get("status").asText());
    assertEquals("2026-10-03", payload.get("nextDate").asText());

    verifyServiceCall(action);
  }

  @ParameterizedTest
  @EnumSource(Action.class)
  void shouldReturnNotFound(Action action) {
    stubResult(action, Optional.empty());

    HttpResponseMessage result = invoke(action, JOB_ID.toString());

    assertEquals(HttpStatus.NOT_FOUND, result.getStatus());
    verifyServiceCall(action);
  }

  @ParameterizedTest
  @MethodSource("invalidIds")
  void shouldRejectInvalidIdWithoutCallingService(
      Action action, String jobId) {

    HttpResponseMessage result = invoke(action, jobId);

    assertEquals(HttpStatus.BAD_REQUEST, result.getStatus());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @EnumSource(Action.class)
  void shouldReturnConflictForUnsupportedState(Action action) {
    stubFailure(
        action,
        new RecoveryJobStateConflictException("Simulated state conflict"));

    HttpResponseMessage result = invoke(action, JOB_ID.toString());

    assertEquals(HttpStatus.CONFLICT, result.getStatus());
    verifyServiceCall(action);
  }

  @ParameterizedTest
  @EnumSource(Action.class)
  void shouldReturnConflictForStaleEtag(Action action) {
    HttpResponse storageResponse = mock(HttpResponse.class);
    when(storageResponse.getStatusCode()).thenReturn(412);

    TableServiceException failure =
        new TableServiceException(
            "Simulated concurrent update",
            storageResponse,
            new TableServiceError(
                "UpdateConditionNotSatisfied",
                "Simulated concurrent update"));

    stubFailure(action, failure);

    HttpResponseMessage result = invoke(action, JOB_ID.toString());

    assertEquals(HttpStatus.CONFLICT, result.getStatus());
    verifyServiceCall(action);
  }

  @ParameterizedTest
  @EnumSource(Action.class)
  void shouldNotTreatUnexpectedIllegalStateAsConflict(Action action) {
    stubFailure(
        action,
        new IllegalStateException("Simulated internal failure"));

    Logger logger = mock(Logger.class);
    when(context.getLogger()).thenReturn(logger);

    HttpResponseMessage result = invoke(action, JOB_ID.toString());

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatus());
    verifyServiceCall(action);
  }
}