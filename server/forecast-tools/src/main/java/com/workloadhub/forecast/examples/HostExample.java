package com.workloadhub.forecast.examples;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.BacktestScore;
import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecast.Json;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Every call the WorkloadHub server makes into this module, in one runnable file.
 *
 * <p>Run it inside the development container, against the local PostgreSQL scripts/postgres.sh runs:
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
 *   <li><b>Real.</b> Nothing but the properties: the module's auto-configuration builds
 *       {@code ForecastService} and everything under it on top of the {@code DataSource} Spring Boot makes
 *       from {@code spring.datasource.*}, which the server already has. Every {@code ForecastService} call
 *       in {@link Calls} is real.
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
                    usage: run-host-example.sh [--url JDBC_URL] [--user USER] [--password PASSWORD] [--team UUID] [--as-of ISO_DATE] [--narrate] [--lang en|fr]

                      --url       the PostgreSQL database (default WHF_DB_URL, else jdbc:postgresql://localhost:5432/workloadhub)
                      --user      (default WHF_DB_USER, else workloadhub)
                      --password  (default WHF_DB_PASSWORD, else workloadhub)
                      --team      the team to forecast, one the listing marks TEAM_LEADER (the example acts as
                                  that user); without it the example lists the teams and stops
                      --as-of     the run day the fixed clock reports (default 2026-09-06, the seed's last day)
                      --narrate   also ask Copilot for the narrative; needs WHF_TOKEN_KEY and a stored token
                      --lang      narrative language, en or fr (default en)""");
            return;
        }
        String url = setting(opts, "url", "WHF_DB_URL", "jdbc:postgresql://localhost:5432/workloadhub");
        String user = setting(opts, "user", "WHF_DB_USER", "workloadhub");
        String password = setting(opts, "password", "WHF_DB_PASSWORD", "workloadhub");
        SpringApplicationBuilder app = new SpringApplicationBuilder(HostExample.class)
                .web(WebApplicationType.NONE)      // this example has no HTTP surface; the server has its own
                .bannerMode(Banner.Mode.OFF)
                .properties(Map.ofEntries(
                        // The host's own pool, built by Spring Boot from these: exactly what the server has already.
                        Map.entry("spring.datasource.url", url),
                        Map.entry("spring.datasource.username", user),
                        Map.entry("spring.datasource.password", password),
                        // The WorkloadHub tables and the module's live in task_service.
                        Map.entry("spring.datasource.hikari.data-source-properties.currentSchema", "task_service,public"),
                        Map.entry("example.as-of", opts.getOrDefault("as-of", "2026-09-06")),
                        // The whf.* properties a host sets. run-threads 1 keeps the output in order; the server's
                        // default is 2. token-key is the base64 32-byte key that encrypts the stored GitHub tokens:
                        // without it a token can neither be saved nor read, which is all narration needs.
                        Map.entry("whf.run-threads", "1"),
                        Map.entry("whf.token-key", System.getenv().getOrDefault("WHF_TOKEN_KEY", "")),
                        // The rolling horizon: how many five-weekday windows a run forecasts, 1 to 6 (design
                        // 2026-09-13, section 4). This is the default already, set here only to show where a
                        // host would choose a different one.
                        Map.entry("whf.forecast.windows", "2"),
                        // The module owns its own tables and migrates them at start-up. A host that runs the
                        // module's migrations itself sets this to false instead.
                        Map.entry("whf.flyway.enabled", "true"),
                        Map.entry("logging.level.root", "WARN")));
        try (ConfigurableApplicationContext ctx = app.run()) {
            // The one bean the host talks to. Everything below is a call it could make from a controller.
            ForecastService service = ctx.getBean(ForecastService.class);
            JdbcClient jdbc = ctx.getBean(JdbcClient.class);
            UUID team = pickTeam(jdbc, opts.get("team"));
            if (team == null) {
                return;
            }
            UUID leader = leaderOf(jdbc, team);
            if (leader == null) {
                // Example only, and the only thing that can go wrong before a single call is made: this example
                // has no session to take a user from, so a team with no TEAM_LEADER leaves it nobody to act as.
                // Said here rather than a minute of real computing later, where `startRun` would refuse with
                // INVALID_REQUEST: userId is required, out of main and as a stack trace.
                System.err.println("no TEAM_LEADER on team " + team + ", so the example has no user to act as.");
                System.err.println("Run without --team and pass one of the teams the listing marks TEAM_LEADER.");
                return;
            }
            Calls calls = new Calls(service, ctx.getBean(GitHubTokenStore.class), LocalDate.parse(opts.getOrDefault("as-of", "2026-09-06")),
                    leader, namesOf(jdbc));
            calls.run(team, opts.containsKey("narrate"), opts.getOrDefault("lang", "en"));
        }
    }

    /**
     * Example only: a server knows its team ids from its own pages, and its session's user. Prints the teams
     * when --team is absent, each with whether it has a {@code TEAM_LEADER} — see {@link #leaderOf}, which has
     * nobody to act as without one. The seed leaves plenty of teams without: a department team's head is a
     * {@code SKILL_TEAM_LEADER}, and a manager who is also an {@code ADMIN} keeps that role.
     */
    private static UUID pickTeam(JdbcClient jdbc, String requested) {
        if (requested != null) {
            return UUID.fromString(requested);
        }
        System.out.printf("%-38s %-14s %s%n", "team id", "leader", "name");
        for (Map<String, Object> row : jdbc.sql("""
                SELECT t.id, t.name, (SELECT COUNT(*) FROM team_members tm JOIN users u ON u.id = tm.user_id
                                      WHERE tm.team_id = t.id AND u.role = 'TEAM_LEADER') AS leaders
                FROM teams t ORDER BY t.name""").query().listOfRows()) {
            boolean hasLeader = ((Number) row.get("leaders")).intValue() > 0;
            System.out.printf("%-38s %-14s %s%n", row.get("id"), hasLeader ? "TEAM_LEADER" : "-", row.get("name"));
        }
        System.out.println("\npass one of the teams marked TEAM_LEADER as --team <uuid>: the example acts as that user,"
                + "\nand the ones marked - have nobody it could act as");
        return null;
    }

    /**
     * Example only: the leader stands in for the authenticated user, because a token can only be stored for a
     * user who exists. A server passes the session's own user id and has already checked the role.
     */
    private static UUID leaderOf(JdbcClient jdbc, UUID team) {
        return jdbc.sql("SELECT u.id FROM users u JOIN team_members tm ON tm.user_id = u.id WHERE tm.team_id = ? AND u.role = 'TEAM_LEADER'")
                .param(team).query(String.class).optional().map(UUID::fromString).orElse(null);
    }

    /** Example only: the module answers in user ids, and the host already has the names. */
    private static Map<UUID, String> namesOf(JdbcClient jdbc) {
        Map<UUID, String> names = new HashMap<>();
        for (Map<String, Object> row : jdbc.sql("SELECT id, full_name FROM users").query().listOfRows()) {
            names.put((UUID) row.get("id"), (String) row.get("full_name"));
        }
        return names;
    }

    private static String setting(Map<String, String> opts, String option, String variable, String fallback) {
        if (opts.containsKey(option) && !opts.get(option).isEmpty()) {
            return opts.get(option);
        }
        String env = System.getenv(variable);
        return env == null || env.isBlank() ? fallback : env;
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
     * Nine of {@link ForecastService}'s eleven methods, in the order a host uses them. Each one is what a single
     * endpoint would do: no other class of this module is on the host's import list. {@code findRun} and
     * {@code latestRunOf} are not called here: they exist to authorize a poll after a restart, which this
     * synchronous, one-run-at-a-time example never needs.
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
         * {@code requestedBy} is the authenticated user, and may be null; the host needs no table of its own
         * to authorize a later poll after a restart, since {@code findRun(runId)} answers for a run whatever
         * its status.
         */
        private UUID startAndWait(UUID team) throws InterruptedException {
            UUID runId;
            try {
                runId = service.startRun(new RunRequest(team, user));
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
         * GET /forecast-runs/{id}. The whole run: the booster's backtest MAE, the per-origin scores behind it,
         * the windows of the rolling horizon per member, the per-day rows behind them, and the exact facts the
         * narrator is allowed to see.
         *
         * <p>The model is trained and scored once over the whole history, then applied per team, so two teams
         * forecast on the same day report the same MAE; only the windows below are the team's own.
         * {@code mean_actual_hours} and {@code confidence} live only in the facts JSON — {@link RunSummary}
         * carries {@code mae} alone, which is why {@link #listRuns} below cannot show them.
         */
        private void result(UUID runId) throws Exception {
            RunResult r = service.getRun(runId);
            RunSummary run = r.run();
            JsonNode model = Json.mapper().readTree(r.factsJson()).path("model");
            System.out.printf("%ngetRun: mae %s, mean_actual_hours %s, confidence %s, status %s, %d member-windows, %d member-days%n",
                    run.mae(), model.path("mean_actual_hours").asString("null"), model.path("confidence").asString("null"), run.status(),
                    r.memberWindows().size(), r.memberDays().size());
            List<BacktestScore> scores = r.scores();
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

        /**
         * GET /teams/{id}/forecast-runs?limit. The run history a leader sees; the limit is clamped by the module.
         * {@code mae} is the only model figure a {@link RunSummary} carries — {@code mean_actual_hours} and
         * {@code confidence} live only in the facts JSON of one run's own {@link RunResult}, read in
         * {@link #result}, not in this list.
         */
        private void listRuns(UUID team) {
            List<RunSummary> runs = service.listRuns(team, 5);
            System.out.printf("%nlistRuns: %d%n", runs.size());
            for (RunSummary s : runs) {
                System.out.printf("  %s  as-of %s  %-6s  mae %s  %s%n", s.id(), s.asOf(), s.status(), s.mae(), s.finishedAt());
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
            String token = System.getenv("WHF_EXAMPLE_GH_TOKEN");
            if (token != null && !token.isBlank()) {
                tokens.save(user, token.trim());      // the host does this once, from its settings page
            }
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
            NarrativeResult result = service.narrate(new NarrativeRequest(runId, user, language, null));
            System.out.printf("narrate -> %s in %d attempts, %d tool calls%n", result.status(), result.attempts(), result.toolCalls());
            System.out.println("  " + (result.narrativeJson() != null ? result.narrativeJson() : result.error() + " / " + result.rawText()));
            // Every number the model wrote is checked against the facts; an unverified one is reported, never promoted.
            System.out.println("  verification: " + result.verificationJson());
        }
    }
}
