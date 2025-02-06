package it.gov.pagopa.fdr.to.eventhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.microsoft.azure.functions.ExecutionContext;
import it.gov.pagopa.fdr.to.eventhub.util.SampleContentFileUtil;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlobProcessingFunctionTest {

  @Mock private EventHubProducerClient eventHubClientFlowTx;

  @Mock private EventHubProducerClient eventHubClientReportedIUV;

  @Mock private ExecutionContext context;

  @Mock private Logger mockLogger;

  private BlobProcessingFunction function;

  @BeforeEach
  public void setup() {
    function = new BlobProcessingFunction(eventHubClientFlowTx, eventHubClientReportedIUV);
    lenient().when(eventHubClientFlowTx.createBatch()).thenReturn(mock(EventDataBatch.class));
    lenient().when(eventHubClientReportedIUV.createBatch()).thenReturn(mock(EventDataBatch.class));
  }

  private byte[] createGzipCompressedData(String input) throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
      gzipOutputStream.write(input.getBytes(StandardCharsets.UTF_8));
    }
    return byteArrayOutputStream.toByteArray();
  }

  @Test
  void testBlobTriggerProcessing() throws Exception {
    when(context.getLogger()).thenReturn(mockLogger);
    String sampleXml = SampleContentFileUtil.getSampleXml("sample.xml");
    byte[] compressedData = createGzipCompressedData(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);

    verify(eventHubClientFlowTx, atLeastOnce()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, atLeastOnce()).send(any(EventDataBatch.class));
  }

  @Test
  void testBigBlobTriggerProcessing() throws Exception {
    when(context.getLogger()).thenReturn(mockLogger);
    String sampleXml = SampleContentFileUtil.getSampleXml("big_sample.xml");
    byte[] compressedData = createGzipCompressedData(sampleXml);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");

    function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);

    verify(eventHubClientFlowTx, atLeastOnce()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, atLeastOnce()).send(any(EventDataBatch.class));
  }

  @Test
  void testProcessBlobWithNullData() {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    assertThrows(
        IllegalArgumentException.class,
        () -> function.processFDR1BlobFiles(null, "sampleBlob", metadata, context));
  }

  @Test
  void testProcessBlobWithInvalidGzipData() {
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
  void testProcessBlobWithEmptyXml() throws Exception {
    when(context.getLogger()).thenReturn(mockLogger);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    byte[] compressedData = createGzipCompressedData("");
    function.processFDR1BlobFiles(compressedData, "sampleBlob", metadata, context);

    verify(eventHubClientFlowTx, never()).send(any(ArrayList.class));
    verify(eventHubClientReportedIUV, never()).send(any(ArrayList.class));
    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).severe(logCaptor.capture());
  }

  @Test
  void testProcessBlobWithMalformedXml() throws Exception {
    when(context.getLogger()).thenReturn(mockLogger);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "true");
    byte[] compressedData = createGzipCompressedData("<xml>malformed</xml>");
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
  void testValidateBlobMetadata_NullMetadata() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> function.processFDR1BlobFiles(new byte[] {}, "testBlob", null, context));

    assertEquals(
        "Invalid blob metadata: sessionId or insertedTimestamp is missing.",
        exception.getMessage());
  }

  @Test
  void testValidateBlobMetadata_EmptyMetadata() {
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
  void testValidateBlobMetadata_MissingKeys() {
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
  void testValidateBlobMetadata_ElaborateFalse() {
    when(context.getLogger()).thenReturn(mockLogger);
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", "1234");
    metadata.put("insertedTimestamp", "2025-01-30T10:15:30");
    metadata.put("elaborate", "false");

    function.processFDR1BlobFiles(new byte[] {}, "testBlob", metadata, context);

    verify(eventHubClientFlowTx, never()).send(any(EventDataBatch.class));
    verify(eventHubClientReportedIUV, never()).send(any(EventDataBatch.class));

    ArgumentCaptor<Supplier<String>> logCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(mockLogger, atLeastOnce()).info(logCaptor.capture());

    boolean logContainsExpectedMessage =
        logCaptor.getAllValues().stream()
            .map(Supplier::get)
            .anyMatch(log -> log.contains("Skipping processing for Blob container"));
    assert logContainsExpectedMessage
        : "The log does not contain the expected message for 'elaborate' false";
  }
}
