package it.gov.pagopa.fdr.to.eventhub;

import com.azure.data.tables.models.TableServiceException;
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
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryTableClients;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJob;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobRequest;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryJobStatus;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryJobTableMapper;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobService;
import it.gov.pagopa.fdr.to.eventhub.service.recovery.RecoveryJobStateConflictException;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class RecoveryJobsFunction {

    private static final String MESSAGE = "message";

    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private RecoveryJobService service;

    public RecoveryJobsFunction() {
        // Initialize storage clients lazily on the first valid request.
    }

    public RecoveryJobsFunction(RecoveryJobService service) {
        this.service = Objects.requireNonNull(service, "service is required");
    }

    @FunctionName("RecoveryJobsCreate")
    public HttpResponseMessage create(@HttpTrigger(name = "request", methods = {
            HttpMethod.POST }, route = "recovery/jobs", authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> request,
            ExecutionContext context) {

        Optional<String> body = request.getBody();

        if (body.isEmpty() || body.orElseThrow().isBlank()) {
            return response(request, HttpStatus.BAD_REQUEST, Map.of(MESSAGE, "Request body is required"));
        }

        final RecoveryJobRequest jobRequest;

        try {
            jobRequest = OBJECT_MAPPER.readValue(body.orElseThrow(), RecoveryJobRequest.class);
        } catch (JsonProcessingException e) {
            return response(request, HttpStatus.BAD_REQUEST, Map.of(MESSAGE, "Invalid recovery job request"));
        }

        if (jobRequest == null) {
            return response(request, HttpStatus.BAD_REQUEST, Map.of(MESSAGE, "Request body must be a JSON object"));
        }

        try {
            RecoveryJob job = getService().create(jobRequest);

            return response(request, HttpStatus.ACCEPTED, job);
        } catch (Exception e) {
            context.getLogger().log(Level.SEVERE, "Unable to create recovery job", e);

            return response(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of(MESSAGE, "Unable to create recovery job"));
        }
    }

    @FunctionName("RecoveryJobsGet")
    public HttpResponseMessage get(@HttpTrigger(name = "request", methods = {
            HttpMethod.GET }, route = "recovery/jobs/{jobId}", authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> request,
            @BindingName("jobId") String jobId, ExecutionContext context) {

        final UUID parsedJobId;

        try {
            parsedJobId = parseJobId(jobId);
        } catch (IllegalArgumentException e) {
            return response(request, HttpStatus.BAD_REQUEST, Map.of(MESSAGE, "jobId must be a valid UUID"));
        }

        try {
            Optional<RecoveryJob> job = getService().find(parsedJobId);

            if (job.isEmpty()) {
                return response(request, HttpStatus.NOT_FOUND, Map.of(MESSAGE, "Recovery job not found"));
            }

            return response(request, HttpStatus.OK, job.orElseThrow());
        } catch (Exception e) {
            context.getLogger().log(Level.SEVERE, "Unable to read recovery job", e);

            return response(request, HttpStatus.INTERNAL_SERVER_ERROR, Map.of(MESSAGE, "Unable to read recovery job"));
        }
    }

    private static UUID parseJobId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }

        UUID parsed = UUID.fromString(value);

        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("jobId must use canonical UUID format");
        }

        return parsed;
    }

    private enum ControlAction {
        PAUSE, RESUME
    }

    @FunctionName("RecoveryJobsPause")
    public HttpResponseMessage pause(@HttpTrigger(name = "request", methods = {
            HttpMethod.POST }, route = "recovery/jobs/{jobId}/pause", authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> request,
            @BindingName("jobId") String jobId, ExecutionContext context) {

        return control(request, jobId, context, ControlAction.PAUSE);
    }

    @FunctionName("RecoveryJobsResume")
    public HttpResponseMessage resume(@HttpTrigger(name = "request", methods = {
            HttpMethod.POST }, route = "recovery/jobs/{jobId}/resume", authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> request,
            @BindingName("jobId") String jobId, ExecutionContext context) {

        return control(request, jobId, context, ControlAction.RESUME);
    }

    private HttpResponseMessage control(HttpRequestMessage<Optional<String>> request, String jobId,
            ExecutionContext context, ControlAction action) {

        final UUID parsedJobId;

        try {
            parsedJobId = parseJobId(jobId);
        } catch (IllegalArgumentException e) {
            return response(request, HttpStatus.BAD_REQUEST, Map.of(MESSAGE, "jobId must be a valid UUID"));
        }

        try {
            RecoveryJobService jobService = getService();

            Optional<RecoveryJob> result = switch (action) {
            case PAUSE -> jobService.pause(parsedJobId);
            case RESUME -> jobService.resume(parsedJobId);
            };

            if (result.isEmpty()) {
                return response(request, HttpStatus.NOT_FOUND, Map.of(MESSAGE, "Recovery job not found"));
            }

            RecoveryJob job = result.orElseThrow();

            boolean accepted = job.status() == RecoveryJobStatus.PAUSING
                    || (action == ControlAction.RESUME && job.status() == RecoveryJobStatus.PENDING);

            return response(request, accepted ? HttpStatus.ACCEPTED : HttpStatus.OK, job);

        } catch (RecoveryJobStateConflictException e) {
            return response(request, HttpStatus.CONFLICT,
                    Map.of(MESSAGE, "Operation is not allowed in the current job status"));

        } catch (TableServiceException e) {
            if (e.getResponse() != null && e.getResponse().getStatusCode() == 412) {

                return response(request, HttpStatus.CONFLICT,
                        Map.of(MESSAGE, "Recovery job was modified concurrently; read its current status"));
            }

            return controlFailure(request, context, e);

        } catch (Exception e) {
            return controlFailure(request, context, e);
        }
    }

    private HttpResponseMessage controlFailure(HttpRequestMessage<?> request, ExecutionContext context,
            Exception exception) {

        context.getLogger().log(Level.SEVERE, "Unable to control recovery job", exception);

        return response(request, HttpStatus.INTERNAL_SERVER_ERROR, Map.of(MESSAGE, "Unable to control recovery job"));
    }

    private synchronized RecoveryJobService getService() {
        if (service == null) {
            RecoveryTableClients clients = RecoveryTableClients.fromEnvironment();

            RecoveryJobRepository repository = new RecoveryJobRepository(clients.jobs(), new RecoveryJobTableMapper());

            service = new RecoveryJobService(repository, Clock.systemUTC());
        }

        return service;
    }

    private HttpResponseMessage response(HttpRequestMessage<?> request, HttpStatus status, Object body) {

        final String json;

        try {
            json = OBJECT_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize recovery response", e);
        }

        return request.createResponseBuilder(status).header("Content-Type", "application/json").body(json).build();
    }
}