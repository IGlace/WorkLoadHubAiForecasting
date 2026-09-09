package com.workloadhub.forecast.data;

import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Writes an envelope as one PostgreSQL transaction of INSERT statements, for `psql -f`. */
public final class SqlExportWriter {

    private static final int ROWS_PER_STATEMENT = 200;

    private SqlExportWriter() {
    }

    public static void write(ExportEnvelope env, Writer out) throws IOException {
        out.write("BEGIN;\nSET search_path TO task_service;\n");
        for (String table : WorkloadHubSchema.TABLE_ORDER) {
            List<LinkedHashMap<String, Object>> rows = WorkloadHubSchema.parentsFirst(table, env.rows(table));
            if (rows.isEmpty()) {
                continue;
            }
            List<String> columns = new ArrayList<>(rows.get(0).keySet());
            for (int start = 0; start < rows.size(); start += ROWS_PER_STATEMENT) {
                out.write("INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES\n");
                int end = Math.min(rows.size(), start + ROWS_PER_STATEMENT);
                for (int i = start; i < end; i++) {
                    LinkedHashMap<String, Object> row = rows.get(i);
                    StringBuilder sb = new StringBuilder("(");
                    for (int c = 0; c < columns.size(); c++) {
                        sb.append(c == 0 ? "" : ", ").append(literal(row.get(columns.get(c))));
                    }
                    sb.append(i == end - 1 ? ");\n" : "),\n");
                    out.write(sb.toString());
                }
            }
        }
        out.write("COMMIT;\n");
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
