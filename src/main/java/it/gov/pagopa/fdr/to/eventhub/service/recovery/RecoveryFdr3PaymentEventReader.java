package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.gov.pagopa.fdr.to.eventhub.mapper.FlowMapper;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr3.Flow;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryBlobContentRepository;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class RecoveryFdr3PaymentEventReader {

  private static final String SESSION_ID = "sessionId";
  private static final String INSERTED_TIMESTAMP = "insertedTimestamp";

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
          .build();

  private final RecoveryBlobContentRepository contentRepository;
  private final RecoveryBlobDecompressor decompressor;
  private final RecoveryPaymentEventSelector selector;

  private final RecoveryFdr3IdentityReader identityReader =
      new RecoveryFdr3IdentityReader();

  private final RecoveryFdr3IdentityMatcher identityMatcher =
      new RecoveryFdr3IdentityMatcher();

  private final RecoveryFdr3BlobMetadataInspector metadataInspector =
      new RecoveryFdr3BlobMetadataInspector();

  private final RecoveryFdr1DateNormalizer timestampNormalizer =
      new RecoveryFdr1DateNormalizer();

  public RecoveryFdr3PaymentEventReader(
      RecoveryBlobContentRepository contentRepository,
      RecoveryBlobDecompressor decompressor,
      RecoveryPaymentEventSelector selector) {

    this.contentRepository =
        Objects.requireNonNull(
            contentRepository, "contentRepository is required");

    this.decompressor =
        Objects.requireNonNull(
            decompressor, "decompressor is required");

    this.selector =
        Objects.requireNonNull(selector, "selector is required");
  }

  /**
   * Prepares payment events for the requested business day.
   * The caller must consume and close the returned stream.
   */
  public Stream<ReportedIUVEventModel> read(
      LocalDate paymentDay,
      RecoveryPublicationSource source) throws IOException {

    Objects.requireNonNull(paymentDay, "paymentDay is required");
    Objects.requireNonNull(source, "source is required");

    return selector.select(paymentDay, source, readAll(source));
  }

  /**
   * Prepares all payments from the verified JSON snapshot, without day filtering.
   * The caller must consume and close the returned stream.
   */
  public Stream<ReportedIUVEventModel> readAll(
      RecoveryPublicationSource source) throws IOException {

    Objects.requireNonNull(source, "source is required");

    if (source.sourceType()
        != RecoveryPublicationSource.SourceType.FDR3_JSON) {

      throw new IllegalArgumentException(
          "An FDR3 JSON publication source is required");
    }

    Map<String, String> metadata = mappingMetadata(source);

    byte[] compressed =
        contentRepository.download(source.blob());

    byte[] json = decompressor.decompress(compressed);

    validateIdentity(source, json);

    Flow flow = MAPPER.readValue(json, Flow.class);

    if (flow.getPayments() == null) {
      throw new IOException("FDR3 payments array is required");
    }

    // Blob metadata is authoritative. Ignore metadata embedded in JSON.
    flow.setMetadata(metadata);

    return FlowMapper.toReportedIUVEventStream(flow)
        .onClose(flow::releaseResources);
  }

  private void validateIdentity(
      RecoveryPublicationSource source,
      byte[] json) throws IOException {

    var identity = identityReader.read(json);

    var comparison =
        identityMatcher.compare(source.candidate(), identity);

    if (!comparison.matches()
        || !source.eventFlowDate().equals(identity.flowDate())) {

      throw new IOException(
          "FDR3 content identity differs from the selected publication");
    }
  }

  private Map<String, String> mappingMetadata(
      RecoveryPublicationSource source) throws IOException {

    var inspection = metadataInspector.inspect(source.blob());

    if (!(inspection
        instanceof RecoveryFdr3BlobMetadataInspector.Valid valid)) {

      throw new IOException(
          "FDR3 source metadata is not valid for execution");
    }

    if (!source.sessionId().equalsIgnoreCase(valid.sessionId())) {
      throw new IOException(
          "Blob session differs from the selected publication");
    }

    String insertedTimestamp =
        requiredMetadata(source.blob().metadata(), INSERTED_TIMESTAMP);

    final String normalizedTimestamp;

    try {
      normalizedTimestamp =
          timestampNormalizer.normalize(insertedTimestamp).toString();
    } catch (DateTimeException exception) {
      throw new IOException(
          "Invalid original insertedTimestamp", exception);
    }

    var metadata = new HashMap<>(source.blob().metadata());

    // Canonical keys are required by the existing event mapper.
    metadata.put(SESSION_ID, valid.sessionId());
    metadata.put(INSERTED_TIMESTAMP, normalizedTimestamp);

    return metadata;
  }

  private static String requiredMetadata(
      Map<String, String> metadata,
      String name) throws IOException {

    var values =
        metadata.entrySet().stream()
            .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
            .map(Map.Entry::getValue)
            .toList();

    if (values.size() != 1 || values.get(0).isBlank()) {
      throw new IOException(
          "Missing or ambiguous original metadata: " + name);
    }

    return values.get(0);
  }
}