package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class RecoveryAdxPublicationQueryTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private static final UUID FIRST =
      UUID.fromString("6fd3e2eb-1d1d-4068-ba74-fa9866141524");

  private static final UUID SECOND =
      UUID.fromString("24fea4e9-c5d3-4d21-b74b-d51be0774d11");

  @Test
  void shouldBuildDayAndPublicationParameters() {
    var query =
        new RecoveryAdxPublicationQuery(
            DAY, List.of(FIRST, SECOND));

    assertEquals(
        "2026-10-01T00:00:00Z",
        query.parameters().get("recoveryDay"));

    assertEquals(
        "[\"24fea4e9-c5d3-4d21-b74b-d51be0774d11\","
            + "\"6fd3e2eb-1d1d-4068-ba74-fa9866141524\"]",
        query.parameters().get("recoveryPublicationIds"));
  }

  @Test
  void shouldKeepValuesOutsideQueryTextAndFilterBeforeAggregation() {
    var first =
        new RecoveryAdxPublicationQuery(DAY, List.of(FIRST));

    var second =
        new RecoveryAdxPublicationQuery(
            DAY.plusDays(1), List.of(SECOND));

    assertEquals(first.text(), second.text());
    assertFalse(first.text().contains(FIRST.toString()));
    assertFalse(first.text().contains(DAY.toString()));

    assertTrue(
        first.text().contains(
            "DATA_ESITO_SINGOLO_PAGAMENTO == recoveryDay"));

    assertTrue(
        first.text().indexOf("where tostring(UNIQUE_ID)")
            < first.text().indexOf("summarize"));

    assertTrue(first.text().contains("PublicationId"));
    assertTrue(first.text().contains("| take 10001"));
  }

  @Test
  void shouldDeduplicatePublicationIds() {
    var query =
        new RecoveryAdxPublicationQuery(
            DAY, List.of(FIRST, FIRST));

    assertEquals(
        "[\"" + FIRST + "\"]",
        query.parameters().get("recoveryPublicationIds"));
  }

  @Test
  void shouldSnapshotInputCollection() {
    var ids = new ArrayList<>(List.of(FIRST));

    var query = new RecoveryAdxPublicationQuery(DAY, ids);

    ids.clear();

    assertEquals(
        "[\"" + FIRST + "\"]",
        query.parameters().get("recoveryPublicationIds"));
  }

  @Test
  void shouldRejectEmptyIds() {
    List<UUID> ids = List.of();

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryAdxPublicationQuery(DAY, ids));
  }

  @Test
  void shouldAcceptMaximumBatchSize() {
    var ids = ids(RecoveryAdxPublicationQuery.MAX_PUBLICATIONS);

    var query = new RecoveryAdxPublicationQuery(DAY, ids);

    assertTrue(
        query.parameters()
            .get("recoveryPublicationIds")
            .contains(ids.get(99).toString()));
  }

  @Test
  void shouldRejectOversizedBatch() {
    var ids = ids(RecoveryAdxPublicationQuery.MAX_PUBLICATIONS + 1);

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryAdxPublicationQuery(DAY, ids));
  }

  @Test
  void shouldRejectNullDay() {
    var ids = List.of(FIRST);

    assertThrows(
        NullPointerException.class,
        () -> new RecoveryAdxPublicationQuery(null, ids));
  }

  @Test
  void shouldRejectNullCollection() {
    assertThrows(
        NullPointerException.class,
        () -> new RecoveryAdxPublicationQuery(DAY, null));
  }

  @Test
  void shouldRejectNullPublicationId() {
    var ids = new ArrayList<UUID>();
    ids.add(null);

    assertThrows(
        NullPointerException.class,
        () -> new RecoveryAdxPublicationQuery(DAY, ids));
  }

  @Test
  void shouldExposeImmutableParameters() {
    var query =
        new RecoveryAdxPublicationQuery(DAY, List.of(FIRST));

    var parameters = query.parameters();

    assertThrows(
        UnsupportedOperationException.class,
        () -> parameters.put("recoveryDay", "another-day"));
  }

  private static List<UUID> ids(int count) {
    return LongStream.range(0, count)
        .mapToObj(value -> new UUID(0L, value))
        .toList();
  }
}