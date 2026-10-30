package it.gov.pagopa.fdr.to.eventhub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryTableClients;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobRequest;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobTableMapper;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobService;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

public final class RecoveryJobsFunction {

  private static final JsonMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private RecoveryJobService service;

  public RecoveryJobsFunction() {
    // Initialize storage clients lazily on the first valid request.
  }

  public RecoveryJobsFunction(RecoveryJobService service) {
    this.service = Objects.requireNonNull(service, "service is required");
  }

  @FunctionName("RecoveryJobsCreate")
  public HttpResponseMessage create(
      @HttpTrigger(
              name = "request",
              methods = {HttpMethod.POST},
              route = "recovery/jobs",
              authLevel = AuthorizationLevel.ANONYMOUS)
          HttpRequestMessage<Optional<String>> request,
      ExecutionContext context) {

    Optional<String> body = request.getBody();

    if (body.isEmpty() || body.orElseThrow().isBlank()) {
      return response(
          request,
          HttpStatus.BAD_REQUEST,
          Map.of("message", "Request body is required"));
    }

    final RecoveryJobRequest jobRequest;

    try {
      jobRequest =
          OBJECT_MAPPER.readValue(
              body.orElseThrow(), RecoveryJobRequest.class);
    } catch (JsonProcessingException e) {
      return response(
          request,
          HttpStatus.BAD_REQUEST,
          Map.of("message", "Invalid recovery job request"));
    }

    if (jobRequest == null) {
      return response(
          request,
          HttpStatus.BAD_REQUEST,
          Map.of("message", "Request body must be a JSON object"));
    }

    try {
      RecoveryJob job = getService().create(jobRequest);

      return response(request, HttpStatus.ACCEPTED, job);
    } catch (Exception e) {
      context.getLogger().log(
          Level.SEVERE, "Unable to create recovery job", e);

      return response(
          request,
          HttpStatus.INTERNAL_SERVER_ERROR,
          Map.of("message", "Unable to create recovery job"));
    }
  }

  private synchronized RecoveryJobService getService() {
    if (service == null) {
      RecoveryTableClients clients =
          RecoveryTableClients.fromEnvironment();

      RecoveryJobRepository repository =
          new RecoveryJobRepository(
              clients.jobs(), new RecoveryJobTableMapper());

      service = new RecoveryJobService(repository, Clock.systemUTC());
    }

    return service;
  }

  private HttpResponseMessage response(
      HttpRequestMessage<?> request,
      HttpStatus status,
      Object body) {

    final String json;

    try {
      json = OBJECT_MAPPER.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Unable to serialize recovery response", e);
    }

    return request
        .createResponseBuilder(status)
        .header("Content-Type", "application/json")
        .body(json)
        .build();
  }
}