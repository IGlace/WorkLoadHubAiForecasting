# Host Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The module's progress reports a rotating, bilingual label instead of Copilot's streamed text, runs interrupted by a restart are failed at start-up, a Java-interface sample host demonstrates and tests the v1 role rules, the narration executor and the polling, and the server's integration is documented.

**Architecture:** Four tasks: the progress labels (`ProgressLabel`, `RunProgress`, `RunProgressTracker` with a clock, its consumers); the start-up reconciliation (`JdbcRunStore.failInterrupted`, `DefaultForecastService.recoverInterruptedRuns`); the sample host in the module's tests (`ForecastAccess`, `HostForecastFacade`, `JavaHostIntegrationTest`); the documents. `mvn -B -q verify` in `server/` must be green after every task (three to four minutes, 600000 ms timeout; jqwik's "If you are an AI Agent..." banner is library output to ignore).

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 6, SQLite and PostgreSQL (Testcontainers when Docker is present), picocli.

**Spec:** `docs/superpowers/specs/2026-09-11-host-integration-design.md` (read it first). Background: `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md` section 10.

## Global Constraints

- `RunProgress(UUID runId, String phase, int percent, String message, ProgressLabel label)`; `ProgressLabel(String en, String fr)`; no `thinking`/`answer` anywhere in the public API; the label table of spec section 4.1 verbatim; rotation every 4 seconds by the tracker's clock as `(seconds since the step started / 4) mod n`; `TOOL_DONE` returns to the asking phrases.
- `Narrator` and `NarrationProgress` are unchanged: `thinking(text)`, `answer(text)` and `resetAnswer()` keep their signatures and become no-ops in the tracker.
- `JdbcRunStore.failInterrupted(LocalDateTime now)` sets `status = FAILED`, `error = 'interrupted by a restart'`, `finished_at = now` on `QUEUED` and `RUNNING` rows and returns the count; called once at start-up by the auto-configuration and by the CLI's `Services.open`.
- The v1 role table of spec section 3.2; "one team at a time" for `SKILL_TEAM_LEADER` only; `requestedBy` is the signed-in user; the module never re-checks.
- No test talks to Copilot (`FakeGateway`); the sample host pins its clock at the seed's end (2026-09-06).
- Package prefix `com.workloadhub.forecast` under `server/forecast-core/src/main/java/...` (main), `server/forecast-core/src/test/java/...` (tests) and `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/` (CLI).
- Commit messages: imperative subject, a short body saying why, then after a blank line the two trailer lines the dispatch names. Do not push. No model identifier in committed content.

---

## File structure

| Task | Creates | Modifies |
|---|---|---|
| 1 | `api/ProgressLabel.java`, `test/.../service/TickingClock.java` | `api/RunProgress.java`, `service/RunProgressTracker.java`, `service/DefaultForecastService.java` (stored-status progress), `ForecastAutoConfiguration.java`, `cli/Services.java`, `cli/NarrateCommand.java`, tests `RunProgressTrackerTest`, `DefaultForecastServiceTest`, `ForecastControllerTest`, `SampleHostIntegrationTest`; `server/README.md` (progress rows) |
| 2 | | `store/JdbcRunStore.java`, `service/DefaultForecastService.java`, `ForecastAutoConfiguration.java`, `cli/Services.java`, tests `JdbcRunStoreTest`, `DefaultForecastServiceTest` |
| 3 | `test/.../samplehost/ForecastAccess.java`, `HostForbidden.java`, `HostForecastFacade.java`, `JavaHostIntegrationTest.java` | |
| 4 | | `server/README.md`, the narration spec, the Java spec, `docs/requirements/requirements-v1.md`, `docs/backlog.md`, `CLAUDE.md` |

---

### Task 1: Progress labels instead of text tails

**Files:**
- Create: `api/ProgressLabel.java`, `test/.../service/TickingClock.java`
- Modify: `api/RunProgress.java`, `service/RunProgressTracker.java`, `service/DefaultForecastService.java`, `ForecastAutoConfiguration.java`, `cli/Services.java`, `cli/NarrateCommand.java`, `test/.../service/RunProgressTrackerTest.java`, `test/.../service/DefaultForecastServiceTest.java`, `test/.../web/ForecastControllerTest.java`, `test/.../samplehost/SampleHostIntegrationTest.java`, `server/README.md`

**Interfaces:**
- Produces: `ProgressLabel(String en, String fr)`; `RunProgress(UUID runId, String phase, int percent, String message, ProgressLabel label)`; `RunProgressTracker(Clock clock)` (the no-argument constructor uses `Clock.systemUTC()`); `RunProgressTracker.phaseLabel(String phase)` static; `RunProgressTracker.ROTATION = Duration.ofSeconds(4)`.

- [ ] **Step 1: The failing tracker test**

Create `test/.../service/TickingClock.java`:

```java
package com.workloadhub.forecast.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move by hand. */
final class TickingClock extends Clock {

    private Instant now;

    TickingClock(Instant start) {
        this.now = start;
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
```

In `test/.../service/RunProgressTrackerTest.java` replace `narrationStepsPercentAndTailsAreTracked` and `runProgressHasNoTailsOutsideNarration` with (imports: `com.workloadhub.forecast.ai.NarrationProgress`, `com.workloadhub.forecast.api.ProgressLabel`, `java.time.Duration`, `java.time.Instant`, `java.util.Set`):

