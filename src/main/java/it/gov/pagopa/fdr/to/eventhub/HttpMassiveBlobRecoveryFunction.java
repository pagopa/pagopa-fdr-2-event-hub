package it.gov.pagopa.fdr.to.eventhub;

import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.BlobFileData;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import lombok.Getter;

/** Azure Functions with Azure Http trigger. */
public class HttpMassiveBlobRecoveryFunction {

	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final String JSON_FILENAME = "fileName";
	private static final String JSON_CONTAINER = "container";
	private static final String JSON_DATEFROM = "dateFrom";
	private static final String JSON_DATETO = "dateTo";
	private final String fdr1Container =
			System.getenv().getOrDefault("BLOB_STORAGE_FDR1_CONTAINER", "fdr1-flows");
	@Getter private final EventHubProducerClient eventHubClientFlowTx;
	@Getter private final EventHubProducerClient eventHubClientReportedIUV;

	public HttpMassiveBlobRecoveryFunction() {
		this.eventHubClientFlowTx =
				CommonUtil.createEventHubClient(
						System.getenv("EVENT_HUB_FLOWTX_CONNECTION_STRING"),
						System.getenv("EVENT_HUB_FLOWTX_NAME"));

		this.eventHubClientReportedIUV =
				CommonUtil.createEventHubClient(
						System.getenv("EVENT_HUB_REPORTEDIUV_CONNECTION_STRING"),
						System.getenv("EVENT_HUB_REPORTEDIUV_NAME"));
	}

	public HttpMassiveBlobRecoveryFunction(
			EventHubProducerClient eventHubClientFlowTx,
			EventHubProducerClient eventHubClientReportedIUV) {
		this.eventHubClientFlowTx = eventHubClientFlowTx;
		this.eventHubClientReportedIUV = eventHubClientReportedIUV;
	}

