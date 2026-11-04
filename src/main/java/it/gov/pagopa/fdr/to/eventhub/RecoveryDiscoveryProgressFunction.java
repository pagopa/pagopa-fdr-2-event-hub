package it.gov.pagopa.fdr.to.eventhub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryTableClients;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryDiscoveryCheckpointTableMapper;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobTableMapper;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryDiscoveryProgressService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class RecoveryDiscoveryProgressFunction {

  private static final JsonMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private RecoveryDiscoveryProgressService service;

  public RecoveryDiscoveryProgressFunction() {
    // Initialize storage clients only after request validation.
  }

  public RecoveryDiscoveryProgressFunction(
      RecoveryDiscoveryProgressService service) {

    this.service = Objects.requireNonNull(service, "service is required");
  }

  @FunctionName("RecoveryJobsDiscoveryGet")
  public HttpResponseMessage get(
      @HttpTrigger(
          name = "request",
          methods = {HttpMethod.GET},
          route = "recovery/jobs/{jobId}/discovery/{day}",
          authLevel = AuthorizationLevel.ANONYMOUS)
      HttpRequestMessage<Optional<String>> request,
      @BindingName("jobId") String jobId,
      @BindingName("day") String day,
      ExecutionContext context) {

    final UUID parsedJobId;
    final LocalDate parsedDay;

    try {
      parsedJobId = parseJobId(jobId);
      parsedDay = parseDay(day);

    } catch (IllegalArgumentException e) {
      return response(
          request,
          HttpStatus.BAD_REQUEST,
          Map.of(
              "message",
              "jobId must be a canonical UUID and day must use YYYY-MM-DD"));
    }

    try {
      var result = getService().find(parsedJobId, parsedDay);

      if (result.isEmpty()) {
        return response(
            request,
            HttpStatus.NOT_FOUND,
            Map.of("message", "Recovery job not found"));
      }

      return response(request, HttpStatus.OK, result.orElseThrow());

    } catch (RecoveryDiscoveryProgressService.DayOutsideJobRangeException e) {
      return response(
          request,
          HttpStatus.BAD_REQUEST,
          Map.of("message", "day must belong to the recovery job date range"));

    } catch (Exception e) {
      context.getLogger().log(
          Level.SEVERE,
          "Unable to read recovery discovery progress",
          e);

      return response(
          request,
          HttpStatus.INTERNAL_SERVER_ERROR,
          Map.of("message", "Unable to read recovery discovery progress"));
    }
  }

  private static UUID parseJobId(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("jobId is required");
    }

    UUID parsed = UUID.fromString(value);

    if (!parsed.toString().equalsIgnoreCase(value)) {
      throw new IllegalArgumentException("jobId must be canonical");
    }

    return parsed;
  }

  private static LocalDate parseDay(String value) {
    if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
      throw new IllegalArgumentException("day must use YYYY-MM-DD");
    }

    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("day must be a valid date", e);
    }
  }

  private synchronized RecoveryDiscoveryProgressService getService() {
    if (service == null) {
      RecoveryTableClients clients = RecoveryTableClients.fromEnvironment();

      service = new RecoveryDiscoveryProgressService(
          new RecoveryJobRepository(
              clients.jobs(),
              new RecoveryJobTableMapper()),
          new RecoveryDiscoveryCheckpointRepository(
              clients.items(),
              new RecoveryDiscoveryCheckpointTableMapper()));
    }

    return service;
  }

  private static HttpResponseMessage response(
      HttpRequestMessage<?> request,
      HttpStatus status,
      Object body) {

    try {
      return request.createResponseBuilder(status)
          .header("Content-Type", "application/json")
          .body(OBJECT_MAPPER.writeValueAsString(body))
          .build();

    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Unable to serialize discovery progress response",
          e);
    }
  }
}