```java
    @Test
    void everyPhaseHasABilingualLabel() {
        RunProgressTracker t = new RunProgressTracker();
        UUID id = UUID.randomUUID();
        t.start(id);
        assertEquals(new ProgressLabel("queued", "en attente"), t.get(id).orElseThrow().label());
        for (String[] phase : new String[][] {{"LOADING", "reading the team's data", "lecture des données de l'équipe"},
                {"FEATURES", "preparing the history", "préparation de l'historique"}, {"BACKTEST", "scoring the models", "évaluation des modèles"},
                {"FORECAST", "predicting the next two weeks", "prévision des deux prochaines semaines"}, {"FACTS", "assembling the facts", "assemblage des faits"},
                {"PERSIST", "saving the run", "enregistrement"}}) {
            t.update(id, phase[0], 50, "detail");
            assertEquals(new ProgressLabel(phase[1], phase[2]), t.get(id).orElseThrow().label(), phase[0]);
            assertEquals("detail", t.get(id).orElseThrow().message());
        }
        t.done(id);
        assertEquals(new ProgressLabel("forecast ready", "prévision prête"), t.get(id).orElseThrow().label());
        t.failed(id, "boom");
        assertEquals(new ProgressLabel("forecast failed", "échec de la prévision"), t.get(id).orElseThrow().label());
        assertEquals("boom", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("working", "en cours"), RunProgressTracker.phaseLabel("SOMETHING_ELSE"));
    }

    @Test
    void narrationStepsRotateTheirLabelsByTheClockAndKeepNoText() {
        TickingClock clock = new TickingClock(Instant.parse("2026-09-06T12:00:00Z"));
        RunProgressTracker t = new RunProgressTracker(clock);
        UUID id = UUID.randomUUID();
        t.done(id);
        NarrationProgress p = t.narrationProgress(id);
        p.step(NarrationProgress.Step.STARTING, null);
        assertEquals("NARRATING", t.get(id).orElseThrow().phase());
        assertEquals(5, t.get(id).orElseThrow().percent());
        assertEquals("starting Copilot", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("starting Copilot", "démarrage de Copilot"), t.get(id).orElseThrow().label());
        p.step(NarrationProgress.Step.SESSION, null);
        assertEquals(10, t.get(id).orElseThrow().percent());
        assertEquals(new ProgressLabel("starting Copilot", "démarrage de Copilot"), t.get(id).orElseThrow().label());
        p.step(NarrationProgress.Step.ASKING, "2");
        assertEquals(20, t.get(id).orElseThrow().percent());
        assertEquals("asking Copilot (attempt 2)", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("consulting Copilot", "consultation de Copilot"), t.get(id).orElseThrow().label(), "0 s: the first phrase");
        clock.advance(Duration.ofSeconds(4));
        assertEquals(new ProgressLabel("thinking", "réflexion"), t.get(id).orElseThrow().label(), "4 s: the second phrase");
        clock.advance(Duration.ofSeconds(4));
        assertEquals(new ProgressLabel("writing the report", "rédaction du rapport"), t.get(id).orElseThrow().label(), "8 s: the third phrase");
        clock.advance(Duration.ofSeconds(4));
        assertEquals(new ProgressLabel("consulting Copilot", "consultation de Copilot"), t.get(id).orElseThrow().label(), "12 s: back to the first");
        p.thinking("Reading ");
        p.answer("{\"run_summary\"");
        p.resetAnswer();
        assertEquals(new ProgressLabel("consulting Copilot", "consultation de Copilot"), t.get(id).orElseThrow().label(), "text does not change the label");
        for (int i = 0; i < 20; i++) {
            p.step(NarrationProgress.Step.TOOL, "get_member_forecast");
        }
        assertEquals(80, t.get(id).orElseThrow().percent(), "tool calls add five points up to eighty");
        assertEquals("tool get_member_forecast", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("collecting data", "collecte des données"), t.get(id).orElseThrow().label());
        clock.advance(Duration.ofSeconds(5));
        assertEquals(new ProgressLabel("reading the forecast", "lecture de la prévision"), t.get(id).orElseThrow().label());
        p.step(NarrationProgress.Step.TOOL_DONE, "get_member_forecast");
        assertEquals("tool get_member_forecast done", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("consulting Copilot", "consultation de Copilot"), t.get(id).orElseThrow().label(), "a finished tool returns to the asking phrases");
        p.step(NarrationProgress.Step.CHECKING, null);
        assertEquals(90, t.get(id).orElseThrow().percent());
        assertEquals(new ProgressLabel("checking the numbers", "vérification des chiffres"), t.get(id).orElseThrow().label());
        t.narrated(id);
        assertEquals("NARRATED", t.get(id).orElseThrow().phase());
        assertEquals(100, t.get(id).orElseThrow().percent());
        assertEquals(new ProgressLabel("report ready", "rapport prêt"), t.get(id).orElseThrow().label());
        t.narrationFailed(id, "timeout: no answer within 300 s");
        assertEquals("NARRATION_FAILED", t.get(id).orElseThrow().phase());
        assertEquals("timeout: no answer within 300 s", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("narration failed", "échec de la narration"), t.get(id).orElseThrow().label());
        assertEquals(Set.of("runId", "phase", "percent", "message", "label"),
                Set.of(java.util.Arrays.stream(RunProgress.class.getRecordComponents()).map(c -> c.getName()).toArray(String[]::new)),
                "no text tails in the public record");
    }
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=RunProgressTrackerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`ProgressLabel`, `label()`).

- [ ] **Step 2: The records and the tracker**

Create `api/ProgressLabel.java`:

```java
package com.workloadhub.forecast.api;

