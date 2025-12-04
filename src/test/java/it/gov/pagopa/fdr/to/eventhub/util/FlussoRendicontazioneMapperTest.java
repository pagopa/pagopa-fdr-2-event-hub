package it.gov.pagopa.fdr.to.eventhub.util;

import static it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper.parseDate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

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
			LocalDateTime localDateTime = parseDate(date);
			assertNotNull(localDateTime);
			assertEquals(LocalDateTime.of(2025, 3, 6, 10, 1, 36), localDateTime);
		}
	}

	@Test
	void testParseDate_withoutTimezone_parsedAsIs() {
		String date = "2025-03-06T11:01:36";
		LocalDateTime localDateTime = parseDate(date);
		assertNotNull(localDateTime);
		assertEquals(LocalDateTime.of(2025, 3, 6, 11, 1, 36), localDateTime);
	}


	@Test
	void testParseDateWithoutHH() {
		String date = "2025-03-06";
		LocalDateTime localDateTime = parseDate(date);
		assertNotNull(localDateTime);
		assertEquals(LocalDateTime.of(2025, 3, 6, 0, 0), localDateTime);
	}
}
