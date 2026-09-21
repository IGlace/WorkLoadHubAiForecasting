package com.workloadhub.forecast.tools;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.tools.export.ExportEnvelope;
import com.workloadhub.forecast.tools.export.ExportExporter;
import com.workloadhub.forecast.tools.export.ExportFiles;
import com.workloadhub.forecast.tools.export.ExportImporter;
import com.workloadhub.forecast.tools.export.SqlExportWriter;
import com.workloadhub.forecast.tools.export.WorkloadHubSchema;
import com.workloadhub.forecast.tools.prepare.ExportPreparer;
import com.workloadhub.forecast.tools.seed.SeedConfig;
import com.workloadhub.forecast.tools.seed.SeedGenerator;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Builds an experiment database on PostgreSQL: the six things the owner does from a terminal that the
 * WorkloadHub server never does.
 *
 * <p>Until 2026-09-17 this was a single-file program under {@code server/tools}, compiled by the launcher against
 * {@code forecast-core}'s classes; it is now a class of {@code forecast-tools}, the module that holds everything the
 * host never runs, so the gate compiles it. {@code server/tools/experiment.sh} is still the way in.
 *
 * <p>There is no picocli here (it is a dependency of neither module, and a library the host ships has no business
 * carrying a command-line parser), so {@link Args} reads the flags; the behaviour that matters is pinned by
 * {@code ExperimentFlowTest}, which drives the same calls in process.
 *
 * <p>Messages and help are English only, like the command line before it. Exit codes: 0 done, 2 a bad request,
 * 1 anything else.
 */
public final class Experiment {

    private static final String USAGE = """
            usage: experiment.sh <command> [options]

              init-db  [--force]
                       Create the schema task_service with the 24 WorkloadHub tables and the module's tables in
                       the database at --url. Refuses when task_service already exists; --force drops it first.

              import   <export.json>
                       Load a WorkloadHub JSON export (real or seeded), replacing existing rows.

              export   <out.json>
                       Write the database's WorkloadHub tables as a JSON export.

              seed     --out FILE [--export FILE] [--synthetic] [--users N] [--weeks N]
                       [--end ISO_DATE] [--seed N] [--force]
                       --end defaults to today, so a seed ends on the day it is generated.
                       Generate an export with weeks of realistic history, from a real export or a
                       synthetic directory. Real mode (no --synthetic) needs --export and refuses to
                       write inside a git repository without --force: its output holds personal data.

              prepare  <export.json> --out FILE [--force]
                       Rewrite a real export so the forecast can count its people: every user active,
                       and each user's role set to the one their job title gives them, which is the role
                       the forecast itself derives. Prints what the file then holds.
                       Transitional — delete it once WorkloadHub's own active flag means what it says.
                       Refuses to write inside a git repository without --force: its output holds
                       personal data.

              fixture  [--out DIR]
                       Regenerate forecast-core's seeded test fixture: workloadhub-schema.sql and seeded-rows.sql in DIR.

            Connection: --url, --user, --password; else WHF_DB_URL, WHF_DB_USER, WHF_DB_PASSWORD; else
            jdbc:postgresql://localhost:5432/workloadhub, workloadhub, workloadhub, which scripts/postgres.sh
            creates. Run this inside the development container (bash scripts/devbox.sh shell).
            """;

