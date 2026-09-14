import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.data.ExportExporter;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.data.SqlExportWriter;
import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecast.seed.SeedGenerator;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.sqlite.SQLiteDataSource;

/**
 * Builds and scores an experiment database on SQLite: the five things the owner does from a terminal that the
 * WorkloadHub server never does.
 *
 * <p>This is not a Maven module and deliberately so. Until 2026-09-12 it was {@code forecast-cli}, a Spring Boot
 * application with a picocli command tree and a repackaged jar, wrapping 408 lines around classes that all live in
 * {@code forecast-core} already. Only {@code forecast-core} ships to the host, so the wrapper was a second artifact
 * to keep green for no one's benefit — and its repackaged jar could not even be put on a classpath, because Boot
 * hides its dependencies under {@code BOOT-INF/lib}. Java 21's single-file source launcher (JEP 330) runs this file
 * directly against {@code forecast-core/target/classes}, the way {@code server/examples/HostExample.java} already
 * runs. {@code server/tools/experiment.sh} is the way in.
 *
 * <p>Consequences of having no build of its own: there is no picocli here (it is not a {@code forecast-core}
 * dependency, and a library the host ships has no business carrying a command-line parser), so {@link Args} reads
 * the flags; and this file is compiled by nobody but the launcher, so the behaviour that matters is pinned by
 * {@code ExperimentFlowTest} in {@code forecast-core}, which drives the same core calls in process.
 *
 * <p>Messages and help are English only, like the command line before it. Exit codes: 0 done, 2 a bad request,
 * 1 anything else.
 */
public final class Experiment {

    private static final String USAGE = """
            usage: experiment.sh <command> [options]

              init-db  --db FILE [--force]
                       Create the 24 WorkloadHub tables and the module's tables in a new SQLite file.
                       --force deletes the file first if it exists.

              import   --db FILE <export.json>
                       Load a WorkloadHub JSON export (real or seeded), replacing existing rows.

              export   --db FILE <out.json>
                       Write the database's WorkloadHub tables as a JSON export.

              seed     --out FILE [--export FILE] [--synthetic] [--users N] [--weeks N]
                       [--end ISO_DATE] [--seed N] [--format json|sql] [--force]
                       Generate an export with weeks of realistic history, from a real export or a
                       synthetic directory. Real mode (no --synthetic) needs --export and refuses to
                       write inside a git repository without --force: its output holds personal data.

            The default database is ./workloadhub.db. Run this inside the development container
            (bash scripts/devbox.sh shell): that is where Java, Maven and XGBoost's libgomp are.
            """;

    private Experiment() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] argv) {
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
                case "init-db" -> initDb(Args.parse(rest, Set.of("db"), Set.of("force")));
                case "import" -> importExport(Args.parse(rest, Set.of("db"), Set.of()));
                case "export" -> export(Args.parse(rest, Set.of("db"), Set.of()));
                case "seed" -> seed(Args.parse(rest, Set.of("out", "export", "users", "weeks", "end", "seed", "format"),
                        Set.of("synthetic", "force")));
                default -> {
                    System.err.println("error: unknown command '" + command + "'\n");
                    System.err.println(USAGE);
                    yield 2;
                }
            };
        } catch (Bad e) {
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

    // ---- the five commands ------------------------------------------------------------------------------------

    private static int initDb(Args args) throws Exception {
        Path db = args.db();
        if (Files.exists(db)) {
            if (!args.flag("force")) {
                System.err.println(db + " exists; use --force to recreate it");
                return 2;
            }
            Files.delete(db);
        }
        DataSource ds = dataSource(db);
        WorkloadHubSchema.createSqlite(ds);
        ForecastMigrations.run(ds);
        System.out.println("Created " + db.toAbsolutePath() + " with the WorkloadHub schema and the forecast tables");
        return 0;
    }

    private static int importExport(Args args) throws Exception {
        Path file = args.onlyFile("the export file");
        ExportEnvelope envelope = ExportFiles.read(file);
        Map<String, Integer> counts = new ExportImporter(dataSource(args.db())).importAll(envelope, true);
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
        ExportEnvelope envelope = new ExportExporter(dataSource(args.db())).exportAll();
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
        String format = args.string("format", "json");
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
        if (!format.equals("json") && !format.equals("sql")) {
            System.err.println("--format must be json or sql");
            return 2;
        }
        ExportEnvelope input = export == null ? null : ExportFiles.read(export);
        int weeks = args.number("weeks", 52);
        SeedConfig cfg = new SeedConfig(weeks, args.date("end") == null ? LocalDate.now() : args.date("end"), args.whole("seed", 42), synthetic, users);
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
        System.out.printf("Wrote %s in %d ms: %d weeks ending %s, seed %d, %s%n", out, ms, weeks, cfg.lastDay(), cfg.seed(),
                synthetic ? "synthetic identities" : "real identities (do not commit)");
        for (String table : new String[] {"users", "teams", "team_members", "projects", "tasks", "task_history", "time_logs",
                "absences", "personal_leaves", "user_capacity", "team_capacity", "holidays"}) {
            System.out.printf("  %-18s %8d%n", table, result.rows(table).size());
        }
        return 0;
    }

    static DataSource dataSource(Path db) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
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

        String string(String name, String fallback) {
            return has(name) ? values.get(name) : fallback;
        }

        String require(String name) {
            if (!has(name)) {
                throw new Bad("--" + name + " is required");
            }
            return values.get(name);
        }

        Path db() {
            return Path.of(string("db", "./workloadhub.db"));
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
