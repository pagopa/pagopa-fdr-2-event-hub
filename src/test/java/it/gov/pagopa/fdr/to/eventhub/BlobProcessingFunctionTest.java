package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.microsoft.azure.functions.ExecutionContext;
import it.gov.pagopa.fdr.to.eventhub.client.AppInsightTelemetryClient;
import it.gov.pagopa.fdr.to.eventhub.exception.AlertAppException;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlStAXParser;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import it.gov.pagopa.fdr.to.eventhub.util.SampleContentFileUtil;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.xml.sax.SAXException;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith({MockitoExtension.class, SystemStubsExtension.class})
class BlobProcessingFunctionTest {

  @SystemStub private final EnvironmentVariables environmentVariables = new EnvironmentVariables();
  @Captor ArgumentCaptor<String> logCaptor;
  @Mock private EventHubProducerClient eventHubClientFlowTx;
  @Mock private EventHubProducerClient eventHubClientReportedIUV;
  @Mock private ExecutionContext context;
  @Mock private FDR1XmlStAXParser mockFDR1XmlParser;
  @Mock private AppInsightTelemetryClient aiTelemetryClientMock;
  private BlobProcessingFunction function;

  @BeforeEach
  void setup() {
    function =
        new BlobProcessingFunction(
            eventHubClientFlowTx,
            eventHubClientReportedIUV,
            aiTelemetryClientMock,
            mockFDR1XmlParser);
    lenient().when(eventHubClientFlowTx.createBatch()).thenReturn(mock(EventDataBatch.class));
    lenient().when(eventHubClientReportedIUV.createBatch()).thenReturn(mock(EventDataBatch.class));
  }

