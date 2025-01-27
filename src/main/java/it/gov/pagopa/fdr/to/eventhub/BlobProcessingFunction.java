package it.gov.pagopa.fdr.to.eventhub;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.StorageAccount;

import it.gov.pagopa.fdr.to.eventhub.exception.XmlParsingException;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlSAXParser;

public class BlobProcessingFunction {

	private final String fdr1Container = System.getenv().getOrDefault("BLOB_STORAGE_FDR1_CONTAINER", "fdr1-flows");
	private final String fdr3Container = System.getenv().getOrDefault("BLOB_STORAGE_FDR3_CONTAINER", "fdr3-flows");
	private final EventHubProducerClient eventHubClientFlowTx;
	private final EventHubProducerClient eventHubClientReportedIUV;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public BlobProcessingFunction() { 
		this.eventHubClientFlowTx = new EventHubClientBuilder().connectionString(System.getenv("EVENT_HUB_FLOWTX_CONNECTION_STRING"),
				System.getenv("EVENT_HUB_FLOWTX_NAME")) .buildProducerClient();
		this.eventHubClientReportedIUV = new EventHubClientBuilder().connectionString(System.getenv("EVENT_HUB_REPORTEDIUV_CONNECTION_STRING"),
				System.getenv("EVENT_HUB_REPORTEDIUV_NAME")) .buildProducerClient(); 
	}

	// Constructor to inject the Event Hub clients
	public BlobProcessingFunction(EventHubProducerClient eventHubClientFlowTx,
			EventHubProducerClient eventHubClientReportedIUV) { 
		this.eventHubClientFlowTx = eventHubClientFlowTx; 
		this.eventHubClientReportedIUV = eventHubClientReportedIUV; 
	}

	@FunctionName("ProcessFDR1BlobFiles")
	//@StorageAccount("AzureWebJobsStorage")
	public void processFDR1BlobFiles(
			@BlobTrigger(name = "Fdr1BlobTrigger", dataType = "binary", path = "%BLOB_STORAGE_FDR1_CONTAINER%/{blobName}", connection = "FDR_SA_CONNECTION_STRING") byte[] content,
			@BindingName("blobName") String blobName,
			@BindingName("Metadata") Map<String, String> blobMetadata,
			final ExecutionContext context) {
		
		context.getLogger().info(() -> String.format("Triggered for Blob container: %s, name: %s, size: %d bytes", fdr1Container, blobName, content.length));

		try {
			String decompressedContent = isGzip(content) ? decompressGzip(content) : new String(content, StandardCharsets.UTF_8);
			FlussoRendicontazione flusso = parseXml(decompressedContent);
			flusso.setMetadata(blobMetadata);
			processXmlBlobAndSendToEventHub(flusso);
			
		} catch (Exception e) {
			context.getLogger().severe(() -> String.format("Error processing Blob '%s/%s': %s", fdr1Container, blobName, e.getMessage()));
		}
	}
	
	@FunctionName("ProcessFDR3BlobFiles")
	public void processFDR3BlobFiles(
			@BlobTrigger(name = "Fdr3BlobTrigger", dataType = "binary", path = "%BLOB_STORAGE_FDR3_CONTAINER%/{blobName}", connection = "FDR_SA_CONNECTION_STRING") byte[] content,
			@BindingName("blobName") String blobName,
			@BindingName("Metadata") Map<String, String> blobMetadata,
			final ExecutionContext context) {

		context.getLogger().info(() -> String.format("Triggered for Blob container: %s, name: %s, size: %d bytes", fdr3Container, blobName, content.length));

		try {
			String decompressedContent = isGzip(content) ? decompressGzip(content) : new String(content, StandardCharsets.UTF_8);
			// TODO future needs
		} catch (Exception e) {
			context.getLogger().severe(() -> String.format("Error processing Blob '%s/%s': %s", fdr3Container, blobName, e.getMessage()));
		}
	}
	
	private boolean isGzip(byte[] content) {
	    return content.length > 2 && content[0] == (byte) 0x1F && content[1] == (byte) 0x8B;
	}

	private String decompressGzip(byte[] compressedContent) throws Exception {
		try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressedContent));
				InputStreamReader reader = new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8);
				BufferedReader bufferedReader = new BufferedReader(reader)) {

			StringBuilder decompressedData = new StringBuilder();
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				decompressedData.append(line).append("\n");
			}
			return decompressedData.toString();
		}
	}

	
	
	private FlussoRendicontazione parseXml(String xmlContent) throws Exception {
		// using a SAX parser for performance reason
		return FDR1XmlSAXParser.parseXmlContent(xmlContent);
	}


	private void processXmlBlobAndSendToEventHub(FlussoRendicontazione flussoRendicontazione) throws Exception {
		// Convert FlussoRendicontazione to event models
		FlowTxEventModel flowEvent = FlussoRendicontazioneMapper.toFlowTxEventList(flussoRendicontazione);
		List<ReportedIUVEventModel> reportedIUVEventList = FlussoRendicontazioneMapper.toReportedIUVEventList(flussoRendicontazione);

		// Serialize the objects to JSON
		String flowEventJson = objectMapper.registerModule(new JavaTimeModule()).writeValueAsString(flowEvent);
		String reportedIUVEventJson = objectMapper.registerModule(new JavaTimeModule()).writeValueAsString(reportedIUVEventList);

		// Create EventData objects
		EventData flowEventData = new EventData(flowEventJson);
		EventData reportedIUVEventData = new EventData(reportedIUVEventJson);
		
		// TODO da ripristinare
		//eventHubClientFlowTx.send(Arrays.asList(flowEventData));
		//eventHubClientReportedIUV.send(Arrays.asList(reportedIUVEventData));
	}

}
