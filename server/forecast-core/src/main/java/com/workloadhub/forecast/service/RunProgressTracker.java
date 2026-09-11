package com.workloadhub.forecast.service;

import com.workloadhub.forecast.ai.NarrationProgress;
import com.workloadhub.forecast.api.ProgressLabel;
import com.workloadhub.forecast.api.RunProgress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Live progress per run, in memory, for the JVM that runs it: the run's phases, then the narration's steps, each
 * with a label a person can read in English or French; a long step rotates through a few phrases by the clock
 * (design 2026-09-11, section 4.1). Copilot's thinking and answer text are not kept: the stored narrative holds
 * the answer. Bounded to the {@link #MAX_TRACKED} most recently started runs (insertion order, oldest evicted).
 * Every access is synchronised on this instance.
 */
public final class RunProgressTracker {

    static final int MAX_TRACKED = 256;
    /** How long each phrase of a rotating label is shown. */
    public static final Duration ROTATION = Duration.ofSeconds(4);

    private static final Map<String, ProgressLabel> PHASE_LABELS = Map.ofEntries(
            Map.entry("QUEUED", new ProgressLabel("queued", "en attente")),
            Map.entry("LOADING", new ProgressLabel("reading the team's data", "lecture des données de l'équipe")),
            Map.entry("FEATURES", new ProgressLabel("preparing the history", "préparation de l'historique")),
            Map.entry("BACKTEST", new ProgressLabel("scoring the models", "évaluation des modèles")),
            Map.entry("FORECAST", new ProgressLabel("predicting the next two weeks", "prévision des deux prochaines semaines")),
            Map.entry("FACTS", new ProgressLabel("assembling the facts", "assemblage des faits")),
            Map.entry("PERSIST", new ProgressLabel("saving the run", "enregistrement")),
            Map.entry("RUNNING", new ProgressLabel("running", "en cours")),
            Map.entry("DONE", new ProgressLabel("forecast ready", "prévision prête")),
            Map.entry("FAILED", new ProgressLabel("forecast failed", "échec de la prévision")),
            Map.entry("NARRATED", new ProgressLabel("report ready", "rapport prêt")),
            Map.entry("NARRATION_FAILED", new ProgressLabel("narration failed", "échec de la narration")));
    private static final ProgressLabel STARTING_LABEL = new ProgressLabel("starting Copilot", "démarrage de Copilot");
    private static final List<ProgressLabel> ASKING_LABELS = List.of(
            new ProgressLabel("consulting Copilot", "consultation de Copilot"),
            new ProgressLabel("thinking", "réflexion"),
            new ProgressLabel("writing the report", "rédaction du rapport"));
    private static final List<ProgressLabel> TOOL_LABELS = List.of(
            new ProgressLabel("collecting data", "collecte des données"),
            new ProgressLabel("reading the forecast", "lecture de la prévision"));
    private static final ProgressLabel CHECKING_LABEL = new ProgressLabel("checking the numbers", "vérification des chiffres");
    private static final ProgressLabel UNKNOWN_LABEL = new ProgressLabel("working", "en cours");

    private static final class Entry {
        String phase;
        int percent;
        String message;
        NarrationProgress.Step step; // null outside narration
        Instant stepStarted;

        Entry(String phase, int percent, String message) {
            this.phase = phase;
            this.percent = percent;
            this.message = message;
        }
    }

    private final Clock clock;
    private final Map<UUID, Entry> progress = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public RunProgressTracker() {
        this(Clock.systemUTC());
    }

    public RunProgressTracker(Clock clock) {
        this.clock = clock;
    }

    /** The label of a phase that has no live entry (a stored status), or a generic one for a phase this class does not know. */
    public static ProgressLabel phaseLabel(String phase) {
        return PHASE_LABELS.getOrDefault(phase, UNKNOWN_LABEL);
    }

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
        return e == null ? Optional.empty() : Optional.of(new RunProgress(runId, e.phase, e.percent, e.message, label(e)));
    }

    private ProgressLabel label(Entry e) {
        if (!"NARRATING".equals(e.phase)) {
            return phaseLabel(e.phase);
        }
        NarrationProgress.Step step = e.step == null ? NarrationProgress.Step.STARTING : e.step;
        return switch (step) {
            case STARTING, SESSION -> STARTING_LABEL;
            case ASKING, TOOL_DONE -> rotate(ASKING_LABELS, e.stepStarted);
            case TOOL -> rotate(TOOL_LABELS, e.stepStarted);
            case CHECKING -> CHECKING_LABEL;
        };
    }

    private ProgressLabel rotate(List<ProgressLabel> phrases, Instant since) {
        long elapsed = since == null ? 0 : Math.max(0, Duration.between(since, clock.instant()).getSeconds());
        return phrases.get((int) ((elapsed / ROTATION.getSeconds()) % phrases.size()));
    }

    // ----- narration ---------------------------------------------------------------------------

    private Entry narrating(UUID runId) {
        Entry e = progress.get(runId);
        if (e == null || e.step == null) {
            e = new Entry("NARRATING", 0, "starting Copilot");
            e.step = NarrationProgress.Step.STARTING;
            e.stepStarted = clock.instant();
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
        // A finished tool returns to the asking phrases; a repeated tool step keeps its rotation running.
        NarrationProgress.Step next = step == NarrationProgress.Step.TOOL_DONE ? NarrationProgress.Step.ASKING : step;
        if (e.step != next) {
            e.step = next;
            e.stepStarted = clock.instant();
        }
    }

    /** The streamed text is not kept (design 2026-09-11, decision 2); the stored narrative holds the answer. */
    public synchronized void thinking(UUID runId, String text) {
        narrating(runId);
    }

    public synchronized void answer(UUID runId, String text) {
        narrating(runId);
    }

    public synchronized void resetAnswer(UUID runId) {
        narrating(runId);
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
