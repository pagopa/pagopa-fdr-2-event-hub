package it.gov.pagopa.fdr.to.eventhub.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.DatiSingoloPagamento;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.fdr1.FlussoRiversamento;
import it.gov.pagopa.fdr.to.eventhub.util.DateParsingUtil;

@ExtendWith(MockitoExtension.class)
class FlussoRendicontazioneMapperTest {

	@Test
	void testParseDate_withTimezone_normalizedToUtcLocalTime() {
		// All these strings represent the SAME INSTANT: 2025-03-06T10:01:36Z
		String[] dates =
				new String[] {
						"2025-03-06T11:01:36+01:00",
						"2025-03-06T05:01:36-05:00",
						"2025-03-06T10:01:36Z",
		};

		for (String date : dates) {
			LocalDateTime localDateTime = DateParsingUtil.parseDateTimeToUtcLocal(date);
			assertNotNull(localDateTime);
			assertEquals(LocalDateTime.of(2025, 3, 6, 10, 1, 36), localDateTime);
		}
	}

	@Test
	void testParseDate_withoutTimezone_parsedAsIs() {
		String date = "2025-03-06T11:01:36";
		LocalDateTime localDateTime = DateParsingUtil.parseDateTimeToUtcLocal(date);
		assertNotNull(localDateTime);
		assertEquals(LocalDateTime.of(2025, 3, 6, 11, 1, 36), localDateTime);
	}


	@Test
	void testParseDateWithoutHH() {
		String date = "2025-03-06";
		LocalDateTime localDateTime = DateParsingUtil.parseDateTimeToUtcLocal(date);
		assertNotNull(localDateTime);
		assertEquals(LocalDateTime.of(2025, 3, 6, 0, 0), localDateTime);
	}
	
	@Test
	void testIdsp() {

	  DatiSingoloPagamento p =
	      DatiSingoloPagamento.builder()
	          .identificativoUnivocoVersamento("IUV1")
	          .identificativoUnivocoRiscossione("IUR1")
	          .indiceDatiSingoloPagamento("3")
	          .singoloImportoPagato(1.00d)
	          .codiceEsitoSingoloPagamento(0)
	          .dataEsitoSingoloPagamento("2025-03-06")
	          .build();

	  FlussoRiversamento riversamento =
	      FlussoRiversamento.builder()
	          .identificativoFlusso("FDR123")
	          .dataOraFlusso("2025-03-06T10:01:36Z")
	          .dataRegolamento("2025-03-06")
	          .numeroTotalePagamenti(1)
	          .importoTotalePagamenti(1.00d)
	          .identificativoUnivocoRegolamento("REG123")
	          .datiSingoliPagamenti(List.of(p))
	          .build();

	  FlussoRendicontazione flusso =
	      FlussoRendicontazione.builder()
	          .identificativoPSP("PSP1")
	          .identificativoIntermediarioPSP("BROKER1")
	          .identificativoDominio("PA1")
	          .flussoRiversamento(riversamento)
	          .metadata(Map.of("sessionId", "S1", "insertedTimestamp", "2025-03-06T10:01:36Z"))
	          .build();

	  ReportedIUVEventModel out =
	      FlussoRendicontazioneMapper.toReportedIUVEventStream(flusso)
	          .findFirst()
	          .orElseThrow();

	  assertEquals("3", out.getIdsp());
	}

}
