package it.gov.pagopa.fdr.to.eventhub.parser;

import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRiversamento;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class FlussoRendicontazioneTagParser2 {

    // NESSUN CAMPO DI ISTANZA (tranne utility final)

    public FlussoRendicontazione parse(XMLInputFactory factory, InputStream xmlStream)
            throws XMLStreamException {

        // Le variabili di stato sono LOCALI al metodo
        StringBuilder value = new StringBuilder();
        Map<String, String> rawData = new HashMap<>();
        FlussoRiversamento completeFlow = null;
        String rawBase64Content = null;

        XMLStreamReader reader = factory.createXMLStreamReader(xmlStream);

        while (reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    value.setLength(0); // Pulisce il buffer per il nuovo elemento
                    break;
                case XMLStreamConstants.CHARACTERS:
                    value.append(reader.getText().trim());
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    String content = value.toString().trim();
                    String tagName = normalizeTag(reader.getLocalName());

                    if ("xmlRendicontazione".equals(tagName)) {
                        rawBase64Content = content;
                    } else if ("nodoInviaFlussoRendicontazione".equals(tagName)) {
                        if (rawBase64Content == null) {
                            throw new XMLStreamException("Tag 'nodoInviaFlussoRendicontazione' trovato prima di 'xmlRendicontazione'");
                        }
                        // Esegui il sub-parsing (con la logica corretta di decodifica)
                        completeFlow = parseSubFlusso(factory, rawBase64Content);
                        rawBase64Content = null; // Rilascia la memoria della stringa il prima possibile
                    } else {
                        rawData.put(tagName, content);
                    }
                    break;
            }
        }
        reader.close();

        return mapToFlussoRendicontazione(rawData, completeFlow);
    }

    // Metodo helper per il sub-parsing
    private FlussoRiversamento parseSubFlusso(XMLInputFactory factory, String base64Content)
            throws XMLStreamException {

        byte[] decodedXml = Base64.getDecoder().decode(base64Content);
        try (InputStream subStream = new ByteArrayInputStream(decodedXml)) {
            FlussoRiversamentoTagParser2 parser = new FlussoRiversamentoTagParser2();
            return parser.parse(factory, subStream);
        } catch (IOException e) {
            throw new XMLStreamException("Errore I/O durante il parsing del flusso Base64", e);
        }
    }

    // Il mapping ora accetta completeFlow come parametro
    private FlussoRendicontazione mapToFlussoRendicontazione(Map<String, String> rawData, FlussoRiversamento completeFlow) {

        return FlussoRendicontazione.builder()
                .identificativoPSP(rawData.get("identificativoPSP"))
                .identificativoIntermediarioPSP(rawData.get("identificativoIntermediarioPSP"))
                .identificativoCanale(rawData.get("identificativoCanale"))
                .password(rawData.get("password"))
                .identificativoDominio(rawData.get("identificativoDominio"))
                .identificativoFlusso(rawData.get("identificativoFlusso"))
                .dataOraFlusso(rawData.get("dataOraFlusso"))
                .flussoRiversamento(completeFlow)
                .build();
    }

    private String normalizeTag(String qName) {
        return qName.contains(":") ? qName.substring(qName.indexOf(":") + 1) : qName;
    }
}