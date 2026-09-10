package com.workloadhub.forecast.service;

import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.ModelScore;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.ForecastRepository;
import com.workloadhub.forecast.facts.FactsBuilder;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.JdbcRunStore;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    public DefaultForecastService(DataSource dataSource, Dialect dialect, ForecastRunner runner, JdbcRunStore store, RunProgressTracker progress,
            int threads, boolean plannedWorkDefault) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.runner = runner;
        this.store = store;
        this.progress = progress;
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "forecast-run");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public UUID startRun(RunRequest request) {
        UUID id = enqueue(request);
        executor.submit(() -> execute(id, request));
        return id;
    }

    /** The same run, synchronously, for the CLI and the tests; throws when the run fails. */
    public RunResult runNow(RunRequest request) {
        UUID id = enqueue(request);
        RuntimeException failure = execute(id, request);
        if (failure != null) {
            throw failure;
        }
        return getRun(id);
    }

    private UUID enqueue(RunRequest request) {
        requireTeam(request.teamId());
        UUID id = store.create(request, LocalDateTime.now());
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
    private RuntimeException execute(UUID id, RunRequest request) {
        try {
            store.markRunning(id);
            progress.update(id, "LOADING", 2, "reading the WorkloadHub tables");
            ForecastData data = new ForecastRepository(JdbcClient.create(dataSource), dialect).loadAll();
            Prepared prepared = runner.prepare(data, request.asOf(), request.forcedModel(),
                    (phase, percent, message) -> progress.update(id, phase, percent, message));
            TeamOutcome outcome = runner.forTeam(prepared, request.teamId(), request.plannedWork());
            progress.update(id, "FACTS", 85, "building the facts");
            String facts = FactsBuilder.toJson(FactsBuilder.build(outcome, id, LocalDateTime.now()));
            progress.update(id, "PERSIST", 95, "storing the run");
            store.finish(id, prepared.champion(), prepared.championMase(), backtestJson(prepared), outcome.memberWeeks(), facts, LocalDateTime.now());
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
        return new RunResult(run, scores, mase, unavailable, store.memberWeeks(runId), store.facts(runId).orElse("{}"));
    }

    @Override
    public List<RunSummary> listRuns(UUID teamId, int limit) {
        return store.list(teamId, Math.min(MAX_LIST, Math.max(1, limit)));
    }

    @Override
    public RunProgress progress(UUID runId) {
        Optional<RunProgress> live = progress.get(runId);
        if (live.isPresent()) {
            return live.get();
        }
        RunSummary run = store.find(runId).orElseThrow(() -> ForecastException.of("RUN_NOT_FOUND", "run " + runId + " not found"));
        return switch (run.status()) {
            case QUEUED -> new RunProgress(runId, "QUEUED", 0, "queued", null, null);
            case RUNNING -> new RunProgress(runId, "RUNNING", 50, "running in another instance", null, null);
            case DONE -> new RunProgress(runId, "DONE", 100, "done", null, null);
            case FAILED -> new RunProgress(runId, "FAILED", 100, run.error(), null, null);
        };
    }

    @Override
    public NarrativeResult narrate(NarrativeRequest request) {
        throw ForecastException.of("COPILOT_UNAVAILABLE", "narration is not part of this build yet");
    }

    @Override
    public Optional<NarrativeResult> narrative(UUID runId, String language) {
        throw ForecastException.of("COPILOT_UNAVAILABLE", "narration is not part of this build yet");
    }

    @Override
    public CopilotStatus copilotStatus(UUID userId) {
        throw ForecastException.of("COPILOT_UNAVAILABLE", "narration is not part of this build yet");
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
    }
}
