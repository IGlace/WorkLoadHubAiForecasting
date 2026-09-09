package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class RoundTripTest {

    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");

    /** Rows compared with numbers as doubles and timestamps parsed, so 16 and 16.0 or .54207 and .542070 are equal. */
    static Map<String, List<TreeMap<String, Object>>> canonical(ExportEnvelope env) {
        Map<String, List<TreeMap<String, Object>>> out = new TreeMap<>();
        env.data().forEach((table, rows) -> out.put(table, rows.stream().map(row -> {
            TreeMap<String, Object> m = new TreeMap<>();
            row.forEach((k, v) -> m.put(k, canonicalValue(v)));
            return m;
        }).sorted((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id")))).toList()));
        return out;
    }

    static Object canonicalValue(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && TIMESTAMP.matcher(s).matches()) {
            return LocalDateTime.parse(s);
        }
        return v;
    }

    static void roundTrip(DataSource ds) throws Exception {
        ExportEnvelope in = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        Map<String, Integer> counts = new ExportImporter(ds).importAll(in, true);
        assertEquals(2, counts.get("users"));
        assertEquals(1, counts.get("tasks"));
        ExportEnvelope out = new ExportExporter(ds).exportAll();
        assertEquals(WorkloadHubSchema.TABLE_ORDER.size(), out.data().size());
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> onlyFixtureTables = new LinkedHashMap<>();
        in.data().keySet().forEach(t -> onlyFixtureTables.put(t, out.rows(t)));
        assertEquals(canonical(in), canonical(out.withData(onlyFixtureTables)));
        // importing again with replace=true leaves the same row counts
        assertEquals(counts, new ExportImporter(ds).importAll(in, true));
    }

    @Test
    void roundTripsOnSqlite() throws Exception {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        roundTrip(ds);
    }

    @Test
    void roundTripsOnPostgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        roundTrip(ds);
    }

    @Test
    void additiveImportKeepsExistingRowsAndFailsOnDuplicates() throws Exception {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        ExportEnvelope in = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));

        // importing into an empty schema with replace=false behaves like a replacing import
        Map<String, Integer> counts = new ExportImporter(ds).importAll(in, false);
        assertEquals(2, counts.get("users"));
        assertEquals(1, counts.get("tasks"));

        // importing the same rows again without replace hits primary-key conflicts and rolls back
        assertThrows(IllegalStateException.class, () -> new ExportImporter(ds).importAll(in, false));

        // the failed import left no partial rows behind: counts are exactly what the first import wrote
        ExportEnvelope out = new ExportExporter(ds).exportAll();
        assertEquals(2, out.rows("users").size());
        assertEquals(1, out.rows("tasks").size());
    }
}
