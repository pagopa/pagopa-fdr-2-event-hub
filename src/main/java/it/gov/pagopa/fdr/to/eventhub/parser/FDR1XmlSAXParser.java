package it.gov.pagopa.fdr.to.eventhub.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import it.gov.pagopa.fdr.to.eventhub.exception.XmlParsingException;
import it.gov.pagopa.fdr.to.eventhub.model.DatiSingoloPagamento;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.FlussoRiversamento;
import it.gov.pagopa.fdr.to.eventhub.model.Istituto;
import lombok.experimental.UtilityClass;

@UtilityClass
public class FDR1XmlSAXParser {

	public static FlussoRendicontazione parseXmlContent(String xmlContent)
			throws ParserConfigurationException, SAXException, IOException {
		SAXParserFactory factory = SAXParserFactory.newInstance();
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

		SAXParser parser = factory.newSAXParser();
		AtomicReference<FlussoRendicontazione> flusso = new AtomicReference<>();
		Consumer<FlussoRendicontazione> updater = flusso::set;

		DefaultHandler handler = new DefaultHandler() {
			private StringBuilder value = new StringBuilder();
			private String flussoRiversamentoBase64;
			private String identificativoPSP;
			private String identificativoIntermediarioPSP;
			private String identificativoCanale;
			private String password;
			private String identificativoDominio;
			private String identificativoFlusso;
			private String dataOraFlusso;

			@Override
			public void startElement(String uri, String localName, String qName, Attributes attributes)
					throws SAXException {
				value.setLength(0);
			}

			@Override
			public void characters(char[] ch, int start, int length) throws SAXException {
				value.append(ch, start, length);
			}

			@Override
			public void endElement(String uri, String localName, String qName) throws SAXException {
				String content = value.toString().trim();

				switch (qName) {
				case "identificativoPSP":
					identificativoPSP = content;
					break;
				case "identificativoIntermediarioPSP":
					identificativoIntermediarioPSP = content;
					break;
				case "identificativoCanale":
					identificativoCanale = content;
					break;
				case "password":
					password = content;
					break;
				case "identificativoDominio":
					identificativoDominio = content;
					break;
				case "identificativoFlusso":
					identificativoFlusso = content;
					break;
				case "dataOraFlusso":
					dataOraFlusso = content;
					break;
				case "xmlRendicontazione":
					flussoRiversamentoBase64 = content;
					break;
				case "ns5:nodoInviaFlussoRendicontazione":
					FlussoRiversamento flussoRiversamento = decodeAndParseFlussoRiversamento(flussoRiversamentoBase64);

					updater.accept(FlussoRendicontazione.builder().identificativoPSP(identificativoPSP)
							.identificativoIntermediarioPSP(identificativoIntermediarioPSP)
							.identificativoCanale(identificativoCanale).password(password)
							.identificativoDominio(identificativoDominio).identificativoFlusso(identificativoFlusso)
							.dataOraFlusso(dataOraFlusso).flussoRiversamento(flussoRiversamento).build());
					break;
				default:
					break;
				}
			}
		};

		parser.parse(new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)), handler);

		return flusso.get();
	}

	private static FlussoRiversamento decodeAndParseFlussoRiversamento(String base64Content)
			throws XmlParsingException {
		if (base64Content == null || base64Content.isEmpty()) {
			return null;
		}
		byte[] decodedBytes = Base64.getDecoder().decode(base64Content);
		String decodedXml = new String(decodedBytes, StandardCharsets.UTF_8);

		return parseFlussoRiversamento(decodedXml);
	}

	public static FlussoRiversamento parseFlussoRiversamento(String decodedXml) throws XmlParsingException {
		try {
			SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

			SAXParser parser = factory.newSAXParser();
			FlussoRiversamentoHandler handler = new FlussoRiversamentoHandler();

			parser.parse(new ByteArrayInputStream(decodedXml.getBytes(StandardCharsets.UTF_8)), handler);
			return handler.getFlussoRiversamento();
		} catch (Exception e) {
			throw new XmlParsingException("Error parsing flusso riversamento", e);
		}
	}
}

