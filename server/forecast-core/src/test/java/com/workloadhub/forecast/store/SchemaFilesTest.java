package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SchemaFilesTest {

    private static final Pattern TABLE = Pattern.compile("CREATE TABLE (?:task_service\\.)?(\\w+) \\((.*?)\\n\\);", Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile("^\\s{4}(\\w+) ", Pattern.MULTILINE);

    static Map<String, TreeSet<String>> columnsOf(String ddl) {
        Map<String, TreeSet<String>> out = new LinkedHashMap<>();
        Matcher m = TABLE.matcher(ddl);
        while (m.find()) {
            TreeSet<String> cols = new TreeSet<>();
            Matcher c = COLUMN.matcher(m.group(2));
            while (c.find()) {
                String name = c.group(1);
                if (!name.equals("CONSTRAINT") && !name.equals("PRIMARY") && !name.equals("UNIQUE")
                        && !name.equals("FOREIGN") && !name.equals("CHECK")) {
                    cols.add(name);
                }
            }
            out.put(m.group(1), cols);
        }
        return out;
    }

    static String resource(String name) throws IOException {
        try (var in = SchemaFilesTest.class.getResourceAsStream("/schema/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void sqliteTranslationHasTheSameTablesAndColumnsAsThePostgresqlDump() throws IOException {
        Map<String, TreeSet<String>> pg = columnsOf(resource("workloadhub-postgresql.sql"));
        Map<String, TreeSet<String>> lite = columnsOf(resource("workloadhub-sqlite.sql"));
        assertEquals(24, pg.size());
        assertEquals(pg, lite);
    }

    @Test
    void tableOrderCoversEveryTableOnce() throws IOException {
        Map<String, TreeSet<String>> pg = columnsOf(resource("workloadhub-postgresql.sql"));
        assertEquals(new TreeSet<>(pg.keySet()), new TreeSet<>(WorkloadHubSchema.TABLE_ORDER));
        assertEquals(pg.size(), WorkloadHubSchema.TABLE_ORDER.size());
    }

    @Test
    void booleanColumnsExistInTheDump() throws IOException {
        Map<String, TreeSet<String>> pg = columnsOf(resource("workloadhub-postgresql.sql"));
        WorkloadHubSchema.BOOLEAN_COLUMNS.forEach((table, cols) ->
                assertTrue(pg.get(table).containsAll(cols), table + " " + cols));
    }

    @Test
    void createsAllTablesOnSqlite() throws SQLException {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        assertEquals(24, countTables(ds));
    }

    @Test
    void createsAllTablesOnPostgresql() throws SQLException {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        assertEquals(24, countTables(ds));
    }

    static int countTables(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            int n = 0;
            try (ResultSet rs = md.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if (schema == null || schema.equals("task_service") || schema.equals("main")) {
                        n++;
                    }
                }
            }
            return n;
        }
    }
}
