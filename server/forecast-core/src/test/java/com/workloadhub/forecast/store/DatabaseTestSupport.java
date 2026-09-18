package com.workloadhub.forecast.store;

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

    /** A new database holding schema task_service and the 24 WorkloadHub tables, nothing else. */
    public static DataSource postgresWithSchema() {
        DataSource ds = postgres();
        WorkloadHubSchema.createPostgresql(ds);
        return ds;
    }

    /** A new database with the WorkloadHub tables and the module's own tables. */
    public static DataSource postgresMigrated() {
        DataSource ds = postgresWithSchema();
        ForecastMigrations.run(ds);
        return ds;
    }
}
