package com.workloadhub.forecast.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class NarrateCommandTest {

    static String captureErr(CommandLine cli, int expectedExit, String... args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream old = System.err;
        System.setErr(new PrintStream(err, true));
        try {
            assertEquals(expectedExit, cli.execute(args), () -> "exit code for " + String.join(" ", args) + "\n" + err);
        } finally {
            System.setErr(old);
        }
        return err.toString();
    }

    static Path seeded(Path dir, CommandLine cli) {
        Path db = dir.resolve("n.db");
        Path seeded = dir.resolve("seeded.json");
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "12", "--weeks", "12", "--seed", "5", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        return db;
    }

    @Test
    void narrateRefusesToRunWithoutTheTokenKey(@TempDir Path dir) {
        Assumptions.assumeTrue(System.getenv(Services.TOKEN_KEY_ENV) == null, "WHF_TOKEN_KEY is set in this environment");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        Path db = seeded(dir, cli);
        String err = captureErr(cli, 2, "narrate", "--db", db.toString(), "--run", "11111111-1111-1111-1111-111111111111", "--user", "nobody");
        assertTrue(err.contains(Services.TOKEN_KEY_ENV), err);
        assertTrue(err.contains("openssl rand -base64 32"), err);
    }

    @Test
    void narrateRejectsABadRunIdAndAnUnknownLanguage(@TempDir Path dir) {
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        Path db = seeded(dir, cli);
        assertEquals(2, cli.execute("narrate", "--db", db.toString(), "--run", "not-a-uuid", "--user", "x", "--lang", "en"));
        assertEquals(2, cli.execute("narrate", "--db", db.toString(), "--run", "11111111-1111-1111-1111-111111111111", "--user", "x", "--lang", "de"));
    }

    /**
     * `copilot status` calls the gateway's runtime(), which without a CLI path resolves the in-process runtime and
     * extracts 91 MB into ~/.copilot; the test points at a stub CLI through the system property the CLI reads
     * first, so the suite never extracts anything. The user id is read from the seeded database.
     */
    @Test
    void copilotStatusReportsAUserWithoutAToken(@TempDir Path dir) throws Exception {
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        Path db = seeded(dir, cli);
        Path stub = dir.resolve("copilot");
        Files.writeString(stub, "#!/bin/sh\n");
        assertTrue(stub.toFile().setExecutable(true));
        System.setProperty("whf.copilot.cli-path", stub.toString());
        try {
            String user;
            DbOptions options = new DbOptions();
            options.db = db;
            try (Services s = Services.open(options.dataSource())) {
                user = s.jdbc().sql("SELECT id FROM users ORDER BY id LIMIT 1").query(String.class).single();
            }
            String out = RunCommandTest.capture(cli, 0, "copilot", "status", "--db", db.toString(), "--user", user);
            assertTrue(out.contains("hasToken: false"), out);
            assertTrue(out.contains("runtimeAvailable: true"), out);
            assertTrue(out.contains("runtimeVersion: 1.0.13-preview.6"), out);
            assertEquals(2, cli.execute("copilot", "status", "--db", db.toString(), "--user", "nobody-at-all"));
        } finally {
            System.clearProperty("whf.copilot.cli-path");
        }
    }
}
