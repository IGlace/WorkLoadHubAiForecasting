package com.workloadhub.forecast.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class RunCommandTest {

    static String capture(CommandLine cli, int expectedExit, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true));
        try {
            assertEquals(expectedExit, cli.execute(args), () -> "exit code for " + String.join(" ", args) + "\n" + out);
        } finally {
            System.setOut(old);
        }
        return out.toString();
    }

    @Test
    void seedThenRunThenList(@TempDir Path dir) {
        Path db = dir.resolve("r.db");
        Path seeded = dir.resolve("seeded.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "14", "--weeks", "20", "--seed", "3", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        String teams = capture(cli, 0, "teams", "--db", db.toString());
        String team = teams.lines().filter(l -> !l.isBlank() && !l.startsWith("id")).map(l -> l.split("\\s{2,}")[0]).findFirst().orElseThrow();
        String run = capture(cli, 0, "run", "--db", db.toString(), "--team", team, "--as-of", "2026-09-06", "--model", "seasonal_naive");
        assertTrue(run.contains("champion seasonal_naive"), run);
        assertTrue(run.contains("capacity"), run);
        String runs = capture(cli, 0, "runs", "--db", db.toString(), "--team", team);
        assertTrue(runs.contains("DONE"), runs);
        assertEquals(2, cli.execute("run", "--db", db.toString(), "--team", "no-such-team", "--as-of", "2026-09-06"));
        assertEquals(2, cli.execute("run", "--db", db.toString(), "--team", team, "--as-of", "2026-09-06", "--model", "gbm"));
        String json = capture(cli, 0, "run", "--db", db.toString(), "--team", team, "--as-of", "2026-09-06", "--json");
        assertTrue(json.trim().startsWith("{") && json.contains("\"memberWeeks\""), json);
    }
}
