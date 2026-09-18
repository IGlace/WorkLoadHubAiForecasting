package com.workloadhub.forecastweb.host;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunSummary;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The host's service in front of the module (design 2026-09-11, sections 3.3 to 3.6): the role check before
 * every call, one run at a time for a skill team leader, the run registry, narration on the host's own
 * two-thread executor with one narration per run and language in flight, and the token calls of the settings
 * page. This is the class the production server writes; the controllers above it only translate HTTP.
 */
public final class HostForecastFacade implements AutoCloseable {

    private final ForecastService service;
    private final ForecastAccess access;
    private final GitHubTokenStore tokens;
    private final RunRegistry registry;
    private final Clock clock;
    private final ExecutorService narrations = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "forecast-narration");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> narrationsInFlight = ConcurrentHashMap.newKeySet();

    public HostForecastFacade(ForecastService service, ForecastAccess access, GitHubTokenStore tokens, RunRegistry registry, Clock clock) {
        this.service = service;
        this.access = access;
        this.tokens = tokens;
        this.registry = registry;
        this.clock = clock;
    }

    /**
     * Whether the forecast itself is still being computed, the only thing the one-at-a-time rule looks at:
     * DONE and FAILED end a run, and every narration phase implies the run is done.
     */
    public static boolean runInProgress(String phase) {
        return phase != null && !phase.equals("DONE") && !phase.equals("FAILED") && !phase.startsWith("NARRAT");
    }

    public UUID startRun(ActingUser user, UUID teamId) {
        ForecastAccess.Decision decision = access.run(user.id(), teamId);
        if (!decision.allowed()) {
            throw new HostForbidden(decision.reason());
        }
        if (user.role().equals("SKILL_TEAM_LEADER")) {
            Optional<UUID> latest = registry.latestRunOf(user.id());
            if (latest.isPresent() && runInProgress(service.progress(latest.get()).phase())) {
                throw new HostForbidden("one team at a time: run " + latest.get() + " is still in progress");
            }
        }
        UUID id = service.startRun(new RunRequest(teamId, user.id()));
        registry.register(id, teamId, user.id(), LocalDateTime.now(clock));
        return id;
    }

    /** The team a run belongs to, from the registry; a run this host never started is not found. */
    public UUID teamOf(UUID runId) {
        return registry.teamOf(runId).orElseThrow(() -> ForecastException.of("RUN_NOT_FOUND", "run " + runId + " was not started by this host"));
    }

    public RunProgress progress(ActingUser user, UUID runId) {
        requireView(user, teamOf(runId));
        return service.progress(runId);
    }

    public RunResult getRun(ActingUser user, UUID runId) {
        requireView(user, teamOf(runId));
        return service.getRun(runId);
    }

    public List<RunSummary> listRuns(ActingUser user, UUID teamId, int limit) {
        requireView(user, teamId);
        return service.listRuns(teamId, limit);
    }

    public List<CurrentDayForecast> currentForecast(ActingUser user, UUID teamId, LocalDate from, LocalDate to) {
        requireView(user, teamId);
        return service.currentForecast(teamId, from, to);
    }

    public AccuracyResult accuracy(ActingUser user, UUID teamId, LocalDate from, LocalDate to) {
        requireView(user, teamId);
        return service.accuracy(teamId, from, to);
    }

    /**
     * Refuses before submitting when the caller cannot narrate (no token, run not done, one already in flight),
     * then runs the narration on the host's executor and returns at once; the page polls {@code progress}.
     * The token check is one query; {@code copilotStatus} opens a Copilot session and is the settings page's.
     */
    public Future<NarrativeResult> narrate(ActingUser user, UUID runId, String language, String model) {
        requireView(user, teamOf(runId));
        if (!tokens.has(user.id())) {
            throw ForecastException.of("TOKEN_MISSING", "no GitHub token stored for user " + user.id());
        }
        service.getRun(runId); // RUN_NOT_FOUND or RUN_NOT_DONE before anything is queued
        String lang = normalise(language);
        String key = runId + "|" + lang;
        if (!narrationsInFlight.add(key)) {
            throw ForecastException.of("NARRATION_IN_PROGRESS", "run " + runId + " is already being narrated in " + lang);
        }
        try {
            return narrations.submit(() -> {
                try {
                    return service.narrate(new NarrativeRequest(runId, user.id(), lang, model));
                } finally {
                    narrationsInFlight.remove(key);
                }
            });
        } catch (RuntimeException e) {
            narrationsInFlight.remove(key);
            throw e;
        }
    }

    public boolean narrationInFlight(UUID runId, String language) {
        return narrationsInFlight.contains(runId + "|" + normalise(language));
    }

    public Optional<NarrativeResult> narrative(ActingUser user, UUID runId, String language) {
        requireView(user, teamOf(runId));
        return service.narrative(runId, language);
    }

    public CopilotStatus copilotStatus(ActingUser user) {
        return service.copilotStatus(user.id());
    }

    public boolean hasToken(ActingUser user) {
        return tokens.has(user.id());
    }

    public void saveToken(ActingUser user, String token) {
        tokens.save(user.id(), token);
    }

    public void clearToken(ActingUser user) {
        tokens.clear(user.id());
    }

    public ForecastAccess access() {
        return access;
    }

    private static String normalise(String language) {
        return language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
    }

    private void requireView(ActingUser user, UUID teamId) {
        ForecastAccess.Decision decision = access.view(user.id(), teamId);
        if (!decision.allowed()) {
            throw new HostForbidden(decision.reason());
        }
    }

    @Override
    public void close() throws InterruptedException {
        narrations.shutdown();
        narrations.awaitTermination(10, TimeUnit.SECONDS);
    }
}
