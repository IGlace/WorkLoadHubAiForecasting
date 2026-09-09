package com.workloadhub.forecast.store;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlite.SQLiteDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Data sources for tests: a fresh in-memory SQLite, and PostgreSQL in Docker when available. */
public final class DatabaseTestSupport {

    private static PostgreSQLContainer<?> postgres;

    private DatabaseTestSupport() {
    }

    /** A new, empty, private in-memory SQLite database (shared-cache URL so pooled connections see it). */
    public static DataSource sqliteInMemory() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:file:" + UUID.randomUUID() + "?mode=memory&cache=shared");
        return keepAlive(ds);
    }

    /** SQLite drops a memory database when its last connection closes; hold one open for the test's life. */
    private static DataSource keepAlive(SQLiteDataSource ds) {
        try {
            java.sql.Connection anchor = ds.getConnection();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    anchor.close();
                } catch (Exception ignored) {
                    // shutting down
                }
            }));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return ds;
    }

    /** A PostgreSQL 16 container with a fresh database per call, or an assumption failure that skips the test. */
    public static synchronized DataSource postgresOrSkip() {
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            docker = false;
        }
        Assumptions.assumeTrue(docker, "Docker is not reachable: PostgreSQL tests skipped");
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
        }
        String db = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE " + db);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1"));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        ds.setCurrentSchema("task_service,public");
        return ds;
    }
}
