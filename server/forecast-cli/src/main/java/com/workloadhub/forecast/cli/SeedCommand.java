package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.SqlExportWriter;
import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecast.seed.SeedGenerator;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "seed", description = "Generate a WorkloadHub export with weeks of realistic history, from a real export or a synthetic directory.")
public class SeedCommand implements Callable<Integer> {

    @Option(names = "--export", description = "A WorkloadHub JSON export to take users, teams, projects and reference rows from.")
    Path export;

    @Option(names = "--weeks", defaultValue = "52", description = "Weeks of history (default: ${DEFAULT-VALUE}).")
    int weeks;

    @Option(names = "--end", description = "The as-of date; history ends there (default: today).")
    LocalDate end;

    @Option(names = "--seed", defaultValue = "42", description = "Random seed; the same seed gives the same file.")
    long seed;

    @Option(names = "--synthetic", description = "Replace identities with generated ones (and generate a directory when no --export).")
    boolean synthetic;

    @Option(names = "--users", defaultValue = "0", description = "Synthetic mode: keep or generate this many users (0 = all, or 40 without --export).")
    int users;

    @Option(names = "--format", defaultValue = "json", description = "json or sql (PostgreSQL INSERT script).")
    String format;

    @Option(names = "--force", description = "Allow real-mode output inside a git repository.")
    boolean force;

    @Option(names = "--out", required = true, description = "Output file.")
    Path out;

    @Override
    public Integer call() throws Exception {
        if (!synthetic && export == null) {
            System.err.println("Real mode needs --export <file>; or pass --synthetic");
            return 2;
        }
        if (!synthetic && users > 0) {
            System.err.println("--users only applies with --synthetic (real mode keeps every user from --export)");
            return 2;
        }
        if (!synthetic && !force && insideGitRepository(out)) {
            System.err.println("Real-mode output holds personal data; write it outside the repository or pass --force");
            return 2;
        }
        if (!format.equals("json") && !format.equals("sql")) {
            System.err.println("--format must be json or sql");
            return 2;
        }
        ExportEnvelope input = export == null ? null : ExportFiles.read(export);
        SeedConfig cfg = new SeedConfig(weeks, end == null ? LocalDate.now() : end, seed, synthetic, users);
        long started = System.nanoTime();
        ExportEnvelope result = SeedGenerator.generate(input, cfg);
        if (format.equals("sql")) {
            try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                SqlExportWriter.write(result, w);
            }
        } else {
            ExportFiles.write(out, result);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("Wrote %s in %d ms: %d weeks ending %s, seed %d, %s%n", out, ms, weeks, cfg.lastDay(), seed,
                synthetic ? "synthetic identities" : "real identities (do not commit)");
        for (String table : new String[] {"users", "teams", "team_members", "projects", "tasks", "task_history", "time_logs",
                "absences", "personal_leaves", "user_capacity", "team_capacity", "holidays"}) {
            System.out.printf("  %-18s %8d%n", table, result.rows(table).size());
        }
        return 0;
    }

    static boolean insideGitRepository(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) {
                return true;
            }
            dir = dir.getParent();
        }
        return false;
    }
}
