package com.workloadhub.forecast.tools.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.seed.SeedConfig;
import com.workloadhub.forecast.tools.seed.SeedGenerator;
import com.workloadhub.forecast.tools.testing.DatabaseTestSupport;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SqlExportWriterTest {

    @Test
    void writesQuotedInsertsInDependencyOrder() throws Exception {
        ExportEnvelope env = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        String sql = out.toString();
        assertTrue(sql.startsWith("BEGIN;\nSET search_path TO task_service;\n"));
        assertTrue(sql.trim().endsWith("COMMIT;"));
        assertTrue(sql.indexOf("INSERT INTO users") < sql.indexOf("INSERT INTO tasks"));
        assertTrue(sql.contains("'New Year''s Day'") || sql.contains("'New Year’s Day'"), "quotes escaped");
        assertTrue(sql.contains("FALSE") && sql.contains("NULL"));
        // The fixture excludes refresh_tokens and nothing else, so it is a full export and lands whole, even
        // though several of its tables carry no rows at all.
        assertEquals(List.of("refresh_tokens"), env.excludedTables());
        assertTrue(!sql.contains("DELETE FROM"), "a full export deletes nothing");
        assertTrue(!sql.contains("RAISE EXCEPTION"), "no partial-landing guard on a full export");
    }

    @Test
    void scriptLoadsIntoPostgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        ExportEnvelope env = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(out.toString());
        }
        assertEquals(2, new ExportExporter(ds).exportAll().rows("users").size());
    }

    @Test
    void scriptOfASeededDatasetLoadsIntoPostgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        ExportEnvelope env = SeedGenerator.generate(null, new SeedConfig(12, LocalDate.of(2026, 9, 6), 5, true, 24));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(out.toString());
        }
        assertEquals(env.rows("tasks").size(), new ExportExporter(ds).exportAll().rows("tasks").size());
    }

    /**
     * The writer only ever inserts: a seed lands through the importer, and the script is the fixture's (design
     * 2026-09-18, section 3). A real-mode envelope gets no deletes, no upsert and no guard either.
     */
    @Test
    void aRealModeEnvelopeIsPlainInsertsToo() throws Exception {
        ExportEnvelope input = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        String sql = out.toString();
        assertTrue(!sql.contains("DELETE FROM") && !sql.contains("ON CONFLICT") && !sql.contains("RAISE EXCEPTION"), sql.substring(0, 200));
        assertTrue(sql.contains("INSERT INTO projects") && sql.contains("INSERT INTO tasks") && !sql.contains("INSERT INTO users"),
                "the five work tables and nothing of the directory");
    }
}