/** What a person sees while a run or a narration is in progress, in both languages (design 2026-09-11, section 4.1). */
public record ProgressLabel(String en, String fr) {
}
```

Replace `api/RunProgress.java` with:

```java
package com.workloadhub.forecast.api;

import java.util.UUID;

/** Where a run stands: its phase and percent, a technical message for logs, and a label for people. */
public record RunProgress(UUID runId, String phase, int percent, String message, ProgressLabel label) {
}
```

Replace `service/RunProgressTracker.java` with:

```java
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
```

Note: `narrated(...)` and `narrationFailed(...)` call `narrating(runId)`, which sets the phase to `NARRATING` before they set the final phase, as before; the label switch reads the final phase. If `narrating(runId)` re-creates an entry for a run whose live entry was evicted, it starts at `STARTING`, as before.

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=RunProgressTrackerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (three tests).

- [ ] **Step 3: The consumers**

`service/DefaultForecastService.java`, `progress(UUID runId)`: the four stored-status cases become

```java
        return switch (run.status()) {
            case QUEUED -> new RunProgress(runId, "QUEUED", 0, "queued", RunProgressTracker.phaseLabel("QUEUED"));
            case RUNNING -> new RunProgress(runId, "RUNNING", 50, "running in another instance", RunProgressTracker.phaseLabel("RUNNING"));
            case DONE -> new RunProgress(runId, "DONE", 100, "done", RunProgressTracker.phaseLabel("DONE"));
            case FAILED -> new RunProgress(runId, "FAILED", 100, run.error(), RunProgressTracker.phaseLabel("FAILED"));
        };
```

`ForecastAutoConfiguration.java`: `runProgressTracker(Clock clock)` returns `new RunProgressTracker(clock)` (the `Clock` bean exists; declare the tracker bean after `forecastClock` or rely on injection order, Spring resolves it). `cli/Services.java`: `new RunProgressTracker(Clock.systemDefaultZone())` (import `java.time.Clock`, already imported for the service).

`cli/NarrateCommand.java`: the progress printer prints the label and the message whenever either changes and no text tails; replace `progressPrinter` and delete `tail(...)`:

```java
    /** Polls the tracker and prints each new step's label and message to stderr as they arrive. */
    private static Thread progressPrinter(Services s, UUID runId) {
        Thread t = new Thread(() -> {
            String last = "";
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Optional<RunProgress> p = s.progress().get(runId);
                    if (p.isPresent() && p.get().phase().startsWith("NARRAT")) {
                        RunProgress rp = p.get();
                        String line = "[" + rp.percent() + "%] " + rp.label().en() + " (" + rp.message() + ")";
                        if (!line.equals(last)) {
                            System.err.println(line);
                            last = line;
                        }
                    }
                    Thread.sleep(POLL_MILLIS);
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
        }, "narrate-progress");
        t.setDaemon(true);
        return t;
    }
```

The `@Command` description and any comment that says "streams the thinking and the answer" say "prints the progress" instead.

Tests: `test/.../service/DefaultForecastServiceTest.java` line with `assertNotNull(p.answer());` becomes `assertEquals("report ready", p.label().en());` (and `assertEquals("rapport prêt", p.label().fr());`). `test/.../web/ForecastControllerTest.java`: the mocked progress becomes `new RunProgress(RUN, "NARRATING", 40, "tool get_member_forecast", new ProgressLabel("collecting data", "collecte des données"))` (import `ProgressLabel`) and the assertion `.andExpect(jsonPath("$.answer").value("{"))` becomes `.andExpect(jsonPath("$.label.en").value("collecting data")).andExpect(jsonPath("$.label.fr").value("collecte des données")).andExpect(jsonPath("$.answer").doesNotExist())`. `test/.../samplehost/SampleHostIntegrationTest.java`: the `NARRATED` progress check adds `.andExpect(jsonPath("$.label.en").value("report ready"))`.

`server/README.md`: the REST row `GET /runs/{id}/progress` becomes `| \`GET /runs/{id}/progress\` | | 200, \`RunProgress\` (\`phase\`, \`percent\`, \`message\` for logs, \`label\` \`{en, fr}\` for people; the label rotates through a few phrases every four seconds while Copilot works) |`; the paragraph after the table that says "whose narration phases are `NARRATING` (with the `thinking` and `answer` tails, the last 16 000 characters of each), then `NARRATED` or `NARRATION_FAILED`" becomes "whose narration phases are `NARRATING` (with a rotating label such as "collecting data", "consulting Copilot", "thinking"), then `NARRATED` or `NARRATION_FAILED`; Copilot's streamed text is not exposed, the stored narrative holds the answer"; the CLI table's `narrate` row says "prints the progress labels and steps to stderr"; in "Narrating with Copilot", "streams the steps, the thinking and the answer to stderr" becomes "prints the progress steps and labels to stderr" and "In the streamed steps, confirm" becomes "In the printed steps (the message names each tool), confirm".

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='RunProgressTrackerTest,DefaultForecastServiceTest,ForecastControllerTest,SampleHostIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 4: Whole gate and commit**

Run: `cd server && mvn -B -q verify` (the CLI module compiles against the new record)
Expected: exit 0.

