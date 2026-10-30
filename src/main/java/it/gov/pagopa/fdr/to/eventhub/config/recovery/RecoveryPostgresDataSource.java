package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;

public final class RecoveryPostgresDataSource implements AutoCloseable {

  private final HikariConfig configuration;
  private final Function<HikariConfig, HikariDataSource> poolFactory;

  private HikariDataSource pool;
  private boolean closed;

  public RecoveryPostgresDataSource(HikariConfig configuration) {
    this(configuration, HikariDataSource::new);
  }

  RecoveryPostgresDataSource(
      HikariConfig configuration,
      Function<HikariConfig, HikariDataSource> poolFactory) {

    this.configuration =
        Objects.requireNonNull(configuration, "configuration is required");
    this.poolFactory =
        Objects.requireNonNull(poolFactory, "poolFactory is required");
  }

  public synchronized DataSource dataSource() {
    if (closed) {
      throw new IllegalStateException(
          "Recovery PostgreSQL datasource has been closed");
    }

    if (pool == null) {
      pool =
          Objects.requireNonNull(
              poolFactory.apply(configuration),
              "poolFactory must return a datasource");
    }

    return pool;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }

    closed = true;

    if (pool != null) {
      pool.close();
    }
  }
}