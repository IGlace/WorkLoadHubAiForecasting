package com.workloadhub.forecast.tools.testing;

import com.workloadhub.forecast.tools.export.WorkloadHubSchema;
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
 * A copy of forecast-core's test support, fixed to one engine: thirty lines are cheaper than a test-jar of core
 * published for one class. Keep the image tag equal to the other copy's.
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

    /** A new database holding schema task_service and the 24 WorkloadHub tables. */
    public static DataSource postgresWithSchema() {
        DataSource ds = postgres();
        WorkloadHubSchema.createPostgresql(ds);
        return ds;
    }
}
