package com.workloadhub.forecast.data;

import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

/** Inserts an export's rows table by table, parents first, through plain JDBC on either engine. */
public final class ExportImporter {

    private final DataSource dataSource;
    private final Dialect dialect;

    public ExportImporter(DataSource dataSource) {
        this.dataSource = dataSource;
        this.dialect = Dialect.of(dataSource);
    }

    /** Column name to database type name, in table order, from the driver's metadata. */
    static LinkedHashMap<String, String> columns(Connection c, String table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        try (ResultSet rs = md.getColumns(null, null, table, "%")) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && !schema.equals("task_service") && !schema.equals("main")) {
                    continue;
                }
                out.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getString("TYPE_NAME"));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Table not found: " + table);
        }
        return out;
    }

    /** Inserts every table of the envelope that exists in the schema; with replace, deletes children then parents first. */
    public Map<String, Integer> importAll(ExportEnvelope envelope, boolean replace) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            if (replace) {
                List<String> reverse = new ArrayList<>(WorkloadHubSchema.TABLE_ORDER);
                java.util.Collections.reverse(reverse);
                try (Statement st = c.createStatement()) {
                    for (String table : reverse) {
                        st.execute("DELETE FROM " + table);
                    }
                }
            }
            for (String table : WorkloadHubSchema.TABLE_ORDER) {
                List<LinkedHashMap<String, Object>> rows = envelope.rows(table);
                counts.put(table, rows.isEmpty() ? 0 : insert(c, table, rows));
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Import failed: " + e.getMessage(), e);
        }
        return counts;
    }

    private int insert(Connection c, String table, List<LinkedHashMap<String, Object>> rows) throws SQLException {
        LinkedHashMap<String, String> schemaColumns = columns(c, table);
        List<String> cols = new ArrayList<>();
        for (String col : schemaColumns.keySet()) {
            if (rows.stream().anyMatch(r -> r.containsKey(col))) {
                cols.add(col);
            }
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (")
                .append(String.join(", ", cols)).append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(dialect.placeholder(schemaColumns.get(cols.get(i))));
        }
        sql.append(")");
        int n = 0;
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (LinkedHashMap<String, Object> row : rows) {
                for (int i = 0; i < cols.size(); i++) {
                    bind(ps, i + 1, table, cols.get(i), row.get(cols.get(i)));
                }
                ps.addBatch();
                n++;
                if (n % 500 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        return n;
    }

    private void bind(PreparedStatement ps, int index, String table, String column, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NULL);
        } else if (value instanceof Boolean b) {
            ps.setObject(index, dialect.bool(b));
        } else if (WorkloadHubSchema.isBoolean(table, column) && value instanceof Number num) {
            ps.setObject(index, dialect.bool(num.intValue() != 0));
        } else if (value instanceof Long l) {
            ps.setLong(index, l);
        } else if (value instanceof Double d) {
            ps.setDouble(index, d);
        } else if (value instanceof Number num) {
            ps.setDouble(index, num.doubleValue());
        } else {
            ps.setString(index, value.toString());
        }
    }
}
