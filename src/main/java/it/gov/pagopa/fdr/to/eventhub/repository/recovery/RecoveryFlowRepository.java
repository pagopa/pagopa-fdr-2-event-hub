package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

public final class RecoveryFlowRepository {

  private static final int MAX_PAGE_SIZE = 1000;

  private static final String FIND_PAGE_SQL = """
      SELECT DISTINCT
          f.id,
          f.name,
          f.revision,
          f.psp_domain_id,
          f.org_domain_id
      FROM fdr3.payment_full_view p
      INNER JOIN fdr3.flow f ON p.flow_id = f.id
      WHERE p.pay_date >= ?
        AND p.pay_date < ?
        AND f.id > ?
      ORDER BY f.id
      LIMIT ?
      """;

  private final DataSource dataSource;
  private final int queryTimeoutSeconds;

  public RecoveryFlowRepository(
      DataSource dataSource, int queryTimeoutSeconds) {

    this.dataSource =
        Objects.requireNonNull(dataSource, "dataSource is required");

    if (queryTimeoutSeconds <= 0) {
      throw new IllegalArgumentException(
          "queryTimeoutSeconds must be positive");
    }

    this.queryTimeoutSeconds = queryTimeoutSeconds;
  }

  public List<RecoveryFlowCandidate> findPage(
      LocalDate paymentDay,
      long afterFlowId,
      int pageSize) throws SQLException {

    Objects.requireNonNull(paymentDay, "paymentDay is required");

    if (afterFlowId < 0) {
      throw new IllegalArgumentException("afterFlowId must not be negative");
    }

    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and " + MAX_PAGE_SIZE);
    }

    LocalDateTime from = paymentDay.atStartOfDay();
    LocalDateTime to = paymentDay.plusDays(1).atStartOfDay();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(FIND_PAGE_SQL)) {

      statement.setQueryTimeout(queryTimeoutSeconds);
      statement.setObject(1, from);
      statement.setObject(2, to);
      statement.setLong(3, afterFlowId);
      statement.setInt(4, pageSize);

      try (ResultSet resultSet = statement.executeQuery()) {
        List<RecoveryFlowCandidate> candidates = new ArrayList<>();

        while (resultSet.next()) {
          long flowId = requiredLong(resultSet, "id");
          long revision = requiredLong(resultSet, "revision");

          candidates.add(
              new RecoveryFlowCandidate(
                  flowId,
                  resultSet.getString("name"),
                  revision,
                  resultSet.getString("psp_domain_id"),
                  resultSet.getString("org_domain_id")));
        }

        return List.copyOf(candidates);
      }
    }
  }

  private static long requiredLong(
      ResultSet resultSet, String column) throws SQLException {

    Long value = resultSet.getObject(column, Long.class);

    if (value == null) {
      throw new SQLException("Missing required value: " + column);
    }

    return value;
  }
}