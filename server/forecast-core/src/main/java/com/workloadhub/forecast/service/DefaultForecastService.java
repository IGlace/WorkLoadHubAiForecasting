package com.workloadhub.forecast.service;

import com.workloadhub.forecast.ai.AuthStatus;
import com.workloadhub.forecast.ai.CopilotConnection;
import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.NarrationOutcome;
import com.workloadhub.forecast.ai.NarrationProgress;
import com.workloadhub.forecast.ai.Narrator;
import com.workloadhub.forecast.ai.Prompts;
import com.workloadhub.forecast.ai.RuntimeInfo;
import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.ModelScore;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.ForecastRepository;
import com.workloadhub.forecast.eval.Accuracy;
import com.workloadhub.forecast.eval.Truth;
import com.workloadhub.forecast.facts.FactsBuilder;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.JdbcNarrativeStore;
import com.workloadhub.forecast.store.JdbcRunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Runs on a bounded executor, everything persisted, errors recorded on the run row. */
public final class DefaultForecastService implements ForecastService, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultForecastService.class);
    static final int MAX_LIST = 200;

    private final DataSource dataSource;
    private final Dialect dialect;
    private final ForecastRunner runner;
    private final JdbcRunStore store;
    private final RunProgressTracker progress;
    private final ExecutorService executor;
    private final Clock clock;

    public static final Duration QUOTA_TIMEOUT = Duration.ofSeconds(10);

    private final GitHubTokenStore tokens;
    private final JdbcNarrativeStore narratives;
    private final Narrator narrator;
    private final CopilotGateway gateway;

    public DefaultForecastService(DataSource dataSource, Dialect dialect, ForecastRunner runner, JdbcRunStore store, RunProgressTracker progress,
            int threads, boolean plannedWorkDefault, GitHubTokenStore tokens, JdbcNarrativeStore narratives, Narrator narrator, CopilotGateway gateway,
            Clock clock) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.runner = runner;
        this.store = store;
        this.progress = progress;
        this.tokens = tokens;
        this.narratives = narratives;
        this.narrator = narrator;
        this.gateway = gateway;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "forecast-run");
            t.setDaemon(true);
            return t;
        });
    }

    /** Called once when the module starts: runs the previous process left behind cannot be resumed (design 2026-09-11, section 4.2). */
    public int recoverInterruptedRuns() {
        int n = store.failInterrupted(LocalDateTime.now(clock));
        if (n > 0) {
            LOG.warn("marked {} run(s) left QUEUED or RUNNING by a previous process as FAILED", n);
        }
        return n;
    }

    @Override
    public UUID startRun(RunRequest request) {
        LocalDate asOf = LocalDate.now(clock);
        UUID id = enqueue(request, asOf);
        executor.submit(() -> execute(id, request, asOf));
        return id;
    }

    /** The same run, synchronously, for the CLI and the tests; throws when the run fails. */
    public RunResult runNow(RunRequest request) {
        return runNow(request, LocalDate.now(clock));
    }

    /** The synchronous run with an explicit run day: an experiment entry for seeded databases, never used by the server. */
    public RunResult runNow(RunRequest request, LocalDate asOf) {
        UUID id = enqueue(request, asOf);
        RuntimeException failure = execute(id, request, asOf);
        if (failure != null) {
            throw failure;
        }
        return getRun(id);
    }

    private UUID enqueue(RunRequest request, LocalDate asOf) {
        requireTeam(request.teamId());
        UUID id = store.create(request, asOf, LocalDateTime.now());
        progress.start(id);
        return id;
    }

    private void requireTeam(UUID teamId) {
        boolean exists = !JdbcClient.create(dataSource).sql("SELECT id FROM teams WHERE id = " + dialect.placeholder("uuid"))
                .param(teamId.toString()).query().listOfRows().isEmpty();
        if (!exists) {
            throw ForecastException.of("TEAM_NOT_FOUND", "team " + teamId + " does not exist");
        }
    }

    /** Returns the failure instead of throwing so the executor path and the synchronous path share it. */
    private RuntimeException execute(UUID id, RunRequest request, LocalDate asOf) {
        try {
            store.markRunning(id);
            progress.update(id, "LOADING", 2, "reading the WorkloadHub tables");
            ForecastData data = new ForecastRepository(JdbcClient.create(dataSource), dialect).loadAll();
            Prepared prepared = runner.prepare(data, asOf, request.forcedModel(),
                    (phase, percent, message) -> progress.update(id, phase, percent, message));
            TeamOutcome outcome = runner.forTeam(prepared, request.teamId(), request.plannedWork());
            progress.update(id, "FACTS", 85, "building the facts");
            String facts = FactsBuilder.toJson(FactsBuilder.build(outcome, id, LocalDateTime.now()));
            progress.update(id, "PERSIST", 95, "storing the run");
            store.finish(id, prepared.champion(), prepared.championMase(), backtestJson(prepared), outcome.memberWindows(), outcome.memberDays(), facts,
                    LocalDateTime.now());
            progress.done(id);
            return null;
        } catch (ForecastException e) {
            return fail(id, e, e);
        } catch (ModelUnavailable e) {
            return fail(id, e, ForecastException.of("RUN_FAILED", e.getMessage()));
        } catch (RuntimeException e) {
            return fail(id, e, ForecastException.of("RUN_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private RuntimeException fail(UUID id, RuntimeException cause, ForecastException reported) {
        LOG.error("forecast run {} failed", id, cause);
        store.fail(id, reported.getMessage(), LocalDateTime.now());
        progress.failed(id, reported.getMessage());
        return reported;
    }

    static String backtestJson(Prepared p) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> scores = new ArrayList<>();
        for (Backtest.Score s : p.backtest().scores()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", s.model());
            row.put("origin", s.origin().toString());
            row.put("horizon", s.horizon());
            row.put("mae", finite(s.mae()));
            row.put("mase", finite(s.mase()));
            scores.add(row);
        }
        root.put("scores", scores);
        Map<String, Object> mase = new TreeMap<>();
        p.backtest().meanMaseByModel().forEach((k, v) -> mase.put(k, finite(v)));
        root.put("mase_by_model", mase);
        root.put("unavailable", new TreeMap<>(p.backtest().unavailable()));
        root.put("origins", p.backtestOrigins().stream().map(LocalDate::toString).toList());
        return ExportFiles.mapper().writeValueAsString(root);
    }

    private static Double finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    @Override
    public RunResult getRun(UUID runId) {
        RunSummary run = store.find(runId).orElseThrow(() -> ForecastException.of("RUN_NOT_FOUND", "run " + runId + " not found"));
        if (run.status() != RunStatus.DONE) {
            throw ForecastException.of("RUN_NOT_DONE", "run " + runId + " is " + run.status());
        }
        JsonNode bt = ExportFiles.mapper().readTree(store.backtestJson(runId).orElse("{}"));
        List<ModelScore> scores = new ArrayList<>();
        for (JsonNode s : bt.path("scores")) {
            scores.add(new ModelScore(s.path("model").asText(), LocalDate.parse(s.path("origin").asText()), s.path("horizon").asInt(),
                    s.path("mae").isNull() ? null : s.path("mae").asDouble(), s.path("mase").isNull() ? null : s.path("mase").asDouble()));
        }
        Map<String, Double> mase = new TreeMap<>();
        bt.path("mase_by_model").properties().forEach(e -> mase.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asDouble()));
        Map<String, String> unavailable = new TreeMap<>();
        bt.path("unavailable").properties().forEach(e -> unavailable.put(e.getKey(), e.getValue().asText()));
        return new RunResult(run, scores, mase, unavailable, store.memberWindows(runId), store.memberDays(runId), store.facts(runId).orElse("{}"));
    }

    @Override
    public List<RunSummary> listRuns(UUID teamId, int limit) {
        return store.list(teamId, Math.min(MAX_LIST, Math.max(1, limit)));
    }

    @Override
    public List<CurrentDayForecast> currentForecast(UUID teamId, LocalDate from, LocalDate to) {
        if (teamId == null || from == null || to == null) {
            throw ForecastException.invalidRequest("teamId, from and to are required");
        }
        if (from.isAfter(to)) {
            throw ForecastException.invalidRequest("from " + from + " is after to " + to);
        }
        requireTeam(teamId);
        return store.currentDays(teamId, from, to);
    }

    @Override
    public AccuracyResult accuracy(UUID teamId, LocalDate from, LocalDate to) {
        if (teamId == null || from == null || to == null) {
            throw ForecastException.invalidRequest("teamId, from and to are required");
        }
        if (from.isAfter(to)) {
            throw ForecastException.invalidRequest("from " + from + " is after to " + to);
        }
        requireTeam(teamId);
        LocalDate today = LocalDate.now(clock);
        LocalDate last = to.isBefore(today) ? to : today.minusDays(1);
        if (from.isAfter(last)) {
            return new AccuracyResult(teamId, from, last, today, List.of(), List.of(new AccuracyScore(Accuracy.TEAM, teamId.toString(), 0,
                    Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN)), 0);
        }
        ForecastData data = new ForecastRepository(JdbcClient.create(dataSource), dialect).loadAll();
        return Accuracy.evaluate(teamId, from, last, today, store.currentDays(teamId, from, last), store.runDays(teamId, from, last),
                Truth.realisedHoursByDay(data));
    }

    @Override
    public RunProgress progress(UUID runId) {
        Optional<RunProgress> live = progress.get(runId);
        if (live.isPresent()) {
            return live.get();
        }
        RunSummary run = store.find(runId).orElseThrow(() -> ForecastException.of("RUN_NOT_FOUND", "run " + runId + " not found"));
        return switch (run.status()) {
            case QUEUED -> new RunProgress(runId, "QUEUED", 0, "queued", RunProgressTracker.phaseLabel("QUEUED"));
            case RUNNING -> new RunProgress(runId, "RUNNING", 50, "running in another instance", RunProgressTracker.phaseLabel("RUNNING"));
            case DONE -> new RunProgress(runId, "DONE", 100, "done", RunProgressTracker.phaseLabel("DONE"));
            case FAILED -> new RunProgress(runId, "FAILED", 100, run.error(), RunProgressTracker.phaseLabel("FAILED"));
        };
    }

    @Override
    public NarrativeResult narrate(NarrativeRequest request) {
        if (request == null || request.runId() == null) {
            throw ForecastException.invalidRequest("runId is required");
        }
        if (request.requestedBy() == null) {
            throw ForecastException.invalidRequest("requestedBy is required");
        }
        String language = request.language() == null ? "" : request.language().trim().toLowerCase(Locale.ROOT);
        if (!Prompts.SUPPORTED_LANGUAGES.contains(language)) {
            throw ForecastException.invalidRequest("language must be en or fr");
        }
        UUID runId = request.runId();
        RunSummary run = store.find(runId).orElseThrow(() -> ForecastException.of("RUN_NOT_FOUND", "run " + runId + " not found"));
        if (run.status() != RunStatus.DONE) {
            throw ForecastException.of("RUN_NOT_DONE", "run " + runId + " is " + run.status());
        }
        // The one place the module reads a user's token.
        String token = tokens.load(request.requestedBy())
                .orElseThrow(() -> ForecastException.of("TOKEN_MISSING", "no GitHub token stored for user " + request.requestedBy()));
        JsonNode facts = ExportFiles.mapper().readTree(store.facts(runId).orElse("{}"));
        NarrationProgress live = progress.narrationProgress(runId);
        NarrationOutcome outcome;
        try {
            outcome = narrator.narrate(facts, language, request.model(), token, live);
        } catch (ForecastException e) {
            progress.narrationFailed(runId, e.code() + ": " + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            LOG.error("narration of run {} failed", runId, e);
            progress.narrationFailed(runId, "COPILOT_UNAVAILABLE: " + e.getMessage());
            throw ForecastException.of("COPILOT_UNAVAILABLE", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        NarrativeResult result = narratives.save(runId, language, outcome, LocalDateTime.now());
        if (outcome.status() == NarrativeStatus.FAILED) {
            progress.narrationFailed(runId, outcome.error());
        } else {
            progress.narrated(runId);
        }
        return result;
    }

    @Override
    public Optional<NarrativeResult> narrative(UUID runId, String language) {
        if (store.find(runId).isEmpty()) {
            throw ForecastException.of("RUN_NOT_FOUND", "run " + runId + " not found");
        }
        return narratives.latest(runId, language == null ? "" : language.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public CopilotStatus copilotStatus(UUID userId) {
        if (userId == null) {
            throw ForecastException.invalidRequest("userId is required");
        }
        boolean hasToken = tokens.has(userId);
        RuntimeInfo rt = gateway.runtime();
        if (!hasToken) {
            return new CopilotStatus(userId, false, rt.available(), rt.path(), rt.version(), null, null, null, "no GitHub token stored for this user");
        }
        if (!rt.available()) {
            return new CopilotStatus(userId, true, false, rt.path(), rt.version(), null, null, null, rt.message());
        }
        String token = tokens.load(userId).orElseThrow(() -> ForecastException.of("TOKEN_MISSING", "no GitHub token stored for user " + userId));
        CopilotConnection connection;
        try {
            connection = gateway.open(token);
        } catch (ForecastException e) {
            return new CopilotStatus(userId, true, false, rt.path(), rt.version(), null, null, null, e.getMessage());
        }
        try (connection) {
            AuthStatus auth;
            try {
                auth = connection.authStatus();
            } catch (RuntimeException e) {
                return new CopilotStatus(userId, true, true, rt.path(), rt.version(), false, null, null, "could not read Copilot sign-in status: " + e.getMessage());
            }
            if (!auth.authenticated()) {
                return new CopilotStatus(userId, true, true, rt.path(), rt.version(), false, null, null,
                        auth.message() != null ? auth.message() : "the token is not accepted by Copilot");
            }
            Optional<Map<String, Object>> quota = connection.quota(QUOTA_TIMEOUT);
            String quotaJson = quota.map(q -> ExportFiles.mapper().writeValueAsString(q)).orElse(null);
            String message = "signed in as " + auth.login() + (quota.isPresent() ? "" : "; quota unavailable");
            return new CopilotStatus(userId, true, true, rt.path(), rt.version(), true, auth.login(), quotaJson, message);
        }
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
    }
}
