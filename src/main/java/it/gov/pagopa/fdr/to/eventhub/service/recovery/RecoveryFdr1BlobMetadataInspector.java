package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryFdr1BlobMetadataInspector {

  private static final String SESSION_ID = "sessionId";
  private static final String SERVICE_IDENTIFIER = "serviceIdentifier";
  private static final String ELABORATE = "elaborate";

  private static final List<String> REQUIRED_FIELDS =
      List.of(SESSION_ID, SERVICE_IDENTIFIER, ELABORATE);

  public enum UnresolvedReason {
    MISSING_METADATA,
    AMBIGUOUS_METADATA,
    INVALID_METADATA,
    UNSUPPORTED_SERVICE
  }

  public sealed interface Inspection permits Valid, Unresolved {
  }

  public record Valid(
      String sessionId,
      boolean elaborate) implements Inspection {
  }

  public record Unresolved(
      UnresolvedReason reason,
      String field) implements Inspection {
  }

  public Inspection inspect(RecoveryBlobSnapshot blob) {
    Objects.requireNonNull(blob, "blob is required");

    Map<String, String> values = new HashMap<>();

    for (String field : REQUIRED_FIELDS) {
      List<String> matchingValues =
          blob.metadata().entrySet().stream()
              .filter(entry -> field.equalsIgnoreCase(entry.getKey()))
              .map(Map.Entry::getValue)
              .toList();

      if (matchingValues.size() > 1) {
        return new Unresolved(
            UnresolvedReason.AMBIGUOUS_METADATA, field);
      }

      if (matchingValues.isEmpty() || matchingValues.get(0).isBlank()) {
        return new Unresolved(
            UnresolvedReason.MISSING_METADATA, field);
      }

      values.put(field, matchingValues.get(0));
    }

    String sessionId = values.get(SESSION_ID);

    if (!isFullUuid(sessionId)) {
      return new Unresolved(
          UnresolvedReason.INVALID_METADATA, SESSION_ID);
    }

    if (!"FDR001".equals(values.get(SERVICE_IDENTIFIER))) {
      return new Unresolved(
          UnresolvedReason.UNSUPPORTED_SERVICE, SERVICE_IDENTIFIER);
    }

    String elaborate = values.get(ELABORATE);

    if (!"true".equalsIgnoreCase(elaborate)
        && !"false".equalsIgnoreCase(elaborate)) {
      return new Unresolved(
          UnresolvedReason.INVALID_METADATA, ELABORATE);
    }

    return new Valid(sessionId, Boolean.parseBoolean(elaborate));
  }

  private static boolean isFullUuid(String value) {
    try {
      return UUID.fromString(value).toString().equalsIgnoreCase(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}