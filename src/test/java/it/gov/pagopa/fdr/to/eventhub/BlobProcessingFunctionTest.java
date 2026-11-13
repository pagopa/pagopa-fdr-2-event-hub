package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.specialized.BlobInputStream;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.RetryContext;
import it.gov.pagopa.fdr.to.eventhub.client.AppInsightTelemetryClient;
import it.gov.pagopa.fdr.to.eventhub.exception.AlertAppException;
import it.gov.pagopa.fdr.to.eventhub.exception.EventHubException;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.parser.FDR1XmlStAXParser;
import it.gov.pagopa.fdr.to.eventhub.util.CommonUtil;
import it.gov.pagopa.fdr.to.eventhub.util.ErrorCodes;
import it.gov.pagopa.fdr.to.eventhub.util.SampleContentFileUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.internal.Pair;
import org.slf4j.Logger;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith({MockitoExtension.class, SystemStubsExtension.class})
class BlobProcessingFunctionTest {

  @SystemStub private final EnvironmentVariables environmentVariables = new EnvironmentVariables();
  @Captor ArgumentCaptor<String> logCaptor;
  @Mock private EventHubProducerClient eventHubClientFlowTx;
  @Mock private EventHubProducerClient eventHubClientReportedIUV;
  @Mock private ExecutionContext executionContext;
  @Mock private RetryContext retryContext;
  @Mock private FDR1XmlStAXParser mockFDR1XmlParser;
  @Mock private AppInsightTelemetryClient aiTelemetryClientMock;

  private BlobProcessingFunction function;

  /** Simula la lettura streaming del Blob senza caricare dati tramite il binding della Function. */
  private BlobClient mockBlob(byte[] content) throws IOException {
    BlobClient blobClient = mock(BlobClient.class);
    BlobInputStream blobStream = mock(BlobInputStream.class);
    ByteArrayInputStream delegate = new ByteArrayInputStream(content);

    when(blobClient.openInputStream()).thenReturn(blobStream);
    lenient().doAnswer(invocation -> {
      byte[] buffer = invocation.getArgument(0);
      int offset = invocation.getArgument(1);
      int length = invocation.getArgument(2);
      return delegate.read(buffer, offset, length);
    }).when(blobStream).read(any(byte[].class), anyInt(), anyInt());
    lenient().doAnswer(invocation -> delegate.read()).when(blobStream).read();
    lenient().doAnswer(invocation -> {
      delegate.close();
      return null;
    }).when(blobStream).close();
    return blobClient;
  }

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
    byte[] compressedData = SampleContentFileUtil.gzipCompress(sampleXml);

    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      when(mockFlusso.getIdentificativoFlusso()).thenReturn("1234");
      when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            return new CommonUtil.Pair<>(in, true);
          });
      mockedUtil.when(() -> CommonUtil.processXmlBlobAndSendToEventHub(
          any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

      assertDoesNotThrow(() ->
          function.processFDR1BlobFiles(
              mockBlob(compressedData), "sampleBlob", metadata, executionContext)
      );
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

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class, Mockito.CALLS_REAL_METHODS)) {
      when(mockFlusso.getIdentificativoFlusso()).thenReturn("1234");
      mockedUtil.when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            return new CommonUtil.Pair<>(in, true);
          });
      mockedUtil.when(() -> CommonUtil.processXmlBlobAndSendToEventHub(
          any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

      assertDoesNotThrow(
          () ->
              function.processFDR1BlobFiles(
                  mockBlob(compressedData), "sampleBlob", metadata, executionContext));
    }
  }

