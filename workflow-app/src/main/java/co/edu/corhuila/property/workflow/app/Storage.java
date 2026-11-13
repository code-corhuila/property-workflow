package co.edu.corhuila.property.workflow.app;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The connection pool to the schema workflow, built here explicitly so its limits
 * are read in review instead of hidden in defaults (Annex C, numeral 5.3.10).
 * The in-memory adapters are used only when PERSISTENCE=memory is set explicitly,
 * for tests and local development: never by default (norm 5.5.2).
 */
final class Storage implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private Storage(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = dataSource == null ? null : new JdbcTemplate(dataSource);
        this.tx = dataSource == null ? null : new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    static Storage connect(String persistence, String url, String user, String password, int poolMax,
                           int statementTimeoutMs) {
        if ("memory".equals(persistence)) {
            return new Storage(null);
        }
        if (!"postgres".equals(persistence)) {
            throw new IllegalStateException("PERSISTENCE must be postgres or memory, not '" + persistence + "'");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is required: the workflow stores the state of every Saga in the schema "
                    + "workflow. PERSISTENCE=memory exists only for tests and local development");
        }
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        pool.setUsername(user);
        pool.setPassword(password);
        pool.setMaximumPoolSize(poolMax);                            // sized against max_connections
        pool.setConnectionTimeout(Duration.ofSeconds(5).toMillis()); // wait for a free connection
        pool.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        pool.setConnectionInitSql("SET statement_timeout = " + statementTimeoutMs);
        return new Storage(new HikariDataSource(pool));
    }

    boolean inMemory() {
        return dataSource == null;
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    TransactionTemplate tx() {
        return tx;
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