```bash
git add server
git commit -m "feat(server): progress shows a rotating bilingual label, never Copilot's text

A person watching a run or a narration sees short phrases in their
language that change every few seconds (design 2026-09-11, decision 2);
the thinking and answer tails leave RunProgress, the stored narrative
keeps the answer for audit. The tracker takes a clock so the rotation is
testable."
```

---

### Task 2: Start-up reconciliation of interrupted runs

**Files:**
- Modify: `store/JdbcRunStore.java`, `service/DefaultForecastService.java`, `ForecastAutoConfiguration.java`, `cli/Services.java`, `test/.../store/JdbcRunStoreTest.java`, `test/.../service/DefaultForecastServiceTest.java`

**Interfaces:**
- Produces: `JdbcRunStore.INTERRUPTED = "interrupted by a restart"`, `int failInterrupted(LocalDateTime now)`; `int DefaultForecastService.recoverInterruptedRuns()`.

- [ ] **Step 1: Failing tests**

`test/.../store/JdbcRunStoreTest.java`, add (with SQLite and PostgreSQL twins like `lifecycle`):

```java
    void failInterruptedMarksQueuedAndRunningRowsOnly(DataSource ds) {
        JdbcRunStore store = new JdbcRunStore(ds, Dialect.of(ds));
        UUID queued = store.create(new RunRequest(TEAM, USER, null, null), LocalDate.of(2026, 9, 6), T0);
        UUID running = store.create(new RunRequest(TEAM, USER, null, null), LocalDate.of(2026, 9, 6), T0);
        store.markRunning(running);
        UUID done = store.create(new RunRequest(TEAM, USER, null, null), LocalDate.of(2026, 9, 6), T0);
        store.finish(done, "xgboost", 0.8, "{}", List.of(), List.of(), "{}", T0.plusMinutes(1));
        UUID failed = store.create(new RunRequest(TEAM, USER, null, null), LocalDate.of(2026, 9, 6), T0);
        store.fail(failed, "boom", T0.plusMinutes(1));
        assertEquals(2, store.failInterrupted(T0.plusHours(1)));
        for (UUID id : List.of(queued, running)) {
            RunSummary r = store.find(id).orElseThrow();
            assertEquals(RunStatus.FAILED, r.status(), id.toString());
            assertEquals(JdbcRunStore.INTERRUPTED, r.error());
            assertEquals(T0.plusHours(1), r.finishedAt());
        }
        assertEquals(RunStatus.DONE, store.find(done).orElseThrow().status());
        assertEquals("boom", store.find(failed).orElseThrow().error());
        assertEquals(0, store.failInterrupted(T0.plusHours(2)), "nothing left to reconcile");
    }

    @Test
    void sqliteFailInterrupted() {
        failInterruptedMarksQueuedAndRunningRowsOnly(sqlite());
    }

    @Test
    void postgresFailInterrupted() {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ForecastMigrations.run(ds);
        failInterruptedMarksQueuedAndRunningRowsOnly(ds);
    }
```

`test/.../service/DefaultForecastServiceTest.java`, add:

```java
    @Test
    void interruptedRunsAreFailedWhenTheModuleStarts() {
        Dialect dialect = Dialect.of(SeededData.dataSource());
        JdbcRunStore raw = new JdbcRunStore(SeededData.dataSource(), dialect);
        UUID left = raw.create(new RunRequest(team, null, null, null), SeededData.asOf(), LocalDateTime.now());
        raw.markRunning(left);
        assertTrue(service.recoverInterruptedRuns() >= 1);
        RunSummary r = service.listRuns(team, 50).stream().filter(s -> s.id().equals(left)).findFirst().orElseThrow();
        assertEquals(RunStatus.FAILED, r.status());
        assertEquals("interrupted by a restart", r.error());
        assertEquals("FAILED", service.progress(left).phase());
        assertEquals("forecast failed", service.progress(left).label().en());
    }
```

(`RunSummary`, `RunStatus` imports if missing.) Run the two classes: compilation failure.

- [ ] **Step 2: The store and the service**

`store/JdbcRunStore.java`, after `fail(...)`:

```java
    public static final String INTERRUPTED = "interrupted by a restart";

    /** After a restart nothing can still be running a QUEUED or RUNNING row (design 2026-09-11, section 4.2): fail them all, return how many. */
    public int failInterrupted(LocalDateTime now) {
        return jdbc.sql("UPDATE forecast_runs SET status = ?, error = ?, finished_at = " + ph("timestamp") + " WHERE status IN (?, ?)")
                .param(RunStatus.FAILED.name()).param(INTERRUPTED).param(ts(now))
                .param(RunStatus.QUEUED.name()).param(RunStatus.RUNNING.name()).update();
    }
```

`service/DefaultForecastService.java`, after the constructor:

```java
    /** Called once when the module starts: runs the previous process left behind cannot be resumed (design 2026-09-11, section 4.2). */
    public int recoverInterruptedRuns() {
        int n = store.failInterrupted(LocalDateTime.now(clock));
        if (n > 0) {
            LOG.warn("marked {} run(s) left QUEUED or RUNNING by a previous process as FAILED", n);
        }
        return n;
    }
```

`ForecastAutoConfiguration.forecastService(...)`: build the service into a local variable, call `service.recoverInterruptedRuns()`, return it. `cli/Services.open(...)`: the same right after constructing the service.

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='JdbcRunStoreTest,DefaultForecastServiceTest,SampleHostIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 3: Whole gate and commit**

