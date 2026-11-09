package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryFdr3BlobMetadataInspector {

  private static final String SESSION_ID = "sessionId";
  private static final String SERVICE_IDENTIFIER = "serviceIdentifier";
  private static final String ELABORATE = "elaborate";

  private static final List<String> REQUIRED_FIELDS =
      List.of(SESSION_ID, SERVICE_IDENTIFIER, ELABORATE);

  public sealed interface Inspection permits Valid, Unresolved {
  }

  public record Valid(
      String sessionId,
      boolean elaborate) implements Inspection {
  }

  public record Unresolved(
      Reason reason,
      String field) implements Inspection {
  }

  public enum Reason {
    MISSING_METADATA,
    AMBIGUOUS_METADATA,
    INVALID_METADATA,
    UNSUPPORTED_SERVICE
  }

  public Inspection inspect(RecoveryBlobSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot is required");

    Map<String, String> values = new HashMap<>();

    for (String field : REQUIRED_FIELDS) {
      List<String> matches =
          snapshot.metadata().entrySet().stream()
              .filter(entry ->
                  field.equalsIgnoreCase(entry.getKey()))
              .map(Map.Entry::getValue)
              .toList();

      if (matches.isEmpty()) {
        return new Unresolved(Reason.MISSING_METADATA, field);
      }

      if (matches.size() > 1) {
        return new Unresolved(Reason.AMBIGUOUS_METADATA, field);
      }

      String value = matches.get(0);

      if (value.isBlank()) {
        return new Unresolved(Reason.MISSING_METADATA, field);
      }

      values.put(field, value);
    }

    String sessionId = values.get(SESSION_ID);

    if (!isCanonicalUuid(sessionId)) {
      return new Unresolved(Reason.INVALID_METADATA, SESSION_ID);
    }

    if (!"FDR03".equals(values.get(SERVICE_IDENTIFIER))) {
      return new Unresolved(
          Reason.UNSUPPORTED_SERVICE,
          SERVICE_IDENTIFIER);
    }

    String elaborate = values.get(ELABORATE);

    if (!"true".equalsIgnoreCase(elaborate)
        && !"false".equalsIgnoreCase(elaborate)) {

      return new Unresolved(Reason.INVALID_METADATA, ELABORATE);
    }

    return new Valid(
        sessionId,
        Boolean.parseBoolean(elaborate));
  }

  private static boolean isCanonicalUuid(String value) {
    try {
      return UUID.fromString(value)
          .toString()
          .equalsIgnoreCase(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}