	@FunctionName("HTTPBlobRecovery")
	public HttpResponseMessage run(
			@HttpTrigger(
					name = "HTTPBlobRecoveryTrigger",
					methods = {HttpMethod.POST},
					route = "notify/fdr",
					authLevel = AuthorizationLevel.ANONYMOUS)
			HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {

		// Check if body is present
		Optional<String> requestBody = request.getBody();
		if (requestBody.isEmpty()) {
			return CommonUtil.badRequest(request, "Missing request body");
		}

		// Get named parameter
		boolean sendFlowEvent =
				Boolean.parseBoolean(request.getQueryParameters().getOrDefault("sendFlowEvent", "true"));
		boolean sendPaymentEvents =
				Boolean.parseBoolean(request.getQueryParameters().getOrDefault("sendPaymentEvent", "true"));

		try {
			JsonNode jsonNode = objectMapper.readTree(requestBody.get());
			String fileName =
					Optional.ofNullable(jsonNode.get(JSON_FILENAME)).map(JsonNode::asText).orElse(null);
			String container =
					Optional.ofNullable(jsonNode.get(JSON_CONTAINER)).map(JsonNode::asText).orElse(null);
			String fromStr = Optional.ofNullable(jsonNode.get(JSON_DATEFROM)).map(JsonNode::asText).orElse(null);
			String toStr = Optional.ofNullable(jsonNode.get(JSON_DATETO)).map(JsonNode::asText).orElse(null);

			if (container == null) {
                return CommonUtil.badRequest(request, "The 'container' field is mandatory.");
            }
            if (fileName == null && (fromStr == null || toStr == null)) {
                return CommonUtil.badRequest(request, "Either 'fileName' or both 'dateFrom' and 'dateTo' must be provided.");
            }
            if (fileName != null && (fromStr != null || toStr != null)) {
                return CommonUtil.badRequest(request, "'fileName' and 'dateFrom/dateTo' are mutually exclusive.");
            }

			LocalDateTime fromDateTime = parseDate(fromStr, true);
			LocalDateTime toDateTime = parseDate(toStr, false);

			context
			.getLogger()
			.fine(
					() ->
					String.format(
							"[HTTP FDR] Triggered at: %s for Blob container: %s, name: %s",
							LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
							container,
							fileName));
			
			List<BlobFileData> filesToProcess = Collections.emptyList();

			if (fileName != null) {
				BlobFileData fileData = CommonUtil.getBlobFile("FDR_SA_CONNECTION_STRING", container, fileName, context);
				if (Objects.isNull(fileData)) {
					return CommonUtil.notFound(
							request, String.format("File %s not found in container %s", fileName, container));
				}
				CommonUtil.checkBlobMetadata(request, fileData.getMetadata(), fileName, container);
				filesToProcess.add(fileData);
			}else {
				filesToProcess = CommonUtil.getBlobFilesInDateRange(request,"FDR_SA_CONNECTION_STRING", container, fromDateTime, toDateTime, context);
			}

			
			List<String> errors = Collections.emptyList();
			for (BlobFileData fileData : filesToProcess) {
				errors.add(processBlobFile(fileData, container, sendFlowEvent, sendPaymentEvents, context));
            }

			return errors.isEmpty() ? CommonUtil.ok(request,String.format("Processed recovery request for file: %s in container: %s", fileName, container)) : 
				CommonUtil.serviceUnavailable(
					request,
					"{ \"errors\": [" + errors.stream()
			        .map(err -> "\"" + err + "\"")
			        .collect(Collectors.joining(", ")) + "] }");

		} catch (IOException e) {
			return CommonUtil.badRequest(request, "Invalid JSON format");
		} catch (IllegalArgumentException e) {
			return CommonUtil.badRequest(request, e.getMessage());
		} catch (Exception e) {
			context.getLogger().severe("[HTTP FDR] Unexpected error: " + e.getMessage());
			return CommonUtil.serverError(request, "Internal Server Error");
		}
	}

	private String processBlobFile(BlobFileData fileData, String container, boolean sendFlowEvent, boolean sendPaymentEvents, 
			ExecutionContext context) throws IOException, ParserConfigurationException, SAXException {
		
		String error = "";
		boolean isValidGzipFile = CommonUtil.isGzip(fileData.getFileContent());
		try (InputStream decompressedStream =
				isValidGzipFile ? CommonUtil.decompressGzip(fileData.getFileContent())
						: new ByteArrayInputStream(fileData.getFileContent())) {

			boolean eventBatchSent;
			String flowName;

			if (fdr1Container.equals(container)) {
				context.getLogger().info("Processing data from FdR1 container.");
				FlussoRendicontazione flusso = CommonUtil.parseXml(decompressedStream);
				flusso.setMetadata(fileData.getMetadata());
				flowName = flusso.getIdentificativoFlusso();
				eventBatchSent = CommonUtil.processXmlBlobAndSendToEventHub(
						eventHubClientFlowTx, eventHubClientReportedIUV, flusso, context, sendFlowEvent, sendPaymentEvents);
			} else {
				context.getLogger().info("Processing data from FdR3 container.");
				Flow flusso = CommonUtil.parseJSON(decompressedStream);
				flusso.setMetadata(fileData.getMetadata());
				flowName = flusso.getFdr();
				eventBatchSent = CommonUtil.processJsonBlobAndSendToEventHub(
						eventHubClientFlowTx, eventHubClientReportedIUV, flusso, context, sendFlowEvent, sendPaymentEvents);
			}

			if (!eventBatchSent) {	
				error = String.format(
								"EventHub failed to confirm batch processing for flow ID %s [file %s, container"
										+ " %s]",
										flowName, fileData.getFileName(), container);
			}
		}
		
		return error;
	}

	private LocalDateTime parseDate(String dateStr, boolean isStart) {
		if (dateStr == null) {
			return null;
		}
		try {
			return LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		} catch (DateTimeParseException e) {
			try {
				LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
				return isStart ? date.atStartOfDay() : date.atTime(23, 59, 59);
			}
			catch (DateTimeParseException ex) {
				throw new IllegalArgumentException("Invalid date format for value: "+dateStr+". Expected yyyy-MM-dd or yyyy-MM-dd'T'HH:mm:ss");
			}
		}
	}
}