Run: `cd server && mvn -B -q verify`
Expected: exit 0.

```bash
git add server
git commit -m "feat(server): fail the runs a restart interrupted when the module starts

One server instance runs the forecasts in memory; a row left QUEUED or
RUNNING by the previous process can never finish, so the service marks
it FAILED with a clear error at start-up (the CLI does the same on each
command) and a page polling it can offer to run again."
```

---

### Task 3: The sample host through the Java interface

**Files:**
- Create: `test/.../samplehost/ForecastAccess.java`, `test/.../samplehost/HostForbidden.java`, `test/.../samplehost/HostForecastFacade.java`, `test/.../samplehost/JavaHostIntegrationTest.java`

**Interfaces:**
- Consumes: `ForecastService`, `GitHubTokenStore`, `FakeGateway`, `SampleHostApplication` (fixed clock at 2026-09-06, `FakeGateway` bean, seeded SQLite), `RunProgress.label()`.
- Produces (test sources only, the reference for the real host): `ForecastAccess.roleOf/canRun/canView`, `HostForecastFacade.startRun/progress/narrate/narrative/currentForecast/waitFor`.

- [ ] **Step 1: The access rules**

Create `test/.../samplehost/HostForbidden.java`:

```java
package com.workloadhub.forecast.samplehost;

/** What the real host maps to HTTP 403: the signed-in user may not do this. */
public final class HostForbidden extends RuntimeException {
    public HostForbidden(String message) {
        super(message);
    }
}
```

Create `test/.../samplehost/ForecastAccess.java`:

```java
package com.workloadhub.forecast.samplehost;

import com.workloadhub.forecast.store.Dialect;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The v1 role rules (design 2026-09-11, section 3.2), read from the WorkloadHub tables: what the real host enforces
 * before calling the module. ADMIN runs and views any team; a SKILL_TEAM_LEADER runs and views the teams whose
 * parent team they manage; a TEAM_LEADER runs and views the teams they manage; members view the teams they belong
 * to; VIEWER and CENTER_MANAGER view any team and run none.
 */
public final class ForecastAccess {

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public ForecastAccess(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public String roleOf(UUID userId) {
        return jdbc.sql("SELECT role FROM users WHERE id = " + dialect.placeholder("uuid")).param(userId.toString()).query().listOfRows().stream()
                .findFirst().map(r -> String.valueOf(r.get("role"))).orElseThrow(() -> new HostForbidden("unknown user " + userId));
    }

    public boolean canRun(UUID userId, UUID teamId) {
        return switch (roleOf(userId)) {
            case "ADMIN" -> true;
            case "TEAM_LEADER" -> manages(userId, teamId);
            case "SKILL_TEAM_LEADER" -> managesParentOf(userId, teamId);
            default -> false;
        };
    }

    public boolean canView(UUID userId, UUID teamId) {
        return switch (roleOf(userId)) {
            case "ADMIN", "VIEWER", "CENTER_MANAGER" -> true;
            case "TEAM_LEADER" -> manages(userId, teamId) || memberOf(userId, teamId);
            case "SKILL_TEAM_LEADER" -> managesParentOf(userId, teamId) || memberOf(userId, teamId);
            case "MEMBER" -> memberOf(userId, teamId);
            default -> false;
        };
    }

    boolean manages(UUID userId, UUID teamId) {
        return !jdbc.sql("SELECT id FROM teams WHERE id = " + dialect.placeholder("uuid") + " AND manager_id = " + dialect.placeholder("uuid"))
                .param(teamId.toString()).param(userId.toString()).query().listOfRows().isEmpty();
    }

    boolean managesParentOf(UUID userId, UUID teamId) {
        return !jdbc.sql("SELECT t.id FROM teams t JOIN teams p ON p.id = t.parent_team_id WHERE t.id = " + dialect.placeholder("uuid")
                + " AND p.manager_id = " + dialect.placeholder("uuid")).param(teamId.toString()).param(userId.toString()).query().listOfRows().isEmpty();
    }

    boolean memberOf(UUID userId, UUID teamId) {
        return !jdbc.sql("SELECT team_id FROM team_members WHERE team_id = " + dialect.placeholder("uuid") + " AND user_id = " + dialect.placeholder("uuid"))
                .param(teamId.toString()).param(userId.toString()).query().listOfRows().isEmpty();
    }
}
```

- [ ] **Step 2: The facade**

Create `test/.../samplehost/HostForecastFacade.java`:

```java
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

    static final Set<String> FINISHED = Set.of("DONE", "FAILED", "NARRATED", "NARRATION_FAILED");

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

    public UUID startRun(UUID userId, UUID teamId) {
        if (!access.canRun(userId, teamId)) {
            throw new HostForbidden("user " + userId + " may not run a forecast for team " + teamId);
        }
        if (access.roleOf(userId).equals("SKILL_TEAM_LEADER")) {
            UUID latest = latestRunOfUser.get(userId);
            if (latest != null && !FINISHED.contains(service.progress(latest).phase())) {
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
```

- [ ] **Step 3: The integration test**

Create `test/.../samplehost/JavaHostIntegrationTest.java`. It boots `SampleHostApplication` without the REST surface (`whf.web.enabled` stays false), finds users of every role in the seeded `users`/`teams`/`team_members` tables through `JdbcClient`, and exercises the facade:

