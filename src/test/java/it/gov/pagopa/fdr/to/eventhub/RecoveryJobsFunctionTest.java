package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobRequest;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryJobsFunctionTest {

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

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldCreateJobAndReturnAccepted(boolean dryRun) throws Exception {
    String json =
        """
        {
          "dateFrom": "2026-10-01",
          "dateTo": "2026-10-31",
          "dryRun": %s
        }
        """.formatted(dryRun);

    RecoveryJob job =
        RecoveryJob.create(
            new RecoveryJobRequest("2026-10-01", "2026-10-31", dryRun),
            Clock.fixed(
                Instant.parse("2026-10-30T08:30:00Z"),
                ZoneOffset.UTC));

    when(request.getBody()).thenReturn(Optional.of(json));
    when(service.create(any(RecoveryJobRequest.class))).thenReturn(job);

    HttpResponseMessage result = function.create(request, context);

    assertEquals(HttpStatus.ACCEPTED, result.getStatus());

    ArgumentCaptor<RecoveryJobRequest> captor =
        ArgumentCaptor.forClass(RecoveryJobRequest.class);

    verify(service).create(captor.capture());

    assertEquals(LocalDate.of(2026, 10, 1), captor.getValue().getDateFrom());
    assertEquals(LocalDate.of(2026, 10, 31), captor.getValue().getDateTo());
    assertEquals(dryRun, captor.getValue().isDryRun());

    JsonNode payload =
        new ObjectMapper().readTree((String) result.getBody());

    assertEquals(job.jobId().toString(), payload.get("jobId").asText());
    assertEquals("PENDING", payload.get("status").asText());
    assertEquals("2026-10-01", payload.get("nextDate").asText());
    assertEquals(dryRun, payload.get("dryRun").asBoolean());

    verify(builder).header("Content-Type", "application/json");
  }

  @Test
  void shouldRejectMissingBody() {
    when(request.getBody()).thenReturn(Optional.empty());

    HttpResponseMessage result = function.create(request, context);

    assertEquals(HttpStatus.BAD_REQUEST, result.getStatus());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "",
      " ",
      "null",
      "{}",
      "invalid-json",
      """
      {"dateFrom":"2026-10-31","dateTo":"2026-10-01"}
      """,
      """
      {"dateFrom":"2026-02-30","dateTo":"2026-10-31"}
      """,
      """
      {"dateFrom":"2026-10-01","dateTo":"2026-10-31","dryRun":"false"}
      """,
      """
      {"dateFrom":"2026-10-01","dateTo":"2026-10-31","unexpected":true}
      """,
      """
      {"dateFrom":"2026-10-01","dateTo":"2026-10-31"} {}
      """
  })
  void shouldRejectInvalidBodyWithoutCallingService(String json) {
    when(request.getBody()).thenReturn(Optional.of(json));

    HttpResponseMessage result = function.create(request, context);

    assertEquals(HttpStatus.BAD_REQUEST, result.getStatus());
    verifyNoInteractions(service);
  }

  @Test
  void shouldReturnServerErrorWhenPersistenceFails() throws Exception {
    String json =
        """
        {"dateFrom":"2026-10-01","dateTo":"2026-10-31"}
        """;

    when(request.getBody()).thenReturn(Optional.of(json));
    Logger logger = mock(Logger.class);
    when(context.getLogger()).thenReturn(logger);

    when(service.create(any(RecoveryJobRequest.class)))
        .thenThrow(new IllegalStateException("Simulated storage failure"));

    HttpResponseMessage result = function.create(request, context);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatus());

    JsonNode payload =
        new ObjectMapper().readTree((String) result.getBody());

    assertEquals(
        "Unable to create recovery job",
        payload.get("message").asText());

    ArgumentCaptor<RecoveryJobRequest> captor =
        ArgumentCaptor.forClass(RecoveryJobRequest.class);

    verify(service).create(captor.capture());
    assertEquals(true, captor.getValue().isDryRun());
  }
}