package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecoveryPostgresDataSourceTest {

  private final HikariConfig configuration = new HikariConfig();
  private final HikariDataSource pool = mock(HikariDataSource.class);

  @SuppressWarnings("unchecked")
  private final Function<HikariConfig, HikariDataSource> poolFactory =
      mock(Function.class);

  private final RecoveryPostgresDataSource resources =
      new RecoveryPostgresDataSource(configuration, poolFactory);

  @BeforeEach
  void setUp() {
    when(poolFactory.apply(configuration)).thenReturn(pool);
  }

  @AfterEach
  void tearDown() {
    resources.close();
  }

  @Test
  void shouldNotCreatePoolDuringConstruction() {
    verifyNoInteractions(poolFactory, pool);
  }

  @Test
  void shouldCreatePoolOnceAndReuseIt() {
    DataSource first = resources.dataSource();
    DataSource second = resources.dataSource();

    assertSame(pool, first);
    assertSame(first, second);

    verify(poolFactory, times(1)).apply(configuration);
  }

  @Test
  void shouldCloseWithoutCreatingUnusedPool() {
    resources.close();

    verifyNoInteractions(poolFactory, pool);
  }

  @Test
  void shouldCloseExistingPoolOnlyOnce() {
    resources.dataSource();

    resources.close();
    resources.close();

    verify(pool, times(1)).close();
  }

  @Test
  void shouldRejectAccessAfterClose() {
    resources.close();

    assertThrows(
        IllegalStateException.class,
        resources::dataSource);

    verifyNoInteractions(poolFactory, pool);
  }

  @Test
  void shouldAllowNewInitializationAttemptAfterFailure() {
    IllegalStateException failure =
        new IllegalStateException("Simulated pool initialization failure");

    when(poolFactory.apply(configuration))
        .thenThrow(failure)
        .thenReturn(pool);

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            resources::dataSource));

    assertSame(pool, resources.dataSource());

    verify(poolFactory, times(2)).apply(configuration);
  }

  @Test
  void shouldCreateOnlyOnePoolForConcurrentAccess() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(4);

    try {
      List<Callable<DataSource>> tasks = new ArrayList<>();

      for (int i = 0; i < 20; i++) {
        tasks.add(resources::dataSource);
      }

      List<Future<DataSource>> results = executor.invokeAll(tasks);

      for (Future<DataSource> result : results) {
        assertSame(pool, result.get());
      }

      verify(poolFactory, times(1)).apply(configuration);
    } finally {
      executor.shutdownNow();
    }
  }
}