```java
package com.workloadhub.forecast.samplehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.testing.SeededData;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The WorkloadHub server calling the module from its own code: the role rules, the run, the polling, the narration (design 2026-09-11). */
@SpringBootTest(classes = SampleHostApplication.class, properties = {"whf.run-threads=1", "whf.token-key=" + JavaHostIntegrationTest.KEY})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaHostIntegrationTest {

    static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Autowired ForecastService service;
    @Autowired GitHubTokenStore tokens;
    @Autowired CopilotGateway gateway;
    @Autowired DataSource dataSource;

    HostForecastFacade host;
    JdbcClient jdbc;
    UUID team;          // a team with members whose parent team has a manager
    UUID leader;        // teams.manager_id of team
    UUID head;          // the parent team's manager: a SKILL_TEAM_LEADER
    UUID member;        // a MEMBER of team
    UUID viewer;        // any VIEWER
    Optional<UUID> otherTeam;   // a team with members under a different parent, when the seed has one
    Optional<UUID> outsider;    // a MEMBER of otherTeam

    @BeforeAll
    void boot() {
        jdbc = JdbcClient.create(dataSource);
        host = new HostForecastFacade(service, new ForecastAccess(jdbc, Dialect.of(dataSource)));
        List<Map<String, Object>> teams = jdbc.sql("SELECT t.id AS id, t.manager_id AS leader, t.parent_team_id AS parent, p.manager_id AS head"
                + " FROM teams t JOIN teams p ON p.id = t.parent_team_id WHERE t.manager_id IS NOT NULL AND p.manager_id IS NOT NULL"
                + " AND EXISTS (SELECT 1 FROM team_members m WHERE m.team_id = t.id) ORDER BY t.id").query().listOfRows();
        assertFalse(teams.isEmpty(), "the seed has teams under a department with a head");
        Map<String, Object> first = teams.get(0);
        team = UUID.fromString(first.get("id").toString());
        leader = UUID.fromString(first.get("leader").toString());
        head = UUID.fromString(first.get("head").toString());
        assertEquals("TEAM_LEADER", role(leader));
        assertEquals("SKILL_TEAM_LEADER", role(head));
        member = SeededData.data().membersOfTeam(team).stream().map(m -> m.id()).filter(id -> role(id).equals("MEMBER")).findFirst().orElseThrow();
        viewer = jdbc.sql("SELECT id FROM users WHERE role = 'VIEWER' ORDER BY id").query().listOfRows().stream().findFirst()
                .map(r -> UUID.fromString(r.get("id").toString())).orElseThrow();
        otherTeam = teams.stream().filter(t -> !t.get("head").toString().equals(head.toString())).findFirst().map(t -> UUID.fromString(t.get("id").toString()));
        outsider = otherTeam.flatMap(t -> SeededData.data().membersOfTeam(t).stream().map(m -> m.id()).filter(id -> role(id).equals("MEMBER")).findFirst());
    }

    @AfterAll
    void shutdown() throws Exception {
        host.close();
    }

    String role(UUID userId) {
        return jdbc.sql("SELECT role FROM users WHERE id = ?").param(userId.toString()).query().listOfRows().get(0).get("role").toString();
    }

    @Test
    void theRolesDecideWhoRunsAndWhoViews() {
        assertThrows(HostForbidden.class, () -> host.startRun(member, team), "a member never runs");
        assertThrows(HostForbidden.class, () -> host.startRun(viewer, team), "a viewer never runs");
        otherTeam.ifPresent(t -> assertThrows(HostForbidden.class, () -> host.startRun(leader, t), "a leader runs only the teams they manage"));
        otherTeam.ifPresent(t -> assertThrows(HostForbidden.class, () -> host.startRun(head, t), "a skill team leader runs only the teams under them"));
        outsider.ifPresent(u -> assertThrows(HostForbidden.class, () -> host.currentForecast(u, team, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18)),
                "a member of another team views nothing here"));
        assertDoesNotThrow(() -> host.currentForecast(viewer, team, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18)), "a viewer may read");
    }

    @Test
    void aLeaderRunsTheirTeamThePagePollsTheLabelsAndReadsTheForecast() throws Exception {
        UUID run = host.startRun(leader, team);
        List<RunProgress> seen = host.waitFor(member, run, Set.of("DONE", "FAILED"), Duration.ofMinutes(2));
        RunProgress last = seen.get(seen.size() - 1);
        assertEquals("DONE", last.phase(), last.message());
        assertEquals("forecast ready", last.label().en());
        assertEquals("prévision prête", last.label().fr());
        assertTrue(seen.stream().allMatch(p -> p.label() != null && !p.label().en().isBlank() && !p.label().fr().isBlank()), "every state shown had a label");
        List<CurrentDayForecast> current = host.currentForecast(member, team, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18));
        assertFalse(current.isEmpty(), "the member reads their own team's current forecast");
        assertTrue(current.stream().allMatch(c -> c.runId().equals(run)));
        assertEquals(service.getRun(run).memberDays().size(), current.size());
    }

    @Test
    void aSkillTeamLeaderRunsOneTeamAtATime() throws Exception {
        UUID first = host.startRun(head, team);
        assertThrows(HostForbidden.class, () -> host.startRun(head, team), "a second start while the first is in progress");
        host.waitFor(head, first, Set.of("DONE", "FAILED"), Duration.ofMinutes(2));
        UUID second = host.startRun(head, team);
        host.waitFor(head, second, Set.of("DONE", "FAILED"), Duration.ofMinutes(2));
        assertEquals("DONE", host.progress(head, second).phase());
    }

    @Test
    void narrationRunsOnTheHostExecutorAndTheNarrativeIsReadableByThoseWhoMayView() throws Exception {
        UUID run = host.startRun(leader, team);
        host.waitFor(leader, run, Set.of("DONE", "FAILED"), Duration.ofMinutes(2));
        assertEquals("TOKEN_MISSING", assertThrows(ForecastException.class, () -> host.narrate(leader, run, "fr")).code());
        tokens.save(leader, "gho_host_sample");
        FakeGateway fake = (FakeGateway) gateway;
        fake.replies.clear();
        fake.replies.add(FakeGateway.goodNarrative(ExportFiles.mapper().readTree(service.getRun(run).factsJson())));
        NarrativeResult result = host.narrate(leader, run, "fr").get(2, TimeUnit.MINUTES);
        assertEquals(NarrativeStatus.OK, result.status());
        assertEquals("fr", result.language());
        RunProgress after = host.progress(leader, run);
        assertEquals("NARRATED", after.phase());
        assertEquals("rapport prêt", after.label().fr());
        assertEquals(result, host.narrative(viewer, run, "fr").orElseThrow(), "a viewer reads the narrative");
        outsider.ifPresent(u -> assertThrows(HostForbidden.class, () -> host.narrative(u, run, "fr")));
        assertTrue(host.narrative(leader, run, "en").isEmpty());
        tokens.clear(leader);
    }
}
```

