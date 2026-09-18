package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
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

    /** One migration creates the six module tables, the history table and the two users columns; running it twice is a no-op. */
    @Test
    void migratesAFreshDatabaseInOneStep() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        ForecastMigrations.run(ds);
        ForecastMigrations.run(ds);
        assertEquals(new TreeSet<>(List.of("forecast_current_days", "forecast_facts", "forecast_member_days", "forecast_member_windows",
                "forecast_narratives", "forecast_runs", "forecast_schema_history")), tables(ds));
        assertTrue(hasColumn(ds, "users", "github_token"));
        assertTrue(hasColumn(ds, "users", "github_token_updated_at"));
        assertTrue(hasColumn(ds, "forecast_runs", "mae"));
        assertFalse(hasColumn(ds, "forecast_runs", "champion_model"), "the tournament columns never existed in the final V1");
        assertTrue(hasColumn(ds, "forecast_narratives", "status"));
        assertTrue(hasColumn(ds, "forecast_narratives", "raw_text"));
        assertTrue(hasColumn(ds, "forecast_narratives", "tool_calls"));
        assertTrue(hasColumn(ds, "forecast_member_windows", "backlog_excess_hrs"));
        assertTrue(hasColumn(ds, "forecast_member_windows", "due_excess_hrs"));
        assertTrue(hasColumn(ds, "forecast_member_days", "working_day"));
        assertTrue(hasColumn(ds, "forecast_current_days", "forecast_at"));
        for (String table : List.of("forecast_member_windows", "forecast_member_days", "forecast_current_days")) {
            assertFalse(hasColumn(ds, table, "open_hrs"), table + " never had the open/new/planned split");
        }
        // type = 'SQL' excludes the baseline marker row, which Flyway also stamps with a non-null
        // version (0, from baselineVersion("0")); "WHERE version IS NOT NULL" alone would count it too.
        try (Connection c = ds.getConnection(); ResultSet rs = c.createStatement().executeQuery(
                "SELECT COUNT(*) FROM forecast_schema_history WHERE type = 'SQL'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "exactly one versioned migration applied");
        }
    }
}
