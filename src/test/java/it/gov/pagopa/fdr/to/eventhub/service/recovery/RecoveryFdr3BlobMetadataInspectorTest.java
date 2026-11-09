package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr3BlobMetadataInspectorTest {

  private static final String SESSION_ID =
      "a92570e7-d5f5-49da-bb8f-0226e2d95ac7";

  private final RecoveryFdr3BlobMetadataInspector inspector =
      new RecoveryFdr3BlobMetadataInspector();

  private Map<String, String> validMetadata() {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("sessionId", SESSION_ID);
    metadata.put("serviceIdentifier", "FDR03");
    metadata.put("elaborate", "true");
    return metadata;
  }

  private RecoveryBlobSnapshot snapshot(
      Map<String, String> metadata) {

    return new RecoveryBlobSnapshot(
        "fdr3-flows",
        "flow_PSP_1.json.zip",
        "\"source-etag\"",
        1234L,
        metadata);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldAcceptBothElaborateValues(boolean elaborate) {
    Map<String, String> metadata = validMetadata();
    metadata.put("elaborate", Boolean.toString(elaborate));

    assertEquals(
        new RecoveryFdr3BlobMetadataInspector.Valid(
            SESSION_ID, elaborate),
        inspector.inspect(snapshot(metadata)));
  }

  @Test
  void shouldReadMetadataNamesIgnoringCase() {
    Map<String, String> metadata =
        Map.of(
            "SESSIONID", SESSION_ID,
            "SERVICEIDENTIFIER", "FDR03",
            "ELABORATE", "false");

    assertEquals(
        new RecoveryFdr3BlobMetadataInspector.Valid(
            SESSION_ID, false),
        inspector.inspect(snapshot(metadata)));
  }

  @Test
  void shouldPreserveOriginalSessionIdCase() {
    String original = SESSION_ID.toUpperCase(Locale.ROOT);
    Map<String, String> metadata = validMetadata();
    metadata.put("sessionId", original);

    assertEquals(
        new RecoveryFdr3BlobMetadataInspector.Valid(
            original, true),
        inspector.inspect(snapshot(metadata)));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "sessionId", "serviceIdentifier", "elaborate"
  })
  void shouldReportMissingMetadata(String field) {
    Map<String, String> metadata = validMetadata();
    metadata.remove(field);

    assertEquals(
        new RecoveryFdr3BlobMetadataInspector.Unresolved(
            RecoveryFdr3BlobMetadataInspector.Reason.MISSING_METADATA,
            field),
        inspector.inspect(snapshot(metadata)));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "sessionId", "serviceIdentifier", "elaborate"
  })
  void shouldReportBlankMetadata(String field) {
    Map<String, String> metadata = validMetadata();
    metadata.put(field, " ");

    assertEquals(
        new RecoveryFdr3BlobMetadataInspector.Unresolved(
            RecoveryFdr3BlobMetadataInspector.Reason.MISSING_METADATA,
            field),
        inspector.inspect(snapshot(metadata)));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "sessionId", "serviceIdentifier", "elaborate"
  })
  void shouldRejectAmbiguousMetadataNames(String field) {
    Map<String, String> metadata = validMetadata();

    metadata.put(
        field.toUpperCase(Locale.ROOT),
        metadata.get(field));

    assertEquals(
        new RecoveryFdr3BlobMetadataInspector.Unresolved(
            RecoveryFdr3BlobMetadataInspector.Reason.AMBIGUOUS_METADATA,
            field),
        inspector.inspect(snapshot(metadata)));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "not-a-uuid",
      "1-1-1-1-1",
      " a92570e7-d5f5-49da-bb8f-0226e2d95ac7"
  })
  void shouldRejectInvalidSessionId(String value) {
    Map<String, String> metadata = validMetadata();
    metadata.put("sessionId", value);

    assertEquals(
        new RecoveryFdr3BlobMetadataInspector.Unresolved(
            RecoveryFdr3BlobMetadataInspector.Reason.INVALID_METADATA,
            "sessionId"),
        inspector.inspect(snapshot(metadata)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"yes", "1", " true "})
  void shouldRejectInvalidElaborate(String value) {
    Map<String, String> metadata = validMetadata();
    metadata.put("elaborate", value);

    assertEquals(
        new RecoveryFdr3BlobMetadataInspector.Unresolved(
            RecoveryFdr3BlobMetadataInspector.Reason.INVALID_METADATA,
            "elaborate"),
        inspector.inspect(snapshot(metadata)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"FDR001", "FDR3", "unknown"})
  void shouldReportUnsupportedService(String value) {
    Map<String, String> metadata = validMetadata();
    metadata.put("serviceIdentifier", value);

    assertEquals(
        new RecoveryFdr3BlobMetadataInspector.Unresolved(
            RecoveryFdr3BlobMetadataInspector.Reason.UNSUPPORTED_SERVICE,
            "serviceIdentifier"),
        inspector.inspect(snapshot(metadata)));
  }

  @Test
  void shouldIgnoreInsertedTimestamp() {
    Map<String, String> metadata = validMetadata();
    metadata.put("insertedTimestamp", "not-a-valid-timestamp");

    assertEquals(
        new RecoveryFdr3BlobMetadataInspector.Valid(
            SESSION_ID, true),
        inspector.inspect(snapshot(metadata)));
  }

  @Test
  void shouldRejectNullSnapshot() {
    assertThrows(
        NullPointerException.class,
        () -> inspector.inspect(null));
  }
}