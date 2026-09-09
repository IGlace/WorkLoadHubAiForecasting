package com.workloadhub.forecast.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/** Facts about the WorkloadHub tables the module reads: load order, boolean columns, DDL. */
public final class WorkloadHubSchema {

    /** Parents before children, so an import can insert in this order. */
    public static final List<String> TABLE_ORDER = List.of(
            "user_roles", "job_titles", "users", "teams", "team_members", "job_title_role_mappings",
            "role_change_requests", "task_statuses", "task_types", "projects", "project_history",
            "tasks", "task_history", "task_comments", "task_attachments", "time_logs", "absences",
            "personal_leaves", "holidays", "user_capacity", "team_capacity", "notifications",
            "sync_metadata", "refresh_tokens");

    /** Columns that are boolean in PostgreSQL and integer 0/1 on SQLite. */
    public static final Map<String, Set<String>> BOOLEAN_COLUMNS = Map.of(
            "holidays", Set.of("active"),
            "notifications", Set.of("is_read"),
            "projects", Set.of("archived"),
            "sync_metadata", Set.of("sync_in_progress"),
            "task_statuses", Set.of("active"),
            "task_types", Set.of("active"),
            "tasks", Set.of("archived", "reopened_from_done"),
            "teams", Set.of("active"),
            "user_roles", Set.of("active"),
            "users", Set.of("active"));

    private WorkloadHubSchema() {
    }

    public static boolean isBoolean(String table, String column) {
        return BOOLEAN_COLUMNS.getOrDefault(table, Set.of()).contains(column);
    }

    /** Creates the 24 tables on an empty SQLite database. */
    public static void createSqlite(DataSource dataSource) {
        runScript(dataSource, "/schema/workloadhub-sqlite.sql");
    }

    /** Creates schema task_service and the 24 tables on an empty PostgreSQL database. */
    public static void createPostgresql(DataSource dataSource) {
        runScript(dataSource, "/schema/workloadhub-postgresql.sql");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("SET search_path TO task_service, public");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot set search_path", e);
        }
    }

    static void runScript(DataSource dataSource, String resource) {
        String sql = readResource(resource);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String statement : sql.split(";\\s*\\n")) {
                String trimmed = stripComments(statement).trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Schema script failed: " + resource, e);
        }
    }

    static String stripComments(String statement) {
        StringBuilder out = new StringBuilder();
        for (String line : statement.split("\n")) {
            if (!line.trim().startsWith("--")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    static String readResource(String resource) {
        try (InputStream in = WorkloadHubSchema.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }
}
