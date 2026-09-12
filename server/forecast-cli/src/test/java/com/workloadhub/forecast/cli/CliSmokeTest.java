package com.workloadhub.forecast.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliSmokeTest {

    /**
     * The CLI builds and evaluates an experiment database; everything a host does through {@code ForecastService}
     * (a run, its progress, the current forecast, accuracy, narration) is shown by {@code examples/HostExample.java}
     * instead. Nothing else may creep back in: a removed command is an unmatched argument at the root, not a
     * subcommand, and picocli answers that with its usage exit code.
     */
    @Test
    void onlyTheFiveExperimentCommandsAreRegistered() {
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(Set.of("init-db", "import", "export", "seed", "eval"), cli.getSubcommands().keySet());

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream old = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(err, true));
            exit = cli.execute("run", "--team", "x");
        } finally {
            System.setErr(old);
        }
        assertEquals(CommandLine.ExitCode.USAGE, exit, () -> "'run' should be unknown now\n" + err);
        assertTrue(err.toString().contains("Unmatched argument"), () -> "expected a parse error, got:\n" + err);
    }

    @Test
    void initImportExport(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        Path fixture = Path.of("../forecast-core/src/test/resources/fixtures/mini-export.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(2, cli.execute("init-db", "--db", db.toString()), "refuses to overwrite without --force");
        assertEquals(0, cli.execute("import", "--db", db.toString(), fixture.toString()));
        Path out = dir.resolve("out.json");
        assertEquals(0, cli.execute("export", "--db", db.toString(), out.toString()));
        assertTrue(Files.readString(out).contains("\"CT2-CAL-1\""));
    }

    @Test
    void seedSyntheticThenImport(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("s.db");
        Path seeded = dir.resolve("seeded.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "12", "--weeks", "8", "--seed", "1", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        Path sql = dir.resolve("seeded.sql");
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "12", "--weeks", "8", "--seed", "1", "--end", "2026-09-06", "--format", "sql", "--out", sql.toString()));
        assertTrue(Files.readString(sql).contains("INSERT INTO tasks"));
    }

    @Test
    void realModeRefusesToWriteInsideTheRepository() throws Exception {
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        Path fixture = Path.of("../forecast-core/src/test/resources/fixtures/mini-export.json");
        Path inRepo = Path.of("target/real-seeded.json");
        assertEquals(2, cli.execute("seed", "--export", fixture.toString(), "--weeks", "8", "--out", inRepo.toString()));
        assertFalse(Files.exists(inRepo));
    }

    @Test
    void realModeRefusesUsersWithoutSynthetic(@TempDir Path dir) throws Exception {
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        Path fixture = Path.of("../forecast-core/src/test/resources/fixtures/mini-export.json");
        Path out = dir.resolve("real-seeded.json");
        assertEquals(2, cli.execute("seed", "--export", fixture.toString(), "--weeks", "8", "--users", "5", "--out", out.toString()));
        assertFalse(Files.exists(out));
    }

    /**
     * The reason the command line exists: seed, load, score. A small synthetic population and two origins keep
     * it to a few seconds while still exercising the whole harness, and the three files are what
     * {@code tools/parity.sh} reads afterwards.
     *
     * <p>Both ways {@code eval} refuses are pinned here too, because each returns 2 from its own arm of
     * {@link EvalCommand#call()}: a name that resolves to no team never reaches the harness, and an unknown
     * model is the harness's own {@code INVALID_REQUEST}. Neither may become a stack trace or a 1.
     */
    @Test
    void evalWritesTheHarnessFiles(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("e.db");
        Path seeded = dir.resolve("seeded.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "14", "--weeks", "24", "--seed", "5", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));

        Path out = dir.resolve("eval");
        String text = capture(cli, 0, "eval", "--db", db.toString(), "--as-of", "2026-09-06", "--origins", "2", "--models", "seasonal_naive",
                "--out", out.toString());
        assertTrue(text.contains("seasonal_naive"), text);
        assertTrue(Files.exists(out.resolve("scores.csv")) && Files.exists(out.resolve("demand.csv")) && Files.exists(out.resolve("summary.md")),
                () -> "scores.csv, demand.csv and summary.md in " + out);

        assertEquals(2, cli.execute("eval", "--db", db.toString(), "--teams", "no-such-team", "--out", out.toString()), "an unknown team");
        assertEquals(2, cli.execute("eval", "--db", db.toString(), "--models", "gbm", "--out", out.toString()), "an unknown model");
    }

    /** Runs the CLI with stdout captured, asserting the exit code and showing the output when it is not the one expected. */
    private static String capture(CommandLine cli, int expectedExit, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(out, true));
            assertEquals(expectedExit, cli.execute(args), () -> "exit code for " + String.join(" ", args) + "\n" + out);
        } finally {
            System.setOut(old);
        }
        return out.toString();
    }
}
