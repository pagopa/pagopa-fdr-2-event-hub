package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.amqp.exception.AmqpErrorContext;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.microsoft.azure.functions.ExecutionContext;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlSAXParser;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith({MockitoExtension.class, SystemStubsExtension.class})
class BlobProcessingFunctionTest {

  @Mock private EventHubProducerClient eventHubClientFlowTx;

  @Mock private EventHubProducerClient eventHubClientReportedIUV;

  @Mock private ExecutionContext context;

  @Mock private Logger mockLogger;

  @SystemStub private EnvironmentVariables environmentVariables = new EnvironmentVariables();

  private BlobProcessingFunction function;

  @BeforeEach
  void setup() {
    function = new BlobProcessingFunction(eventHubClientFlowTx, eventHubClientReportedIUV);
    lenient().when(eventHubClientFlowTx.createBatch()).thenReturn(mock(EventDataBatch.class));
    lenient().when(eventHubClientReportedIUV.createBatch()).thenReturn(mock(EventDataBatch.class));
  }

  @Test
  void testFDR1BlobTriggerProcessing() throws Exception {
    EventDataBatch mockEventDataBatch = mock(EventDataBatch.class);
    when(context.getLogger()).thenReturn(mockLogger);
    when(eventHubClientFlowTx.createBatch()).thenReturn(mockEventDataBatch);
    when(eventHubClientReportedIUV.createBatch()).thenReturn(mockEventDataBatch);
    when(mockEventDataBatch.tryAdd(any(com.azure.messaging.eventhubs.EventData.class)))
        .thenReturn(Boolean.TRUE);
    String sampleXml = SampleContentFileUtil.getSampleXml("sample.xml");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    FlussoRendicontazione flussoRendicontazione =
        FDR1XmlSAXParser.parseXmlStream(
            new ByteArrayInputStream(sampleXml.getBytes(StandardCharsets.UTF_8)));

    try (MockedStatic<FDR1XmlSAXParser> mockedStatic = mockStatic(FDR1XmlSAXParser.class)) {
      mockedStatic
          .when(() -> FDR1XmlSAXParser.parseXmlStream(any(InputStream.class)))
          .thenReturn(flussoRendicontazione);

      function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);
    }

    verify(eventHubClientFlowTx, atLeastOnce()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, atLeastOnce()).send(any(EventDataBatch.class));

    FlowTxEventModel flowEvent =
        FlussoRendicontazioneMapper.toFlowTxEventList(flussoRendicontazione);

