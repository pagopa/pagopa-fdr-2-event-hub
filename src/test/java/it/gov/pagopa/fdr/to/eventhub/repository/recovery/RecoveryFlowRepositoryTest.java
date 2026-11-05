package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryFlowRepositoryTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

  private final DataSource dataSource = mock(DataSource.class);
  private final Connection connection = mock(Connection.class);
  private final PreparedStatement statement = mock(PreparedStatement.class);
  private final ResultSet resultSet = mock(ResultSet.class);

  private final RecoveryFlowRepository repository =
      new RecoveryFlowRepository(dataSource, 60);

  @BeforeEach
  void setUp() throws SQLException {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
  }

  @Test
  void shouldMapCandidatesAndBuildBlobName() throws SQLException {
    when(resultSet.next()).thenReturn(true, true, false);

    when(resultSet.getObject("id", Long.class))
        .thenReturn(68L, 74L);
    when(resultSet.getObject("revision", Long.class))
        .thenReturn(1L, 2L);
    when(resultSet.getString("name"))
        .thenReturn("2026-10-02-FLOW-A", "2026-10-02-FLOW-B");
    when(resultSet.getString("psp_domain_id"))
        .thenReturn("PSP-A", "PSP-B");
    when(resultSet.getString("org_domain_id"))
        .thenReturn("DOMAIN-A", "DOMAIN-B");

    List<RecoveryFlowCandidate> result =
        repository.findPage(DAY, 0, 100);

    assertEquals(
        List.of(
            new RecoveryFlowCandidate(
                68L, "2026-10-02-FLOW-A", 1L, "PSP-A", "DOMAIN-A"),
            new RecoveryFlowCandidate(
                74L, "2026-10-02-FLOW-B", 2L, "PSP-B", "DOMAIN-B")),
        result);

    assertEquals(
        "2026-10-02-FLOW-A_PSP-A_1.json.zip",
        result.get(0).blobName());

    verify(resultSet).close();
    verify(statement).close();
    verify(connection).close();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "2026-10-01",
      "2026-10-31",
      "2026-03-29",
      "2026-10-25"
  })
  void shouldBindCalendarDayWithoutTimezoneShift(String value)
      throws SQLException {

    LocalDate day = LocalDate.parse(value);

    repository.findPage(day, 74L, 250);

    verify(statement).setObject(1, day.atStartOfDay());
    verify(statement).setObject(2, day.plusDays(1).atStartOfDay());
    verify(statement).setLong(3, 74L);
    verify(statement).setInt(4, 250);
    verify(statement).setQueryTimeout(60);
  }

  @Test
  void shouldReturnEmptyPageWhenNoCandidatesExist() throws SQLException {
    when(resultSet.next()).thenReturn(false);

    assertTrue(repository.findPage(DAY, 0, 100).isEmpty());

    verify(resultSet).close();
    verify(statement).close();
    verify(connection).close();
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1001})
  void shouldRejectInvalidPageSizeBeforeAccessingDatabase(int pageSize) {
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findPage(DAY, 0, pageSize));

    verifyNoInteractions(dataSource);
  }

  @Test
  void shouldRejectNegativeCursorBeforeAccessingDatabase() {
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.findPage(DAY, -1, 100));

    verifyNoInteractions(dataSource);
  }

  @Test
  void shouldRejectNullDayBeforeAccessingDatabase() {
    assertThrows(
        NullPointerException.class,
        () -> repository.findPage(null, 0, 100));

    verifyNoInteractions(dataSource);
  }

  @Test
  void shouldPropagateQueryFailureAndCloseResources() throws SQLException {
    SQLException failure = new SQLException("Simulated query failure");
    when(statement.executeQuery()).thenThrow(failure);

    assertSame(
        failure,
        assertThrows(
            SQLException.class,
            () -> repository.findPage(DAY, 0, 100)));

    verify(statement).close();
    verify(connection).close();
  }

  @Test
  void shouldRejectMissingRevisionInsteadOfUsingZero() throws SQLException {
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getObject("id", Long.class)).thenReturn(68L);
    when(resultSet.getObject("revision", Long.class)).thenReturn(null);

    SQLException failure =
        assertThrows(
            SQLException.class,
            () -> repository.findPage(DAY, 0, 100));

    assertEquals("Missing required value: revision", failure.getMessage());

    verify(resultSet).close();
    verify(statement).close();
    verify(connection).close();
  }
}