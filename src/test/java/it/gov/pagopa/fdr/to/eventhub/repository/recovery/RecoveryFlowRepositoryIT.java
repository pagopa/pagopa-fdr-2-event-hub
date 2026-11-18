package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryPostgresConfiguration;
import it.gov.pagopa.fdr.to.eventhub.config.recovery.RecoveryPostgresDataSource;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "RECOVERY_POSTGRES_INTEGRATION_TESTS",
    matches = "true")
class RecoveryFlowRepositoryIT {

  private static final int PAGE_SIZE = 50;
  private static final int MAX_TEST_CANDIDATES = 1000;

  @Test
  void shouldConnectReadOnlyAndReadCandidatesFromDev() throws Exception {
    String configuredDay = System.getenv("RECOVERY_POSTGRES_TEST_DAY");

    assertNotNull(
        configuredDay,
        "RECOVERY_POSTGRES_TEST_DAY must contain the day to verify");

    LocalDate day = LocalDate.parse(configuredDay);

    try (RecoveryPostgresDataSource resources =
        new RecoveryPostgresDataSource(
            RecoveryPostgresConfiguration.fromEnvironment())) {

      DataSource dataSource = resources.dataSource();

      try (Connection connection = dataSource.getConnection()) {
        assertTrue(
            connection.isReadOnly(),
            "The recovery connection must be read-only");

        try (Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(10);

          try (ResultSet result =
              statement.executeQuery("SHOW transaction_read_only")) {

            assertTrue(result.next());
            assertTrue(
                "on".equalsIgnoreCase(result.getString(1)),
                "PostgreSQL must enforce a read-only transaction");
          }
        }
      }

      RecoveryFlowRepository repository =
          new RecoveryFlowRepository(dataSource, 60);

      List<RecoveryFlowCandidate> candidates =
          repository.findPage(day, 0L, 10);

      assertNotNull(candidates);
      assertTrue(candidates.size() <= 10);

      long previousFlowId = 0L;

      for (RecoveryFlowCandidate candidate : candidates) {
        assertTrue(
            candidate.flowId() > previousFlowId,
            "Candidates must have distinct, increasing flow IDs");

        previousFlowId = candidate.flowId();
      }

      System.out.printf(
          "DEV recovery discovery completed: day=%s, candidates=%d%n",
          day,
          candidates.size());
    }
  }

  @Test
  void shouldReadAllPublishedRevisionsWithoutDuplicatesFromDev()
      throws Exception {

    String configuredDay = System.getenv("RECOVERY_POSTGRES_TEST_DAY");
    assertNotNull(configuredDay);

    LocalDate day = LocalDate.parse(configuredDay);
    var configuration = RecoveryPostgresConfiguration.fromEnvironment();

    assertEquals(
        "jdbc:postgresql://fdr-db.d.internal.postgresql.pagopa.it:5432/fdr3",
        configuration.getJdbcUrl(),
        "This integration test must target the verified DEV database");

    try (RecoveryPostgresDataSource resources =
        new RecoveryPostgresDataSource(configuration)) {

      SelectionExpectation expected =
          readSelectionExpectation(resources.dataSource(), day);

      System.out.printf(
              "DEV reference selection: day=%s, published=%d, "
                  + "historical=%d, latest=%d, excluded=%d%n",
              day,
              expected.publishedIds().size(),
              expected.historicalCount(),
              expected.latestCount(),
              expected.excludedCount());

      assertTrue(
          expected.historicalCount() > 0,
          "The test day must contain historical published revisions");
      assertTrue(
          expected.latestCount() > 0,
          "The test day must contain latest published revisions");
      assertTrue(
          expected.excludedCount() > 0,
          "The test day must contain non-published candidates");

      RecoveryFlowRepository repository =
          new RecoveryFlowRepository(resources.dataSource(), 60);

      Set<Long> actualIds = new HashSet<>();
      long cursor = 0L;
      int nonEmptyPages = 0;
      boolean exhausted = false;

      for (int pageNumber = 0;
          pageNumber <= MAX_TEST_CANDIDATES / PAGE_SIZE;
          pageNumber++) {

        List<RecoveryFlowCandidate> page =
            repository.findPage(day, cursor, PAGE_SIZE);

        assertTrue(page.size() <= PAGE_SIZE);

        if (page.isEmpty()) {
          exhausted = true;
          break;
        }

        nonEmptyPages++;

        for (RecoveryFlowCandidate candidate : page) {
          assertTrue(
              candidate.flowId() > cursor,
              "Flow IDs must increase within and across pages");
          assertTrue(
              actualIds.add(candidate.flowId()),
              "A revision must not appear more than once");
          assertEquals(
                  expected.flowDates().get(candidate.flowId()),
                  candidate.flowDate(),
                  "Flow date differs for flowId=" + candidate.flowId());

          cursor = candidate.flowId();
        }

        assertTrue(
            actualIds.size() <= MAX_TEST_CANDIDATES,
            "The DEV fixture exceeds the integration test limit");
      }

      assertTrue(exhausted, "Pagination must reach an empty final page");
      assertEquals(expected.publishedIds(), actualIds);

      System.out.printf(
          "DEV published selection verified: day=%s, published=%d, "
              + "historical=%d, latest=%d, excluded=%d, pages=%d%n",
          day,
          actualIds.size(),
          expected.historicalCount(),
          expected.latestCount(),
          expected.excludedCount(),
          nonEmptyPages);
    }
  }

  private SelectionExpectation readSelectionExpectation(
      DataSource dataSource, LocalDate day) throws Exception {

    String sql = """
        SELECT f.id, f.status, f.is_latest, f.date AS flow_date
        FROM fdr3.flow f
        WHERE EXISTS (
            SELECT 1
            FROM fdr3.payment_full_view p
            WHERE p.flow_id = f.id
              AND p.pay_date >= ?
              AND p.pay_date < ?
        )
        ORDER BY f.id
        LIMIT ?
        """;

    Set<Long> publishedIds = new HashSet<>();
    Map<Long, LocalDateTime> flowDates = new HashMap<>();
    int historicalCount = 0;
    int latestCount = 0;
    int excludedCount = 0;
    int rows = 0;

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {

      assertTrue(connection.isReadOnly());

      statement.setQueryTimeout(30);
      statement.setObject(1, day.atStartOfDay());
      statement.setObject(2, day.plusDays(1).atStartOfDay());
      statement.setInt(3, MAX_TEST_CANDIDATES + 1);

      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          rows++;
          assertTrue(
              rows <= MAX_TEST_CANDIDATES,
              "Choose a DEV test day with at most 1000 candidate revisions");

          if ("PUBLISHED".equals(result.getString("status"))) {
              publishedIds.add(result.getLong("id"));

              flowDates.put(result.getLong("id"), result.getObject("flow_date", LocalDateTime.class));

              if (result.getBoolean("is_latest")) {
                  latestCount++;
              } else {
                  historicalCount++;
              }
          } else {
              excludedCount++;
          }
        }
      }
    }

    return new SelectionExpectation(
        Set.copyOf(publishedIds),
        historicalCount,
        latestCount,
        excludedCount,
        Map.copyOf(flowDates));
  }

  private record SelectionExpectation(Set<Long> publishedIds, int historicalCount, int latestCount, int excludedCount,
          Map<Long, LocalDateTime> flowDates) {
  }
}