    assertNotNull(flowEvent);
    // it is verified that the distinct on the dates has left the only expected
    // date for all payments
    assertEquals(1, flowEvent.getAllDates().size());

    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atMost(2)).info(logCaptor.capture());
  }

  @Test
  void testFDR1BigBlobTriggerProcessing() throws Exception {
    EventDataBatch mockEventDataBatch = mock(EventDataBatch.class);
    when(context.getLogger()).thenReturn(mockLogger);
    when(eventHubClientFlowTx.createBatch()).thenReturn(mockEventDataBatch);
    when(eventHubClientReportedIUV.createBatch()).thenReturn(mockEventDataBatch);
    when(mockEventDataBatch.tryAdd(any(com.azure.messaging.eventhubs.EventData.class)))
        .thenReturn(Boolean.TRUE);
    String sampleXml = SampleContentFileUtil.getSampleXml("big_sample.xml");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);

    verify(eventHubClientFlowTx, atLeastOnce()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, atLeastOnce()).send(any(EventDataBatch.class));
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
  void testFDR1ProcessBlobWithInvalidGzipData() {
    when(context.getLogger()).thenReturn(mockLogger);
    String invalidData = "invalidData";
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    function.processFDR1BlobFiles(
        invalidData.getBytes(StandardCharsets.UTF_8), "sampleBlob", metadata, context);
    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).severe(logCaptor.capture());
  }

  @Test
  void testFDR1ProcessBlobWithEmptyXml() throws Exception {
    when(context.getLogger()).thenReturn(mockLogger);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData("");
    function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);

    verify(eventHubClientFlowTx, never()).send(any(ArrayList.class));
    verify(eventHubClientReportedIUV, never()).send(any(ArrayList.class));
    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).severe(logCaptor.capture());
  }

  @Test
  void testFDR1ProcessBlobWithMalformedXml() throws Exception {
    when(context.getLogger()).thenReturn(mockLogger);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData("<xml>malformed</xml>");
    function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));

    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).severe(logCaptor.capture());

    boolean logContainsExpectedMessage =
        logCaptor.getAllValues().stream()
            .map(Supplier::get)
            .anyMatch(log -> log.contains("Error processing Blob"));
    assert logContainsExpectedMessage
        : "The log does not contain the expected message for expetion during malformed XML file";
  }

  @Test
  void testFDR1ValidateBlobMetadata_NullMetadata() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> function.processFDR1BlobFiles(new byte[] {}, "testBlob", null, context));

    assertEquals(
        "Invalid blob metadata: sessionId or insertedTimestamp is missing.",
        exception.getMessage());
  }

  @Test
  void testFDR1ValidateBlobMetadata_EmptyMetadata() {
    Map<String, String> emptyMetadata = new HashMap<>();
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> function.processFDR1BlobFiles(new byte[] {}, "testBlob", emptyMetadata, context));

    assertEquals(
        "Invalid blob metadata: sessionId or insertedTimestamp is missing.",
        exception.getMessage());
  }

  @Test
  void testFDR1ValidateBlobMetadata_MissingKeys() {
    Map<String, String> invalidMetadata = new HashMap<>();
    invalidMetadata.put("sessionId", "1234");
    // "insertedTimestamp" key is missing

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                function.processFDR1BlobFiles(new byte[] {}, "testBlob", invalidMetadata, context));

    assertEquals(
        "Invalid blob metadata: sessionId or insertedTimestamp is missing.",
        exception.getMessage());
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
    EventDataBatch mockEventDataBatch = mock(EventDataBatch.class);
    when(context.getLogger()).thenReturn(mockLogger);
    when(eventHubClientFlowTx.createBatch()).thenReturn(mockEventDataBatch);
    // precondition for tryAdd fail
    when(mockEventDataBatch.tryAdd(any(com.azure.messaging.eventhubs.EventData.class)))
        .thenThrow(
            new AmqpException(
                Boolean.TRUE, "Failed to add event data", mock(AmqpErrorContext.class)));
    String sampleXml = SampleContentFileUtil.getSampleXml("sample.xml");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);

    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).severe(logCaptor.capture());

    logCaptor.getAllValues().stream()
        .map(Supplier::get)
        .anyMatch(log -> log.contains("Error processing Blob"));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));

    // precondition for send fail
    when(mockEventDataBatch.tryAdd(any(com.azure.messaging.eventhubs.EventData.class)))
        .thenReturn(Boolean.TRUE);
    doThrow(NullPointerException.class).when(eventHubClientFlowTx).send(any(EventDataBatch.class));

    function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);

    logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).severe(logCaptor.capture());

    logCaptor.getAllValues().stream()
        .map(Supplier::get)
        .anyMatch(log -> log.contains("Error processing Blob"));

    verify(eventHubClientFlowTx, atLeastOnce()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
  }

  @Test
  void testFDR1BigBlobTriggerProcessingCheckAllDates() throws Exception {
    EventDataBatch mockEventDataBatch = mock(EventDataBatch.class);
    when(context.getLogger()).thenReturn(mockLogger);
    when(eventHubClientFlowTx.createBatch()).thenReturn(mockEventDataBatch);
    when(eventHubClientReportedIUV.createBatch()).thenReturn(mockEventDataBatch);
    when(mockEventDataBatch.tryAdd(any(com.azure.messaging.eventhubs.EventData.class)))
        .thenReturn(Boolean.TRUE);
    String sampleXml = SampleContentFileUtil.getSampleXml("big_sample.xml");

    FlussoRendicontazione flussoRendicontazione =
        FDR1XmlSAXParser.parseXmlStream(
            new ByteArrayInputStream(sampleXml.getBytes(StandardCharsets.UTF_8)));

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

    try (MockedStatic<FDR1XmlSAXParser> mockedStatic = mockStatic(FDR1XmlSAXParser.class)) {
      mockedStatic
          .when(() -> FDR1XmlSAXParser.parseXmlStream(any(InputStream.class)))
          .thenReturn(flussoRendicontazione);

      byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleXml);
      Map<String, String> metadata = new HashMap<>();
      metadata.put("sessionId", "1234");
      metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
      metadata.put("elaborate", "true");

      function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);
    }

    verify(eventHubClientFlowTx, atLeastOnce()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, atLeastOnce()).send(any(EventDataBatch.class));

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
    String sampleXml = SampleContentFileUtil.getSampleXml("sample.xml");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    function.processFDR3BlobFiles(compressedData, "sampleBlob", metadata, context);
    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).info(logCaptor.capture());
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