    private Experiment() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String[] argv) {
        if (argv.length == 0) {
            System.err.println(USAGE);
            return 2;
        }
        String command = argv[0];
        if (command.equals("--help") || command.equals("-h") || command.equals("help")) {
            System.out.println(USAGE);
            return 0;
        }
        String[] rest = Arrays.copyOfRange(argv, 1, argv.length);
        if (List.of(rest).contains("--help") || List.of(rest).contains("-h")) {
            System.out.println(USAGE);
            return 0;
        }
        try {
            return switch (command) {
                case "init-db" -> initDb(Args.parse(rest, Set.of("url", "user", "password"), Set.of("force")));
                case "import" -> importExport(Args.parse(rest, Set.of("url", "user", "password"), Set.of()));
                case "export" -> export(Args.parse(rest, Set.of("url", "user", "password"), Set.of()));
                case "seed" -> seed(Args.parse(rest, Set.of("url", "user", "password", "out", "export", "users", "weeks", "end", "seed"),
                        Set.of("synthetic", "force")));
                case "prepare" -> prepare(Args.parse(rest, Set.of("out"), Set.of("force")));
                case "fixture" -> fixture(Args.parse(rest, Set.of("out", "url", "user", "password"), Set.of()));
                default -> {
                    System.err.println("error: unknown command '" + command + "'\n");
                    System.err.println(USAGE);
                    yield 2;
                }
            };
        } catch (Bad | IllegalArgumentException e) {
            // Bad is the driver's own; the seed throws IllegalArgumentException for an incomplete real-mode export
            System.err.println("error: " + e.getMessage());
            return 2;
        } catch (ForecastException e) {
            System.err.println("error: " + e.code() + ": " + e.getMessage());
            return "INVALID_REQUEST".equals(e.code()) ? 2 : 1;
        } catch (Exception e) {
            System.err.println("error: " + e);
            return 1;
        }
    }

    // ---- the six commands ------------------------------------------------------------------------------------

    private static int initDb(Args args) throws Exception {
        args.noFiles();
        DataSource ds = dataSource(args);
        boolean exists;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.schemata WHERE schema_name = 'task_service'")) {
            exists = rs.next();
        }
        if (exists) {
            if (!args.flag("force")) {
                System.err.println("schema task_service already exists in " + url(args) + "; use --force to drop and recreate it");
                return 2;
            }
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP SCHEMA task_service CASCADE");
            }
        }
        WorkloadHubSchema.createPostgresql(ds);
        ForecastMigrations.run(ds);
        System.out.println("Created schema task_service in " + url(args) + " with the WorkloadHub schema and the forecast tables");
        return 0;
    }

    private static int importExport(Args args) throws Exception {
        Path file = args.onlyFile("the export file");
        ExportEnvelope envelope = ExportFiles.read(file);
        Map<String, Integer> counts = new ExportImporter(dataSource(args)).importAll(envelope, true);
        counts.forEach((table, n) -> {
            if (n > 0) {
                System.out.printf("%-26s %7d%n", table, n);
            }
        });
        System.out.println("Imported " + counts.values().stream().mapToInt(Integer::intValue).sum() + " rows from " + file);
        return 0;
    }

    private static int export(Args args) throws Exception {
        Path file = args.onlyFile("the output file");
        ExportEnvelope envelope = new ExportExporter(dataSource(args)).exportAll();
        ExportFiles.write(file, envelope);
        System.out.println("Wrote " + file + " (" + envelope.data().values().stream().mapToInt(List::size).sum() + " rows)");
        return 0;
    }

    private static int seed(Args args) throws Exception {
        args.noFiles();
        Path out = Path.of(args.require("out"));
        boolean synthetic = args.flag("synthetic");
        Path export = args.has("export") ? Path.of(args.value("export")) : null;
        int users = args.number("users", 0);
        if (!synthetic && export == null) {
            System.err.println("Real mode needs --export <file>; or pass --synthetic");
            return 2;
        }
        if (!synthetic && users > 0) {
            System.err.println("--users only applies with --synthetic (real mode keeps every user from --export)");
            return 2;
        }
        if (!synthetic && !args.flag("force") && insideGitRepository(out)) {
            System.err.println("Real-mode output holds personal data; write it outside the repository or pass --force");
            return 2;
        }
        ExportEnvelope input = export == null ? null : ExportFiles.read(export);
        int weeks = args.number("weeks", 52);
        SeedConfig cfg = new SeedConfig(weeks, args.date("end") == null ? LocalDate.now() : args.date("end"), args.whole("seed", 42), synthetic, users);
        long started = System.nanoTime();
        ExportEnvelope result = SeedGenerator.generate(input, cfg);
        ExportFiles.write(out, result);
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("Wrote %s in %d ms: %d weeks ending %s, seed %d, %s%n", out, ms, weeks, cfg.lastDay(), cfg.seed(),
                synthetic ? "synthetic identities" : "real identities (do not commit)");
        // Only the tables that hold rows: a synthetic envelope carries every table of the schema, so printing
        // every key meant twenty-four lines of which more than half read zero.
        for (String table : result.data().keySet()) {
            int rows = result.rows(table).size();
            if (rows > 0) {
                System.out.printf("  %-18s %8d%n", table, rows);
            }
        }
        return 0;
    }

    /**
     * Rewrites a real export so its people can be counted. Transitional: see {@link ExportPreparer}. Pure in,
     * pure out and nothing random, so one export always gives byte-identical output.
     */
    private static int prepare(Args args) throws Exception {
        Path in = args.onlyFile("the export to prepare");
        Path out = Path.of(args.require("out"));
        if (!args.flag("force") && insideGitRepository(out)) {
            System.err.println("A prepared export holds personal data; write it outside the repository or pass --force");
            return 2;
        }
        ExportPreparer.Result result = ExportPreparer.prepare(ExportFiles.read(in));
        ExportFiles.write(out, result.envelope());
        System.out.printf("Wrote %s: %d users activated, %d members in %d teams, %d SKILL_TEAM_LEADER "
                + "(who run a team beneath them and are never forecast themselves)%n",
                out, result.usersActivated(), result.teamMembers(), result.teamLeaders(), result.skillTeamLeaders());
        if (result.inNoTeam() > 0) {
            System.out.printf("  %d more carry a counted role but are in no team, so no run reaches them:"
                    + " they report to a skill team leader, a centre manager or an admin, or to nobody at all%n",
                    result.inNoTeam());
        }
        return 0;
    }

    private static int fixture(Args args) throws Exception {
        args.noFiles();
        Path dir = Path.of(args.require("out"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("workloadhub-schema.sql"), WorkloadHubSchema.readResource("/schema/workloadhub-postgresql.sql"),
                StandardCharsets.UTF_8);
        ExportEnvelope env = SeedGenerator.generate(null, SeedGenerator.FIXTURE);
        try (Writer w = Files.newBufferedWriter(dir.resolve("seeded-rows.sql"), StandardCharsets.UTF_8)) {
            SqlExportWriter.write(env, w);
        }
        System.out.println("Wrote " + dir.resolve("workloadhub-schema.sql") + " and " + dir.resolve("seeded-rows.sql")
                + " (" + env.rows("tasks").size() + " tasks, " + env.rows("time_logs").size() + " time logs)");
        return 0;
    }

    static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/workloadhub";

    static String setting(Args args, String option, String variable, String fallback) {
        if (args.has(option)) {
            return args.value(option);
        }
        String env = System.getenv(variable);
        return env == null || env.isBlank() ? fallback : env;
    }

    static String url(Args args) {
        return setting(args, "url", "WHF_DB_URL", DEFAULT_URL);
    }

    static DataSource dataSource(Args args) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url(args));
        ds.setUser(setting(args, "user", "WHF_DB_USER", "workloadhub"));
        ds.setPassword(setting(args, "password", "WHF_DB_PASSWORD", "workloadhub"));
        ds.setCurrentSchema("task_service,public");
        return ds;
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

    // ---- arguments --------------------------------------------------------------------------------------------

    /** A bad request from the user: reported without a stack trace, exit code 2. */
    static final class Bad extends RuntimeException {

        Bad(String message) {
            super(message);
        }
    }

    /**
     * The flags of one command. Options take a value, flags do not, and anything a command does not declare is an
     * error rather than a silent no-op — the old picocli tree refused unknown arguments and so does this.
     */
    static final class Args {

        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<String> files = new ArrayList<>();

        static Args parse(String[] argv, Set<String> options, Set<String> flags) {
            Args args = new Args();
            for (int i = 0; i < argv.length; i++) {
                String token = argv[i];
                if (!token.startsWith("--")) {
                    args.files.add(token);
                    continue;
                }
                String name = token.substring(2);
                if (flags.contains(name)) {
                    args.values.put(name, "");
                } else if (options.contains(name)) {
                    if (i + 1 >= argv.length) {
                        throw new Bad("--" + name + " needs a value");
                    }
                    args.values.put(name, argv[++i]);
                } else {
                    throw new Bad("unknown option '" + token + "'; try --help");
                }
            }
            return args;
        }

        boolean has(String name) {
            return values.containsKey(name) && !values.get(name).isEmpty();
        }

        boolean flag(String name) {
            return values.containsKey(name);
        }

        String value(String name) {
            return values.get(name);
        }

        String require(String name) {
            if (!has(name)) {
                throw new Bad("--" + name + " is required");
            }
            return values.get(name);
        }

        int number(String name, int fallback) {
            long value = whole(name, fallback);
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new Bad("--" + name + " is out of range: " + value);
            }
            return (int) value;
        }

        long whole(String name, long fallback) {
            if (!has(name)) {
                return fallback;
            }
            try {
                return Long.parseLong(values.get(name).trim());
            } catch (NumberFormatException e) {
                throw new Bad("--" + name + " must be a whole number, was '" + values.get(name) + "'");
            }
        }

        LocalDate date(String name) {
            if (!has(name)) {
                return null;
            }
            try {
                return LocalDate.parse(values.get(name).trim());
            } catch (DateTimeParseException e) {
                throw new Bad("--" + name + " must be an ISO date (2026-09-06), was '" + values.get(name) + "'");
            }
        }

        Path onlyFile(String what) {
            if (files.size() != 1) {
                throw new Bad("expected " + what + " as the one argument, got " + (files.isEmpty() ? "none" : files));
            }
            return Path.of(files.get(0));
        }

        void noFiles() {
            if (!files.isEmpty()) {
                throw new Bad("unexpected argument " + files + "; this command takes options only");
            }
        }
    }
}
