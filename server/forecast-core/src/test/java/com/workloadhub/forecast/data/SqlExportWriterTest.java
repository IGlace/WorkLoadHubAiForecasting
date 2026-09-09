package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.io.StringWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
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
}
