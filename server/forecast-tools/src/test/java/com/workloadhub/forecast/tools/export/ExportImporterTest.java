package com.workloadhub.forecast.tools.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workloadhub.forecast.tools.seed.SeedConfig;
import com.workloadhub.forecast.tools.seed.SeedGenerator;
import com.workloadhub.forecast.tools.testing.DatabaseTestSupport;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ExportImporterTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/mini-export.json");

    @Test
    void replaceDeletesOnlyTheTablesTheEnvelopeCarries() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        ExportEnvelope input = ExportFiles.read(FIXTURE);
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

    /**
     * The seed only ever targets the local database (design 2026-09-18): it owns everything under projects, so a
     * replace clears the application's own history and comments of the rows it replaces instead of failing on
     * their foreign keys.
     */
    @Test
    void replaceClearsTheApplicationTablesUnderTheWorkTables() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        ExportEnvelope input = ExportFiles.read(FIXTURE);
        new ExportImporter(ds).importAll(input, true);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO project_history (id, project_id, changed_by, field_name, changed_at, created_at, updated_at)"
                    + " VALUES ('a0000000-0000-0000-0000-000000000001', '80000000-0000-0000-0000-000000000001',"
                    + " '30000000-0000-0000-0000-000000000001', 'name', now(), now(), now())");
            st.execute("INSERT INTO task_comments (id, task_id, user_id, content, created_at, updated_at)"
                    + " VALUES ('a0000000-0000-0000-0000-000000000002', '90000000-0000-0000-0000-000000000001',"
                    + " '30000000-0000-0000-0000-000000000001', 'a comment on a task the seed replaces', now(), now())");
            st.execute("INSERT INTO task_attachments (id, task_id, uploaded_by, file_size, content_type, storage_path,"
                    + " original_file_name, stored_file_name, created_at, updated_at)"
                    + " VALUES ('a0000000-0000-0000-0000-000000000003', '90000000-0000-0000-0000-000000000001',"
                    + " '30000000-0000-0000-0000-000000000001', 12, 'text/plain', '/attachments/a3', 'notes.txt', 'a3.txt',"
                    + " now(), now())");
        }
        ExportEnvelope seeded = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        new ExportImporter(ds).importAll(seeded, true);
        ExportEnvelope back = new ExportExporter(ds).exportAll();
        assertEquals(0, back.rows("project_history").size(), "the history of the replaced projects goes with them");
        assertEquals(0, back.rows("task_comments").size(), "the comments on the replaced tasks go with them");
        assertEquals(0, back.rows("task_attachments").size(), "the attachments on the replaced tasks go with them");
        assertEquals(ids(seeded, "projects"), ids(back, "projects"), "projects are the seed's, no more, no less");
        assertEquals(input.rows("users").size(), back.rows("users").size(), "the directory is untouched");
    }

    private static Set<String> ids(ExportEnvelope env, String table) {
        return env.rows(table).stream().map(r -> String.valueOf(r.get("id"))).collect(Collectors.toSet());
    }
}
