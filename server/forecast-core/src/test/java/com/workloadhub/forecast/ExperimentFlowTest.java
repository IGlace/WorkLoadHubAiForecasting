package com.workloadhub.forecast;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@code server/tools/Experiment.java}, the program the owner builds and scores an experiment database with.
 *
 * <p>It has no module of its own — Java 21's single-file source launcher compiles it on the spot (JEP 330) — so
 * without this test nothing would compile it at all, which is how {@code server/examples/HostExample.java} stands
 * today and is not a precedent worth repeating. Until 2026-09-12 the same ground was covered by {@code CliSmokeTest}
 * in {@code forecast-cli}; the module is gone and its coverage lives here.
 *
 * <p>The child JVM gets this JVM's own classpath, which is {@code forecast-core}'s classes and dependencies —
 * the same thing {@code server/tools/core-classpath.sh} resolves for the launcher script.
 */
class ExperimentFlowTest {

    private static final Path DRIVER = Path.of("../tools/Experiment.java");
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/mini-export.json");

    /** What one run of the driver did: its exit code and everything it printed, both streams together. */
    private record Run(int exit, String output) {
    }

    @Test
    void initImportAndExportRoundTripAnExport(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        assertOk(experiment("init-db", "--db", db.toString()), "Created");
        assertEquals(2, experiment("init-db", "--db", db.toString()).exit(), "refuses to overwrite without --force");
        assertOk(experiment("init-db", "--db", db.toString(), "--force"), "Created");
        assertOk(experiment("import", "--db", db.toString(), FIXTURE.toString()), "Imported");
        Path out = dir.resolve("out.json");
        assertOk(experiment("export", "--db", db.toString(), out.toString()), "rows)");
        assertTrue(Files.readString(out).contains("\"CT2-CAL-1\""));
    }

    @Test
    void seedWritesJsonAndSqlThatImports(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("s.db");
        Path seeded = dir.resolve("seeded.json");
        assertOk(experiment("seed", "--synthetic", "--users", "12", "--weeks", "8", "--seed", "1", "--end", "2026-09-06",
                "--out", seeded.toString()), "synthetic identities");
        assertOk(experiment("init-db", "--db", db.toString()), "Created");
        assertOk(experiment("import", "--db", db.toString(), seeded.toString()), "Imported");
        Path sql = dir.resolve("seeded.sql");
        assertOk(experiment("seed", "--synthetic", "--users", "12", "--weeks", "8", "--seed", "1", "--end", "2026-09-06",
                "--format", "sql", "--out", sql.toString()), "Wrote");
        assertTrue(Files.readString(sql).contains("INSERT INTO tasks"));
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
     * The reason the driver exists: seed, load, score. A small synthetic population and two origins keep it to a few
     * seconds while still going through {@code ForecastService.evaluate} and writing the three files
     * {@code tools/parity.sh} reads afterwards — including {@code summary.md}, whose first line that script parses
     * the as-of date out of.
     */
    @Test
    void evalScoresTheDatabaseAndWritesTheHarnessFiles(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("e.db");
        Path seeded = dir.resolve("seeded.json");
        assertOk(experiment("seed", "--synthetic", "--users", "14", "--weeks", "24", "--seed", "5", "--end", "2026-09-06",
                "--out", seeded.toString()), "Wrote");
        assertOk(experiment("init-db", "--db", db.toString()), "Created");
        assertOk(experiment("import", "--db", db.toString(), seeded.toString()), "Imported");

        Path out = dir.resolve("eval");
        assertOk(experiment("eval", "--db", db.toString(), "--as-of", "2026-09-06", "--origins", "2", "--models", "seasonal_naive",
                "--out", out.toString()), "seasonal_naive");
        assertTrue(Files.exists(out.resolve("scores.csv")) && Files.exists(out.resolve("demand.csv")) && Files.exists(out.resolve("summary.md")),
                () -> "scores.csv, demand.csv and summary.md in " + out);
        assertTrue(Files.readString(out.resolve("summary.md")).startsWith("# Forecast evaluation, as of 2026-09-06"),
                "tools/parity.sh reads the as-of date off the first line");

        assertEquals(2, experiment("eval", "--db", db.toString(), "--teams", "no-such-team", "--out", out.toString()).exit(), "an unknown team");
        assertEquals(2, experiment("eval", "--db", db.toString(), "--models", "gbm", "--out", out.toString()).exit(), "an unknown model");
    }

    /** A mistyped command or option is a bad request, not a stack trace and not a silent no-op. */
    @Test
    void unknownCommandsAndOptionsAreRefused() throws Exception {
        assertEquals(2, experiment().exit(), "no command at all");
        assertEquals(2, experiment("run", "--team", "x").exit(), "'run' is the host's business, not the driver's");
        assertEquals(2, experiment("init-db", "--wat", "x").exit());
        assertEquals(2, experiment("import", "--db", "x.db").exit(), "import needs a file");
        assertEquals(0, experiment("--help").exit());
    }

    private static void assertOk(Run run, String expected) {
        assertEquals(0, run.exit(), () -> "exit code\n" + run.output());
        assertTrue(run.output().contains(expected), () -> "expected '" + expected + "' in:\n" + run.output());
    }

    private static Run experiment(String... args) throws Exception {
        assertTrue(Files.exists(DRIVER), () -> DRIVER.toAbsolutePath() + " is missing");
        List<String> command = new ArrayList<>(List.of(java(), "--class-path", System.getProperty("java.class.path"), DRIVER.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.MINUTES), () -> "the driver did not finish:\n" + output);
        return new Run(process.exitValue(), output);
    }

    private static String java() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }
}