Drop the `Assumptions` import (unused); `otherTeam`/`outsider` are `Optional` because the seed's department count is not pinned by this plan.

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='JavaHostIntegrationTest,SampleHostIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If the seed's `team_members` does not include the team leader as a member (the `member` lookup filters on `MEMBER`), that is expected; if the query in `boot()` finds no team, read `Directory.java` in `seed/` to see how parent teams are formed and adjust the SQL (the department team is the parent; its manager is the head), and say so in the report.

- [ ] **Step 4: Whole gate and commit**

Run: `cd server && mvn -B -q verify`
Expected: exit 0.

```bash
git add server/forecast-core/src/test
git commit -m "test(server): a sample host that calls the module from its own code

The reference for the WorkloadHub server: the v1 role rules read from
the users and teams tables, one run at a time for a skill team leader,
narration on the host's own executor with page-style polling of the
labels, and the current forecast read by those who may view the team."
```

---

### Task 4: The documents

**Files:**
- Modify: `server/README.md`, `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md`, `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`, `docs/requirements/requirements-v1.md`, `docs/backlog.md`, `CLAUDE.md`

- [ ] **Step 1: The integration guide**

In `server/README.md`, after the "### Properties" section's closing paragraph (the clock paragraph) and before "### The REST surface", add:

```markdown
### Integrating from the server's own code

The design is `docs/superpowers/specs/2026-09-11-host-integration-design.md`; the reference implementation is the
sample host in the tests (`forecast-core/src/test/java/com/workloadhub/forecast/samplehost/`: `ForecastAccess`,
`HostForecastFacade`, `JavaHostIntegrationTest`).

- **Wiring**: add the dependency, keep `whf.web.enabled` false, set `whf.token-key` from your secret store,
  `whf.work-dir` to a directory the service account can write, and declare a `java.time.Clock` bean in your
  time zone. The module's Flyway creates its tables in your schema under `forecast_schema_history`.
- **Who may do what** (the host enforces it; the module trusts `requestedBy`):

  | role | may start a run for | may view |
  |---|---|---|
  | `ADMIN` | any team | any team |
  | `SKILL_TEAM_LEADER` | a team whose parent team they manage, one at a time | those teams and their own memberships |
  | `TEAM_LEADER` | the teams they manage | those teams and their own memberships |
  | `MEMBER` | none | the teams they belong to |
  | `VIEWER`, `CENTER_MANAGER` | none | any team |

- **A run**: check the role, `startRun(new RunRequest(teamId, userId, null, null))`, let the page poll
  `progress(runId)` and show `label` in the user's language until `DONE` or `FAILED`, then read `getRun(runId)`
  and `currentForecast(teamId, from, to)` (per member and day, two windows of five weekdays).
- **A narration**: check the role, `copilotStatus(userId).hasToken()` and that the run is `DONE`, then submit
  `narrate(new NarrativeRequest(runId, userId, language, null))` to your own bounded executor and return; the
  page polls `progress(runId)` (`NARRATING` with a label that rotates through "collecting data", "consulting
  Copilot", "thinking"; then `NARRATED` or `NARRATION_FAILED`) and reads `narrative(runId, language)`. Refuse a
  second narration of the same run and language while one is in flight.
- **Tokens**: your settings page calls `GitHubTokenStore.save(userId, token)` and `clear`, and shows
  `copilotStatus(userId)`. The module reads a token in one place, at narration, and never returns it.
- **Errors**: `ForecastException.code()`: `*_NOT_FOUND` → 404, `INVALID_REQUEST` → 400, everything else → 409; a
  refused role check is your 403.
- **One instance**: progress and the run executor live in the JVM. At start-up the module marks runs left
  `QUEUED` or `RUNNING` by the previous process as `FAILED` (`interrupted by a restart`).
```

