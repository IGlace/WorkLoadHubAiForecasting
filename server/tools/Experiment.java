import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.calendar.Horizon;
import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.data.ExportExporter;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.data.SqlExportWriter;
import com.workloadhub.forecast.eval.EvalConfig;
import com.workloadhub.forecast.eval.EvalResult;
import com.workloadhub.forecast.eval.Report;
import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecast.seed.SeedGenerator;
import com.workloadhub.forecast.store.Dialect;
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
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
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

              eval     --db FILE [--as-of ISO_DATE] [--origins N] [--windows N] [--teams a,b]
                       [--out DIR]
                       Score the booster at every origin (arrival level) and replay whole runs per team
                       (demand level); writes scores.csv, demand.csv and summary.md. --windows is the
                       window count (1 to 6, default 2); since a feature matrix is tied to the count it
                       was built with, eval always rebuilds one at the count given rather than reuse one
                       built at another. --teams takes names or ids. Without --as-of, the latest task
                       creation date. Default --out is ./eval/<as-of>.

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
                case "eval" -> eval(Args.parse(rest, Set.of("db", "as-of", "origins", "windows", "teams", "out"), Set.of()));
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

    /**
     * The only command that boots the module. Everything above talks to a class in {@code forecast-core}; this one
     * goes through {@link ForecastService#evaluate}, the same call the Spring host makes, so the scores measure the
     * engine as a host configures it rather than a copy of it assembled here.
     */
    private static int eval(Args args) throws Exception {
        args.noFiles();
        LocalDate asOf = args.date("as-of");
        int origins = args.number("origins", 6);
        int windows = args.number("windows", 2);
        if (windows < Horizon.MIN_WINDOWS || windows > Horizon.MAX_WINDOWS) {
            throw new Bad("whf.forecast.windows must be between " + Horizon.MIN_WINDOWS + " and " + Horizon.MAX_WINDOWS + ", but was " + windows);
        }
        try (ConfigurableApplicationContext ctx = boot(args.db(), windows)) {
            List<UUID> teams = new ArrayList<>();
            for (String team : split(args.string("teams", ""))) {
                teams.add(resolveTeam(ctx.getBean(JdbcClient.class), ctx.getBean(Dialect.class), team));
            }
            System.out.println("Evaluating as of " + (asOf == null ? "the latest task creation date" : asOf) + " with " + origins
                    + " origins, " + windows + " windows, teams " + (teams.isEmpty() ? "all" : teams.size()));
            EvalResult result = ctx.getBean(ForecastService.class).evaluate(new EvalConfig(asOf, origins, teams, windows));
            Path outDir = args.has("out") ? Path.of(args.value("out")) : Path.of("eval", result.resolved().asOf().toString());
            Report.write(result, Report.versions(), outDir);
            System.out.println(Report.levelA(result));
            System.out.println("Wrote " + outDir.toAbsolutePath());
            return 0;
        }
    }

    // ---- wiring -----------------------------------------------------------------------------------------------

    /** The host supplies only this; the module's auto-configuration builds {@code ForecastService} on top of it. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Module {

        private static Path file;

        @Bean
        DataSource dataSource() {
            return Experiment.dataSource(file);
        }
    }

    /**
     * A feature matrix is tied to the window count it was built with (design 2026-09-13, section 3.2), so
     * {@code eval} always boots a fresh context at the requested count rather than reuse one built at another;
     * {@code whf.forecast.windows} here is what {@code ForecastAutoConfiguration} reads to size the runner.
     */
    private static ConfigurableApplicationContext boot(Path db, int windows) {
        Module.file = db;
        return new SpringApplicationBuilder(Module.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                // the module's beans come from its auto-configuration import file, never from scanning, so the
                // warning about a @EnableAutoConfiguration class in the default package is about nothing here
                .properties(Map.of("logging.level.root", "WARN",
                        "logging.level.org.springframework.boot.autoconfigure.AutoConfigurationPackages", "ERROR",
                        "whf.forecast.windows", String.valueOf(windows)))
                .run();
    }

    static DataSource dataSource(Path db) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        return ds;
    }

    /** --teams accepts a UUID or a team name; an ambiguous or unknown name lists what there is. */
    static UUID resolveTeam(JdbcClient jdbc, Dialect dialect, String nameOrId) {
        UUID asUuid = tryUuid(nameOrId);
        if (asUuid != null) {
            boolean exists = !jdbc.sql("SELECT id FROM teams WHERE id = " + dialect.placeholder("uuid"))
                    .param(asUuid.toString()).query().listOfRows().isEmpty();
            if (!exists) {
                throw new Bad("no team with id " + asUuid);
            }
            return asUuid;
        }
        List<Map<String, Object>> rows = jdbc.sql("SELECT id, name FROM teams WHERE LOWER(name) = LOWER(?) ORDER BY name")
                .param(nameOrId.trim()).query().listOfRows();
        if (rows.size() == 1) {
            return UUID.fromString(rows.get(0).get("id").toString());
        }
        List<String> names = jdbc.sql("SELECT name FROM teams ORDER BY name").query().listOfRows().stream().map(r -> r.get("name").toString()).toList();
        throw new Bad(rows.isEmpty() ? "no team named '" + nameOrId + "'; teams: " + names
                : rows.size() + " teams named '" + nameOrId + "', use the id");
    }

    private static UUID tryUuid(String nameOrId) {
        try {
            return UUID.fromString(nameOrId.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
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

    private static List<String> split(String commaSeparated) {
        return commaSeparated.isBlank() ? List.of()
                : Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
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
