package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryJobRequestTest {

  private final ObjectMapper objectMapper =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();

  @Test
  void shouldReadRequestFromJson() throws Exception {
    String json = """
        {
          "dateFrom": "2026-10-01",
          "dateTo": "2026-10-31",
          "dryRun": true
        }
        """;

    RecoveryJobRequest request =
        objectMapper.readValue(json, RecoveryJobRequest.class);

    assertEquals(LocalDate.of(2026, 10, 1), request.getDateFrom());
    assertEquals(LocalDate.of(2026, 10, 31), request.getDateTo());
    assertTrue(request.isDryRun());
  }

  @Test
  void shouldPreserveExplicitFalse() throws Exception {
    String json = """
        {
          "dateFrom": "2026-10-01",
          "dateTo": "2026-10-31",
          "dryRun": false
        }
        """;

    RecoveryJobRequest request =
        objectMapper.readValue(json, RecoveryJobRequest.class);

    assertFalse(request.isDryRun());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      """
      {"dateFrom":"2026-10-01","dateTo":"2026-10-31"}
      """,
      """
      {"dateFrom":"2026-10-01","dateTo":"2026-10-31","dryRun":null}
      """
  })
  void shouldDefaultDryRunToTrue(String json) throws Exception {
    RecoveryJobRequest request =
        objectMapper.readValue(json, RecoveryJobRequest.class);

    assertTrue(request.isDryRun());
  }

  @Test
  void shouldAcceptSingleDay() {
    RecoveryJobRequest request =
        new RecoveryJobRequest("2026-10-01", "2026-10-01", true);

    assertEquals(request.getDateFrom(), request.getDateTo());
  }

  @Test
  void shouldAcceptValidLeapDay() {
    RecoveryJobRequest request =
        new RecoveryJobRequest("2028-02-29", "2028-02-29", true);

    assertEquals(LocalDate.of(2028, 2, 29), request.getDateFrom());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
      " ",
      "01/10/2026",
      "2026-1-01",
      "2026-10-1",
      "2026-02-29",
      "2026-02-30",
      "2026-13-01",
      "2026-10-01T00:00:00Z",
      " 2026-10-01 "
  })
  void shouldRejectInvalidDates(String invalidDate) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryJobRequest(invalidDate, "2026-10-31", true));

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryJobRequest("2026-01-01", invalidDate, true));
  }

  @Test
  void shouldRejectReversedRange() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new RecoveryJobRequest(
                "2026-10-31", "2026-10-01", true));

    assertEquals(
        "'dateFrom' must be before or equal to 'dateTo'",
        exception.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      """
      {"dateTo":"2026-10-31"}
      """,
      """
      {"dateFrom":"2026-10-01"}
      """,
      """
      {"dateFrom":"2026-10-31","dateTo":"2026-10-01"}
      """,
      """
      {"dateFrom":"2026-10-01","dateTo":"2026-10-31","dryRun":"false"}
      """,
      """
      {"dateFrom":"2026-10-01","dateTo":"2026-10-31","dryRun":0}
      """
  })
  void shouldRejectInvalidJsonRequest(String json) {
    assertThrows(
        JsonProcessingException.class,
        () -> objectMapper.readValue(json, RecoveryJobRequest.class));
  }
}