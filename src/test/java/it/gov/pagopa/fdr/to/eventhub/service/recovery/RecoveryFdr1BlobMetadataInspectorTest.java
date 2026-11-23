package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFdr1BlobMetadataInspectorTest {

  private static final String SESSION_ID =
      "6ecc100f-cd56-422d-815b-8243edc81a84";

  private final RecoveryFdr1BlobMetadataInspector inspector =
      new RecoveryFdr1BlobMetadataInspector();

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shouldAcceptBothElaborateValues(boolean elaborate) {
    var metadata = validMetadata();
    metadata.put("elaborate", Boolean.toString(elaborate));

    var result = inspector.inspect(snapshot(metadata));

    assertEquals(
        new RecoveryFdr1BlobMetadataInspector.Valid(
            SESSION_ID, elaborate),
        result);
  }

  @ParameterizedTest
  @CsvSource({
      "sessionId,false",
      "serviceIdentifier,false",
      "elaborate,false",
      "sessionId,true",
      "serviceIdentifier,true",
      "elaborate,true"
  })
  void shouldReportMissingOrBlankMetadata(
      String field, boolean blank) {

    var metadata = validMetadata();

    if (blank) {
      metadata.put(field, " ");
    } else {
      metadata.remove(field);
    }

    var result = inspector.inspect(snapshot(metadata));

    assertEquals(
        new RecoveryFdr1BlobMetadataInspector.Unresolved(
            RecoveryFdr1BlobMetadataInspector.UnresolvedReason
                .MISSING_METADATA,
            field),
        result);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "sessionId",
      "serviceIdentifier",
      "elaborate"
  })
  void shouldRejectCaseVariantDuplicates(String field) {
    var metadata = validMetadata();

    metadata.put(
        field.toUpperCase(Locale.ROOT),
        metadata.get(field));

    var result = inspector.inspect(snapshot(metadata));

    assertEquals(
        new RecoveryFdr1BlobMetadataInspector.Unresolved(
            RecoveryFdr1BlobMetadataInspector.UnresolvedReason
                .AMBIGUOUS_METADATA,
            field),
        result);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "invalid",
      "1-1-1-1-1",
      "6ecc100fcd56422d815b8243edc81a84",
      " 6ecc100f-cd56-422d-815b-8243edc81a84 "
  })
  void shouldRejectInvalidSessionId(String sessionId) {
    var metadata = validMetadata();
    metadata.put("sessionId", sessionId);

    var result = inspector.inspect(snapshot(metadata));

    assertEquals(
        new RecoveryFdr1BlobMetadataInspector.Unresolved(
            RecoveryFdr1BlobMetadataInspector.UnresolvedReason
                .INVALID_METADATA,
            "sessionId"),
        result);
  }

  @Test
  void shouldRejectAnotherService() {
    var metadata = validMetadata();
    metadata.put("serviceIdentifier", "FDR03");

    var result = inspector.inspect(snapshot(metadata));

    assertEquals(
        new RecoveryFdr1BlobMetadataInspector.Unresolved(
            RecoveryFdr1BlobMetadataInspector.UnresolvedReason
                .UNSUPPORTED_SERVICE,
            "serviceIdentifier"),
        result);
  }

  @ParameterizedTest
  @ValueSource(strings = {"yes", "1", " false "})
  void shouldRejectInvalidElaborate(String elaborate) {
    var metadata = validMetadata();
    metadata.put("elaborate", elaborate);

    var result = inspector.inspect(snapshot(metadata));

    assertEquals(
        new RecoveryFdr1BlobMetadataInspector.Unresolved(
            RecoveryFdr1BlobMetadataInspector.UnresolvedReason
                .INVALID_METADATA,
            "elaborate"),
        result);
  }

  @Test
  void shouldAcceptCaseVariantsAndPreserveSessionId() {
    String uppercaseSessionId = SESSION_ID.toUpperCase(Locale.ROOT);

    var metadata =
        Map.of(
            "SESSIONID", uppercaseSessionId,
            "SERVICEIDENTIFIER", "FDR001",
            "ELABORATE", "FALSE");

    var result = inspector.inspect(snapshot(metadata));

    assertEquals(
        new RecoveryFdr1BlobMetadataInspector.Valid(
            uppercaseSessionId, false),
        result);
  }

  @Test
  void shouldIgnoreInsertedTimestamp() {
    var metadata = validMetadata();
    metadata.put("insertedTimestamp", "not-a-date");

    var result = inspector.inspect(snapshot(metadata));

    assertEquals(
        new RecoveryFdr1BlobMetadataInspector.Valid(
            SESSION_ID, false),
        result);
  }

  @Test
  void shouldRejectNullSnapshot() {
    assertThrows(
        NullPointerException.class,
        () -> inspector.inspect(null));
  }

  private static Map<String, String> validMetadata() {
    return new HashMap<>(
        Map.of(
            "sessionId", SESSION_ID,
            "serviceIdentifier", "FDR001",
            "elaborate", "false"));
  }

  private static RecoveryBlobSnapshot snapshot(
      Map<String, String> metadata) {

    return new RecoveryBlobSnapshot(
        "fdr1-flows",
        "flow-1_original.xml.zip",
        "\"etag-1\"",
        100L,
        metadata);
  }
}