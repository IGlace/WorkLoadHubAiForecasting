package com.workloadhub.forecast.data;

import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;

/** Reads every WorkloadHub table back into an envelope with JSON-shaped values. */
public final class ExportExporter {

    private final DataSource dataSource;
    private final Dialect dialect;

    public ExportExporter(DataSource dataSource) {
        this.dataSource = dataSource;
        this.dialect = Dialect.of(dataSource);
    }

    public ExportEnvelope exportAll() {
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String table : WorkloadHubSchema.TABLE_ORDER) {
                data.put(table, readTable(st, table));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Export failed: " + e.getMessage(), e);
        }
        String db = dialect == Dialect.SQLITE ? "sqlite" : "postgresql";
        return new ExportEnvelope(db, "task_service", LocalDateTime.now().withNano(0).toString(),
                List.of(), data);
    }

    private List<LinkedHashMap<String, Object>> readTable(Statement st, String table) throws SQLException {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    String col = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                    row.put(col, jsonValue(table, col, rs.getObject(i)));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    Object jsonValue(String table, String column, Object v) {
        if (v == null) {
            return null;
        }
        if (WorkloadHubSchema.isBoolean(table, column)) {
            return dialect.asBoolean(v);
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (v instanceof UUID u) {
            return u.toString();
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            return ((Number) v).longValue();
        }
        if (v instanceof Number num) {
            double d = num.doubleValue();
            return d;
        }
        return v.toString();
    }
}
