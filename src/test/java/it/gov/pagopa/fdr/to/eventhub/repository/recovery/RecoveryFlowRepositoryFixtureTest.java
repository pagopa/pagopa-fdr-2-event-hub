package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * SQL semantics tests against a dedicated, disposable PostgreSQL database on loopback.
 * Create database fdr_recovery_fixture for user fdr_fixture on localhost:55434 (trust auth).
 * Run with -Dtest=RecoveryFlowRepositoryFixtureTest -Dfdr.fixture.tests=true.
 * Override the local port with -Dfdr.fixture.port; the host and database cannot be overridden.
 */
@EnabledIfSystemProperty(named = "fdr.fixture.tests", matches = "true")
class RecoveryFlowRepositoryFixtureTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 22);
  private final PGSimpleDataSource dataSource = new PGSimpleDataSource();
  private RecoveryFlowRepository repository;
  private boolean schemaCreated;

  @BeforeEach
  void setUp() throws Exception {
    // This fixture must never connect to an application environment.
    dataSource.setServerNames(new String[] {"127.0.0.1"});
    dataSource.setPortNumbers(new int[] {Integer.getInteger("fdr.fixture.port", 55434)});
    dataSource.setDatabaseName("fdr_recovery_fixture");
    dataSource.setUser("fdr_fixture");
    dataSource.setPrepareThreshold(0);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      // Deliberately fail if the schema exists instead of deleting another fixture.
      statement.execute("CREATE SCHEMA fdr3");
      schemaCreated = true;
      statement.execute("""
          CREATE TABLE fdr3.flow (
              id bigint NOT NULL,
              date timestamp NOT NULL,
              name text NOT NULL,
              revision bigint NOT NULL,
              psp_domain_id text NOT NULL,
              org_domain_id text NOT NULL,
              status text NOT NULL,
              is_latest boolean NOT NULL,
              PRIMARY KEY (id, date)
          ) PARTITION BY RANGE (date)
          """);
      statement.execute("""
          CREATE TABLE fdr3.payment (
              flow_id bigint NOT NULL,
              flow_date timestamp NOT NULL,
              pay_date timestamp NOT NULL
          ) PARTITION BY RANGE (flow_date)
          """);
      for (String table : List.of("flow", "payment")) {
        statement.execute("CREATE TABLE fdr3." + table + "_july PARTITION OF fdr3."
            + table + " FOR VALUES FROM ('2026-07-01') TO ('2026-08-01')");
        statement.execute("CREATE TABLE fdr3." + table + "_november PARTITION OF fdr3."
            + table + " FOR VALUES FROM ('2026-11-01') TO ('2026-12-01')");
      }
      statement.execute("CREATE INDEX ON fdr3.payment (pay_date, flow_id, flow_date)");
      statement.execute("""
          INSERT INTO fdr3.flow VALUES
          (10, '2026-07-15', 'FLOW-A', 1, 'PSP', 'ORG', 'PUBLISHED', false),
          (20, '2026-11-01', 'FLOW-B', 2, 'PSP', 'ORG', 'PUBLISHED', true),
          (30, '2026-11-02', 'WRONG-DATE', 1, 'PSP', 'ORG', 'PUBLISHED', true),
          (40, '2026-11-03', 'NEXT-DAY', 1, 'PSP', 'ORG', 'PUBLISHED', true),
          (50, '2026-11-04', 'FLOW-C', 1, 'PSP', 'ORG', 'PUBLISHED', true),
          (60, '2026-11-05', 'FLOW-D', 1, 'PSP', 'ORG', 'PUBLISHED', true)
          """);
      statement.execute("""
          INSERT INTO fdr3.payment VALUES
          (1,  '2026-07-01', '2026-11-22'),
          (10, '2026-07-15', '2026-11-22'),
          (10, '2026-07-15', '2026-11-22 12:00:00'),
          (10, '2026-07-15', '2026-11-22 23:59:59.999999'),
          (15, '2026-11-01', '2026-11-22'),
          (20, '2026-11-01', '2026-11-22'),
          (20, '2026-11-06', '2026-11-22'),
          (30, '2026-11-07', '2026-11-22'),
          (40, '2026-11-03', '2026-11-23'),
          (50, '2026-11-04', '2026-11-22 18:00:00'),
          (60, '2026-11-05', '2026-11-21 23:59:59.999999')
          """);
    }
    repository = new RecoveryFlowRepository(dataSource, 10);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (schemaCreated) {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA fdr3 CASCADE");
      }
    }
  }

  @Test
  void shouldPageDistinctRevisionsAcrossPartitionsAndReachEnd() throws Exception {
    List<RecoveryFlowCandidate> first = repository.findPage(DAY, 0, 2);
    assertEquals(List.of(10L, 20L), ids(first));
    assertEquals(1L, first.get(0).revision());
    assertEquals(2L, first.get(1).revision());
    assertEquals(List.of(50L), ids(repository.findPage(DAY, 20, 2)));
    assertTrue(repository.findPage(DAY, 50, 2).isEmpty());
    assertEquals(List.of(10L, 20L, 50L), ids(repository.findPage(DAY, 0, 100)));
  }

  @Test
  void shouldExcludeOrphansBeforeApplyingPageLimit() throws Exception {
    assertEquals(List.of(10L, 20L), ids(repository.findPage(DAY, 0, 2)));
    assertEquals(List.of(20L, 50L), ids(repository.findPage(DAY, 10, 2)));
  }

  @Test
  void shouldMatchBothFlowIdAndExactFlowDate() throws Exception {
    List<RecoveryFlowCandidate> page = repository.findPage(DAY, 0, 100);
    assertEquals(List.of(10L, 20L, 50L), ids(page));
    assertEquals(LocalDateTime.parse("2026-07-15T00:00:00"), page.get(0).flowDate());
    assertEquals(LocalDateTime.parse("2026-11-01T00:00:00"), page.get(1).flowDate());
  }

  @Test
  void shouldUseInclusiveStartAndExclusiveEndOfPaymentDay() throws Exception {
    assertEquals(List.of(10L, 20L, 50L), ids(repository.findPage(DAY, 0, 100)));
    assertEquals(List.of(40L), ids(repository.findPage(DAY.plusDays(1), 0, 100)));
    assertEquals(List.of(60L), ids(repository.findPage(DAY.minusDays(1), 0, 100)));
  }

  @Test
  void shouldMatchIndependentReferenceSelectionAcrossAllPages() throws Exception {
    List<Long> expected = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.prepareStatement("""
            SELECT DISTINCT f.id
            FROM fdr3.payment p
            JOIN fdr3.flow f ON f.id = p.flow_id AND f.date = p.flow_date
            WHERE p.pay_date >= ? AND p.pay_date < ?
            ORDER BY f.id
            """)) {
      statement.setObject(1, DAY.atStartOfDay());
      statement.setObject(2, DAY.plusDays(1).atStartOfDay());
      try (var result = statement.executeQuery()) {
        while (result.next()) {
          expected.add(result.getLong(1));
        }
      }
    }
    List<Long> actual = new ArrayList<>();
    long cursor = 0;
    for (int i = 0; i < 10; i++) {
      List<RecoveryFlowCandidate> page = repository.findPage(DAY, cursor, 1);
      if (page.isEmpty()) {
        assertEquals(expected, actual);
        return;
      }
      assertEquals(1, page.size());
      assertTrue(page.get(0).flowId() > cursor);
      cursor = page.get(0).flowId();
      actual.add(cursor);
    }
    throw new AssertionError("Pagination did not finish");
  }

  @Test
  void shouldNotFilterByStatusOrLatestFlag() throws Exception {
    // A synthetic status deliberately outside the archive contract detects a reintroduced filter.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("UPDATE fdr3.flow SET status = 'SYNTHETIC' WHERE id = 20");
    }
    assertEquals(List.of(10L, 20L, 50L), ids(repository.findPage(DAY, 0, 100)));
  }

  private static List<Long> ids(List<RecoveryFlowCandidate> page) {
    return page.stream().map(RecoveryFlowCandidate::flowId).toList();
  }
}
