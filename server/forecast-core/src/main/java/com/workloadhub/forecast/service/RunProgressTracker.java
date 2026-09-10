package com.workloadhub.forecast.service;

import com.workloadhub.forecast.ai.NarrationProgress;
import com.workloadhub.forecast.api.RunProgress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Live progress per run, in memory, for the JVM that runs it: the run's phases, then the narration's steps and the
 * tail of what Copilot is thinking and answering. Bounded to the {@link #MAX_TRACKED} most recently started runs
 * (insertion order, oldest evicted) and to {@link #TAIL_CHARS} characters per live text. Every access is
 * synchronised on this instance.
 */
public final class RunProgressTracker {

    static final int MAX_TRACKED = 256;
    public static final int TAIL_CHARS = 16_000;

    private static final class Entry {
        String phase;
        int percent;
        String message;
        StringBuilder thinking; // null outside narration
        StringBuilder answer;

        Entry(String phase, int percent, String message) {
            this.phase = phase;
            this.percent = percent;
            this.message = message;
        }

        RunProgress view(UUID runId) {
            return new RunProgress(runId, phase, percent, message, thinking == null ? null : thinking.toString(), answer == null ? null : answer.toString());
        }
    }

    private final Map<UUID, Entry> progress = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public synchronized void start(UUID runId) {
        progress.put(runId, new Entry("QUEUED", 0, "queued"));
    }

    public synchronized void update(UUID runId, String phase, int percent, String message) {
        progress.put(runId, new Entry(phase, percent, message));
    }

    public synchronized void done(UUID runId) {
        progress.put(runId, new Entry("DONE", 100, "done"));
    }

    public synchronized void failed(UUID runId, String message) {
        progress.put(runId, new Entry("FAILED", 100, message));
    }

    public synchronized Optional<RunProgress> get(UUID runId) {
        Entry e = progress.get(runId);
        return e == null ? Optional.empty() : Optional.of(e.view(runId));
    }

    // ----- narration ---------------------------------------------------------------------------

    private Entry narrating(UUID runId) {
        Entry e = progress.get(runId);
        if (e == null || e.thinking == null) {
            e = new Entry("NARRATING", 0, "starting Copilot");
            e.thinking = new StringBuilder();
            e.answer = new StringBuilder();
            progress.put(runId, e);
        }
        e.phase = "NARRATING";
        return e;
    }

    public synchronized void narration(UUID runId, NarrationProgress.Step step, String detail) {
        Entry e = narrating(runId);
        switch (step) {
            case STARTING -> {
                e.percent = 5;
                e.message = "starting Copilot";
            }
            case SESSION -> {
                e.percent = 10;
                e.message = "creating session";
            }
            case ASKING -> {
                e.percent = 20;
                e.message = detail == null ? "asking Copilot" : "asking Copilot (attempt " + detail + ")";
            }
            case TOOL -> {
                e.percent = Math.min(80, Math.max(20, e.percent) + 5);
                e.message = detail == null ? "tool" : "tool " + detail;
            }
            case TOOL_DONE -> e.message = detail == null ? "tool done" : "tool " + detail + " done";
            case CHECKING -> {
                e.percent = 90;
                e.message = "checking the answer against the facts";
            }
        }
    }

    public synchronized void thinking(UUID runId, String text) {
        append(narrating(runId).thinking, text);
    }

    public synchronized void answer(UUID runId, String text) {
        append(narrating(runId).answer, text);
    }

    public synchronized void resetAnswer(UUID runId) {
        Entry e = progress.get(runId);
        if (e != null && e.answer != null) {
            e.answer.setLength(0);
        }
    }

    public synchronized void narrated(UUID runId) {
        Entry e = narrating(runId);
        e.phase = "NARRATED";
        e.percent = 100;
        e.message = "narrated";
    }

    public synchronized void narrationFailed(UUID runId, String message) {
        Entry e = narrating(runId);
        e.phase = "NARRATION_FAILED";
        e.percent = 100;
        e.message = message;
    }

    private static void append(StringBuilder sb, String text) {
        sb.append(text);
        if (sb.length() > TAIL_CHARS) {
            sb.delete(0, sb.length() - TAIL_CHARS);
        }
    }

    /** The narrator's view of this tracker for one run. */
    public NarrationProgress narrationProgress(UUID runId) {
        RunProgressTracker t = this;
        return new NarrationProgress() {
            @Override
            public void step(Step step, String detail) {
                t.narration(runId, step, detail);
            }

            @Override
            public void thinking(String text) {
                t.thinking(runId, text);
            }

            @Override
            public void answer(String text) {
                t.answer(runId, text);
            }

            @Override
            public void resetAnswer() {
                t.resetAnswer(runId);
            }
        };
    }
}
