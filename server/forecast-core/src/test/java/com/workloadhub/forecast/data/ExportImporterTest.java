package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecast.seed.SeedGenerator;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.nio.file.Path;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ExportImporterTest {

    @Test
    void replaceDeletesOnlyTheTablesTheEnvelopeCarries() throws Exception {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        ExportEnvelope input = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        new ExportImporter(ds).importAll(input, true);
        ExportEnvelope seeded = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        new ExportImporter(ds).importAll(seeded, true);
        ExportEnvelope back = new ExportExporter(ds).exportAll();
        assertEquals(input.rows("users").size(), back.rows("users").size(), "the directory survives a five-table replace");
        assertEquals(input.rows("holidays").size(), back.rows("holidays").size());
        assertEquals(seeded.rows("tasks").size(), back.rows("tasks").size(), "the work tables are replaced, not appended");
        assertEquals(seeded.rows("projects").size(), back.rows("projects").size());
        assertEquals(seeded.rows("personal_leaves").size(), back.rows("personal_leaves").size());
    }
}
