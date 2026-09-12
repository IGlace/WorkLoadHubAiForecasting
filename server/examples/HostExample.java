package com.workloadhub.forecast.examples;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.api.ModelScore;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
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
 * Every call the WorkloadHub server makes into this module, in one runnable file.
 *
 * <p>Run it inside the development container, against the seeded SQLite database:
 *
 * <pre>
 * bash scripts/devbox.sh shell
 * bash server/examples/run-host-example.sh                    # lists the teams and stops
 * bash server/examples/run-host-example.sh --team &lt;uuid&gt;      # the whole round trip
 * </pre>
 *
 * <p>What is real host code and what is only here so the example runs alone:
 *
 * <ul>
 *   <li><b>Real.</b> The two beans below ({@code DataSource}, and nothing else: the module's
 *       auto-configuration builds {@code ForecastService} and everything under it on top of the host's
 *       own {@code DataSource}), and every {@code ForecastService} call in {@link Calls}.
 *   <li><b>Only for the example.</b> The fixed {@link Clock} (the server leaves the module's default,
 *       {@code Clock.systemDefaultZone()}, alone — this one is pinned to the seed's last day so a run has
 *       history to learn from), the team listing in {@link #pickTeam}, and printing to stdout instead of
 *       returning JSON from a controller.
 *   <li><b>Missing on purpose.</b> The role check before every call, the one-run-at-a-time rule per leader
 *       and the narration executor: those belong to the host, not to this module. The shape they take is in
 *       {@code forecast-core/src/test/java/com/workloadhub/forecast/samplehost/HostForecastFacade.java},
 *       which is the reference for the server's own service class.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class HostExample {

    /**
     * The host's own DataSource. In the server this is the application's PostgreSQL pool, configured by
     * {@code spring.datasource.*} and never built by hand; the module reads the dialect off it.
     */
    @Bean
    DataSource dataSource(@Value("${example.db}") String file) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file);
        return ds;
    }

    /**
     * Example only. {@code startRun} takes the run day from this clock, and the seeded database ends on
     * 2026-09-06; with the real clock a run would forecast from today, find no history and produce nothing
     * worth looking at. A server deletes this bean.
     */
    @Bean
    Clock clock(@Value("${example.as-of}") String asOf) {
        return Clock.fixed(LocalDate.parse(asOf).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = options(args);
        if (opts.containsKey("help")) {
            System.out.println("""
                    usage: run-host-example.sh [--db FILE] [--team UUID] [--as-of ISO_DATE] [--narrate] [--lang en|fr]

                      --db       SQLite file to read (default /data/workloadhub.db)
                      --team     the team to forecast; without it the example lists the teams and stops
                      --as-of    the run day the fixed clock reports (default 2026-09-06, the seed's last day)
                      --narrate  also ask Copilot for the narrative; needs WHF_TOKEN_KEY and a stored token
                      --lang     narrative language, en or fr (default en)""");
            return;
        }
        String db = opts.getOrDefault("db", "/data/workloadhub.db");
        SpringApplicationBuilder app = new SpringApplicationBuilder(HostExample.class)
                .web(WebApplicationType.NONE)      // this example has no HTTP surface; the server has its own
                .bannerMode(Banner.Mode.OFF)
                .properties(Map.of(
                        "example.db", db,
                        "example.as-of", opts.getOrDefault("as-of", "2026-09-06"),
                        // The whf.* properties a host sets. run-threads 1 keeps the output in order; the server's
                        // default is 2. token-key is the base64 32-byte key that encrypts the stored GitHub tokens:
                        // without it a token can neither be saved nor read, which is all narration needs.
                        "whf.run-threads", "1",
                        "whf.token-key", System.getenv().getOrDefault("WHF_TOKEN_KEY", ""),
                        "whf.default-weekly-hours", "44",
                        "whf.planned-work.enabled", "true",
                        // The module owns its own tables and migrates them at start-up. A host that runs the
                        // module's migrations itself sets this to false instead.
                        "whf.flyway.enabled", "true",
                        "logging.level.root", "WARN"));
        try (ConfigurableApplicationContext ctx = app.run()) {
            // The one bean the host talks to. Everything below is a call it could make from a controller.
            ForecastService service = ctx.getBean(ForecastService.class);
            JdbcClient jdbc = ctx.getBean(JdbcClient.class);
            UUID team = pickTeam(jdbc, opts.get("team"));
            if (team == null) {
                return;
            }
            Calls calls = new Calls(service, ctx.getBean(GitHubTokenStore.class), LocalDate.parse(opts.getOrDefault("as-of", "2026-09-06")),
                    leaderOf(jdbc, team), namesOf(jdbc));
            calls.run(team, opts.containsKey("narrate"), opts.getOrDefault("lang", "en"));
        }
    }

    /** Example only: a server knows its team ids from its own pages. Prints the teams when --team is absent. */
    private static UUID pickTeam(JdbcClient jdbc, String requested) {
        if (requested != null) {
            return UUID.fromString(requested);
        }
        System.out.printf("%-38s %s%n", "team id", "name");
        for (Map<String, Object> row : jdbc.sql("SELECT id, name FROM teams ORDER BY name").query().listOfRows()) {
            System.out.printf("%-38s %s%n", row.get("id"), row.get("name"));
        }
        System.out.println("\npass one of these as --team <uuid>");
        return null;
    }

    /**
     * Example only: the leader stands in for the authenticated user, because a token can only be stored for a
     * user who exists. A server passes the session's own user id and has already checked the role.
     */
    private static UUID leaderOf(JdbcClient jdbc, UUID team) {
        return jdbc.sql("SELECT u.id FROM users u JOIN team_members tm ON tm.user_id = u.id WHERE tm.team_id = ? AND u.role = 'TEAM_LEADER'")
                .param(team.toString()).query(String.class).optional().map(UUID::fromString).orElse(null);
    }

    /** Example only: the module answers in user ids, and the host already has the names. */
    private static Map<UUID, String> namesOf(JdbcClient jdbc) {
        Map<UUID, String> names = new HashMap<>();
        for (Map<String, Object> row : jdbc.sql("SELECT id, full_name FROM users").query().listOfRows()) {
            names.put(UUID.fromString((String) row.get("id")), (String) row.get("full_name"));
        }
        return names;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = args[i].replaceFirst("^--", "");
            boolean flag = name.equals("narrate") || name.equals("help");
            opts.put(name, flag ? "" : i + 1 < args.length ? args[++i] : "");
        }
        return opts;
    }

    /**
     * The nine methods of {@link ForecastService}, in the order a host uses them. Each one is what a single
     * endpoint would do: no other class of this module is on the host's import list.
     */
    record Calls(ForecastService service, GitHubTokenStore tokens, LocalDate asOf, UUID user, Map<UUID, String> names) {

        private String name(UUID id) {
            return names.getOrDefault(id, String.valueOf(id));
        }

        void run(UUID team, boolean narrate, String language) throws Exception {
            System.out.println("acting as " + name(user) + " (this team's leader; a server passes the session's own user)");
            UUID runId = startAndWait(team);
            if (runId == null) {
                return;
            }
            result(runId);
            currentForecast(team);
            listRuns(team);
            accuracy(team);
            copilot(runId, narrate, language);
        }

        /**
         * POST /teams/{id}/forecast-runs. {@code startRun} returns at once with the run's id and computes on the
         * module's own executor; the browser then polls {@code progress} — a run is a minute or two of work.
         * {@code requestedBy} is the authenticated user, and may be null; the host records (runId, teamId,
         * requestedBy) in its own table, so a restart can still authorize a poll. The third argument forces a
         * model ({@code "xgboost"} or {@code "seasonal_naive"}, null to let the backtest choose) and the fourth
         * switches the planned-work allocation off for this run (null keeps {@code whf.planned-work.enabled}).
         */
        private UUID startAndWait(UUID team) throws InterruptedException {
            UUID runId;
            try {
                runId = service.startRun(new RunRequest(team, user, null, null));
            } catch (ForecastException e) {
                // Every failure of this module arrives as one of these, with a code a controller maps to a status:
                // *_NOT_FOUND to 404, INVALID_REQUEST to 400, the rest (TOKEN_MISSING, COPILOT_UNAVAILABLE,
                // RUN_NOT_DONE, RUN_FAILED) to 409; a refused role check is the host's own 403. Refusing a second
                // run for a leader who already has one is the host's rule too, and has no code here.
                System.out.println("startRun refused: " + e.code() + ": " + e.getMessage());
                return null;
            }
            System.out.println("startRun -> " + runId + "  (run day " + asOf + ", the fixed clock's today)");

            // GET /forecast-runs/{id}/progress, once a second from the page. The label is what the person reads,
            // in both languages; the phase is what code branches on.
            long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            String phase = "";
            while (System.nanoTime() < deadline) {
                RunProgress p = service.progress(runId);
                if (!p.phase().equals(phase)) {
                    phase = p.phase();
                    System.out.printf("  progress %-12s %3d%%  %s / %s%n", p.phase(), p.percent(), p.label().en(), p.label().fr());
                }
                if (p.phase().equals("DONE") || p.phase().equals("FAILED")) {
                    return p.phase().equals("DONE") ? runId : null;
                }
                Thread.sleep(500);
            }
            System.out.println("  gave up waiting");
            return null;
        }

        /**
         * GET /forecast-runs/{id}. The whole run: the champion model and its backtest score, the per-model
         * scores, whatever model was unavailable and why, the two five-weekday windows per member, the per-day
         * rows behind them, and the exact facts the narrator is allowed to see.
         *
         * <p>The model is trained and scored once over the whole history, then applied per team, so two teams
         * forecast on the same day report the same champion and the same MASE; only the windows below are the
         * team's own. A MASE of 1.0 for {@code seasonal_naive} is not a coincidence either: MASE is that model's
         * error taken as the unit, so anything under 1 beats "the same as last week".
         */
        private void result(UUID runId) {
            RunResult r = service.getRun(runId);
            RunSummary run = r.run();
            System.out.printf("%ngetRun: champion %s, MASE %s, status %s, %d member-windows, %d member-days%n",
                    run.championModel(), run.championMase(), run.status(), r.memberWindows().size(), r.memberDays().size());
            for (Map.Entry<String, Double> e : r.maseByModel().entrySet()) {
                System.out.printf("  model %-16s mean MASE %.3f%n", e.getKey(), e.getValue());
            }
            r.unavailable().forEach((model, why) -> System.out.println("  unavailable " + model + ": " + why));
            List<ModelScore> scores = r.scores();
            System.out.println("  " + scores.size() + " backtest scores, e.g. " + (scores.isEmpty() ? "none" : scores.get(0)));

            // The overload the product exists for. Demand is never capped by capacity: overload is the excess.
            System.out.println("  overloaded members, window by window:");
            boolean any = false;
            for (MemberWindowForecast w : r.memberWindows()) {
                if (w.overloadHrs() > 0) {
                    any = true;
                    System.out.printf("    %-20s window %d %s..%s demand %.1f h vs capacity %.1f h -> overload %.1f h (band %.1f..%.1f)%n",
                            name(w.userId()), w.windowIndex(), w.windowStart(), w.windowEnd(), w.demandHrs(), w.capacityHrs(), w.overloadHrs(),
                            w.lowHrs(), w.highHrs());
                }
            }
            if (!any) {
                System.out.println("    none");
            }
            System.out.println("  facts sent to Copilot, stored for audit: " + r.factsJson().length() + " JSON characters");
        }

        /**
         * GET /teams/{id}/forecast?from&amp;to. The team page reads this, not a run: it is the latest value per
         * member and day, whichever run produced it, so the page keeps working while a new run computes.
         */
        private void currentForecast(UUID team) {
            LocalDate from = asOf.plusDays(1);
            List<CurrentDayForecast> days = service.currentForecast(team, from, from.plusDays(13));
            System.out.printf("%ncurrentForecast %s..%s: %d rows%n", from, from.plusDays(13), days.size());
            Map<UUID, double[]> perMember = new HashMap<>();
            for (CurrentDayForecast d : days) {
                double[] sum = perMember.computeIfAbsent(d.userId(), k -> new double[3]);
                sum[0] += d.demandHrs();
                sum[1] += d.capacityHrs();
                sum[2] += d.overloadHrs();
            }
            // Summing the days is the page's own arithmetic, and the overload column does not follow from the
            // other two: it is measured day by day, so eight idle hours on Monday never pay for four extra on
            // Tuesday. A member under capacity over two weeks can still be overloaded on six of its days.
            System.out.println("  summed over those days, per member:");
            perMember.entrySet().stream().limit(5).forEach(e -> System.out.printf("    %-20s demand %.1f h, capacity %.1f h, overload %.1f h%n",
                    name(e.getKey()), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
            if (perMember.size() > 5) {
                System.out.println("    ... " + (perMember.size() - 5) + " more members");
            }
        }

        /** GET /teams/{id}/forecast-runs?limit. The run history a leader sees; the limit is clamped by the module. */
        private void listRuns(UUID team) {
            List<RunSummary> runs = service.listRuns(team, 5);
            System.out.printf("%nlistRuns: %d%n", runs.size());
            for (RunSummary s : runs) {
                System.out.printf("  %s  as-of %s  %-6s  %-14s  MASE %s  %s%n", s.id(), s.asOf(), s.status(), s.championModel(), s.championMase(),
                        s.finishedAt());
            }
        }

        /**
         * GET /teams/{id}/accuracy?from&amp;to. Compares what was forecast for each past weekday, before it
         * arrived, with the hours actually logged on it. Only days already past are scored, so on a seeded
         * database that has never had a run before its last day this is legitimately empty.
         */
        private void accuracy(UUID team) {
            AccuracyResult a = service.accuracy(team, asOf.minusDays(28), asOf.minusDays(1));
            System.out.printf("%naccuracy %s..%s: %d rows, %d scores, %d non-working days%n", a.from(), a.to(), a.current().size(), a.scores().size(),
                    a.nonWorkingDays());
            for (AccuracyScore s : a.scores()) {
                if (s.scope().equals("team")) {
                    System.out.printf("  team n=%d MAE %.2f bias %+.2f MASE %.3f (on %d rows) overload precision %.2f recall %.2f%n",
                            s.n(), s.mae(), s.bias(), s.mase(), s.maseN(), s.overloadPrecision(), s.overloadRecall());
                }
            }
            List<AccuracyRow> rows = new ArrayList<>(a.current());
            if (rows.isEmpty()) {
                System.out.println("  no scored day: nothing in this window was forecast before it arrived. Run once with"
                        + " --as-of 2026-08-20 and once at the default as-of, and this table fills in.");
            }
            rows.stream().limit(3).forEach(r -> System.out.printf("    %-20s %s lead %d: forecast %.1f h, logged %.1f h%n",
                    name(r.userId()), r.day(), r.lead(), r.forecastHrs(), r.loggedHrs()));
        }

        /**
         * GET /me/copilot and POST /forecast-runs/{id}/narrative. {@code copilotStatus} is what the settings page
         * shows before anyone asks for a narrative: whether this user has a token, whether the SDK runtime is
         * unpacked, and what GitHub says about the seat. {@code narrate} then blocks for as long as the model
         * takes, so the host calls it on its own executor and the page polls {@code progress} (NARRATING,
         * NARRATED, NARRATION_FAILED) and finally {@code narrative(runId, language)} for the stored text.
         */
        private void copilot(UUID runId, boolean narrate, String language) {
            CopilotStatus status = service.copilotStatus(user);
            System.out.printf("%ncopilotStatus(%s): token %s, runtime %s (%s), authenticated %s, login %s%n  %s%n", name(user), status.hasToken(),
                    status.runtimeAvailable(), status.runtimeVersion(), status.authenticated(), status.login(), status.message());

            // Already narrated? A page that reloads reads the stored narrative instead of paying for a new one.
            Optional<NarrativeResult> stored = service.narrative(runId, language);
            System.out.println("narrative(" + language + ") already stored: " + stored.map(n -> n.status().toString()).orElse("no"));

            if (!narrate) {
                System.out.println("  (pass --narrate to call the model; it needs WHF_TOKEN_KEY and a token saved for this user)");
                return;
            }
            String token = System.getenv("WHF_EXAMPLE_GH_TOKEN");
            if (token != null && !token.isBlank()) {
                tokens.save(user, token.trim());      // the host does this once, from its settings page
            }
            NarrativeResult result = service.narrate(new NarrativeRequest(runId, user, language, null));
            System.out.printf("narrate -> %s in %d attempts, %d tool calls%n", result.status(), result.attempts(), result.toolCalls());
            System.out.println("  " + (result.narrativeJson() != null ? result.narrativeJson() : result.error() + " / " + result.rawText()));
            // Every number the model wrote is checked against the facts; an unverified one is reported, never promoted.
            System.out.println("  verification: " + result.verificationJson());
        }
    }
}