class FlussoRiversamentoHandler extends DefaultHandler {
	private FlussoRiversamento flussoRiversamento;
	private Istituto currentIstituto;
	private DatiSingoloPagamento currentDatiPagamento;
	private StringBuilder value = new StringBuilder();
	private boolean insideDatiSingoliPagamenti = false;

	public FlussoRiversamentoHandler() {
		this.flussoRiversamento = new FlussoRiversamento();
		this.flussoRiversamento.setDatiSingoliPagamenti(new ArrayList<>());
	}

	public FlussoRiversamento getFlussoRiversamento() {
		return flussoRiversamento;
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
		value.setLength(0);

		switch (qName) {
		case "istitutoMittente", "istitutoRicevente":
			currentIstituto = new Istituto();
			break;
		case "datiSingoliPagamenti":
			currentDatiPagamento = new DatiSingoloPagamento();
			insideDatiSingoliPagamenti = true;
			break;
		default:
			break;
		}
	}

	@Override
	public void characters(char[] ch, int start, int length) throws SAXException {
		value.append(ch, start, length);
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		String content = value.toString().trim();

		if (flussoRiversamento != null) {
			switch (qName) {
			case "versioneOggetto":
				flussoRiversamento.setVersioneOggetto(content);
				break;
			case "identificativoFlusso":
				flussoRiversamento.setIdentificativoFlusso(content);
				break;
			case "dataOraFlusso":
				flussoRiversamento.setDataOraFlusso(content);
				break;
			case "identificativoUnivocoRegolamento":
				flussoRiversamento.setIdentificativoUnivocoRegolamento(content);
				break;
			case "dataRegolamento":
				flussoRiversamento.setDataRegolamento(content);
				break;
			case "numeroTotalePagamenti":
				flussoRiversamento.setNumeroTotalePagamenti(Integer.parseInt(content));
				break;
			case "importoTotalePagamenti":
				flussoRiversamento.setImportoTotalePagamenti(Double.parseDouble(content));
				break;
			default:
				break;
			}
		}

		if (currentIstituto != null) {
			switch (qName) {
			case "tipoIdentificativoUnivoco":
				currentIstituto.setTipoIdentificativoUnivoco(content);
				break;
			case "codiceIdentificativoUnivoco":
				currentIstituto.setCodiceIdentificativoUnivoco(content);
				break;
			case "denominazioneMittente", "denominazioneRicevente":
				currentIstituto.setDenominazione(content);
				break;
			case "istitutoMittente":
				flussoRiversamento.setIstitutoMittente(currentIstituto);
				currentIstituto = null;
				break;
			case "istitutoRicevente":
				flussoRiversamento.setIstitutoRicevente(currentIstituto);
				currentIstituto = null;
				break;
			default:
				break;
			}
		}

		if (insideDatiSingoliPagamenti && currentDatiPagamento != null) {
			switch (qName) {
			case "identificativoUnivocoVersamento":
				currentDatiPagamento.setIdentificativoUnivocoVersamento(content);
				break;
			case "identificativoUnivocoRiscossione":
				currentDatiPagamento.setIdentificativoUnivocoRiscossione(content);
				break;
			case "indiceDatiSingoloPagamento":
				currentDatiPagamento.setIndiceDatiSingoloPagamento(content);
				break;
			case "singoloImportoPagato":
				currentDatiPagamento.setSingoloImportoPagato(Double.parseDouble(content));
				break;
			case "codiceEsitoSingoloPagamento":
				currentDatiPagamento.setCodiceEsitoSingoloPagamento(Integer.parseInt(content));
				break;
			case "dataEsitoSingoloPagamento":
				currentDatiPagamento.setDataEsitoSingoloPagamento(content);
				break;
			case "datiSingoliPagamenti":
				flussoRiversamento.getDatiSingoliPagamenti().add(currentDatiPagamento);
				currentDatiPagamento = null;
				insideDatiSingoliPagamenti = false;
				break;
			default:
				break;
			}
		}
	}
}