package com.workloadhub.forecast.service;

import com.workloadhub.forecast.api.RunProgress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Live progress per run, in memory, for the JVM that runs it. */
public final class RunProgressTracker {

    private final Map<UUID, RunProgress> progress = new ConcurrentHashMap<>();

    public void start(UUID runId) {
        progress.put(runId, new RunProgress(runId, "QUEUED", 0, "queued", null, null));
    }

    public void update(UUID runId, String phase, int percent, String message) {
        progress.put(runId, new RunProgress(runId, phase, percent, message, null, null));
    }

    public void done(UUID runId) {
        progress.put(runId, new RunProgress(runId, "DONE", 100, "done", null, null));
    }

    public void failed(UUID runId, String message) {
        progress.put(runId, new RunProgress(runId, "FAILED", 100, message, null, null));
    }

    public Optional<RunProgress> get(UUID runId) {
        return Optional.ofNullable(progress.get(runId));
    }
}
