package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecast.seed.SeedGenerator;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
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
    }

    @Test
    void scriptLoadsIntoPostgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
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
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ExportEnvelope env = SeedGenerator.generate(null, new SeedConfig(12, LocalDate.of(2026, 9, 6), 5, true, 24));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(out.toString());
        }
        assertEquals(env.rows("tasks").size(), new ExportExporter(ds).exportAll().rows("tasks").size());
    }

    @Test
    void aFiveTableEnvelopeIsLandedWithDeletesAndAProjectsUpsert() throws Exception {
        ExportEnvelope input = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        String sql = out.toString();
        // the reverse of TABLE_ORDER, restricted to the envelope's tables other than projects
        int deletes = sql.indexOf("DELETE FROM personal_leaves;\nDELETE FROM time_logs;\nDELETE FROM task_history;\nDELETE FROM tasks;\n");
        assertTrue(deletes > 0 && deletes < sql.indexOf("INSERT INTO"), "children deleted first, before any insert: " + sql.substring(0, 200));
        assertTrue(!sql.contains("DELETE FROM projects"));
        assertTrue(sql.contains("INSERT INTO projects"));
        assertTrue(sql.contains("ON CONFLICT (id) DO UPDATE SET"), "projects are upserted");
        assertTrue(sql.contains("next_task_number = EXCLUDED.next_task_number"));
        int tasksInsert = sql.indexOf("INSERT INTO tasks");
        assertTrue(!sql.substring(tasksInsert, sql.indexOf(";\n", tasksInsert)).contains("ON CONFLICT"), "only projects carry the upsert");
        assertTrue(!sql.contains("INSERT INTO users"));
    }

    @Test
    void aSyntheticEnvelopeHasNoDeletes() throws Exception {
        ExportEnvelope env = SeedGenerator.generate(null, new SeedConfig(8, LocalDate.of(2026, 9, 6), 5, true, 12));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        assertTrue(!out.toString().contains("DELETE FROM"));
        assertTrue(!out.toString().contains("ON CONFLICT"));
    }

    @Test
    void aFiveTableScriptLandsTwiceOnPostgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ExportEnvelope input = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        // the directory first, as the application's own database would hold it
        StringWriter directory = new StringWriter();
        SqlExportWriter.write(input, directory);
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(directory.toString());
            st.execute(out.toString());
            st.execute(out.toString());   // a second landing replaces, it does not duplicate
        }
        ExportEnvelope back = new ExportExporter(ds).exportAll();
        assertEquals(env.rows("tasks").size(), back.rows("tasks").size());
        assertEquals(env.rows("projects").size(), back.rows("projects").size());
        assertEquals(2, back.rows("users").size(), "the directory is untouched");
    }
}
