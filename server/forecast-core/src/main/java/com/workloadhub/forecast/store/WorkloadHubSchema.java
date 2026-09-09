package com.workloadhub.forecast.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * Table to the column that self-references a row of the same table. None of these are DEFERRABLE
     * in the DDL, so PostgreSQL checks them at the end of each statement: a row referencing a parent
     * written in a later batch or statement fails. {@link #parentsFirst} reorders around this.
     */
    public static final Map<String, String> SELF_REFERENCES = Map.of(
            "users", "manager_id",
            "teams", "parent_team_id",
            "tasks", "parent_task_id",
            "task_comments", "parent_comment_id",
            "task_types", "subtask_type_id");

    private WorkloadHubSchema() {
    }

    /**
     * Reorders a self-referencing table's rows so every row's parent (by {@link #SELF_REFERENCES}'s
     * column for that table) comes before it, when that parent is present in {@code rows}; rows with no
     * such column, an absent parent, or no dependency on one another keep their original relative order.
     * A cycle among self-references cannot be topologically ordered; it is broken (not looped forever) by
     * placing the first row of the cycle encountered without waiting on the rest of the cycle.
     */
    public static List<LinkedHashMap<String, Object>> parentsFirst(String table, List<LinkedHashMap<String, Object>> rows) {
        String column = SELF_REFERENCES.get(table);
        if (column == null || rows.size() < 2) {
            return rows;
        }
        Map<Object, LinkedHashMap<String, Object>> byId = new HashMap<>();
        for (LinkedHashMap<String, Object> r : rows) {
            byId.put(r.get("id"), r);
        }
        List<LinkedHashMap<String, Object>> out = new ArrayList<>(rows.size());
        Set<Object> placed = new HashSet<>();
        for (LinkedHashMap<String, Object> r : rows) {
            placeAncestorsFirst(r, column, byId, out, placed);
        }
        if (out.size() != rows.size()) {
            throw new IllegalStateException(table + ": " + (rows.size() - out.size())
                    + " row(s) with a missing or duplicate id were dropped while ordering parents first");
        }
        return out;
    }

    /**
     * Walks {@code row}'s chain of self-referenced parents (stopping at one already placed, one absent
     * from {@code byId}, or one already seen in this walk, which marks a cycle), then places the chain
     * from its oldest unplaced ancestor down to {@code row} itself, each row placed at most once.
     */
    private static void placeAncestorsFirst(LinkedHashMap<String, Object> row, String column,
            Map<Object, LinkedHashMap<String, Object>> byId, List<LinkedHashMap<String, Object>> out, Set<Object> placed) {
        List<LinkedHashMap<String, Object>> chain = new ArrayList<>();
        Set<Object> onChain = new HashSet<>();
        LinkedHashMap<String, Object> current = row;
        while (current != null && !placed.contains(current.get("id")) && onChain.add(current.get("id"))) {
            chain.add(current);
            Object parentId = current.get(column);
            current = parentId == null ? null : byId.get(parentId);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            LinkedHashMap<String, Object> r = chain.get(i);
            if (placed.add(r.get("id"))) {
                out.add(r);
            }
        }
    }

    public static boolean isBoolean(String table, String column) {
        return BOOLEAN_COLUMNS.getOrDefault(table, Set.of()).contains(column);
    }

    /**
     * The ordered union of every row's keys, in first-seen order across the whole list. Rows of the same
     * table can carry different keys (an existing row kept as-is versus one this module builds fresh), so
     * taking the columns of one row alone (row 0, say) can silently drop a column only a later row has.
     */
    public static List<String> columnsOf(List<LinkedHashMap<String, Object>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        for (LinkedHashMap<String, Object> row : rows) {
            columns.addAll(row.keySet());
        }
        return new ArrayList<>(columns);
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