//  unuseful test
//  @Test
//  void testFDR1ProcessBlobWithNullData() {
//    Map<String, String> metadata = new HashMap<>();
//    metadata.put("sessionId", "1234");
//    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
//    metadata.put("elaborate", "true");
//    assertThrows(
//        IllegalArgumentException.class,
//        () -> function.processFDR1BlobFiles(null, "sampleBlob", metadata, executionContext));
//  }

  @Test
  void testFDR1ProcessBlobWithInvalidGzipData() throws IOException{
    doReturn(retryContext).when(executionContext).getRetryContext();
    doReturn(1).when(retryContext).getMaxretrycount();
    byte[] invalidData = "invalidData".getBytes(StandardCharsets.UTF_8);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            return new CommonUtil.Pair<>(in, true);
          });
      mockedUtil.when(() -> CommonUtil.processXmlBlobAndSendToEventHub(
          any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

      AlertAppException thrown =
          assertThrows(
              AlertAppException.class,
              () ->
                  function.processFDR1BlobFiles(
                      mockBlob(invalidData), "sampleBlob", metadata, executionContext));

      assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));

      verify(aiTelemetryClientMock).createCustomEventForAlert(any(), anyString(), any());
    }

  }

  @Test
  void testFDR1ProcessBlobWithEmptyXml() throws Exception {
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    doReturn(retryContext).when(executionContext).getRetryContext();
    doReturn(1).when(retryContext).getMaxretrycount();
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    byte[] compressedData = SampleContentFileUtil.gzipCompress("");

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            return new CommonUtil.Pair<>(in, true);
          });
      mockedUtil.when(() -> CommonUtil.processXmlBlobAndSendToEventHub(
          any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

      AlertAppException thrown =
          assertThrows(
              AlertAppException.class,
              () ->
                  function.processFDR1BlobFiles(
                      mockBlob(compressedData), "sampleBlob", metadata, executionContext));

      assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));

      verify(eventHubClientFlowTx, never()).send(any(ArrayList.class));
      verify(eventHubClientReportedIUV, never()).send(any(ArrayList.class));
      verify(aiTelemetryClientMock).createCustomEventForAlert(any(), anyString(), any());
    }
  }

  @Test
  void testFDR1ProcessBlobWithMalformedXml() throws Exception {
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    doReturn(retryContext).when(executionContext).getRetryContext();
    doReturn(1).when(retryContext).getMaxretrycount();
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    byte[] compressedData = SampleContentFileUtil.createGzipCompressedData("<xml>malformed</xml>");

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            return new CommonUtil.Pair<>(in, true);
          });
      mockedUtil.when(() -> CommonUtil.processXmlBlobAndSendToEventHub(
          any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

      AlertAppException thrown =
          assertThrows(
              AlertAppException.class,
              () ->
                  function.processFDR1BlobFiles(
                      mockBlob(compressedData), "sampleBlob", metadata, executionContext));

      assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));

      verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
      verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
      verify(aiTelemetryClientMock).createCustomEventForAlert(any(), anyString(), any());
    }
  }

  @Test
  void testFDR1ValidateBlobMetadata_NullMetadata() {
    assertDoesNotThrow(
        () -> function.processFDR1BlobFiles(mock(BlobClient.class), "testBlob", null, executionContext));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
    verify(aiTelemetryClientMock, never()).createCustomEventForAlert(any(), anyString(), any());
  }

  @Test
  void testFDR1ValidateBlobMetadata_EmptyMetadata() {
    Map<String, String> emptyMetadata = new HashMap<>();
    assertDoesNotThrow(
        () ->
            function.processFDR1BlobFiles(
                mock(BlobClient.class), "testBlob", emptyMetadata, executionContext));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
    verify(aiTelemetryClientMock, never()).createCustomEventForAlert(any(), anyString(), any());
  }

  @Test
  void testFDR1ValidateBlobMetadata_MissingKeys() {
    Map<String, String> invalidMetadata = new HashMap<>();
    invalidMetadata.put("sessionId", "1234");
    // "insertedTimestamp" key is missing

    assertDoesNotThrow(
        () ->
            function.processFDR1BlobFiles(
                mock(BlobClient.class), "testBlob", invalidMetadata, executionContext));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
    verify(aiTelemetryClientMock, never()).createCustomEventForAlert(any(), anyString(), any());
  }

  @Test
  void testFDR1ValidateBlobMetadata_ElaborateFalse() {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "false");

    assertDoesNotThrow(
        () -> function.processFDR1BlobFiles(mock(BlobClient.class), "testBlob", metadata, executionContext));

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));
    verify(aiTelemetryClientMock, never()).createCustomEventForAlert(any(), anyString(), any());
  }

  @Test
  void testFDR1BlobTriggerProcessingError() throws Exception {
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    doReturn(retryContext).when(executionContext).getRetryContext();
    doReturn(1).when(retryContext).getMaxretrycount();
    String sampleXml = SampleContentFileUtil.getFileContent("sample.xml");
    byte[] compressedData = SampleContentFileUtil.gzipCompress(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            return new CommonUtil.Pair<>(in, true);
          });
      mockedUtil.when(() -> CommonUtil.processXmlBlobAndSendToEventHub(
          any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

      AlertAppException thrown =
          assertThrows(
              AlertAppException.class,
              () ->
                  function.processFDR1BlobFiles(
                      mockBlob(compressedData), "sampleBlob", metadata, executionContext));

      assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));
      verify(aiTelemetryClientMock).createCustomEventForAlert(any(), anyString(), any());
    }
  }

  @Test
  void testFDR1BlobTriggerProcessingErrorNotLastRetry() throws Exception {
    FlussoRendicontazione mockFlusso = mock(FlussoRendicontazione.class);
    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class))).thenReturn(mockFlusso);
    doReturn(retryContext).when(executionContext).getRetryContext();
    doReturn(5).when(retryContext).getMaxretrycount();
    String sampleXml = SampleContentFileUtil.getFileContent("sample.xml");
    byte[] compressedData = SampleContentFileUtil.gzipCompress(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class)) {
      mockedUtil.when(() -> CommonUtil.validateBlobMetadata(any())).thenReturn(true);
      mockedUtil.when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            return new CommonUtil.Pair<>(in, true);
          });
      mockedUtil.when(() -> CommonUtil.processXmlBlobAndSendToEventHub(
          any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

      AlertAppException thrown =
          assertThrows(
              AlertAppException.class,
              () ->
                  function.processFDR1BlobFiles(
                      mockBlob(compressedData), "sampleBlob", metadata, executionContext));

      assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));
      verify(aiTelemetryClientMock, never()).createCustomEventForAlert(any(), anyString(), any());
    }
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
    byte[] compressedData = SampleContentFileUtil.gzipCompress(sampleJson);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class, Mockito.CALLS_REAL_METHODS)) {
      mockedUtil.when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            return new CommonUtil.Pair<>(in, true);
          });

      mockedUtil.when(() -> CommonUtil.processJsonBlobAndSendToEventHub(any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

      assertDoesNotThrow(
          () ->
              function.processFDR3BlobFiles(
                  mockBlob(compressedData), "sampleBlob", metadata, executionContext));
      verify(aiTelemetryClientMock, never()).createCustomEventForAlert(any(), anyString(), any());
    }
  }

  @Test
  void testFDR3BlobTriggerProcessingError() throws Exception {
    byte[] compressedData = SampleContentFileUtil.gzipCompress("");
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class, Mockito.CALLS_REAL_METHODS)) {
      mockedUtil.when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            return new CommonUtil.Pair<>(in, true);
          });

      mockedUtil.when(() -> CommonUtil.processJsonBlobAndSendToEventHub(any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

      doReturn(retryContext).when(executionContext).getRetryContext();
      doReturn(1).when(retryContext).getMaxretrycount();

      AlertAppException thrown =
          assertThrows(
              AlertAppException.class,
              () ->
                  function.processFDR3BlobFiles(
                      mockBlob(compressedData), "sampleBlob", metadata, executionContext));

      assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));
      verify(aiTelemetryClientMock).createCustomEventForAlert(any(), anyString(), any());
    }
  }

  @Test
  void testFDR3BlobTriggerProcessingErrorNotLastRetry() throws Exception {
    byte[] compressedData = SampleContentFileUtil.gzipCompress("");
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    try (MockedStatic<CommonUtil> mockedUtil = mockStatic(CommonUtil.class, Mockito.CALLS_REAL_METHODS)) {
      mockedUtil.when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            return new CommonUtil.Pair<>(in, true);
          });

      mockedUtil.when(() -> CommonUtil.processJsonBlobAndSendToEventHub(any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

      doReturn(retryContext).when(executionContext).getRetryContext();
      doReturn(5).when(retryContext).getMaxretrycount();

      AlertAppException thrown =
          assertThrows(
              AlertAppException.class,
              () ->
                  function.processFDR3BlobFiles(
                      mockBlob(compressedData), "sampleBlob", metadata, executionContext));

      assertTrue(thrown.toString().contains("[ALERT][Fdr2EventHub]"));
      verify(aiTelemetryClientMock, never()).createCustomEventForAlert(any(), anyString(), any());
    }
  }

  @Test
  void testConstructorInitializesAndReusesClients() {

    environmentVariables.set(
        "FDR_SA_CONNECTION_STRING",
        "fake-fdr-sa-conn-string");
    environmentVariables.set(
        "EVENT_HUB_FLOWTX_CONNECTION_STRING",
        "fake-flowtx-conn-string");
    environmentVariables.set(
        "EVENT_HUB_FLOWTX_NAME",
        "fake-flowtx-name");
    environmentVariables.set(
        "EVENT_HUB_REPORTEDIUV_CONNECTION_STRING",
        "fake-reportediuv-conn-string");
    environmentVariables.set(
        "EVENT_HUB_REPORTEDIUV_NAME",
        "fake-reportediuv-name");
    environmentVariables.set(
        "APPLICATIONINSIGHTS_CONNECTION_STRING",
        "InstrumentationKey=key;IngestionEndpoint=http://localhost:5000/;LiveEndpoint=http://localhost:5000/");

    try (MockedStatic<CommonUtil> mockedCommonUtil =
        Mockito.mockStatic(CommonUtil.class)) {

      EventHubProducerClient flowTxClient =
          mock(EventHubProducerClient.class);

      EventHubProducerClient reportedIuvClient =
          mock(EventHubProducerClient.class);

      BlobServiceClient blobServiceClient =
          mock(BlobServiceClient.class);

      mockedCommonUtil
          .when(() ->
              CommonUtil.createEventHubClient(
                  "fake-flowtx-conn-string",
                  "fake-flowtx-name"))
          .thenReturn(flowTxClient);

      mockedCommonUtil
          .when(() ->
              CommonUtil.createEventHubClient(
                  "fake-reportediuv-conn-string",
                  "fake-reportediuv-name"))
          .thenReturn(reportedIuvClient);


      // Prima istanza: inizializza ClientsHolder
      BlobProcessingFunction first =
          new BlobProcessingFunction();

      // Seconda istanza: deve riutilizzare ClientsHolder
      BlobProcessingFunction second =
          new BlobProcessingFunction();

      // I client sono stati inizializzati correttamente
      assertSame(
          flowTxClient,
          first.getEventHubClientFlowTx());

      assertSame(
          reportedIuvClient,
          first.getEventHubClientReportedIUV());


      // Le due Function condividono gli stessi client
      assertSame(
          first.getEventHubClientFlowTx(),
          second.getEventHubClientFlowTx());

      assertSame(
          first.getEventHubClientReportedIUV(),
          second.getEventHubClientReportedIUV());


      // Le factory devono essere chiamate UNA SOLA VOLTA
      mockedCommonUtil.verify(
          () ->
              CommonUtil.createEventHubClient(
                  "fake-flowtx-conn-string",
                  "fake-flowtx-name"),
          times(1));

      mockedCommonUtil.verify(
          () ->
              CommonUtil.createEventHubClient(
                  "fake-reportediuv-conn-string",
                  "fake-reportediuv-name"),
          times(1));
    }
  }

  @Test
  void testFDR1OutOfMemoryErrorOnEventHubCreateBatchShouldSendAlert() throws Exception {

    String sampleXml = SampleContentFileUtil.getFileContent("sample.xml");
    byte[] compressedData = SampleContentFileUtil.gzipCompress(sampleXml);

    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    function =
        new BlobProcessingFunction(
            eventHubClientFlowTx,
            eventHubClientReportedIUV,
            aiTelemetryClientMock,
            new FDR1XmlStAXParser());

    when(executionContext.getRetryContext()).thenReturn(null);

    when(eventHubClientFlowTx.createBatch())
        .thenThrow(
            new OutOfMemoryError(
                "unable to create native thread: "
                    + "possibly out of memory or process/resource limits reached"));

    try (MockedStatic<CommonUtil> mockedUtil =
        mockStatic(CommonUtil.class, Mockito.CALLS_REAL_METHODS)) {

      mockedUtil
          .when(() -> CommonUtil.isGzipStream(any()))
          .thenAnswer(
              invocation -> {
                InputStream in = invocation.getArgument(0);
                return new CommonUtil.Pair<>(in, true);
              });

      OutOfMemoryError thrown =
          assertThrows(
              OutOfMemoryError.class,
              () ->
                  function.processFDR1BlobFiles(
                      mockBlob(compressedData),
                      "sampleBlob",
                      metadata,
                      executionContext));

      assertNotNull(thrown);

      assertEquals(
          "unable to create native thread: "
              + "possibly out of memory or process/resource limits reached",
          thrown.getMessage());

      verify(aiTelemetryClientMock, never())
          .createCustomEventForAlert(any(), anyString(), any());

      verify(eventHubClientFlowTx, atLeastOnce())
          .createBatch();
    }
  }

  private byte[] gzip(String content) throws IOException {
    ByteArrayOutputStream byteArrayOutputStream =
        new ByteArrayOutputStream();

    try (GZIPOutputStream gzipOutputStream =
        new GZIPOutputStream(byteArrayOutputStream)) {

      gzipOutputStream.write(
          content.getBytes(StandardCharsets.UTF_8));
    }

    return byteArrayOutputStream.toByteArray();
  }


  @Test
  void testFDR1EventHubNotConfirmed() throws Exception {

    ExecutionContext context = mock(ExecutionContext.class);

    Map<String, String> validMetadata = new HashMap<>();
    validMetadata.put("sessionId", "test-session-123");
    validMetadata.put("insertedTimestamp", "2026-11-10T10:00:00");
    validMetadata.put("elaborate", "true");

    byte[] validGzip = gzip("<xml/>");

    BlobClient blobClient = mock(BlobClient.class);
    BlobInputStream blobInputStream = mock(BlobInputStream.class);

    ByteArrayInputStream inputStream =
        new ByteArrayInputStream(validGzip);

    when(blobClient.openInputStream())
        .thenReturn(blobInputStream);

    doAnswer(invocation -> inputStream.read())
        .when(blobInputStream)
        .read();

    doAnswer(invocation -> {
      byte[] buffer = invocation.getArgument(0);
      int offset = invocation.getArgument(1);
      int length = invocation.getArgument(2);

      return inputStream.read(buffer, offset, length);
    }).when(blobInputStream).read(
        any(byte[].class),
        anyInt(),
        anyInt());

    FlussoRendicontazione flusso =
        mock(FlussoRendicontazione.class);

    when(flusso.getIdentificativoFlusso())
        .thenReturn("FLOW-123");

    when(mockFDR1XmlParser.parseXmlStream(any(InputStream.class)))
        .thenReturn(flusso);

    try (MockedStatic<CommonUtil> mocked =
        mockStatic(CommonUtil.class, CALLS_REAL_METHODS)) {

      mocked.when(() ->
              CommonUtil.processXmlBlobAndSendToEventHub(
                  any(),
                  any(),
                  eq(flusso),
                  any(),
                  eq(true),
                  eq(true)))
          .thenReturn(false);

      AlertAppException thrown = assertThrows(
          AlertAppException.class,
          () -> function.processFDR1BlobFiles(
              blobClient,
              "sampleBlob",
              validMetadata,
              context));

      Throwable cause = thrown.getCause();

      assertNotNull(cause);
      assertTrue(
          cause instanceof EventHubException,
          "Expected EventHubException but got: "
              + cause.getClass().getName()
              + " - " + cause.getMessage());
      assertTrue(
          cause.getMessage().contains("FLOW-123"));

      mocked.verify(() ->
              CommonUtil.processXmlBlobAndSendToEventHub(
                  any(),
                  any(),
                  eq(flusso),
                  any(),
                  eq(true),
                  eq(true)),
          times(1));

      verify(flusso, times(1)).releaseResources();
    }
  }

  @Test
  void testFDR1InvalidGzipCreatesAlert()  throws IOException{

    ExecutionContext context = mock(ExecutionContext.class);

    Map<String, String> validMetadata = new HashMap<>();
    validMetadata.put("sessionId", "test-session-123");
    validMetadata.put("insertedTimestamp", "2026-11-10T10:00:00");
    validMetadata.put("elaborate", "true");

    byte[] invalidData = "NOT_A_GZIP_FILE".getBytes(StandardCharsets.UTF_8);

    BlobClient blobClient = mock(BlobClient.class);
    BlobInputStream blobInputStream = mock(BlobInputStream.class);

    ByteArrayInputStream inputStream =
        new ByteArrayInputStream(invalidData);

    when(blobClient.openInputStream())
        .thenReturn(blobInputStream);


    when(blobInputStream.read(
        any(byte[].class), anyInt(), anyInt()))
        .thenAnswer(invocation -> {
          byte[] buffer = invocation.getArgument(0);
          int offset = invocation.getArgument(1);
          int length = invocation.getArgument(2);

          return inputStream.read(buffer, offset, length);
        });

    assertDoesNotThrow(() ->
        function.processFDR1BlobFiles(
            blobClient,
            "sampleBlob",
            validMetadata,
            context));

    verify(aiTelemetryClientMock, times(1))
        .createCustomEventForAlert(
            eq(ErrorCodes.FDR1_E1_1),
            anyString(),
            isNull());

    verifyNoInteractions(mockFDR1XmlParser);
  }

  @ParameterizedTest
  @MethodSource("invalidMetadataProvider")
  void testFDR1InvalidMetadataSkipsProcessing(
      Map<String, String> metadata) {

    ExecutionContext context = mock(ExecutionContext.class);
    BlobClient blobClient = mock(BlobClient.class);

    assertDoesNotThrow(() ->
        function.processFDR1BlobFiles(
            blobClient,
            "sampleBlob",
            metadata,
            context));

    verify(blobClient, never()).openInputStream();
    verifyNoInteractions(mockFDR1XmlParser);
    verifyNoInteractions(aiTelemetryClientMock);
  }

  @ParameterizedTest
  @MethodSource("invalidMetadataProvider")
  void testFDR3InvalidMetadataSkipsProcessing(
      Map<String, String> metadata) {

    ExecutionContext context = mock(ExecutionContext.class);
    BlobClient blobClient = mock(BlobClient.class);

    assertDoesNotThrow(() ->
        function.processFDR3BlobFiles(
            blobClient,
            "sampleBlob",
            metadata,
            context
        )
    );

    verify(blobClient, never()).openInputStream();
    verifyNoInteractions(aiTelemetryClientMock);
  }

  private static Stream<Arguments> invalidMetadataProvider() {
    return Stream.of(
        Arguments.of((Map<String, String>) null),
        Arguments.of(Collections.<String, String>emptyMap()),
        Arguments.of(Map.of(
            "insertedTimestamp", "2026-11-10T10:00:00",
            "elaborate", "true"
        )),
        Arguments.of(Map.of(
            "sessionId", "test-session-123",
            "elaborate", "true"
        )),
        Arguments.of(Map.of(
            "sessionId", "test-session-123",
            "insertedTimestamp", "2026-11-10T10:00:00",
            "elaborate", "false"
        ))
    );
  }
}