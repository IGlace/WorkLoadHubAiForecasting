package com.workloadhub.forecast.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/** The two database engines the module runs on, and the few places their SQL differs. */
public enum Dialect {
    POSTGRESQL,
    SQLITE;

    public static Dialect of(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (product.contains("sqlite")) {
                return SQLITE;
            }
            if (product.contains("postgres")) {
                return POSTGRESQL;
            }
            throw new IllegalStateException("Unsupported database: " + product);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read database metadata", e);
        }
    }

    /** The value to bind for a boolean column. */
    public Object bool(boolean value) {
        return this == SQLITE ? (value ? 1 : 0) : Boolean.valueOf(value);
    }

    /** Reads a boolean column value as returned by the driver. */
    public boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("t") || s.equals("true");
    }

    /**
     * The placeholder for one column in an INSERT. PostgreSQL needs a cast for values bound as
     * strings into uuid, date, time and timestamp columns; SQLite stores them as text.
     */
    public String placeholder(String columnTypeName) {
        if (this == SQLITE) {
            return "?";
        }
        String t = columnTypeName.toLowerCase(Locale.ROOT);
        if (t.equals("uuid") || t.startsWith("timestamp") || t.equals("date") || t.startsWith("time")) {
            return "CAST(? AS " + (t.startsWith("timestamp") ? "timestamp" : t.startsWith("time") ? "time" : t) + ")";
        }
        return "?";
    }

    public String flywayLocation() {
        return "classpath:db/forecast/" + name().toLowerCase(Locale.ROOT);
    }
}
