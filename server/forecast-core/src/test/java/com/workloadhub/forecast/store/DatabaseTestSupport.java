package com.workloadhub.forecast.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The one PostgreSQL container of this JVM and a fresh database on it per call. There is no other engine:
 * without a reachable Docker or podman the gate fails, it never skips (spec 2026-09-17, ruling G).
 */
public final class DatabaseTestSupport {

    /** The same major as the host's database and the schema dump; scripts/postgres.sh reads the same tag. */
    static final String IMAGE = "postgres:18-alpine";

    private static PostgreSQLContainer<?> postgres;

    private DatabaseTestSupport() {
    }

    /** A new, empty database on the shared container. */
    public static synchronized DataSource postgres() {
        if (postgres == null) {
            boolean reachable;
            try {
                reachable = DockerClientFactory.instance().isDockerAvailable();
            } catch (Throwable t) {
                reachable = false;
            }
            if (!reachable) {
                throw new IllegalStateException("no container engine is reachable: the gate needs Docker or podman"
                        + " (server/README.md, \"The development container\")");
            }
            postgres = new PostgreSQLContainer<>(IMAGE);
            postgres.start();
        }
        String db = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + db);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1"));
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        ds.setCurrentSchema("task_service,public");
        return ds;
    }

    /** The WorkloadHub schema, written by `experiment.sh fixture` and read from the test classpath. */
    public static final String SCHEMA_SQL = "/fixtures/workloadhub-schema.sql";

    /** The seeded rows, written by `experiment.sh fixture` and read from the test classpath. */
    public static final String SEEDED_ROWS_SQL = "/fixtures/seeded-rows.sql";

    /**
     * Runs a whole SQL resource in one statement; the driver splits it on semicolons and honours BEGIN and
     * COMMIT inside it. The resource is read from the classpath, not from the working directory, so a module
     * that reuses this class through forecast-core's test-jar finds it too.
     */
    public static void runScript(DataSource ds, String resource) {
        String sql;
        try (InputStream in = DatabaseTestSupport.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("not on the classpath: " + resource);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("script failed: " + resource + ": " + e.getMessage(), e);
        }
    }

    /** A new database holding schema task_service and the 24 WorkloadHub tables, from the committed schema file. */
    public static DataSource postgresWithSchema() {
        DataSource ds = postgres();
        runScript(ds, SCHEMA_SQL);
        return ds;
    }

    /** A new database with the WorkloadHub tables and the module's own tables. */
    public static DataSource postgresMigrated() {
        DataSource ds = postgresWithSchema();
        ForecastMigrations.run(ds);
        return ds;
    }
}