- [ ] **Step 2: The notes**

`docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md`, under `## 10. Progress`: add the blockquote `> Amended on 2026-09-11: \`RunProgress\` carries a bilingual \`label\` that rotates through phrases while Copilot works, and no thinking or answer tails; see \`docs/superpowers/specs/2026-09-11-host-integration-design.md\`, section 4.1.` `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`, under `## 11. Public API` (after the existing 2026-09-10 note): `> Amended on 2026-09-11: \`RunProgress(runId, phase, percent, message, label)\`; runs left QUEUED or RUNNING are failed at start-up; the host's role rules and integration sequence are in \`docs/superpowers/specs/2026-09-11-host-integration-design.md\`.`

`docs/requirements/requirements-v1.md`, section 2 (roles), after the table: `- **[amended 2026-09-11]** In the server integration the host enforces this table before calling the forecast module (\`docs/superpowers/specs/2026-09-11-host-integration-design.md\`, section 3.2): members view their own team, viewers and centre managers view any team and run none, admins run and view any team.`

`docs/backlog.md`: under `## Java migration` add `- **Host integration through the Java interface** (2026-09-11): the host enforces the v1 roles, narration runs on a host executor and the page polls a rotating bilingual label (Copilot's streamed text is no longer exposed), one server instance, runs interrupted by a restart are failed at start-up; the sample host in the tests is the reference. Spec \`docs/superpowers/specs/2026-09-11-host-integration-design.md\`. Rulings: \`VIEWER\` and \`CENTER_MANAGER\` view any team (read only); the one-at-a-time rule applies to skill team leaders only; a member of another team sees nothing.`; in "Approved, not yet built", the **WorkloadHub integration, requirements first** item becomes `- **WorkloadHub integration.** The module's side is designed and demonstrated (\`docs/superpowers/specs/2026-09-11-host-integration-design.md\`, the sample host); what remains is the server's own code (its endpoints, pages and settings), written in the WorkloadHub repository against \`server/README.md\`, "Integrating from the server's own code".`

`CLAUDE.md`, "Where the project stands": after the rolling-windows sentence add `Then the host integration design (\`docs/superpowers/specs/2026-09-11-host-integration-design.md\`): progress labels, start-up reconciliation and a Java-interface sample host; the server's own code is written in the WorkloadHub repository.`; in "Next:" replace `then the host integration` with `then the server's own integration code, against the sample host`.

- [ ] **Step 3: Verify and commit**

Run: `git grep -n "thinking\` and \`answer\|thinking and answer tails\|16 000" -- server/README.md CLAUDE.md` — expected: no output. Then `cd server && mvn -B -q verify`, exit 0.

```bash
git add server/README.md CLAUDE.md docs
git commit -m "docs(server): how the server integrates the module from its own code

The README gains the integration guide (wiring, the role table, the run
and narration sequences, tokens, errors, the single instance), the two
specs and the requirements carry amendment notes, the backlog records
the rulings."
```

---

## Closing notes for the executor and the reviewer

**Rulings this plan takes, to report to the owner at the end:**

1. `VIEWER` and `CENTER_MANAGER` view any team and run none; a `MEMBER` views only the teams they belong to; these two roles are not in the v1 table and the plan fills the gap.
2. The one-at-a-time rule is kept by the host facade in memory (the latest run per skill team leader), which is enough for one instance.
3. The tracker keeps no text at all rather than keeping the tails privately: nothing reads them, and the stored narrative holds the answer.
4. `TOOL_DONE` restarts the asking rotation from its first phrase (a new step start) rather than resuming where the rotation was.
5. The sample host's tests treat a second department as optional (`Optional` team and outsider) so the plan does not pin the seed's department count.

**Rulings taken during execution and the final review (2026-09-11):**

6. The seed provides every role but `VIEWER`; the sample host's viewer is the `CENTER_MANAGER`, which has the
   same rights (spec section 4.3 corrected). The CLI's tests run no narration, so the `narrate` printer is
   covered by reading, not by a test (spec section 6 corrected).
7. The one-at-a-time rule looks at the run's own phase (`runInProgress`): a run whose narration is in flight
   is over, so the leader may start the next team.
8. Start-up reconciliation runs once after all singletons are instantiated (`SmartInitializingSingleton`), so
   a host-owned Flyway has run first; a database that cannot answer is logged and never stops the host. In the
   CLI only `run` reconciles, so read-only commands in a second process never fail a live run (spec 4.2).
9. The host's narration pre-check is `GitHubTokenStore.has(userId)`; `copilotStatus` opens a session and is
   for the settings page (spec 3.4). The host persists `(runId, teamId, requestedBy)` in its own table; the
   sample's in-memory map is a test convenience (spec 3.6). A run lookup by id on `ForecastService` stays out
   of scope.
10. The CLI progress printer prints a line per step (phase or message change), not per rotated phrase; the
    rotation is for polling hosts. The rotation index carries a jqwik property.
11. Parked: the service test's reconciliation sweeps the shared seeded database (sequential tests, safe
    today); the tracker rotates on the host's business clock (the system clock in production); the sample
    facade's guards are check-then-act on in-memory maps, disclosed in the README.

**What comes next:** the live Copilot check on a seeded database, the real export through the seed and the parity procedure, the server's own integration code in the WorkloadHub repository, and the accuracy evaluation reading `forecast_current_days`.
