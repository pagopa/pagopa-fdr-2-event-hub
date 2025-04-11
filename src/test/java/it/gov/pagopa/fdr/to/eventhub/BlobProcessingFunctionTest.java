package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.microsoft.azure.functions.ExecutionContext;
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
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
  @Mock private EventHubProducerClient eventHubClientFlowTx;
  @Mock private EventHubProducerClient eventHubClientReportedIUV;
  @Mock private ExecutionContext context;
  @Mock private Logger mockLogger;
  @Mock private FDR1XmlStAXParser mockFDR1XmlParser;
  private BlobProcessingFunction function;

  @BeforeEach
  void setup() {
    function =
        new BlobProcessingFunction(
            eventHubClientFlowTx, eventHubClientReportedIUV, mockFDR1XmlParser);
    lenient().when(eventHubClientFlowTx.createBatch()).thenReturn(mock(EventDataBatch.class));
    lenient().when(eventHubClientReportedIUV.createBatch()).thenReturn(mock(EventDataBatch.class));
  }

  @Test
  void testFDR1BlobTriggerProcessing() throws Exception {
    when(context.getLogger()).thenReturn(mockLogger);
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

      function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);

      ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
      verify(mockLogger, atLeastOnce()).fine(logCaptor.capture());

      boolean logContainsExpectedMessage =
          logCaptor.getAllValues().stream()
              .map(Supplier::get)
              .anyMatch(log -> log.contains("[FDR1] Execution Finished"));
      assert logContainsExpectedMessage
          : "The log does not contain the expected message for execution finished";
    }
  }

  @Test
  void testFDR1BigBlobTriggerProcessing() throws Exception {
    when(context.getLogger()).thenReturn(mockLogger);
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

      function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);

      ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
      verify(mockLogger, atLeastOnce()).fine(logCaptor.capture());

      boolean logContainsExpectedMessage =
          logCaptor.getAllValues().stream()
              .map(Supplier::get)
              .anyMatch(log -> log.contains("[FDR1] Execution Finished"));
      assert logContainsExpectedMessage
          : "The log does not contain the expected message for execution finished";
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
    when(context.getLogger()).thenReturn(mockLogger);
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    String invalidData = "invalidData";
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    Exception thrown =
        assertThrows(
            Exception.class,
            () ->
                function.processFDR1BlobFiles(
                    invalidData.getBytes(StandardCharsets.UTF_8), "sampleBlob", metadata, context));

    assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));

    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).severe(logCaptor.capture());
  }

  @Test
  void testFDR1ProcessBlobWithEmptyXml() throws Exception {
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    when(context.getLogger()).thenReturn(mockLogger);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData("");

    Exception thrown =
        assertThrows(
            Exception.class,
            () -> function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context));

    assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));

    verify(eventHubClientFlowTx, never()).send(any(ArrayList.class));
    verify(eventHubClientReportedIUV, never()).send(any(ArrayList.class));
    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).severe(logCaptor.capture());
  }

  @Test
  void testFDR1ProcessBlobWithMalformedXml() throws Exception {
    when(context.getLogger()).thenReturn(mockLogger);
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData("<xml>malformed</xml>");
    Exception thrown =
        assertThrows(
            Exception.class,
            () -> function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context));

    assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));

    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).severe(logCaptor.capture());
  }

  @Test
  void testFDR1ValidateBlobMetadata_NullMetadata() {
    when(context.getLogger()).thenReturn(mockLogger);
    function.processFDR1BlobFiles(new byte[] {}, "testBlob", null, context);
    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).warning(logCaptor.capture());
  }

  @Test
  void testFDR1ValidateBlobMetadata_EmptyMetadata() {
    when(context.getLogger()).thenReturn(mockLogger);
    Map<String, String> emptyMetadata = new HashMap<>();
    function.processFDR1BlobFiles(new byte[] {}, "testBlob", emptyMetadata, context);
    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).warning(logCaptor.capture());
  }

  @Test
  void testFDR1ValidateBlobMetadata_MissingKeys() {
    when(context.getLogger()).thenReturn(mockLogger);
    Map<String, String> invalidMetadata = new HashMap<>();
    invalidMetadata.put("sessionId", "1234");
    // "insertedTimestamp" key is missing

    function.processFDR1BlobFiles(new byte[] {}, "testBlob", invalidMetadata, context);
    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).warning(logCaptor.capture());
  }

  @Test
  void testFDR1ValidateBlobMetadata_ElaborateFalse() {
    when(context.getLogger()).thenReturn(mockLogger);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "false");

    function.processFDR1BlobFiles(new byte[] {}, "testBlob", metadata, context);

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));

    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).warning(logCaptor.capture());

    boolean logContainsExpectedMessage =
        logCaptor.getAllValues().stream()
            .map(Supplier::get)
            .anyMatch(log -> log.contains("Skipping processing for Blob container"));
    assert logContainsExpectedMessage
        : "The log does not contain the expected message for 'elaborate' false";
  }

  @Test
  void testFDR1BlobTriggerProcessingError() throws Exception {
    when(context.getLogger()).thenReturn(mockLogger);
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    String sampleXml = SampleContentFileUtil.getFileContent("sample.xml");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    Exception thrown =
        assertThrows(
            Exception.class,
            () -> function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context));

    assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));
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
    when(context.getLogger()).thenReturn(mockLogger);
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

      function.processFDR3BlobFiles(compressedData, "sampleBlob", metadata, context);

      ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
      verify(mockLogger, atLeastOnce()).fine(logCaptor.capture());

      boolean logContainsExpectedMessage =
          logCaptor.getAllValues().stream()
              .map(Supplier::get)
              .anyMatch(log -> log.contains("[FDR3] Execution Finished"));
      assert logContainsExpectedMessage
          : "The log does not contain the expected message for execution finished";
    }
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
