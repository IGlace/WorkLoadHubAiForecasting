package com.workloadhub.forecast.samplehost;

import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * What the real host's service looks like (design 2026-09-11, sections 3.3 to 3.6): the role check before every
 * call, one run at a time for a skill team leader, narration on the host's own two-thread executor with one
 * narration per run and language in flight, and page-style polling of the progress labels.
 */
public final class HostForecastFacade implements AutoCloseable {

    private final ForecastService service;
    private final ForecastAccess access;
    private final ExecutorService narrations = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "forecast-narration");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, UUID> teamOfRun = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> latestRunOfUser = new ConcurrentHashMap<>();
    private final Set<String> narrationsInFlight = ConcurrentHashMap.newKeySet();

    public HostForecastFacade(ForecastService service, ForecastAccess access) {
        this.service = service;
        this.access = access;
    }

    /**
     * Whether the forecast itself is still being computed, the only thing the one-at-a-time rule looks at (design
     * 2026-09-11, section 3.2): {@code DONE} and {@code FAILED} end a run, and every narration phase (the tracker
     * reuses the run's entry for {@code NARRATING}, {@code NARRATED} and {@code NARRATION_FAILED}) implies the run
     * is done; narration has its own guard (section 3.4). Any other phase is a step of the run.
     */
    static boolean runInProgress(String phase) {
        return phase != null && !phase.equals("DONE") && !phase.equals("FAILED") && !phase.startsWith("NARRAT");
    }

    public UUID startRun(UUID userId, UUID teamId) {
        if (!access.canRun(userId, teamId)) {
            throw new HostForbidden("user " + userId + " may not run a forecast for team " + teamId);
        }
        if (access.roleOf(userId).equals("SKILL_TEAM_LEADER")) {
            UUID latest = latestRunOfUser.get(userId);
            if (latest != null && runInProgress(service.progress(latest).phase())) {
                throw new HostForbidden("one team at a time: run " + latest + " is still in progress");
            }
        }
        UUID id = service.startRun(new RunRequest(teamId, userId, null, null));
        teamOfRun.put(id, teamId);
        latestRunOfUser.put(userId, id);
        return id;
    }

    private UUID teamOf(UUID runId) {
        UUID team = teamOfRun.get(runId);
        if (team == null) {
            throw ForecastException.of("RUN_NOT_FOUND", "run " + runId + " was not started by this host");
        }
        return team;
    }

    public RunProgress progress(UUID userId, UUID runId) {
        requireView(userId, teamOf(runId));
        return service.progress(runId);
    }

    public List<CurrentDayForecast> currentForecast(UUID userId, UUID teamId, LocalDate from, LocalDate to) {
        requireView(userId, teamId);
        return service.currentForecast(teamId, from, to);
    }

    /** Refuses before submitting when the caller cannot narrate (no token, run not done, one already in flight). */
    public Future<NarrativeResult> narrate(UUID userId, UUID runId, String language) {
        requireView(userId, teamOf(runId));
        if (!service.copilotStatus(userId).hasToken()) {
            throw ForecastException.of("TOKEN_MISSING", "no GitHub token stored for user " + userId);
        }
        service.getRun(runId); // RUN_NOT_FOUND or RUN_NOT_DONE before anything is queued
        String key = runId + "|" + language;
        if (!narrationsInFlight.add(key)) {
            throw ForecastException.of("NARRATION_IN_PROGRESS", "run " + runId + " is already being narrated in " + language);
        }
        return narrations.submit(() -> {
            try {
                return service.narrate(new NarrativeRequest(runId, userId, language, null));
            } finally {
                narrationsInFlight.remove(key);
            }
        });
    }

    public Optional<NarrativeResult> narrative(UUID userId, UUID runId, String language) {
        requireView(userId, teamOf(runId));
        return service.narrative(runId, language);
    }

    /** What a page does: poll until the phase is one of {@code phases}, collecting the labels it showed. */
    public List<RunProgress> waitFor(UUID userId, UUID runId, Set<String> phases, Duration timeout) throws InterruptedException {
        List<RunProgress> seen = new java.util.ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            RunProgress p = progress(userId, runId);
            if (seen.isEmpty() || !seen.get(seen.size() - 1).label().equals(p.label()) || !seen.get(seen.size() - 1).phase().equals(p.phase())) {
                seen.add(p);
            }
            if (phases.contains(p.phase())) {
                return seen;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("run " + runId + " did not reach " + phases + " within " + timeout);
    }

    private void requireView(UUID userId, UUID teamId) {
        if (!access.canView(userId, teamId)) {
            throw new HostForbidden("user " + userId + " may not view team " + teamId);
        }
    }

    @Override
    public void close() throws InterruptedException {
        narrations.shutdown();
        narrations.awaitTermination(10, TimeUnit.SECONDS);
    }
}
