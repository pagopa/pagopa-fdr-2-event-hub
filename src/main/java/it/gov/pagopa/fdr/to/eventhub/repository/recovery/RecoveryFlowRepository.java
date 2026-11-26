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
          WITH page AS MATERIALIZED (
              SELECT
                  k.flow_id,
                  k.flow_date
              FROM (
                  SELECT DISTINCT
                      p.flow_id,
                      p.flow_date
                  FROM fdr3.payment p
                  WHERE p.pay_date >= ?
                    AND p.pay_date < ?
                    AND p.flow_id > ?
              ) k
              WHERE EXISTS (
                  SELECT 1
                  FROM fdr3.flow fx
                  WHERE fx.id = k.flow_id
                    AND fx.date = k.flow_date
                  OFFSET 0
              )
              ORDER BY k.flow_id, k.flow_date
              LIMIT ?
          )
          SELECT
              f.id,
              f.name,
              f.revision,
              f.psp_domain_id,
              f.org_domain_id,
              f.flow_date
          FROM page p
          CROSS JOIN LATERAL (
              SELECT
                  fx.id,
                  fx.name,
                  fx.revision,
                  fx.psp_domain_id,
                  fx.org_domain_id,
                  fx.date AS flow_date
              FROM fdr3.flow fx
              WHERE fx.id = p.flow_id
                AND fx.date = p.flow_date
              OFFSET 0
          ) f
          ORDER BY f.id, f.flow_date
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
            LocalDateTime flowDate = resultSet.getObject("flow_date", LocalDateTime.class);

            if (flowDate == null) {
                throw new SQLException("Missing required value: flow_date");
            }

            candidates.add(new RecoveryFlowCandidate(flowId, resultSet.getString("name"), revision,
                    resultSet.getString("psp_domain_id"), resultSet.getString("org_domain_id"), flowDate));
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