package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryJobsReadFunctionTest {

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

  private RecoveryJob runningJob() {
    return new RecoveryJob(
        UUID.fromString("a14c9701-8d81-4a50-9f55-9a531e1257c3"),
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 31),
        true,
        RecoveryJobStatus.RUNNING,
        LocalDate.of(2026, 10, 3),
        Instant.parse("2026-10-30T08:00:00Z"),
        Instant.parse("2026-10-30T08:30:00Z"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void shouldReturnJobAndProgress(boolean uppercase) throws Exception {
    RecoveryJob job = runningJob();
    UUID jobId = job.jobId();

    String pathId =
        uppercase
            ? jobId.toString().toUpperCase(Locale.ROOT)
            : jobId.toString();

    when(service.find(jobId)).thenReturn(Optional.of(job));

    HttpResponseMessage result = function.get(request, pathId, context);

    assertEquals(HttpStatus.OK, result.getStatus());

    JsonNode payload =
        new ObjectMapper().readTree((String) result.getBody());

    assertEquals(jobId.toString(), payload.get("jobId").asText());
    assertEquals("RUNNING", payload.get("status").asText());
    assertEquals("2026-10-01", payload.get("dateFrom").asText());
    assertEquals("2026-10-31", payload.get("dateTo").asText());
    assertEquals("2026-10-03", payload.get("nextDate").asText());
    assertEquals(true, payload.get("dryRun").asBoolean());
    assertFalse(payload.has("etag"));

    verify(builder).header("Content-Type", "application/json");
    verify(service).find(jobId);
    verifyNoMoreInteractions(service);
  }

  @Test
  void shouldReturnNotFoundForMissingJob() throws Exception {
    UUID jobId = UUID.randomUUID();
    String pathId = jobId.toString();

    when(service.find(jobId)).thenReturn(Optional.empty());

    HttpResponseMessage result = function.get(request, pathId, context);

    assertEquals(HttpStatus.NOT_FOUND, result.getStatus());

    JsonNode payload =
        new ObjectMapper().readTree((String) result.getBody());

    assertEquals(
        "Recovery job not found",
        payload.get("message").asText());

    verify(service).find(jobId);
    verifyNoMoreInteractions(service);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
      " ",
      "not-a-uuid",
      "1-1-1-1-1",
      "00000000-0000-0000-0000-00000000000g",
      " 00000000-0000-0000-0000-000000000001",
      "00000000-0000-0000-0000-000000000001 "
  })
  void shouldRejectInvalidIdWithoutCallingService(String pathId) {
    HttpResponseMessage result = function.get(request, pathId, context);

    assertEquals(HttpStatus.BAD_REQUEST, result.getStatus());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void shouldReturnServerErrorForReadFailure(boolean corruptedDocument)
      throws Exception {

    UUID jobId = UUID.randomUUID();
    String pathId = jobId.toString();

    RuntimeException failure =
        corruptedDocument
            ? new IllegalArgumentException("Simulated corrupted document")
            : new IllegalStateException("Simulated storage failure");

    when(service.find(jobId)).thenThrow(failure);
    Logger logger = mock(Logger.class);
    when(context.getLogger()).thenReturn(logger);

    HttpResponseMessage result = function.get(request, pathId, context);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatus());

    JsonNode payload =
        new ObjectMapper().readTree((String) result.getBody());

    assertEquals(
        "Unable to read recovery job",
        payload.get("message").asText());

    verify(service).find(jobId);
    verifyNoMoreInteractions(service);
  }
}