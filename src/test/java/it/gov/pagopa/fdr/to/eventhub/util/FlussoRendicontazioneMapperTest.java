package it.gov.pagopa.fdr.to.eventhub.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static it.gov.pagopa.fdr.to.eventhub.mapper.FlussoRendicontazioneMapper.parseDate;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class FlussoRendicontazioneMapperTest {

    @Test
    void testParseDate() {
        String[] dates = new String[] {
            "2025-03-06T11:01:36+01:00",
            "2025-03-06T11:01:36",
            "2025-03-06T05:01:36-05:00",
            "2025-03-06T10:01:36Z"
        };

        for (String date : dates) {
            LocalDateTime localDateTime = parseDate(date);
            assertNotNull(localDateTime);
            assertEquals("2025-03-06T11:01:36", localDateTime.toString());
        }
    }
}
