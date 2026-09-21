package com.workloadhub.forecast.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.ForecastRepository;
import com.workloadhub.forecast.tools.testing.DatabaseTestSupport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.workloadhub.forecast.data.rows.MemberRow;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Drives {@link Experiment}, the program the owner builds an experiment database with. It is a class of this
 * module now, so the gate compiles it and this test calls {@link Experiment#run} in process, capturing both
 * output streams. Until 2026-09-12 the same ground was covered by {@code CliSmokeTest} in {@code forecast-cli};
 * the module is gone and its coverage lives here.
 *
 * <p>The driver connects to a fresh database on the PostgreSQL container this JVM started, whose coordinates are
 * passed as {@code --url}, {@code --user} and {@code --password}.
 */
class ExperimentFlowTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/mini-export.json");

    /** The fixture in the shape a real export arrives in: every user inactive and deactivated, and no team at all. */
    private static final Path RAW = Path.of("src/test/resources/fixtures/raw-export.json");

    /** What one run of the driver did: its exit code and everything it printed, both streams together. */
    private record Run(int exit, String output) {
    }

    @Test
    void initImportAndExportRoundTripAnExport(@TempDir Path dir) throws Exception {
        String[] db = connection(DatabaseTestSupport.postgres());
        assertOk(experiment(concat(db, "init-db")), "Created");
        assertEquals(2, experiment(concat(db, "init-db")).exit(), "refuses to recreate the schema without --force");
        assertOk(experiment(concat(db, "init-db", "--force")), "Created");
        assertOk(experiment(concat(db, "import", FIXTURE.toString())), "Imported");
        Path out = dir.resolve("out.json");
        assertOk(experiment(concat(db, "export", out.toString())), "rows)");
        assertTrue(Files.readString(out).contains("\"CT2-CAL-1\""));
    }

    @Test
    void seedWritesJsonThatImports(@TempDir Path dir) throws Exception {
        String[] db = connection(DatabaseTestSupport.postgres());
        Path seeded = dir.resolve("seeded.json");
        assertOk(experiment("seed", "--synthetic", "--users", "12", "--weeks", "8", "--seed", "1", "--end", "2026-09-06",
                "--out", seeded.toString()), "synthetic identities");
        assertOk(experiment(concat(db, "init-db")), "Created");
        assertOk(experiment(concat(db, "import", seeded.toString())), "Imported");
        assertEquals(2, experiment("seed", "--synthetic", "--users", "12", "--weeks", "8", "--format", "sql",
                "--out", dir.resolve("seeded.sql").toString()).exit(), "--format went with the SQL export (design 2026-09-18)");
        assertFalse(Files.exists(dir.resolve("seeded.sql")));
    }

    /**
     * Real-mode output carries the identities of real people, so it stays outside the repository ({@code CLAUDE.md}).
     * The refusal has to come before the generator runs: an exit code of 2 with the file still absent.
     */
    @Test
    void realModeRefusesTheRepositoryMissingExportAndUsers(@TempDir Path dir) throws Exception {
        Path inRepo = Path.of("target/real-seeded.json");
        assertEquals(2, experiment("seed", "--export", FIXTURE.toString(), "--weeks", "8", "--out", inRepo.toString()).exit());
        assertFalse(Files.exists(inRepo), "the repository guard refuses before generating anything");

        Path outside = dir.resolve("real-seeded.json");
        assertEquals(2, experiment("seed", "--weeks", "8", "--out", outside.toString()).exit(), "real mode needs --export");
        assertEquals(2, experiment("seed", "--export", FIXTURE.toString(), "--weeks", "8", "--users", "5", "--out", outside.toString()).exit(),
                "--users is a synthetic-mode option");
        assertFalse(Files.exists(outside));
    }

    /**
     * Real mode refuses an export that lacks a task status or type (design 2026-09-17, task 2c). Through the
     * driver that is a bad request like any other: a bare message and exit 2, not a stack-trace line and exit 1.
     */
    @Test
    void realModeRefusesAnIncompleteExportAsABadRequest(@TempDir Path dir) throws Exception {
        String json = Files.readString(FIXTURE, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"name\": \"Done\""), "the fixture spells the Done status as expected");
        Path partial = dir.resolve("partial-export.json");
        Files.writeString(partial, json.replace("\"name\": \"Done\"", "\"name\": \"Finished\""), StandardCharsets.UTF_8);
        Path outside = dir.resolve("real-seeded.json");
        Run run = experiment("seed", "--export", partial.toString(), "--weeks", "8", "--out", outside.toString());
        assertEquals(2, run.exit(), run.output());
        assertTrue(run.output().contains("task_statuses lacks 'Done'"), run.output());
        assertFalse(run.output().contains("Exception"), run.output());
        assertFalse(Files.exists(outside));
    }

    @Test
    void fixtureWritesTheSchemaAndTheRows(@TempDir Path dir) throws Exception {
        assertOk(experiment("fixture", "--out", dir.toString()), "seeded-rows.sql");
        assertTrue(Files.readString(dir.resolve("workloadhub-schema.sql")).contains("CREATE SCHEMA task_service;"));
        assertTrue(Files.readString(dir.resolve("seeded-rows.sql")).startsWith("BEGIN;\nSET search_path TO task_service;\n"));
    }

    /** A mistyped command or option is a bad request, not a stack trace and not a silent no-op. */
    @Test
    void unknownCommandsAndOptionsAreRefused() throws Exception {
        assertEquals(2, experiment().exit(), "no command at all");
        assertEquals(2, experiment("run", "--team", "x").exit(), "'run' is the host's business, not the driver's");
        assertEquals(2, experiment("eval").exit(), "the evaluation harness was removed on 2026-09-14");
        assertEquals(2, experiment("init-db", "--wat", "x").exit());
        assertEquals(2, experiment("import").exit(), "import needs a file");
        assertEquals(0, experiment("--help").exit());
    }

    /**
     * A prepared export carries the identities of real people, so it stays outside the repository, exactly as
     * real-mode seed output does. The refusal comes before anything is written.
     */
    @Test
    void prepareRefusesTheRepositoryAndNeedsAnOutput(@TempDir Path dir) throws Exception {
        Path inRepo = Path.of("target/prepared.json");
        assertEquals(2, experiment("prepare", FIXTURE.toString(), "--out", inRepo.toString()).exit());
        assertFalse(Files.exists(inRepo), "the repository guard refuses before writing anything");

        Path outside = dir.resolve("prepared.json");
        assertEquals(2, experiment("prepare", FIXTURE.toString()).exit(), "--out is required");
        assertEquals(2, experiment("prepare", "--out", outside.toString()).exit(), "the export to prepare is required");
        assertEquals(2, experiment("prepare", FIXTURE.toString(), "--out", outside.toString(),
                "--joined", "2021-01-04").exit(), "--joined is gone with the team derivation it stamped");
        assertFalse(Files.exists(outside));
    }

    @Test
    void prepareWritesAnExportEveryUserCanBeCountedIn(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("prepared.json");
        assertOk(experiment("prepare", FIXTURE.toString(), "--out", out.toString()), "users activated");
        String json = Files.readString(out);
        // Json.mapper() indents, and its separator spacing is the pretty printer's business rather than this
        // test's: both spellings are accepted so the assertion is about the value, not the layout.
        assertTrue(json.contains("\"active\" : true") || json.contains("\"active\":true"), json);
        assertFalse(json.contains("\"deactivated_at\" : \"2026") || json.contains("\"deactivated_at\":\"2026"), json);
        // teams and team_members are this step's business no longer, so they come through exactly as they went in.
        assertTrue(json.contains("\"teams\""), json);
        assertTrue(json.contains("TEAM_LEADER"), "a manager carries the leader role their place implies");
    }

    /**
     * The whole chain the {@code prepare} verb exists to unblock: a real export in which nobody is active
     * goes in, and {@code ForecastRepository} counts members at the other end. Straight to {@code seed} it
     * would count none, because every user is inactive. Its `teams` table stays empty throughout, which is
     * the point of the 2026-09-21 change: the hierarchy in `users.manager_id` is enough.
     */
    @Test
    void prepareThenSeedThenImportProducesCountableMembers(@TempDir Path dir) throws Exception {
        DataSource ds = DatabaseTestSupport.postgres();
        String[] db = connection(ds);
        Path prepared = dir.resolve("prepared.json");
        Path seeded = dir.resolve("seeded.json");

        assertOk(experiment("prepare", RAW.toString(), "--out", prepared.toString()), "users activated");
        assertOk(experiment("seed", "--export", prepared.toString(), "--weeks", "8", "--end", "2026-09-06",
                "--out", seeded.toString()), "real identities");
        assertOk(experiment(concat(db, "init-db", "--force")), "Created");
        assertOk(experiment(concat(db, "import", prepared.toString())), "Imported");
        assertOk(experiment(concat(db, "import", seeded.toString())), "Imported");

        ForecastData data = new ForecastRepository(JdbcClient.create(ds)).loadAll();
        assertEquals(2, data.members().size(), "prepare exists so that this is not zero");
        // The whole point of the 2026-09-21 change: the raw export's teams table is empty and nobody cares.
        assertTrue(data.members().stream().anyMatch(m -> m.managerId() != null), "the hierarchy came through");
        UUID team = data.members().stream().map(MemberRow::managerId).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        assertTrue(!data.membersOfTeam(team).isEmpty(), "and it makes a team the forecast can run");
        assertTrue(data.members().stream().allMatch(m -> m.left() == null), "deactivated_at was cleared");
    }

    /** The coordinates of a fresh database on the shared container, as the driver's own options. */
    private static String[] connection(DataSource ds) {
        PGSimpleDataSource pg = (PGSimpleDataSource) ds;
        return new String[] {"--url", pg.getUrl(), "--user", pg.getUser(), "--password", pg.getPassword()};
    }

    /** One argument list: the command and its file first, because the driver reads its command from argv[0], then the options. */
    private static String[] concat(String[] db, String... command) {
        String[] out = Arrays.copyOf(command, command.length + db.length);
        System.arraycopy(db, 0, out, command.length, db.length);
        return out;
    }

    private static void assertOk(Run run, String expected) {
        assertEquals(0, run.exit(), () -> "exit code\n" + run.output());
        assertTrue(run.output().contains(expected), () -> "expected '" + expected + "' in:\n" + run.output());
    }

    private static synchronized Run experiment(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            return new Run(Experiment.run(args), buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }
}