  @Test
  void testFDR1BlobTriggerProcessing() throws Exception {
    String sampleXml = SampleContentFileUtil.getFileContent("sample.xml");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {

      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.isGzip(any())).thenReturn(true);
      mockedUtil
          .when(() -> CommonUtil.decompressGzip(any()))
          .thenReturn(new GZIPInputStream(new ByteArrayInputStream(compressedData)));
      mockedUtil
          .when(
              () ->
                  CommonUtil.processXmlBlobAndSendToEventHub(
                      any(), any(), any(), any(), anyBoolean(), anyBoolean()))
          .thenReturn(true);

      assertDoesNotThrow(
          () -> function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context));
    }
  }

  @Test
  void testFDR1BigBlobTriggerProcessing() throws Exception {
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    String sampleXml = SampleContentFileUtil.getFileContent("big_sample.xml");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    try (MockedStatic<CommonUtil> mockedUtil =
        mockStatic(CommonUtil.class, Mockito.CALLS_REAL_METHODS)) {
      mockedUtil
          .when(
              () ->
                  CommonUtil.processXmlBlobAndSendToEventHub(
                      any(), any(), any(), any(), anyBoolean(), anyBoolean()))
          .thenReturn(true);

      assertDoesNotThrow(
          () -> function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context));
    }
  }

  @Test
  void testFDR1ProcessBlobWithNullData() {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    assertThrows(
        IllegalArgumentException.class,
        () -> function.processFDR1BlobFiles(null, "sampleBlob", metadata, context));
  }

  @Test
  void testFDR1ProcessBlobWithInvalidGzipData() throws SAXException, XMLStreamException {
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    byte[] invalidData = "invalidData".getBytes(StandardCharsets.UTF_8);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    AlertAppException thrown =
        assertThrows(
            AlertAppException.class,
            () -> function.processFDR1BlobFiles(invalidData, "sampleBlob", metadata, context));

    assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));

    verify(aiTelemetryClientMock).createCustomEvent(any(), anyString(), any());
  }

  @Test
  void testFDR1ProcessBlobWithEmptyXml() throws Exception {
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData("");

    AlertAppException thrown =
        assertThrows(
            AlertAppException.class,
            () -> function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context));

    assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));

    verify(eventHubClientFlowTx, never()).send(any(ArrayList.class));
    verify(eventHubClientReportedIUV, never()).send(any(ArrayList.class));
    verify(aiTelemetryClientMock).createCustomEvent(any(), anyString(), any());
  }

  @Test
  void testFDR1ProcessBlobWithMalformedXml() throws Exception {
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData("<xml>malformed</xml>");

    AlertAppException thrown =
        assertThrows(
            AlertAppException.class,
            () -> function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context));

    assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
    verify(aiTelemetryClientMock).createCustomEvent(any(), anyString(), any());
  }

  @Test
  void testFDR1ValidateBlobMetadata_NullMetadata() {
    assertDoesNotThrow(
        () -> function.processFDR1BlobFiles(new byte[] {}, "testBlob", null, context));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
    verify(aiTelemetryClientMock, never()).createCustomEvent(any(), anyString(), any());
  }

  @Test
  void testFDR1ValidateBlobMetadata_EmptyMetadata() {
    Map<String, String> emptyMetadata = new HashMap<>();
    assertDoesNotThrow(
        () -> function.processFDR1BlobFiles(new byte[] {}, "testBlob", emptyMetadata, context));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
    verify(aiTelemetryClientMock, never()).createCustomEvent(any(), anyString(), any());
  }

  @Test
  void testFDR1ValidateBlobMetadata_MissingKeys() {
    Map<String, String> invalidMetadata = new HashMap<>();
    invalidMetadata.put("sessionId", "1234");
    // "insertedTimestamp" key is missing

    assertDoesNotThrow(
        () -> function.processFDR1BlobFiles(new byte[] {}, "testBlob", invalidMetadata, context));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
    verify(aiTelemetryClientMock, never()).createCustomEvent(any(), anyString(), any());
  }

  @Test
  void testFDR1ValidateBlobMetadata_ElaborateFalse() {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "false");

    assertDoesNotThrow(
        () -> function.processFDR1BlobFiles(new byte[] {}, "testBlob", metadata, context));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
    verify(aiTelemetryClientMock, never()).createCustomEvent(any(), anyString(), any());
  }

  @Test
  void testFDR1BlobTriggerProcessingError() throws Exception {
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    String sampleXml = SampleContentFileUtil.getFileContent("sample.xml");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    AlertAppException thrown =
        assertThrows(
            AlertAppException.class,
            () -> function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context));

    assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));
    verify(aiTelemetryClientMock).createCustomEvent(any(), anyString(), any());
  }

  @Test
  void testFDR1BigBlobTriggerProcessingCheckAllDates() throws Exception {
    String sampleXml = SampleContentFileUtil.getFileContent("big_sample.xml");

    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    FlussoRendicontazione flussoRendicontazione =
        new FDR1XmlStAXParser()
            .parseXmlStream(new ByteArrayInputStream(sampleXml.getBytes(StandardCharsets.UTF_8)));
    flussoRendicontazione.setMetadata(metadata);

    // the maximum number of dates is forced to 10 for the test
    FlussoRendicontazioneMapper.setMaxDistinctDates(10);

    // The original flow is modified to have more than 10 distinct dates
    Random random = new Random();
    flussoRendicontazione
        .getFlussoRiversamento()
        .getDatiSingoliPagamenti()
        .forEach(
            dsp -> {
              int dayOfMonth = random.nextInt(28) + 1;
              dsp.setDataEsitoSingoloPagamento(LocalDate.of(2025, 2, dayOfMonth).toString());
            });

    FlowTxEventModel flowEvent =
        FlussoRendicontazioneMapper.toFlowTxEventList(flussoRendicontazione);

    assertNotNull(flowEvent);
    // it is verified that the distinct on the dates has determined the
    // presence of 10 dates plus the fake one
    assertEquals(11, flowEvent.getAllDates().size());
  }

  @Test
  void testFDR3BlobTriggerProcessing() throws Exception {
    String sampleJson = SampleContentFileUtil.getFileContent("sample.json");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleJson);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    try (MockedStatic<CommonUtil> mockedUtil =
        mockStatic(CommonUtil.class, Mockito.CALLS_REAL_METHODS)) {
      mockedUtil
          .when(
              () ->
                  CommonUtil.processJsonBlobAndSendToEventHub(
                      any(), any(), any(), any(), anyBoolean(), anyBoolean()))
          .thenReturn(true);

      assertDoesNotThrow(
          () -> function.processFDR3BlobFiles(compressedData, "sampleBlob", metadata, context));

      verify(aiTelemetryClientMock, never()).createCustomEvent(any(), anyString(), any());
    }
  }

  @Test
  void testFDR3BlobTriggerProcessingError() throws Exception {
    String sampleJson = SampleContentFileUtil.getFileContent("sample.json");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleJson);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    AlertAppException thrown =
        assertThrows(
            AlertAppException.class,
            () -> function.processFDR3BlobFiles(compressedData, "sampleBlob", metadata, context));

    assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));
    verify(aiTelemetryClientMock).createCustomEvent(any(), anyString(), any());
  }

  @Test
  void testConstructorInitializesClients() {
    try (MockedStatic<CommonUtil> mockedCommonUtil = Mockito.mockStatic(CommonUtil.class)) {

      // Simulate environment variables
      environmentVariables.set("EVENT_HUB_FLOWTX_CONNECTION_STRING", "fake-flowtx-conn-string");
      environmentVariables.set("EVENT_HUB_FLOWTX_NAME", "fake-flowtx-name");
      environmentVariables.set(
          "EVENT_HUB_REPORTEDIUV_CONNECTION_STRING", "fake-reportediuv-conn-string");
      environmentVariables.set("EVENT_HUB_REPORTEDIUV_NAME", "fake-reportediuv-name");
      environmentVariables.set(
          "APPLICATIONINSIGHTS_CONNECTION_STRING",
          "InstrumentationKey=key;IngestionEndpoint=http://localhost:5000/;LiveEndpoint=http://localhost:5000/");

      EventHubProducerClient mockClient1 = mock(EventHubProducerClient.class);
      EventHubProducerClient mockClient2 = mock(EventHubProducerClient.class);
      mockedCommonUtil
          .when(
              () -> CommonUtil.createEventHubClient("fake-flowtx-conn-string", "fake-flowtx-name"))
          .thenReturn(mockClient1);
      mockedCommonUtil
          .when(
              () ->
                  CommonUtil.createEventHubClient(
                      "fake-reportediuv-conn-string", "fake-reportediuv-name"))
          .thenReturn(mockClient2);

      // Instantiate the class
      BlobProcessingFunction blobProcessingFunction = new BlobProcessingFunction();

      assertNotNull(blobProcessingFunction.getEventHubClientFlowTx());
      assertNotNull(blobProcessingFunction.getEventHubClientReportedIUV());
      assertEquals(mockClient1, blobProcessingFunction.getEventHubClientFlowTx());
      assertEquals(mockClient2, blobProcessingFunction.getEventHubClientReportedIUV());
    }
  }
}
