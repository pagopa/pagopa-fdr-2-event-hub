package it.gov.pagopa.fdr.to.eventhub;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.StorageAccount;

import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;

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

	@FunctionName("ProcessBlobFiles")
	//@StorageAccount("AzureWebJobsStorage")
	public void processBlobFiles(
			@BlobTrigger(name = "blob", dataType = "binary", path = "{containerName}/{blobName}", connection = "FDR_SA_CONNECTION_STRING") byte[] content,
			@BindingName("containerName") String containerName,
			@BindingName("blobName") String blobName,
			@BindingName("metadata") Map<String, String> blobMetadata,
			ExecutionContext context) {

		context.getLogger().info(() -> String.format("Triggered for Blob container: %s, name: %s, size: %d bytes", containerName, blobName, content.length));

		try {
			String decompressedContent = decompressGzip(content);

			if (fdr1Container.equalsIgnoreCase(containerName)) {
				FlussoRendicontazione flusso = parseXmlContent(decompressedContent);
				flusso.setMetadata(blobMetadata);
				processXmlBlobAndSendToEventHub(flusso);
			} else if (fdr3Container.equalsIgnoreCase(containerName)) {
				// TODO future needs
			} else {
				context.getLogger().warning(() -> String.format("Unsupported type: %s for Blob: %s", containerName, blobName));
			}
		} catch (Exception e) {
			context.getLogger().severe(() -> String.format("Error processing Blob '%s/%s': %s", containerName, blobName, e.getMessage()));
		}
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

	// using a SAX parser for performance reason
	private FlussoRendicontazione parseXmlContent(String xmlContent) throws Exception {
		SAXParserFactory factory = SAXParserFactory.newInstance();
		// sonarlint security report: Disable access to external entities in XML parsing
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

		SAXParser parser = factory.newSAXParser();
		AtomicReference<FlussoRendicontazione> flusso = new AtomicReference<>();
		Consumer<FlussoRendicontazione> updater = flusso::set;

		DefaultHandler handler = new DefaultHandler() {
			private String currentElement;
			private StringBuilder value = new StringBuilder();

			@Override
			public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
				currentElement = qName;
				value.setLength(0);
			}

			@Override
			public void characters(char[] ch, int start, int length) throws SAXException {
				value.append(ch, start, length);
			}

			@Override
			public void endElement(String uri, String localName, String qName) throws SAXException {
				if (qName.equals("ws:nodoInviaFlussoRendicontazione")) {
					updater.accept(FlussoRendicontazione.builder()
							.identificativoPSP(getValue("identificativoPSP"))
							.identificativoIntermediarioPSP(getValue("identificativoIntermediarioPSP"))
							.identificativoCanale(getValue("identificativoCanale"))
							.password(getValue("password"))
							.identificativoDominio(getValue("identificativoDominio"))
							.identificativoFlusso(getValue("identificativoFlusso"))
							.dataOraFlusso(getValue("dataOraFlusso"))
							.build());
				}
				currentElement = null;
			}

			private String getValue(String elementName) {
				if (currentElement != null && currentElement.equals(elementName)) {
					return value.toString().trim();
				}
				return null;
			}
		};

		parser.parse(new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)), handler);

		return flusso.get();
	}

	private void processXmlBlobAndSendToEventHub(FlussoRendicontazione flussoRendicontazione) throws Exception {
		// Convert FlussoRendicontazione to event models
		FlowTxEventModel flowEvent = FlussoRendicontazioneMapper.toFlowTxEvent(flussoRendicontazione);
		ReportedIUVEventModel reportedIUVEvent = FlussoRendicontazioneMapper.toReportedIUVEvent(flussoRendicontazione);

		// Serialize the objects to JSON
		String flowEventJson = objectMapper.writeValueAsString(flowEvent);
		String reportedIUVEventJson = objectMapper.writeValueAsString(reportedIUVEvent);

		// Create EventData objects
		EventData flowEventData = new EventData(flowEventJson);
		EventData reportedIUVEventData = new EventData(reportedIUVEventJson);
		
		eventHubClientFlowTx.send(Arrays.asList(flowEventData));
		eventHubClientReportedIUV.send(Arrays.asList(reportedIUVEventData));
	}

}
