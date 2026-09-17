package com.workloadhub.forecast.data;

import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Writes an envelope as one PostgreSQL transaction of INSERT statements, for `psql -f`. */
public final class SqlExportWriter {

    private static final int ROWS_PER_STATEMENT = 200;

    private SqlExportWriter() {
    }

    /**
     * The guard a partial script opens with: the seed deletes the work tables, and {@code task_comments} and
     * {@code task_attachments} reference them but are user content the seed neither writes nor deletes. A
     * database that still holds them would fail the delete with a foreign-key error naming nothing a reader
     * can act on, so the script says it plainly first, before anything is touched.
     */
    static final String USER_CONTENT_GUARD = "DO $$ BEGIN\n"
            + "  IF EXISTS (SELECT 1 FROM task_comments) OR EXISTS (SELECT 1 FROM task_attachments) THEN\n"
            + "    RAISE EXCEPTION 'task_comments or task_attachments is not empty: the seed never deletes"
            + " user content; empty them first';\n"
            + "  END IF;\n"
            + "END $$;\n";

    public static void write(ExportEnvelope env, Writer out) throws IOException {
        out.write("BEGIN;\nSET search_path TO task_service;\n");
        // partial: the envelope declares it, by excluding a table it does not carry (a real-mode seed), so it
        // lands into a database that already holds the rest — delete the seeded work tables child-first and
        // upsert the shared one. Read the declaration rather than the data keys: what an envelope is
        // responsible for is what it says it is, not what its rows happen to cover, and a full export whose
        // last tables are empty is not partial. refresh_tokens is never exported, so it never counts.
        boolean partial = env.excludedTables().stream()
                .anyMatch(t -> !t.equals("refresh_tokens") && WorkloadHubSchema.TABLE_ORDER.contains(t));
        if (partial) {
            out.write(USER_CONTENT_GUARD);
            List<String> reverse = new ArrayList<>(WorkloadHubSchema.TABLE_ORDER);
            Collections.reverse(reverse);
            for (String table : reverse) {
                if (env.data().containsKey(table) && !table.equals(UPSERTED)) {
                    out.write("DELETE FROM " + table + ";\n");
                }
            }
        }
        for (String table : WorkloadHubSchema.TABLE_ORDER) {
            List<LinkedHashMap<String, Object>> rows = WorkloadHubSchema.parentsFirst(table, env.rows(table));
            if (rows.isEmpty()) {
                continue;
            }
            List<String> columns = WorkloadHubSchema.columnsOf(rows);
            String upsert = partial && table.equals(UPSERTED) ? onConflict(columns) : "";
            for (int start = 0; start < rows.size(); start += ROWS_PER_STATEMENT) {
                out.write("INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES\n");
                int end = Math.min(rows.size(), start + ROWS_PER_STATEMENT);
                for (int i = start; i < end; i++) {
                    LinkedHashMap<String, Object> row = rows.get(i);
                    StringBuilder sb = new StringBuilder("(");
                    for (int c = 0; c < columns.size(); c++) {
                        sb.append(c == 0 ? "" : ", ").append(literal(row.get(columns.get(c))));
                    }
                    sb.append(i == end - 1 ? ")" + upsert + ";\n" : "),\n");
                    out.write(sb.toString());
                }
            }
        }
        out.write("COMMIT;\n");
    }

    /** The one table a real-mode seed shares with the application: existing rows are updated, new ones inserted. */
    static final String UPSERTED = "projects";

    static String onConflict(List<String> columns) {
        StringBuilder sb = new StringBuilder("\nON CONFLICT (id) DO UPDATE SET ");
        boolean first = true;
        for (String c : columns) {
            if (c.equals("id")) {
                continue;
            }
            sb.append(first ? "" : ", ").append(c).append(" = EXCLUDED.").append(c);
            first = false;
        }
        return sb.toString();
    }

    static String literal(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }
}
