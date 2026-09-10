package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ForecastMigrationsTest {

    static TreeSet<String> tables(DataSource ds) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        try (Connection c = ds.getConnection();
                ResultSet rs = c.getMetaData().getTables(null, null, "forecast_%", new String[] {"TABLE"})) {
            while (rs.next()) {
                out.add(rs.getString("TABLE_NAME"));
            }
        }
        return out;
    }

    static boolean hasColumn(DataSource ds, String table, String column) throws Exception {
        try (Connection c = ds.getConnection(); ResultSet rs = c.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    static void check(DataSource ds) throws Exception {
        ForecastMigrations.run(ds);
        ForecastMigrations.run(ds); // idempotent
        assertEquals(new TreeSet<>(java.util.List.of("forecast_current_days", "forecast_facts", "forecast_member_days", "forecast_member_windows",
                "forecast_narratives", "forecast_runs", "forecast_schema_history")), tables(ds));
        assertTrue(hasColumn(ds, "users", "github_token"));
        assertTrue(hasColumn(ds, "users", "github_token_updated_at"));
        assertTrue(hasColumn(ds, "forecast_narratives", "status"), "V2 recreated the narratives table");
        assertTrue(hasColumn(ds, "forecast_narratives", "raw_text"));
        assertTrue(hasColumn(ds, "forecast_narratives", "tool_calls"));
        assertTrue(hasColumn(ds, "forecast_member_windows", "demand_hrs"));
        assertTrue(hasColumn(ds, "forecast_current_days", "forecast_at"));
    }

    /** V1 alone first, then the whole set: the upgrade path a database that ran before the rolling horizon existed takes. */
    static void checkStepwise(DataSource ds) throws Exception {
        ForecastMigrations.run(ds, "1");
        assertTrue(hasColumn(ds, "forecast_narratives", "narrative_json"), "V1 created the narratives table");
        assertFalse(hasColumn(ds, "forecast_narratives", "status"), "V1 knows no status column");
        ForecastMigrations.run(ds, "2");
        assertTrue(hasColumn(ds, "forecast_narratives", "status"), "V2 applies on a database that already ran V1");
        assertTrue(tables(ds).contains("forecast_member_weeks"), "V2 still knows the member-week table");
        ForecastMigrations.run(ds);
        assertTrue(hasColumn(ds, "forecast_narratives", "tool_calls"));
        assertTrue(hasColumn(ds, "forecast_narratives", "raw_text"));
        assertFalse(tables(ds).contains("forecast_member_weeks"), "V3 dropped the member-week table");
        assertTrue(tables(ds).contains("forecast_current_days"));
    }

    @Test
    void v3AppliesOnADatabaseThatRanV1AndV2Sqlite() throws Exception {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        checkStepwise(ds);
    }

    @Test
    void v3AppliesOnADatabaseThatRanV1AndV2Postgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        checkStepwise(ds);
    }

    @Test
    void migratesSqlite() throws Exception {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        check(ds);
    }

    @Test
    void migratesPostgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        check(ds);
    }
}
