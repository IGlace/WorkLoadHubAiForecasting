package com.workloadhub.forecast.service;

import com.workloadhub.forecast.api.RunProgress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Live progress per run, in memory, for the JVM that runs it. Bounded to the {@link #MAX_TRACKED} most
 * recently started runs: a long-lived server would otherwise grow this map forever, one entry per run ever
 * submitted. Eviction is by insertion order, oldest first; {@code update}/{@code done}/{@code failed} touch an
 * existing entry in place and do not count as a new insert. Backed by a plain {@link LinkedHashMap}, so every
 * access is synchronised on this instance.
 */
public final class RunProgressTracker {

    static final int MAX_TRACKED = 256;

    private final Map<UUID, RunProgress> progress = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, RunProgress> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public synchronized void start(UUID runId) {
        progress.put(runId, new RunProgress(runId, "QUEUED", 0, "queued", null, null));
    }

    public synchronized void update(UUID runId, String phase, int percent, String message) {
        progress.put(runId, new RunProgress(runId, phase, percent, message, null, null));
    }

    public synchronized void done(UUID runId) {
        progress.put(runId, new RunProgress(runId, "DONE", 100, "done", null, null));
    }

    public synchronized void failed(UUID runId, String message) {
        progress.put(runId, new RunProgress(runId, "FAILED", 100, message, null, null));
    }

    public synchronized Optional<RunProgress> get(UUID runId) {
        return Optional.ofNullable(progress.get(runId));
    }
}
