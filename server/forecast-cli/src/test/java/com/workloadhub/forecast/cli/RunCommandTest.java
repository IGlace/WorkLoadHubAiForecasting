package com.workloadhub.forecast.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ModelScore;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.JdbcRunStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;
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
    void seedThenRunThenList(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("r.db");
        Path seeded = dir.resolve("seeded.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "14", "--weeks", "20", "--seed", "3", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        String teams = capture(cli, 0, "teams", "--db", db.toString());
        String team = teams.lines().filter(l -> !l.isBlank() && !l.startsWith("id")).map(l -> l.split("\\s{2,}"))
                .filter(cols -> Integer.parseInt(cols[cols.length - 1].trim()) > 0).map(cols -> cols[0]).findFirst().orElseThrow();
        String run = capture(cli, 0, "run", "--db", db.toString(), "--team", team, "--as-of", "2026-09-06", "--model", "seasonal_naive");
        assertTrue(run.contains("champion seasonal_naive"), run);
        assertTrue(run.contains("capacity"), run);
        assertTrue(run.contains("window 1"), run);
        String runs = capture(cli, 0, "runs", "--db", db.toString(), "--team", team);
        assertTrue(runs.contains("DONE"), runs);
        assertEquals(2, cli.execute("run", "--db", db.toString(), "--team", "no-such-team", "--as-of", "2026-09-06"));
        assertEquals(2, cli.execute("run", "--db", db.toString(), "--team", team, "--as-of", "2026-09-06", "--model", "gbm"));
        String json = capture(cli, 0, "run", "--db", db.toString(), "--team", team, "--as-of", "2026-09-06", "--json");
        assertTrue(json.trim().startsWith("{") && json.contains("\"memberWindows\""), json);
        assertTrue(json.contains("\"memberDays\""), json);
        String current = capture(cli, 0, "current", "--db", db.toString(), "--team", team, "--from", "2026-09-07", "--to", "2026-09-18");
        assertTrue(current.contains("2026-09-07") && current.contains("demand"), current);
        String currentJson = capture(cli, 0, "current", "--db", db.toString(), "--team", team, "--from", "2026-09-07", "--to", "2026-09-18", "--json");
        assertTrue(currentJson.trim().startsWith("[") && currentJson.contains("\"demandHrs\""), currentJson);
        assertEquals(2, cli.execute("current", "--db", db.toString(), "--team", team, "--from", "2026-09-18", "--to", "2026-09-07"));
        assertEquals(0, cli.execute("run", "--db", db.toString(), "--team", team, "--as-of", "2026-08-19", "--model", "seasonal_naive"));
        String accuracy = capture(cli, 0, "accuracy", "--db", db.toString(), "--team", team, "--from", "2026-08-20", "--to", "2026-09-02");
        assertTrue(accuracy.contains("team") && accuracy.contains("mae") && accuracy.contains("lead"), accuracy);
        Path out = dir.resolve("accuracy");
        assertEquals(0, cli.execute("accuracy", "--db", db.toString(), "--team", team, "--from", "2026-08-20", "--to", "2026-09-02", "--out", out.toString()));
        assertTrue(Files.readString(out.resolve("accuracy.csv")).startsWith("member_id,day,run_id,lead,forecast,truth"));
        assertTrue(Files.readString(out.resolve("summary.md")).startsWith("# Forecast accuracy, team "));
        String accuracyJson = capture(cli, 0, "accuracy", "--db", db.toString(), "--team", team, "--from", "2026-08-20", "--to", "2026-09-02", "--json");
        assertTrue(accuracyJson.contains("\"scores\"") && accuracyJson.contains("\"current\""), accuracyJson);
        assertFalse(accuracyJson.contains("NaN"), accuracyJson);
        assertEquals(2, cli.execute("accuracy", "--db", db.toString(), "--team", team, "--from", "2026-09-02", "--to", "2026-08-20"));
        assertEquals(2, cli.execute("accuracy", "--db", db.toString(), "--team", "no-such-team"));
    }

    /**
     * A stored score row can still hand back a non-finite double (a defensive guard against any future
     * regression upstream of the mapper, not just the null path {@code getRun} now takes for an unavailable
     * model). The {@code --json} mapper must turn it into JSON {@code null}, never the bare word {@code NaN}.
     */
    @Test
    void jsonOutputNeverContainsTheLiteralNaNForAnUnavailableModel() {
        UUID id = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        RunSummary summary = new RunSummary(id, team, null, LocalDate.of(2026, 9, 6), RunStatus.DONE, null, "seasonal_naive", 1.0, null,
                LocalDateTime.of(2026, 9, 6, 10, 0), LocalDateTime.of(2026, 9, 6, 10, 1));
        List<ModelScore> scores = List.of(new ModelScore("xgboost", LocalDate.of(2026, 8, 24), 1, Double.NaN, Double.NaN));
        Map<String, Double> maseByModel = new java.util.HashMap<>();
        maseByModel.put("xgboost", Double.NaN);
        Map<String, String> unavailable = Map.of("xgboost", "xgboost native library unavailable: boom");
        RunResult r = new RunResult(summary, scores, maseByModel, unavailable, List.of(), List.of(), "{}");
        String json = RunCommand.toJson(r);
        assertFalse(json.contains("NaN"), json);
        assertTrue(json.contains("\"mase\" : null") || json.contains("\"mase\":null"), json);
    }

    @Test
    void evalWritesTheHarnessFiles(@TempDir Path dir) {
        Path db = dir.resolve("e.db");
        Path seeded = dir.resolve("seeded.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "14", "--weeks", "24", "--seed", "5", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        Path out = dir.resolve("eval");
        String text = capture(cli, 0, "eval", "--db", db.toString(), "--as-of", "2026-09-06", "--origins", "2", "--models", "seasonal_naive", "--out", out.toString());
        assertTrue(text.contains("seasonal_naive"), text);
        assertTrue(java.nio.file.Files.exists(out.resolve("scores.csv")) && java.nio.file.Files.exists(out.resolve("demand.csv"))
                && java.nio.file.Files.exists(out.resolve("summary.md")));
        assertEquals(2, cli.execute("eval", "--db", db.toString(), "--models", "gbm", "--out", out.toString()));
    }

    static DataSource dataSource(Path db) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        return ds;
    }

    /**
     * Only the command that owns runs reconciles (design 2026-09-11, section 4.2): {@code runs} in a second
     * terminal must leave the run {@code run} is executing in the first alone.
     */
    @Test
    void onlyRunFailsTheRunsAnEarlierProcessLeftBehind(@TempDir Path dir) {
        Path db = dir.resolve("i.db");
        Path seeded = dir.resolve("seeded.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "14", "--weeks", "20", "--seed", "3", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        DataSource ds = dataSource(db);
        UUID team = UUID.fromString(JdbcClient.create(ds).sql("SELECT team_id AS id FROM team_members GROUP BY team_id ORDER BY team_id")
                .query().listOfRows().get(0).get("id").toString());
        JdbcRunStore store = new JdbcRunStore(ds, Dialect.SQLITE);
        UUID live = store.create(new RunRequest(team, null, null, null), LocalDate.of(2026, 9, 6), LocalDateTime.of(2026, 9, 6, 9, 0));
        store.markRunning(live);

        capture(cli, 0, "runs", "--db", db.toString(), "--team", team.toString());
        assertEquals(RunStatus.RUNNING, store.find(live).orElseThrow().status(), "a read-only command never fails a run another process is executing");

        capture(cli, 0, "run", "--db", db.toString(), "--team", team.toString(), "--as-of", "2026-09-06", "--model", "seasonal_naive");
        RunSummary reconciled = store.find(live).orElseThrow();
        assertEquals(RunStatus.FAILED, reconciled.status(), "run reconciles what an earlier crash left behind");
        assertEquals(JdbcRunStore.INTERRUPTED, reconciled.error());
    }
}
