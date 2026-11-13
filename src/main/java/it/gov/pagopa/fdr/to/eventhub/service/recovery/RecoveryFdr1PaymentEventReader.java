package it.gov.pagopa.fdr.to.eventhub.service.recovery;

import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryPublicationSource;
import it.gov.pagopa.fdr.to.eventhub.parser.FlussoRendicontazioneTagParser;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryBlobContentRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

public final class RecoveryFdr1PaymentEventReader {

  private static final String SESSION_ID = "sessionId";
  private static final String INSERTED_TIMESTAMP = "insertedTimestamp";

  private final RecoveryBlobContentRepository contentRepository;
  private final RecoveryBlobDecompressor decompressor;
  private final RecoveryFdr1PayloadDecoder payloadDecoder;
  private final RecoveryPaymentEventSelector selector;

  private final RecoveryFdr1EnvelopeReader envelopeReader =
      new RecoveryFdr1EnvelopeReader();

  private final RecoveryFdr1ReportReader reportReader =
      new RecoveryFdr1ReportReader();

  private final RecoveryFdr1DateNormalizer dateNormalizer =
      new RecoveryFdr1DateNormalizer();

  private final RecoveryFdr1IdentityMatcher identityMatcher =
      new RecoveryFdr1IdentityMatcher(dateNormalizer);

  private final RecoveryFdr1BlobMetadataInspector metadataInspector =
      new RecoveryFdr1BlobMetadataInspector();

  public RecoveryFdr1PaymentEventReader(
      RecoveryBlobContentRepository contentRepository,
      RecoveryBlobDecompressor decompressor,
      RecoveryFdr1PayloadDecoder payloadDecoder,
      RecoveryPaymentEventSelector selector) {

    this.contentRepository =
        Objects.requireNonNull(
            contentRepository, "contentRepository is required");

    this.decompressor =
        Objects.requireNonNull(
            decompressor, "decompressor is required");

    this.payloadDecoder =
        Objects.requireNonNull(
            payloadDecoder, "payloadDecoder is required");

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
   * Prepares all payments from the verified XML snapshot, without day filtering.
   * The caller must consume and close the returned stream.
   */
  public Stream<ReportedIUVEventModel> readAll(
      RecoveryPublicationSource source) throws IOException {

    Objects.requireNonNull(source, "source is required");

    if (source.sourceType()
        != RecoveryPublicationSource.SourceType.FDR1_XML) {

      throw new IllegalArgumentException(
          "An FDR1 XML publication source is required");
    }

    Map<String, String> metadata = mappingMetadata(source);

    byte[] compressed =
        contentRepository.download(source.blob());

    byte[] outerXml = decompressor.decompress(compressed);

    var envelope = envelopeReader.read(outerXml);

    byte[] innerXml = payloadDecoder.decode(outerXml);
    var report = reportReader.read(innerXml);

    validateIdentity(source, envelope, report);

    FlussoRendicontazione flow = parseFlow(outerXml);

    validateParsedFlow(flow, envelope, report, source);

    flow.setMetadata(metadata);

    return FlussoRendicontazioneMapper.toReportedIUVEventStream(flow)
        .onClose(flow::releaseResources);
  }

  private void validateIdentity(
      RecoveryPublicationSource source,
      RecoveryFdr1EnvelopeReader.RequestIdentity envelope,
      RecoveryFdr1ReportReader.ReportIdentity report)
      throws IOException {

    try {
      if (!identityMatcher.compare(
          source.candidate(), envelope).matches()) {

        throw new IOException(
            "FDR1 envelope differs from the selected publication");
      }

      if (!envelope.name().equals(report.name())
          || report.declaredPaymentCount()
              != report.observedPaymentCount()) {

        throw new IOException(
            "FDR1 inner report is inconsistent");
      }

      if (!source.eventFlowDate().equals(
          dateNormalizer.normalize(report.flowDate()))) {

        throw new IOException(
            "FDR1 original event date differs from the selected source");
      }
    } catch (DateTimeException exception) {
      throw new IOException("Invalid FDR1 publication date", exception);
    }
  }

  private void validateParsedFlow(
      FlussoRendicontazione flow,
      RecoveryFdr1EnvelopeReader.RequestIdentity envelope,
      RecoveryFdr1ReportReader.ReportIdentity report,
      RecoveryPublicationSource source) throws IOException {

    if (flow == null || flow.getFlussoRiversamento() == null) {
      throw new IOException("Missing parsed FDR1 report");
    }

    var parsedReport = flow.getFlussoRiversamento();
    var payments = parsedReport.getDatiSingoliPagamenti();

    if (!envelope.name().equals(flow.getIdentificativoFlusso())
        || !envelope.pspDomainId().equals(
            flow.getIdentificativoPSP())
        || !envelope.orgDomainId().equals(
            flow.getIdentificativoDominio())
        || !report.name().equals(
            parsedReport.getIdentificativoFlusso())
        || payments == null
        || payments.size() != report.observedPaymentCount()) {

      throw new IOException(
          "Parsed FDR1 model differs from the verified XML");
    }

    if (parsedReport.getDataOraFlusso() == null) {
      throw new IOException("Missing parsed FDR1 flow date");
    }

    try {
      if (!source.eventFlowDate().equals(
          dateNormalizer.normalize(
              parsedReport.getDataOraFlusso()))) {

        throw new IOException(
            "Parsed FDR1 flow date differs from the selected source");
      }
    } catch (DateTimeException exception) {
      throw new IOException("Invalid parsed FDR1 flow date", exception);
    }
  }

  private static FlussoRendicontazione parseFlow(byte[] xml)
      throws IOException {

    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();

    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(
        XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

    factory.setXMLResolver(
        (publicId, systemId, baseUri, namespace) -> {
          throw new XMLStreamException(
              "External entities are not allowed");
        });

    try (var input = new ByteArrayInputStream(xml)) {
      return new FlussoRendicontazioneTagParser()
          .parse(factory, input);

    } catch (XMLStreamException | IllegalArgumentException exception) {
      throw new IOException(
          "Cannot parse verified FDR1 payment content", exception);
    }
  }

  private Map<String, String> mappingMetadata(
      RecoveryPublicationSource source) throws IOException {

    var inspection = metadataInspector.inspect(source.blob());

    if (!(inspection
        instanceof RecoveryFdr1BlobMetadataInspector.Valid valid)) {

      throw new IOException(
          "FDR1 source metadata is not valid for execution");
    }

    if (!source.sessionId().equalsIgnoreCase(valid.sessionId())) {
      throw new IOException(
          "Blob session differs from the selected publication");
    }

    var timestamps =
        source.blob().metadata().entrySet().stream()
            .filter(entry ->
                INSERTED_TIMESTAMP.equalsIgnoreCase(entry.getKey()))
            .map(Map.Entry::getValue)
            .toList();

    if (timestamps.size() != 1 || timestamps.get(0).isBlank()) {
      throw new IOException(
          "Missing or ambiguous original insertedTimestamp");
    }

    final String timestamp;

    try {
      timestamp =
          dateNormalizer.normalize(timestamps.get(0)).toString();
    } catch (DateTimeException exception) {
      throw new IOException(
          "Invalid original insertedTimestamp", exception);
    }

    var metadata = new HashMap<>(source.blob().metadata());

    metadata.put(SESSION_ID, valid.sessionId());
    metadata.put(INSERTED_TIMESTAMP, timestamp);

    return metadata;
  }
}