# Java Run, Persistence, Facts, CLI and Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the pipeline core of the previous plan into runnable forecasts: a pure run orchestrator, the facts document, persistence in the module's tables, the `ForecastService` API with progress, the CLI `run`, `runs` and `eval` commands writing the Python harness's files, and the Python-side converter and comparison script that make the parity gate executable.

**Architecture:** `ForecastRunner` is pure (`ForecastData` in, `RunOutcome` out) and split into a global `prepare` (features, backtest, champion, effort model, predictions for every member) and a cheap per-team `forTeam` (placements, planned work, capacity, bands), so the evaluation harness reuses one `prepare` across teams. `FactsBuilder` turns a `TeamOutcome` into a JSON tree of maps, lists, strings and numbers (no Jackson date handling). `JdbcRunStore` is the only new class that writes to the database; `DefaultForecastService` glues store, runner and a bounded executor and is registered by the existing auto-configuration. The Python converter writes a WorkloadHub export into the archived Python service's schema so `uv run whf eval` and `forecast eval` can be run on the same data and compared.

**Tech Stack:** Java 21, Spring JDBC (`JdbcClient`), Jackson 3 (`tools.jackson`, `JsonMapper`), XGBoost4J 3.4.0 (already wired), picocli, JUnit 6, jqwik; Python 3.11 + `uv` in `service/` for the converter (no new Python dependencies).

**Spec:** `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md` sections 9 (pipeline), 10.1 (facts), 11 (API and properties), 12 (CLI), 13 (testing incl. the parity gate); `docs/superpowers/specs/2026-09-07-planned-work-and-likely-work-design.md` sections 5.5 and 6.1 (planned facts, likely work); the Python oracle `service/src/whf/pipeline.py`, `patterns.py`, `eval/harness.py`, `eval/report.py`, `eval/truth.py`. Previous plan: `docs/superpowers/plans/2026-09-09-java-pipeline-core.md` (its classes are the inputs here).

## Global Constraints

- The language model never produces a forecast number; nothing in this plan calls Copilot. `narrate`, `narrative` and `copilotStatus` on `ForecastService` throw `ForecastException("COPILOT_UNAVAILABLE", …)` until the narration plan lands.
- Demand = open + new + planned per member and week; overload = `max(0, demand − capacity)` and never reduces demand; `low ≤ demand ≤ high`; bands apply to new hours only (`low = min(demand, open + planned + max(0, new + q10 × ratio))`, `high = max(demand, open + planned + new + q90 × ratio)` with `q10 = min(0, ·)`, `q90 = max(0, ·)` from the champion's pooled backtest residuals at that horizon); all hours rounded to 2 decimals.
- Run time frame: `origin = Weeks.lastCompleteWeek(asOf)`, `[f1, f2] = Weeks.forecastWeeks(asOf)`, `h1 = Weeks.weeksBetween(origin, f1)` (1 on a Monday, 2 otherwise), horizons `{h1, h1 + 1}`; the feature matrix is built on every counted member (global model); predictions are read at the origin rows of the team's members; a member without an origin row gets 0 new hours.
- Backtest: `Backtest.origins(origin, firstRowWeek)`; factories = floor + xgboost, or `{forced, floor}` when a model is forced; the forced model is used regardless of its score and its mean MASE is reported; a run with fewer than 13 weeks of history still completes with the floor as champion and `facts.data_quality.history_weeks` saying so.
- Capacity per member and week from `CapacityRule` (`whf.default-weekly-hours`, default 40); working days and absence hours stored beside it.
- Planned work: `PlannedWork.allocate` when `whf.planned-work.enabled` (default true) or the request says so; hours after the window are reported, not counted.
- Facts: the Python shape (`run`, `team`, `members` with `history_13w`, `forecast`, `patterns`, `open_tasks`; `projects`; `model`; `rebalancing_candidates`) plus per member `due_hours` per week, `reopened_tasks`, `logged_hours_4w`, `unlogged_tasks`, `likely_work` (`planned`, `project_roles`, `recent_mix`); per team `team_capacity`, `planned_backlog`; `pending_holidays`; `data_quality` (`unresolved_assignments`, `unlogged_tasks`, `history_weeks`). Members are identified by UUID and `full_name`; tasks by key; dates are ISO strings; every number is a JSON number (NaN becomes null); the exact string stored in `forecast_facts.facts_json` is what `getRun` returns.
- Persistence: `forecast_runs` (status `QUEUED`, `RUNNING`, `DONE`, `FAILED`; `backtest_json` = scores and unavailable; `error` one line, stack trace only in the log), `forecast_member_weeks` (one row per member and forecast week), `forecast_facts`; all writes of a finished run in one transaction; typed placeholders through `Dialect.placeholder`; SQLite and PostgreSQL (Testcontainers or skip).
- API: `ForecastService` exactly as spec section 11 (`startRun`, `getRun`, `listRuns`, `progress`, `narrate`, `narrative`, `copilotStatus`); error codes `TEAM_NOT_FOUND`, `RUN_NOT_FOUND`, `RUN_NOT_DONE`, `INVALID_REQUEST`, `COPILOT_UNAVAILABLE`; runs execute on a bounded executor of `whf.run-threads` (default 2) threads; progress is in memory per run.
- CLI (`forecast-cli`): `run --team <name or id> --as-of <date> [--model xgboost|seasonal_naive] [--user <name>]` prints champion, scores and the member-week table and exits 0 (2 on a usage error, 1 on a failed run); `runs --team <name or id>`; `eval [--as-of] [--origins 6] [--models a,b] [--teams a,b] [--out dir]` writes `scores.csv`, `demand.csv`, `summary.md` with the Python harness's columns.
- Evaluation: arrival level = `Backtest.run` over horizons `{1, 2}` with metrics `mae`, `mase`, `beats_naive`, `coverage80`, `wql`, `seconds` (leave-one-origin-out residual band, as the Python harness); demand level = for each origin, `Truncation.at(data, origin + 7 days)`, then one forced-model run per team, rows `model, origin, team_id, member_id, week_start, forecast, truth, capacity, open_hours, new_hours, planned_hours`; truth = `time_logs.hours` summed per user and Monday week.
- Parity gate (spec 13): on the same seeded export, the Python harness (`whf import-workloadhub` then `uv run whf eval --models gbm,seasonal_naive`) and `forecast eval --models xgboost,seasonal_naive` are compared by `server/tools/parity_compare.py`: mean MASE of the booster over origins and horizons 1 and 2 within 0.10, and the same champion decision (booster below 1.0 or not). The converter keeps only fresh arrivals (assignment lag below 2 days) by default so both sides forecast the same series.
- Determinism: two runs on the same data give identical numbers and identical facts JSON except `generated_at`; sorted iteration everywhere.
- Java 21; JUnit 6 + jqwik; `mvn -B -q verify` in `server/` under three minutes without Docker; no model identifiers in any file; commit messages with an imperative subject and a short body; stage by path; never `--no-verify`; the real export and any real-mode seed output stay outside git.

---

## File structure

```text
server/forecast-core/src/main/java/com/workloadhub/forecast/
  api/RunRequest.java, RunStatus.java, RunSummary.java, MemberWeekForecast.java, ModelScore.java,
      RunResult.java, RunProgress.java, NarrativeRequest.java, NarrativeResult.java, CopilotStatus.java,
      ForecastService.java                                                           (Task 1)
  run/ModelRegistry.java                 model names and factories, forced-model restriction       (Task 1)
  run/Prepared.java                      the global part of a run (records)                        (Task 2)
  run/TeamOutcome.java                   the per-team part (records)                               (Task 2)
  run/ForecastRunner.java                prepare(data, asOf, forced) / forTeam(prepared, team, …)  (Task 2)
  facts/Patterns.java                    per-member pattern statistics                             (Task 3)
  facts/Clustering.java                  standardise + k-means + silhouette                        (Task 3)
  facts/FactsBuilder.java                TeamOutcome -> JSON tree -> String                        (Task 4)
  data/rows/TeamCapacityRow.java, data/ForecastData (+teamCapacity), data/ForecastRepository (+query) (Task 4)
  store/JdbcRunStore.java                forecast_runs / forecast_member_weeks / forecast_facts     (Task 5)
  service/RunProgressTracker.java        in-memory progress per run                                (Task 6)
  service/DefaultForecastService.java    ForecastService over store + runner + executor            (Task 6)
  ForecastAutoConfiguration.java         + CapacityRule, ForecastRunner, JdbcRunStore, ForecastService beans (Task 6)
  eval/Truth.java, Metrics.java, EvalConfig.java, EvalResult.java, Harness.java, Report.java       (Task 8)
server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/
  TeamArg.java                           --team name-or-id resolution                              (Task 7)
  RunCommand.java, RunsCommand.java                                                                 (Task 7)
  EvalCommand.java                                                                                  (Task 8)
service/src/whf/data/workloadhub.py      WorkloadHub export -> whf schema                           (Task 9)
service/src/whf/cli.py                   `import-workloadhub` command                               (Task 9)
service/tests/test_workloadhub_import.py                                                            (Task 9)
server/tools/parity_compare.py           compares two scores.csv files                              (Task 10)
server/README.md, docs/backlog.md        procedure and decisions                                    (Task 10)
tests mirror the packages under server/forecast-core/src/test/java and server/forecast-cli/src/test/java
```

Existing inputs (previous plan): `data.ForecastData`, `data.ForecastRepository`, `calendar.Weeks/WorkingCalendar/HourPlacement`, `capacity.CapacityRule`, `lifecycle.Lifecycle/TaskFacts/Family/Mode/Truncation`, `features.Features/FeatureMatrix/FeatureBuilder/WeeklySeries/MemberWeek`, `model.ArrivalModel/SeasonalNaive/XgboostArrival/EffortModel/ModelUnavailable`, `backtest.Backtest`, `planned.PlannedWork`, `store.Dialect/ForecastMigrations`, test fixtures `testing.SeededData` (36 users, 30 weeks, as-of 2026-09-06), `testing.TestData`, `testing.SyntheticMatrix`.

---

### Task 1: API records, the `ForecastService` interface and the model registry

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/{RunRequest,RunStatus,RunSummary,MemberWeekForecast,ModelScore,RunResult,RunProgress,NarrativeRequest,NarrativeResult,CopilotStatus,ForecastService}.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/run/ModelRegistry.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/api/RunRequestTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/run/ModelRegistryTest.java`

**Interfaces:**
- `record RunRequest(UUID teamId, UUID requestedBy, LocalDate asOf, String forcedModel, Boolean plannedWork)` with a compact constructor that throws `ForecastException.invalidRequest(...)` when `teamId` or `asOf` is null or `forcedModel` is not blank and not a known model name (`ModelRegistry.isKnown`); `forcedModel` blank is normalised to null; `boolean plannedWorkOr(boolean defaultValue)`.
- `enum RunStatus { QUEUED, RUNNING, DONE, FAILED }`.
- `record RunSummary(UUID id, UUID teamId, UUID requestedBy, LocalDate asOf, RunStatus status, String forcedModel, String championModel, Double championMase, String error, LocalDateTime createdAt, LocalDateTime finishedAt)`.
- `record MemberWeekForecast(UUID userId, LocalDate weekStart, double openHrs, double newHrs, double plannedHrs, double demandHrs, double lowHrs, double highHrs, double capacityHrs, double overloadHrs, int workingDays, double absenceHrs)`.
- `record ModelScore(String model, LocalDate origin, int horizon, double mae, double mase)`.
- `record RunResult(RunSummary run, List<ModelScore> scores, Map<String, Double> maseByModel, Map<String, String> unavailable, List<MemberWeekForecast> memberWeeks, String factsJson)`.
- `record RunProgress(UUID runId, String phase, int percent, String message, String thinking, String answer)`.
- `record NarrativeRequest(UUID runId, UUID requestedBy, String language, String model)`; `record NarrativeResult(UUID id, UUID runId, String language, String model, String narrativeJson, String verificationJson, String usageJson, LocalDateTime createdAt)`; `record CopilotStatus(UUID userId, boolean hasToken, boolean runtimeAvailable, String message)`.
- `interface ForecastService` with exactly the seven methods of spec section 11 (`UUID startRun(RunRequest)`, `RunResult getRun(UUID)`, `List<RunSummary> listRuns(UUID teamId, int limit)`, `RunProgress progress(UUID)`, `NarrativeResult narrate(NarrativeRequest)`, `Optional<NarrativeResult> narrative(UUID runId, String language)`, `CopilotStatus copilotStatus(UUID userId)`).
- `ModelRegistry`: `static final List<String> NAMES = List.of(SeasonalNaive.NAME, XgboostArrival.NAME)`; `static boolean isKnown(String)`; `static Map<String, Supplier<ArrivalModel>> factories(String forcedModel)` returning a `LinkedHashMap` with the floor first then xgboost, or `{floor, forced}` when a model is forced (the floor alone when the forced model is the floor); `static ArrivalModel create(String name)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunRequestTest {

    static final UUID TEAM = UUID.randomUUID();
    static final LocalDate AS_OF = LocalDate.of(2026, 9, 6);

    @Test
    void normalisesBlankModelAndDefaultsPlannedWork() {
        RunRequest r = new RunRequest(TEAM, null, AS_OF, " ", null);
        assertNull(r.forcedModel());
        assertTrue(r.plannedWorkOr(true));
        assertEquals(false, new RunRequest(TEAM, null, AS_OF, "xgboost", false).plannedWorkOr(true));
    }

    @Test
    void rejectsMissingTeamOrDateOrUnknownModel() {
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new RunRequest(null, null, AS_OF, null, null)).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new RunRequest(TEAM, null, null, null, null)).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new RunRequest(TEAM, null, AS_OF, "chronos", null)).code());
    }
}
```

```java
package com.workloadhub.forecast.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.model.XgboostArrival;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelRegistryTest {

    @Test
    void floorFirstThenXgboostByDefault() {
        assertEquals(List.of(Backtest.FLOOR, XgboostArrival.NAME), List.copyOf(ModelRegistry.factories(null).keySet()));
        assertEquals(List.of(Backtest.FLOOR, XgboostArrival.NAME), List.copyOf(ModelRegistry.factories(XgboostArrival.NAME).keySet()));
        assertEquals(List.of(Backtest.FLOOR), List.copyOf(ModelRegistry.factories(Backtest.FLOOR).keySet()));
        assertTrue(ModelRegistry.isKnown("xgboost"));
        assertFalse(ModelRegistry.isKnown("gbm"));
        assertEquals("xgboost", ModelRegistry.create("xgboost").name());
        assertThrows(IllegalArgumentException.class, () -> ModelRegistry.create("gbm"));
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='RunRequestTest,ModelRegistryTest'`
Expected: compilation errors.

- [ ] **Step 3: Write the records and the interface**

Each record is a plain `public record` in `com.workloadhub.forecast.api` with the components listed in Interfaces. `RunRequest`:

```java
package com.workloadhub.forecast.api;

import com.workloadhub.forecast.run.ModelRegistry;
import java.time.LocalDate;
import java.util.UUID;

/** What a caller asks for: a team, an as-of date, optionally a forced model and the planned-work switch. */
public record RunRequest(UUID teamId, UUID requestedBy, LocalDate asOf, String forcedModel, Boolean plannedWork) {

    public RunRequest {
        if (teamId == null) {
            throw ForecastException.invalidRequest("teamId is required");
        }
        if (asOf == null) {
            throw ForecastException.invalidRequest("asOf is required");
        }
        forcedModel = forcedModel == null || forcedModel.isBlank() ? null : forcedModel.trim();
        if (forcedModel != null && !ModelRegistry.isKnown(forcedModel)) {
            throw ForecastException.invalidRequest("unknown model " + forcedModel + "; known: " + ModelRegistry.NAMES);
        }
    }

    public boolean plannedWorkOr(boolean defaultValue) {
        return plannedWork == null ? defaultValue : plannedWork;
    }
}
```

```java
package com.workloadhub.forecast.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The module's public surface (spec section 11); the host wires it as a bean or calls it from the CLI. */
public interface ForecastService {

    UUID startRun(RunRequest request);

    RunResult getRun(UUID runId);

    List<RunSummary> listRuns(UUID teamId, int limit);

    RunProgress progress(UUID runId);

    NarrativeResult narrate(NarrativeRequest request);

    Optional<NarrativeResult> narrative(UUID runId, String language);

    CopilotStatus copilotStatus(UUID userId);
}
```

- [ ] **Step 4: Write `ModelRegistry`**

```java
package com.workloadhub.forecast.run;

import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.SeasonalNaive;
import com.workloadhub.forecast.model.XgboostArrival;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The arrival models a run may use, by name, and the tournament a forced model restricts to. */
public final class ModelRegistry {

    public static final List<String> NAMES = List.of(SeasonalNaive.NAME, XgboostArrival.NAME);

    private ModelRegistry() {
    }

    public static boolean isKnown(String name) {
        return name != null && NAMES.contains(name);
    }

    public static ArrivalModel create(String name) {
        if (SeasonalNaive.NAME.equals(name)) {
            return new SeasonalNaive();
        }
        if (XgboostArrival.NAME.equals(name)) {
            return new XgboostArrival();
        }
        throw new IllegalArgumentException("unknown model " + name + "; known: " + NAMES);
    }

    /** The floor first, then every other model; a forced model restricts the tournament to the floor and itself. */
    public static Map<String, Supplier<ArrivalModel>> factories(String forcedModel) {
        Map<String, Supplier<ArrivalModel>> out = new LinkedHashMap<>();
        out.put(Backtest.FLOOR, SeasonalNaive::new);
        for (String name : NAMES) {
            if (!name.equals(Backtest.FLOOR) && (forcedModel == null || forcedModel.equals(name))) {
                out.put(name, () -> create(name));
            }
        }
        return out;
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='RunRequestTest,ModelRegistryTest'`
Expected: 3 passed.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/api server/forecast-core/src/main/java/com/workloadhub/forecast/run/ModelRegistry.java server/forecast-core/src/test/java/com/workloadhub/forecast/api server/forecast-core/src/test/java/com/workloadhub/forecast/run/ModelRegistryTest.java
git commit -m "feat(server): public API records, the ForecastService interface and the model registry

Request validation rejects unknown models at construction; the registry
orders the floor first and restricts the tournament when a model is forced."
```

---

### Task 2: `ForecastRunner`: the pure run in two halves

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/run/Prepared.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/run/TeamOutcome.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/run/ForecastRunner.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/run/ForecastRunnerTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/run/ForecastRunnerPropertyTest.java`

**Interfaces:**
- `record Prepared(ForecastData data, Lifecycle lifecycle, WorkingCalendar calendar, LocalDate asOf, LocalDate origin, LocalDate[] forecastWeeks, int[] horizons, FeatureMatrix features, List<LocalDate> backtestOrigins, Backtest.Result backtest, String champion, double championMase, String forcedModel, Map<Integer, double[]> bandOffsets, Map<MemberWeek, Double> predictedEst, EffortModel effort, int historyWeeks, Map<String, Double> secondsByPhase)` — `bandOffsets.get(h)` = `{q10 clamped ≤ 0, q90 clamped ≥ 0}` from `backtest.residuals(champion, h)`; `predictedEst` = the champion's prediction (est hours) for every counted member with an origin row, keyed by `(member, forecast week)`.
- `record TeamOutcome(Prepared prepared, UUID teamId, List<MemberRow> members, boolean plannedWorkEnabled, SortedMap<MemberWeek, Double> openHours, SortedMap<MemberWeek, Double> newHours, PlannedWork.Allocation planned, List<MemberWeekForecast> memberWeeks)`; `memberWeeks` sorted by `MemberWeek`, one entry per team member and forecast week.
- `ForecastRunner(CapacityRule capacityRule, boolean plannedWorkDefault)`:
  - `Prepared prepare(ForecastData data, LocalDate asOf, String forcedModel, ProgressListener progress)` where `interface ProgressListener { void phase(String phase, int percent, String message); static ProgressListener NONE }`; phases `FEATURES`, `BACKTEST`, `FORECAST`.
  - `TeamOutcome forTeam(Prepared prepared, UUID teamId, Boolean plannedWork)`; throws `ForecastException("TEAM_NOT_FOUND", …)` when the team has no counted member.
  - `static double round2(double)`.
- Rules (from the Global Constraints): `origin = Weeks.lastCompleteWeek(asOf)`; `forecastWeeks = Weeks.forecastWeeks(asOf)`; `h1 = (int) Weeks.weeksBetween(origin, f1)`; `horizons = {h1, h1 + 1}`; features = `new FeatureBuilder(data, lc, cal, capacityRule).build(data.members(), origin)`; `historyWeeks` = weeks from the first feature row's week to the origin (0 when no rows); `backtestOrigins = Backtest.origins(origin, firstRowWeek)` (empty when no rows); factories = `ModelRegistry.factories(forced)`; when forced: champion = forced, `championMase = backtest.meanMase(forced)` (NaN when unscored), and a forced model listed in `backtest.unavailable()` throws `ModelUnavailable`; else `Backtest.selectChampion(scores)`; the champion is created with `ModelRegistry.create` and fitted on the whole matrix with `horizons` (when the matrix has no row with a known target at some horizon, the champion is the floor, which never fails); predictions at the origin rows, clipped at 0, for both weeks; `XgboostArrival` closed after predicting; effort = `EffortModel.fit(lc, data)`.
  - `forTeam`: members = `data.membersOfTeam(teamId)` employed on `asOf` or with `left` after the origin (`m.left() == null || m.left().isAfter(origin)`); `teamOf = member.primaryTeamId`; `offDaysOf = CapacityRule.offDays(member, data)`; open tasks = `lc.all()` with `isAssigned()`, not done, assignee in the team; `openHours = EffortModel.placeOpenTasks(open, effort, f1, teamOf, offDaysOf, cal)`; `newHours = EffortModel.placeNewArrivals(predictedEst restricted to the team, …)`; planned = `PlannedWork.allocate(new Request(teamId, members, asOf, forecastWeeks), lc, data, effort, cal, offDaysOf)` when enabled else `Allocation.empty()`; per member and week: `open`, `new`, `planned` rounded, `demand = round2(open + new + planned)`, `ratio = effort.estimateRatio(member, null, team)`, `low = round2(min(demand, open + planned + max(0, new + q10 × ratio)))`, `high = round2(max(demand, open + planned + new + q90 × ratio))`, `capacity = capacityRule.capacity(member, week, data, cal)`, `overload = round2(max(0, demand − capacity))`, `workingDays = cal.workingDaysInWeek(week)`, `absence = capacityRule.absenceHours(...)`. Hours placed outside the two forecast weeks (a long cycle) are dropped from `memberWeeks` but stay in `openHours`/`newHours` for the facts.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.lifecycle.Truncation;
import com.workloadhub.forecast.model.XgboostArrival;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ForecastRunnerTest {

    static ForecastData data;
    static ForecastRunner runner;
    static Prepared prepared;
    static UUID team;

    @BeforeAll
    static void prepare() {
        data = SeededData.data();
        runner = new ForecastRunner(new CapacityRule(40), true);
        prepared = runner.prepare(data, SeededData.asOf(), null, ForecastRunner.ProgressListener.NONE);
        team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
    }

    @Test
    void timeFrameFollowsTheAsOfDate() {
        LocalDate asOf = SeededData.asOf();                                   // a Sunday
        assertEquals(Weeks.lastCompleteWeek(asOf), prepared.origin());
        assertEquals(Weeks.forecastWeeks(asOf)[0], prepared.forecastWeeks()[0]);
        assertEquals(2, prepared.horizons()[0], "as-of is not a Monday: the first forecast week is two weeks after the origin");
        assertEquals(3, prepared.horizons()[1]);
        assertTrue(prepared.historyWeeks() >= 25 && prepared.historyWeeks() <= 30, "30 seeded weeks: " + prepared.historyWeeks());
        assertFalse(prepared.backtestOrigins().isEmpty());
    }

    @Test
    void championIsScoredAndPredictionsExistForEveryMemberWithAnOriginRow() {
        assertTrue(List.of(Backtest.FLOOR, XgboostArrival.NAME).contains(prepared.champion()));
        assertTrue(prepared.backtest().meanMaseByModel().containsKey(XgboostArrival.NAME));
        assertTrue(prepared.backtest().meanMaseByModel().containsKey(Backtest.FLOOR));
        long originRows = prepared.features().filter(k -> k.week().equals(prepared.origin())).rowCount();
        assertEquals(originRows * 2, prepared.predictedEst().size(), "two forecast weeks per member with an origin row");
        assertTrue(prepared.predictedEst().values().stream().allMatch(v -> v >= 0));
        for (int h : prepared.horizons()) {
            double[] band = prepared.bandOffsets().get(h);
            assertTrue(band[0] <= 0 && band[1] >= 0);
        }
    }

    @Test
    void forcedModelIsUsedRegardlessOfItsScore() {
        Prepared floor = runner.prepare(data, SeededData.asOf(), Backtest.FLOOR, ForecastRunner.ProgressListener.NONE);
        assertEquals(Backtest.FLOOR, floor.champion());
        assertEquals(List.of(Backtest.FLOOR), List.copyOf(floor.backtest().meanMaseByModel().keySet()));
        Prepared xgb = runner.prepare(data, SeededData.asOf(), XgboostArrival.NAME, ForecastRunner.ProgressListener.NONE);
        assertEquals(XgboostArrival.NAME, xgb.champion());
        assertEquals(xgb.backtest().meanMase(XgboostArrival.NAME), xgb.championMase(), 1e-12);
    }

    @Test
    void teamOutcomeHoldsTheInvariantsForEveryMemberAndWeek() {
        TeamOutcome out = runner.forTeam(prepared, team, null);
        List<MemberWeekForecast> rows = out.memberWeeks();
        assertEquals(out.members().size() * 2, rows.size());
        for (MemberWeekForecast r : rows) {
            assertEquals(r.demandHrs(), ForecastRunner.round2(r.openHrs() + r.newHrs() + r.plannedHrs()), 1e-9);
            assertEquals(r.overloadHrs(), ForecastRunner.round2(Math.max(0, r.demandHrs() - r.capacityHrs())), 1e-9);
            assertTrue(r.lowHrs() <= r.demandHrs() + 1e-9 && r.demandHrs() <= r.highHrs() + 1e-9);
            assertTrue(r.lowHrs() >= r.openHrs() + r.plannedHrs() - 1e-9, "the band never cuts into placed or planned work");
            assertTrue(r.capacityHrs() >= 0 && r.workingDays() >= 0 && r.workingDays() <= 5);
            assertTrue(r.weekStart().equals(prepared.forecastWeeks()[0]) || r.weekStart().equals(prepared.forecastWeeks()[1]));
        }
        assertTrue(out.plannedWorkEnabled());
        TeamOutcome noPlanned = runner.forTeam(prepared, team, false);
        assertTrue(noPlanned.memberWeeks().stream().allMatch(r -> r.plannedHrs() == 0.0));
        assertEquals(0, noPlanned.planned().pieces().size());
    }

    @Test
    void unknownOrEmptyTeamIsRejected() {
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class, () -> runner.forTeam(prepared, UUID.randomUUID(), null)).code());
    }

    @Test
    void shortHistoryStillCompletesWithTheFloor() {
        ForecastData young = Truncation.at(data, SeededData.asOf().minusWeeks(22));
        Prepared p = runner.prepare(young, SeededData.asOf().minusWeeks(22), null, ForecastRunner.ProgressListener.NONE);
        assertTrue(p.backtestOrigins().isEmpty(), "under 13 weeks before every origin");
        assertEquals(Backtest.FLOOR, p.champion());
        assertTrue(Double.isNaN(p.championMase()));
        assertNotNull(runner.forTeam(p, team, null).memberWeeks());
    }

    @Test
    void twoRunsOnTheSameDataAreIdentical() {
        Prepared again = runner.prepare(data, SeededData.asOf(), null, ForecastRunner.ProgressListener.NONE);
        assertEquals(prepared.champion(), again.champion());
        assertEquals(prepared.predictedEst(), again.predictedEst());
        assertEquals(runner.forTeam(prepared, team, null).memberWeeks(), runner.forTeam(again, team, null).memberWeeks());
    }
}
```

```java
package com.workloadhub.forecast.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

class ForecastRunnerPropertyTest {

    @Property
    void bandsBracketDemandAndNeverCutPlacedWork(@ForAll @DoubleRange(min = 0, max = 60) double open, @ForAll @DoubleRange(min = 0, max = 60) double fresh,
            @ForAll @DoubleRange(min = 0, max = 30) double planned, @ForAll @DoubleRange(min = -40, max = 0) double q10,
            @ForAll @DoubleRange(min = 0, max = 40) double q90, @ForAll @DoubleRange(min = 0.5, max = 2.5) double ratio,
            @ForAll @DoubleRange(min = 0, max = 50) double capacity) {
        ForecastRunner.Band b = ForecastRunner.band(open, fresh, planned, q10, q90, ratio, capacity);
        assertEquals(b.demand(), ForecastRunner.round2(ForecastRunner.round2(open) + ForecastRunner.round2(fresh) + ForecastRunner.round2(planned)), 1e-9);
        assertTrue(b.low() <= b.demand() + 1e-9 && b.demand() <= b.high() + 1e-9);
        assertTrue(b.low() + 1e-9 >= ForecastRunner.round2(open) + ForecastRunner.round2(planned));
        assertEquals(b.overload(), ForecastRunner.round2(Math.max(0, b.demand() - capacity)), 1e-9);
        assertTrue(b.overload() >= 0);
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='ForecastRunnerTest,ForecastRunnerPropertyTest'`
Expected: compilation errors.

- [ ] **Step 3: Write the records and the runner**

```java
package com.workloadhub.forecast.run;

import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.model.EffortModel;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** The global half of a run: everything that does not depend on which team is forecast. */
public record Prepared(
        ForecastData data,
        Lifecycle lifecycle,
        WorkingCalendar calendar,
        LocalDate asOf,
        LocalDate origin,
        LocalDate[] forecastWeeks,
        int[] horizons,
        FeatureMatrix features,
        List<LocalDate> backtestOrigins,
        Backtest.Result backtest,
        String champion,
        double championMase,
        String forcedModel,
        Map<Integer, double[]> bandOffsets,
        Map<MemberWeek, Double> predictedEst,
        EffortModel effort,
        int historyWeeks,
        Map<String, Double> secondsByPhase) {
}
```

```java
package com.workloadhub.forecast.run;

import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.planned.PlannedWork;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

/** The per-team half of a run: placements, planned work and the member-week table. */
public record TeamOutcome(
        Prepared prepared,
        UUID teamId,
        List<MemberRow> members,
        boolean plannedWorkEnabled,
        SortedMap<MemberWeek, Double> openHours,
        SortedMap<MemberWeek, Double> newHours,
        PlannedWork.Allocation planned,
        List<MemberWeekForecast> memberWeeks) {
}
```

```java
package com.workloadhub.forecast.run;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.FeatureBuilder;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.EffortModel;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.planned.PlannedWork;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** One forecast run as pure functions: prepare the global model once, then derive each team's outcome. */
public final class ForecastRunner {

    public interface ProgressListener {
        void phase(String phase, int percent, String message);

        ProgressListener NONE = (phase, percent, message) -> {
        };
    }

    /** The demand arithmetic of one member-week, isolated so a property test can pin it. */
    public record Band(double open, double fresh, double planned, double demand, double low, double high, double overload) {
    }

    private final CapacityRule capacityRule;
    private final boolean plannedWorkDefault;

    public ForecastRunner(CapacityRule capacityRule, boolean plannedWorkDefault) {
        this.capacityRule = capacityRule;
        this.plannedWorkDefault = plannedWorkDefault;
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static Band band(double open, double fresh, double planned, double q10, double q90, double ratio, double capacity) {
        double o = round2(open);
        double n = round2(fresh);
        double p = round2(planned);
        double demand = round2(o + n + p);
        double low = round2(Math.min(demand, o + p + Math.max(0.0, n + Math.min(0.0, q10) * ratio)));
        double high = round2(Math.max(demand, o + p + n + Math.max(0.0, q90) * ratio));
        double overload = round2(Math.max(0.0, demand - capacity));
        return new Band(o, n, p, demand, low, high, overload);
    }

    public Prepared prepare(ForecastData data, LocalDate asOf, String forcedModel, ProgressListener progress) {
        Map<String, Double> seconds = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        progress.phase("FEATURES", 5, "deriving lifecycles and the feature matrix");
        Lifecycle lc = Lifecycle.derive(data);
        WorkingCalendar cal = WorkingCalendar.fromHolidays(data.holidays());
        LocalDate origin = Weeks.lastCompleteWeek(asOf);
        LocalDate[] weeks = Weeks.forecastWeeks(asOf);
        int h1 = (int) Weeks.weeksBetween(origin, weeks[0]);
        int[] horizons = {h1, h1 + 1};
        FeatureMatrix features = new FeatureBuilder(data, lc, cal, capacityRule).build(data.members(), origin);
        LocalDate firstWeek = features.rowCount() == 0 ? origin : features.keys().stream().map(MemberWeek::week).min(LocalDate::compareTo).orElse(origin);
        int historyWeeks = features.rowCount() == 0 ? 0 : (int) Weeks.weeksBetween(firstWeek, origin) + 1;
        List<LocalDate> origins = features.rowCount() == 0 ? List.of() : Backtest.origins(origin, firstWeek);
        seconds.put("features", elapsed(t0));

        long t1 = System.nanoTime();
        progress.phase("BACKTEST", 25, "scoring " + origins.size() + " origins");
        Map<String, Supplier<ArrivalModel>> factories = ModelRegistry.factories(forcedModel);
        Backtest.Result backtest = Backtest.run(features, factories, origins, horizons);
        String champion;
        double championMase;
        if (forcedModel != null) {
            if (backtest.unavailable().containsKey(forcedModel)) {
                throw new ModelUnavailable(backtest.unavailable().get(forcedModel));
            }
            champion = forcedModel;
            championMase = backtest.meanMase(forcedModel);
        } else {
            Backtest.Champion c = Backtest.selectChampion(backtest.scores());
            champion = c.model();
            championMase = c.meanMase();
        }
        seconds.put("backtest", elapsed(t1));

        long t2 = System.nanoTime();
        progress.phase("FORECAST", 60, "fitting " + champion + " and predicting");
        Map<Integer, double[]> offsets = new TreeMap<>();
        for (int h : horizons) {
            double[] q = Backtest.intervalBounds(backtest.residuals(champion, h));
            offsets.put(h, new double[] {Math.min(0.0, q[0]), Math.max(0.0, q[1])});
        }
        Map<MemberWeek, Double> predicted = new TreeMap<>();
        FeatureMatrix atOrigin = features.filter(k -> k.week().equals(origin));
        if (atOrigin.rowCount() > 0) {
            ArrivalModel model = ModelRegistry.create(champion);
            try {
                model.fit(features, horizons);
                for (int i = 0; i < horizons.length; i++) {
                    double[] pred = model.predict(atOrigin, horizons[i]);
                    for (int r = 0; r < atOrigin.rowCount(); r++) {
                        predicted.put(new MemberWeek(atOrigin.key(r).member(), weeks[i]), Math.max(0.0, pred[r]));
                    }
                }
            } finally {
                if (model instanceof AutoCloseable c) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                        // a booster that fails to dispose leaks a little native memory until the JVM exits
                    }
                }
            }
        }
        EffortModel effort = EffortModel.fit(lc, data);
        seconds.put("forecast", elapsed(t2));
        return new Prepared(data, lc, cal, asOf, origin, weeks, horizons, features, origins, backtest, champion, championMase,
                forcedModel, offsets, predicted, effort, historyWeeks, seconds);
    }

    public TeamOutcome forTeam(Prepared p, UUID teamId, Boolean plannedWork) {
        ForecastData data = p.data();
        List<MemberRow> members = data.membersOfTeam(teamId).stream()
                .filter(m -> m.left() == null || m.left().isAfter(p.origin()))
                .toList();
        if (members.isEmpty()) {
            throw ForecastException.of("TEAM_NOT_FOUND", "team " + teamId + " has no counted member");
        }
        boolean planEnabled = plannedWork == null ? plannedWorkDefault : plannedWork;
        Map<UUID, MemberRow> byId = members.stream().collect(Collectors.toMap(MemberRow::id, m -> m));
        Function<UUID, UUID> teamOf = id -> byId.containsKey(id) ? byId.get(id).primaryTeamId() : teamId;
        Function<UUID, Set<LocalDate>> offDaysOf = id -> CapacityRule.offDays(id, data);
        List<TaskFacts> open = p.lifecycle().all().stream()
                .filter(f -> f.isAssigned() && !f.done() && byId.containsKey(f.assignee()))
                .toList();
        LocalDate f1 = p.forecastWeeks()[0];
        SortedMap<MemberWeek, Double> openHours = EffortModel.placeOpenTasks(open, p.effort(), f1, teamOf, offDaysOf, p.calendar());
        Map<MemberWeek, Double> teamPredicted = new TreeMap<>();
        p.predictedEst().forEach((k, v) -> {
            if (byId.containsKey(k.member())) {
                teamPredicted.put(k, v);
            }
        });
        SortedMap<MemberWeek, Double> newHours = EffortModel.placeNewArrivals(teamPredicted, p.effort(), teamOf, offDaysOf, p.calendar());
        PlannedWork.Allocation planned = planEnabled
                ? PlannedWork.allocate(new PlannedWork.Request(teamId, members, p.asOf(), p.forecastWeeks()), p.lifecycle(), data, p.effort(),
                        p.calendar(), offDaysOf)
                : PlannedWork.Allocation.empty();
        List<MemberWeekForecast> rows = new ArrayList<>();
        for (MemberRow m : members) {
            double ratio = p.effort().estimateRatio(m.id(), null, m.primaryTeamId());
            for (int i = 0; i < p.forecastWeeks().length; i++) {
                LocalDate week = p.forecastWeeks()[i];
                MemberWeek key = new MemberWeek(m.id(), week);
                double[] q = p.bandOffsets().get(p.horizons()[i]);
                double capacity = capacityRule.capacity(m, week, data, p.calendar());
                Band b = band(openHours.getOrDefault(key, 0.0), newHours.getOrDefault(key, 0.0), planned.hours().getOrDefault(key, 0.0),
                        q[0], q[1], ratio, capacity);
                rows.add(new MemberWeekForecast(m.id(), week, b.open(), b.fresh(), b.planned(), b.demand(), b.low(), b.high(), capacity,
                        b.overload(), p.calendar().workingDaysInWeek(week), capacityRule.absenceHours(m.id(), week, data, p.calendar())));
            }
        }
        rows.sort((a, b) -> new MemberWeek(a.userId(), a.weekStart()).compareTo(new MemberWeek(b.userId(), b.weekStart())));
        return new TeamOutcome(p, teamId, members, planEnabled, openHours, newHours, planned, List.copyOf(rows));
    }

    private static double elapsed(long since) {
        return (System.nanoTime() - since) / 1e9;
    }
}
```

`data.membersOfTeam` returns members sorted by id (previous plan), so `rows` is already in `MemberWeek` order; the explicit sort keeps the contract visible.

- [ ] **Step 4: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='ForecastRunnerTest,ForecastRunnerPropertyTest'`
Expected: 8 passed. `prepare` on the seeded data runs the backtest (six origins, two models) and takes some seconds; the class prepares once for all tests. If `shortHistoryStillCompletesWithTheFloor` finds an origin, lower the truncation to `minusWeeks(24)`: the requirement is only that no origin has 13 weeks of history.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/run server/forecast-core/src/test/java/com/workloadhub/forecast/run
git commit -m "feat(server): the forecast run as pure functions, global preparation then per-team outcome

Features, backtest, champion, predictions and the effort model are
prepared once per as-of date; each team then gets placements, planned
work, capacity and bands with demand never capped."
```

---

### Task 3: Member patterns and clustering

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/facts/MemberPattern.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/facts/Patterns.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/facts/Clustering.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/facts/PatternsTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/facts/ClusteringTest.java`

**Interfaces:**
- `record MemberPattern(UUID memberId, int tasks13w, double hours13w, double hoursPerWeek13w, double trendHoursPerWeek, Double shareManual, Double shareSelfPicked, Double shareProject, String topWeekday, List<Double> weekdayShares, Double estimateRatioMedian, Double cycleDaysMedian, Map<String, Double> cycleDaysByFamily, Double latenessDaysMedian, Double shareLate, Double shareWithProject, Map<String, Double> hoursByProject, int openTasks, double openEstHours, int overdueOpen)` with `Map<String, Object> toMap()` (keys in snake case exactly as the Python facts: `member_id`, `tasks_13w`, `hours_13w`, `hours_per_week_13w`, `trend_hours_per_week`, `share_manual`, `share_self_picked`, `share_project`, `top_weekday`, `weekday_shares`, `estimate_ratio_median`, `cycle_days_median`, `cycle_days_by_type`, `lateness_days_median`, `share_late`, `share_with_project`, `hours_by_project`, `open_tasks`, `open_est_hours`, `overdue_open`; nulls stay null).
- `Patterns.of(UUID member, Lifecycle lc, ForecastData data, LocalDate asOf)` and `Patterns.table(List<MemberRow> members, Lifecycle lc, ForecastData data, LocalDate asOf)` (sorted by member id). Rules (the Python `member_patterns` with the schema's names): window = `[Weeks.mondayOf(asOf) − 13 weeks, Weeks.mondayOf(asOf))` on `assignedDay`; `weekly` = 13 sums of `estimate()` per Monday week (zeros filled); `trend` = least-squares slope of `weekly` over `0..12`, rounded to 3 decimals; weekday counts over `assignedDay` (Monday..Friday), `weekday_shares` rounded to 3 decimals and `top_weekday` the name of the argmax (first on ties), both `[0,0,0,0,0]` / null with no arrivals; mode shares over recent tasks (null with none); `done` = the member's tasks with `finished() != null` and `actualHours() > 0`; `estimate_ratio_median` over `actual / estimate` for `estimate > 0`; `cycle_days_median` and `cycle_days_by_type` (keyed by `family().label()`) over `cycleDays()`; `lateness_days_median` and `share_late` (`lateness > 0`) over `latenessDays()` when present; `share_with_project` = share of recent tasks with a project; `hours_by_project` = recent estimate per project key over the total (3 decimals), empty when zero; `open_tasks`, `open_est_hours` (`remaining()` when present else `estimate()`), `overdue_open` (`dueDate < asOf`) over the member's assigned, not-done tasks. Medians use the same "lower-middle averaged" rule as `EffortModel.median`. `deadline_proximity_corr` is not computed (the schema has no project dates) — ruling recorded in the closing notes.
- `Clustering.assign(List<MemberPattern> table)` → `Map<UUID, Integer>` (sorted by member id): all 0 when fewer than 6 members; features `hours_per_week_13w, trend_hours_per_week, share_manual, share_self_picked, share_project, estimate_ratio_median, cycle_days_median, share_late` with nulls as 0, standardised (mean 0, std 1, std 0 → 0); for `k = 2 .. min(5, n − 1)`: k-means with k-means++ seeding from `new Random(0)`, 10 restarts keeping the lowest inertia, at most 100 iterations; silhouette score; the labelling with the best silhouette wins; labels renumbered by first appearance in member-id order so the result is stable.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatternsTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 9, 2);              // Wednesday; window Monday 2026-06-01 .. 2026-08-30
    static final MemberRow ANA = TestData.member("ana", TestData.TEAM);
    static final UUID PROJECT = TestData.id("proj");

    @Test
    void recentWindowStatisticsFollowTheThirteenWeeks() {
        LocalDateTime mon = LocalDate.of(2026, 8, 24).atTime(9, 0);       // Monday of the last window week
        TaskRow a = TestData.task("1", ANA.id(), mon, 8).withProject(PROJECT).withReporter(ANA.id());
        TaskRow b = TestData.task("2", ANA.id(), mon.plusDays(1), 4).withType("Bug");
        TaskRow old = TestData.task("3", ANA.id(), LocalDate.of(2026, 5, 4).atTime(9, 0), 40);
        TaskRow current = TestData.task("4", ANA.id(), LocalDate.of(2026, 8, 31).atTime(9, 0), 6);   // this week: outside the window
        ForecastData data = TestData.data(List.of(ANA), List.of(a, b, old, current), List.of(), List.of())
                .withProjects(List.of(new ProjectRow(PROJECT, "PRJ", "Project", "ACTIVE", TestData.TEAM)));
        MemberPattern p = Patterns.of(ANA.id(), Lifecycle.derive(data), data, AS_OF);
        assertEquals(2, p.tasks13w());
        assertEquals(12.0, p.hours13w(), 1e-9);
        assertEquals(12.0 / 13, p.hoursPerWeek13w(), 1e-9);
        assertTrue(p.trendHoursPerWeek() > 0, "all hours in the last week: rising trend");
        assertEquals(0.5, p.shareSelfPicked(), 1e-9);
        assertEquals(0.5, p.shareManual(), 1e-9);
        assertEquals("Monday", p.topWeekday());
        assertEquals(List.of(0.5, 0.5, 0.0, 0.0, 0.0), p.weekdayShares());
        assertEquals(0.5, p.shareWithProject(), 1e-9);
        assertEquals(1.0, p.hoursByProject().get("PRJ"), 1e-9);
        assertNull(p.estimateRatioMedian(), "nothing finished");
        assertEquals(4, p.openTasks());
        assertEquals(58.0, p.openEstHours(), 1e-9);
        assertEquals(ANA.id().toString(), p.toMap().get("member_id"), "member ids are strings in the facts");
        assertEquals(20, p.toMap().size());
    }

    @Test
    void completionStatisticsUseTheWholeHistory() {
        LocalDateTime c = LocalDate.of(2026, 3, 2).atTime(9, 0);
        TaskRow fast = TestData.task("1", ANA.id(), c, 10).withStatus("DONE").withFinished(c.plusDays(1)).withDue(c.toLocalDate().plusDays(3)).withRemaining(0.0);
        TaskRow slow = TestData.task("2", ANA.id(), c, 10).withStatus("DONE").withFinished(c.plusDays(9)).withDue(c.toLocalDate().plusDays(3)).withType("Bug").withRemaining(0.0);
        ForecastData data = TestData.data(List.of(ANA), List.of(fast, slow), List.of(), List.of(
                TestData.log(fast.id(), ANA.id(), c.toLocalDate(), 5), TestData.log(slow.id(), ANA.id(), c.toLocalDate(), 20)));
        MemberPattern p = Patterns.of(ANA.id(), Lifecycle.derive(data), data, AS_OF);
        assertEquals(0, p.tasks13w());
        assertNull(p.shareManual());
        assertNull(p.topWeekday());
        assertEquals((0.5 + 2.0) / 2, p.estimateRatioMedian(), 1e-9);
        assertEquals((2 + 10) / 2.0, p.cycleDaysMedian(), 1e-9);
        assertEquals(2.0, p.cycleDaysByFamily().get("delivery"), 1e-9);
        assertEquals(10.0, p.cycleDaysByFamily().get("defect"), 1e-9);
        assertEquals((-2 + 6) / 2.0, p.latenessDaysMedian(), 1e-9);
        assertEquals(0.5, p.shareLate(), 1e-9);
        assertEquals(0, p.openTasks());
    }

    @Test
    void tableCoversEveryMemberOfTheSeed() {
        ForecastData data = SeededData.data();
        List<MemberPattern> table = Patterns.table(data.members(), Lifecycle.derive(data), data, SeededData.asOf());
        assertEquals(data.members().size(), table.size());
        for (int i = 1; i < table.size(); i++) {
            assertTrue(table.get(i - 1).memberId().toString().compareTo(table.get(i).memberId().toString()) < 0);
        }
        assertTrue(table.stream().anyMatch(p -> p.tasks13w() > 0));
    }
}
```

```java
package com.workloadhub.forecast.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClusteringTest {

    static MemberPattern pattern(int i, double hoursPerWeek, double shareManual, double cycle) {
        return new MemberPattern(new UUID(0x3000_0000_0000_0000L, i), 10, hoursPerWeek * 13, hoursPerWeek, 0.0, shareManual, 1 - shareManual, 0.0,
                "Monday", List.of(1.0, 0.0, 0.0, 0.0, 0.0), 1.0, cycle, Map.of(), 0.0, 0.0, 0.5, Map.of(), 2, 8.0, 0);
    }

    @Test
    void fewerThanSixMembersAreOneCluster() {
        List<MemberPattern> table = List.of(pattern(1, 10, 0.2, 3), pattern(2, 40, 0.9, 12), pattern(3, 11, 0.1, 4));
        assertTrue(Clustering.assign(table).values().stream().allMatch(v -> v == 0));
    }

    @Test
    void twoObviousGroupsSeparateAndLabelsAreStable() {
        List<MemberPattern> table = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            table.add(pattern(i, 10 + i * 0.1, 0.1 + i * 0.01, 3 + i * 0.1));
        }
        for (int i = 6; i <= 10; i++) {
            table.add(pattern(i, 40 + i * 0.1, 0.9 - i * 0.01, 12 + i * 0.1));
        }
        Map<UUID, Integer> first = Clustering.assign(table);
        assertEquals(first, Clustering.assign(table), "deterministic");
        assertEquals(0, first.get(table.get(0).memberId()), "labels renumbered by first appearance");
        for (int i = 0; i < 5; i++) {
            assertEquals(first.get(table.get(0).memberId()), first.get(table.get(i).memberId()));
            assertEquals(first.get(table.get(5).memberId()), first.get(table.get(5 + i).memberId()));
        }
        assertTrue(first.get(table.get(0).memberId()) != first.get(table.get(5).memberId()));
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='PatternsTest,ClusteringTest'`
Expected: compilation errors.

- [ ] **Step 3: Write `MemberPattern` and `Patterns`**

```java
package com.workloadhub.forecast.facts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic statistics about one member, handed to the narrative as facts. */
public record MemberPattern(UUID memberId, int tasks13w, double hours13w, double hoursPerWeek13w, double trendHoursPerWeek, Double shareManual,
        Double shareSelfPicked, Double shareProject, String topWeekday, List<Double> weekdayShares, Double estimateRatioMedian, Double cycleDaysMedian,
        Map<String, Double> cycleDaysByFamily, Double latenessDaysMedian, Double shareLate, Double shareWithProject, Map<String, Double> hoursByProject,
        int openTasks, double openEstHours, int overdueOpen) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("member_id", memberId.toString());
        m.put("tasks_13w", tasks13w);
        m.put("hours_13w", hours13w);
        m.put("hours_per_week_13w", hoursPerWeek13w);
        m.put("trend_hours_per_week", trendHoursPerWeek);
        m.put("share_manual", shareManual);
        m.put("share_self_picked", shareSelfPicked);
        m.put("share_project", shareProject);
        m.put("top_weekday", topWeekday);
        m.put("weekday_shares", weekdayShares);
        m.put("estimate_ratio_median", estimateRatioMedian);
        m.put("cycle_days_median", cycleDaysMedian);
        m.put("cycle_days_by_type", cycleDaysByFamily);
        m.put("lateness_days_median", latenessDaysMedian);
        m.put("share_late", shareLate);
        m.put("share_with_project", shareWithProject);
        m.put("hours_by_project", hoursByProject);
        m.put("open_tasks", openTasks);
        m.put("open_est_hours", openEstHours);
        m.put("overdue_open", overdueOpen);
        return m;
    }
}
```

```java
package com.workloadhub.forecast.facts;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.Mode;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** The Python `member_patterns` on the WorkloadHub lifecycle facts. */
public final class Patterns {

    public static final int WINDOW_WEEKS = 13;
    static final List<String> WEEKDAYS = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday");

    private Patterns() {
    }

    public static List<MemberPattern> table(List<MemberRow> members, Lifecycle lc, ForecastData data, LocalDate asOf) {
        return members.stream().sorted((a, b) -> a.id().toString().compareTo(b.id().toString()))
                .map(m -> of(m.id(), lc, data, asOf)).toList();
    }

    public static MemberPattern of(UUID member, Lifecycle lc, ForecastData data, LocalDate asOf) {
        LocalDate windowEnd = Weeks.mondayOf(asOf);
        LocalDate windowStart = windowEnd.minusWeeks(WINDOW_WEEKS);
        List<TaskFacts> mine = lc.assignedTo(member);
        List<TaskFacts> recent = mine.stream()
                .filter(f -> !f.assignedDay().isBefore(windowStart) && f.assignedDay().isBefore(windowEnd)).toList();
        double[] weekly = new double[WINDOW_WEEKS];
        double[] weekdayCounts = new double[5];
        int self = 0;
        int manual = 0;
        int project = 0;
        int withProject = 0;
        Map<UUID, Double> byProject = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (TaskFacts f : recent) {
            weekly[(int) Weeks.weeksBetween(windowStart, f.assignedDay())] += f.estimate();
            DayOfWeek dow = f.assignedDay().getDayOfWeek();
            if (dow.getValue() <= 5) {
                weekdayCounts[dow.getValue() - 1]++;
            }
            if (f.mode() == Mode.SELF_PICKED) {
                self++;
            } else if (f.mode() == Mode.MANUAL) {
                manual++;
            } else {
                project++;
            }
            if (f.task().projectId() != null) {
                withProject++;
                byProject.merge(f.task().projectId(), f.estimate(), Double::sum);
            }
        }
        int n = recent.size();
        double hours13 = 0;
        for (double w : weekly) {
            hours13 += w;
        }
        double weekdayTotal = 0;
        for (double c : weekdayCounts) {
            weekdayTotal += c;
        }
        List<Double> weekdayShares = new ArrayList<>();
        int top = 0;
        for (int i = 0; i < 5; i++) {
            weekdayShares.add(weekdayTotal == 0 ? 0.0 : round3(weekdayCounts[i] / weekdayTotal));
            if (weekdayCounts[i] > weekdayCounts[top]) {
                top = i;
            }
        }
        Map<String, Double> hoursByProject = new TreeMap<>();
        double projectHours = byProject.values().stream().mapToDouble(Double::doubleValue).sum();
        if (projectHours > 0) {
            Map<UUID, ProjectRow> projects = data.projectById();
            byProject.forEach((id, h) -> hoursByProject.put(projects.containsKey(id) ? projects.get(id).key() : id.toString(), round3(h / projectHours)));
        }
        List<Double> ratios = new ArrayList<>();
        List<Double> cycles = new ArrayList<>();
        Map<String, List<Double>> cyclesByFamily = new TreeMap<>();
        List<Double> lateness = new ArrayList<>();
        for (TaskFacts f : mine) {
            if (f.finished() == null || f.actualHours() <= 0) {
                continue;
            }
            if (f.estimate() > 0) {
                ratios.add(f.actualHours() / f.estimate());
            }
            if (f.cycleDays() != null) {
                cycles.add((double) f.cycleDays());
                cyclesByFamily.computeIfAbsent(f.family().label(), k -> new ArrayList<>()).add((double) f.cycleDays());
            }
            if (f.latenessDays() != null) {
                lateness.add((double) f.latenessDays());
            }
        }
        Map<String, Double> cycleByFamily = new TreeMap<>();
        cyclesByFamily.forEach((k, v) -> cycleByFamily.put(k, median(v)));
        int open = 0;
        double openHours = 0;
        int overdue = 0;
        for (TaskFacts f : mine) {
            if (f.done()) {
                continue;
            }
            open++;
            openHours += f.remaining() != null ? f.remaining() : f.estimate();
            if (f.task().dueDate() != null && f.task().dueDate().isBefore(asOf)) {
                overdue++;
            }
        }
        return new MemberPattern(member, n, hours13, hours13 / WINDOW_WEEKS, round3(slope(weekly)),
                n == 0 ? null : (double) manual / n, n == 0 ? null : (double) self / n, n == 0 ? null : (double) project / n,
                weekdayTotal == 0 ? null : WEEKDAYS.get(top), weekdayShares,
                ratios.isEmpty() ? null : median(ratios), cycles.isEmpty() ? null : median(cycles), cycleByFamily,
                lateness.isEmpty() ? null : median(lateness), lateness.isEmpty() ? null : lateness.stream().filter(l -> l > 0).count() / (double) lateness.size(),
                n == 0 ? null : (double) withProject / n, hoursByProject, open, openHours, overdue);
    }

    /** Least-squares slope of values over 0..n−1. */
    static double slope(double[] values) {
        int n = values.length;
        double xMean = (n - 1) / 2.0;
        double yMean = 0;
        for (double v : values) {
            yMean += v / n;
        }
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (i - xMean) * (values[i] - yMean);
            den += (i - xMean) * (i - xMean);
        }
        return den == 0 ? 0.0 : num / den;
    }

    static double median(List<Double> values) {
        List<Double> s = values.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
```

- [ ] **Step 4: Write `Clustering`**

```java
package com.workloadhub.forecast.facts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Standardised k-means over a few pattern statistics, the labelling with the best silhouette wins. */
public final class Clustering {

    public static final int MIN_MEMBERS = 6;
    static final int RESTARTS = 10;
    static final int MAX_ITERATIONS = 100;

    private Clustering() {
    }

    public static Map<UUID, Integer> assign(List<MemberPattern> tableIn) {
        List<MemberPattern> table = tableIn.stream().sorted((a, b) -> a.memberId().toString().compareTo(b.memberId().toString())).toList();
        Map<UUID, Integer> out = new LinkedHashMap<>();
        int n = table.size();
        if (n < MIN_MEMBERS) {
            table.forEach(p -> out.put(p.memberId(), 0));
            return out;
        }
        double[][] x = standardise(features(table));
        int[] best = new int[n];
        double bestScore = -1.0;
        for (int k = 2; k <= Math.min(5, n - 1); k++) {
            int[] labels = kMeans(x, k, new Random(0));
            if (Arrays.stream(labels).distinct().count() < 2) {
                continue;
            }
            double score = silhouette(x, labels, k);
            if (score > bestScore) {
                bestScore = score;
                best = labels;
            }
        }
        Map<Integer, Integer> renumber = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int label = renumber.computeIfAbsent(best[i], k -> renumber.size());
            out.put(table.get(i).memberId(), label);
        }
        return out;
    }

    static double[][] features(List<MemberPattern> table) {
        double[][] x = new double[table.size()][];
        for (int i = 0; i < table.size(); i++) {
            MemberPattern p = table.get(i);
            x[i] = new double[] {p.hoursPerWeek13w(), p.trendHoursPerWeek(), z(p.shareManual()), z(p.shareSelfPicked()), z(p.shareProject()),
                    z(p.estimateRatioMedian()), z(p.cycleDaysMedian()), z(p.shareLate())};
        }
        return x;
    }

    private static double z(Double v) {
        return v == null ? 0.0 : v;
    }

    static double[][] standardise(double[][] x) {
        int n = x.length;
        int d = x[0].length;
        double[][] out = new double[n][d];
        for (int j = 0; j < d; j++) {
            double mean = 0;
            for (double[] row : x) {
                mean += row[j] / n;
            }
            double var = 0;
            for (double[] row : x) {
                var += (row[j] - mean) * (row[j] - mean) / n;
            }
            double std = Math.sqrt(var);
            for (int i = 0; i < n; i++) {
                out[i][j] = std == 0 ? 0.0 : (x[i][j] - mean) / std;
            }
        }
        return out;
    }

    static int[] kMeans(double[][] x, int k, Random rnd) {
        int[] bestLabels = null;
        double bestInertia = Double.POSITIVE_INFINITY;
        for (int restart = 0; restart < RESTARTS; restart++) {
            double[][] centres = seed(x, k, rnd);
            int[] labels = new int[x.length];
            for (int it = 0; it < MAX_ITERATIONS; it++) {
                boolean changed = false;
                for (int i = 0; i < x.length; i++) {
                    int nearest = nearest(x[i], centres);
                    if (nearest != labels[i]) {
                        labels[i] = nearest;
                        changed = true;
                    }
                }
                double[][] next = new double[k][x[0].length];
                int[] counts = new int[k];
                for (int i = 0; i < x.length; i++) {
                    counts[labels[i]]++;
                    for (int j = 0; j < x[i].length; j++) {
                        next[labels[i]][j] += x[i][j];
                    }
                }
                for (int c = 0; c < k; c++) {
                    if (counts[c] == 0) {
                        next[c] = centres[c];
                    } else {
                        for (int j = 0; j < next[c].length; j++) {
                            next[c][j] /= counts[c];
                        }
                    }
                }
                centres = next;
                if (!changed && it > 0) {
                    break;
                }
            }
            double inertia = 0;
            for (int i = 0; i < x.length; i++) {
                inertia += sq(x[i], centres[labels[i]]);
            }
            if (inertia < bestInertia) {
                bestInertia = inertia;
                bestLabels = labels.clone();
            }
        }
        return bestLabels;
    }

    /** k-means++ seeding. */
    private static double[][] seed(double[][] x, int k, Random rnd) {
        double[][] centres = new double[k][];
        centres[0] = x[rnd.nextInt(x.length)].clone();
        for (int c = 1; c < k; c++) {
            double[] d = new double[x.length];
            double total = 0;
            for (int i = 0; i < x.length; i++) {
                double min = Double.POSITIVE_INFINITY;
                for (int j = 0; j < c; j++) {
                    min = Math.min(min, sq(x[i], centres[j]));
                }
                d[i] = min;
                total += min;
            }
            double r = rnd.nextDouble() * total;
            int chosen = x.length - 1;
            double acc = 0;
            for (int i = 0; i < x.length; i++) {
                acc += d[i];
                if (acc >= r) {
                    chosen = i;
                    break;
                }
            }
            centres[c] = x[chosen].clone();
        }
        return centres;
    }

    private static int nearest(double[] p, double[][] centres) {
        int best = 0;
        double bestD = Double.POSITIVE_INFINITY;
        for (int c = 0; c < centres.length; c++) {
            double d = sq(p, centres[c]);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private static double sq(double[] a, double[] b) {
        double s = 0;
        for (int j = 0; j < a.length; j++) {
            s += (a[j] - b[j]) * (a[j] - b[j]);
        }
        return s;
    }

    static double silhouette(double[][] x, int[] labels, int k) {
        int n = x.length;
        double total = 0;
        for (int i = 0; i < n; i++) {
            double[] sums = new double[k];
            int[] counts = new int[k];
            for (int j = 0; j < n; j++) {
                if (j != i) {
                    sums[labels[j]] += Math.sqrt(sq(x[i], x[j]));
                    counts[labels[j]]++;
                }
            }
            int own = labels[i];
            double a = counts[own] == 0 ? 0.0 : sums[own] / counts[own];
            double b = Double.POSITIVE_INFINITY;
            for (int c = 0; c < k; c++) {
                if (c != own && counts[c] > 0) {
                    b = Math.min(b, sums[c] / counts[c]);
                }
            }
            total += counts[own] == 0 || b == Double.POSITIVE_INFINITY ? 0.0 : (b - a) / Math.max(a, b);
        }
        return total / n;
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='PatternsTest,ClusteringTest'`
Expected: 5 passed.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/facts server/forecast-core/src/test/java/com/workloadhub/forecast/facts
git commit -m "feat(server): member pattern statistics and deterministic clustering for the facts

Thirteen-week arrival mix, weekday habits, estimate and cycle medians
per member, and a seeded k-means with silhouette selection."
```

---

### Task 4: `FactsBuilder` and the team-capacity rows

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows/TeamCapacityRow.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastData.java` (a `teamCapacity` list with `withTeamCapacity`)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastRepository.java` (query `team_capacity`)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Truncation.java` (carry `teamCapacity` through)
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/facts/FactsBuilder.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/facts/FactsBuilderTest.java`

**Interfaces:**
- `record TeamCapacityRow(UUID teamId, LocalDate weekStart, double totalCapacity, double allocated)`.
- `ForecastData.teamCapacity()` (empty by default; sorted by team id string then week) and `ForecastData withTeamCapacity(List<TeamCapacityRow>)`; the repository reads `SELECT team_id, week_start, total_capacity_hrs, allocated_hrs FROM team_capacity` and applies it; `Truncation.at` ends with `.withTeamCapacity(data.teamCapacity())`.
- `FactsBuilder`: `static Map<String, Object> build(TeamOutcome outcome, UUID runId, LocalDateTime generatedAt)` and `static String toJson(Map<String, Object> facts)` (Jackson `JsonMapper` with the LF-pinned pretty printer of `ExportFiles.mapper()`; `NaN` and infinities are replaced by null by the builder, never written). Shape (keys and values, all dates as ISO strings, UUIDs as strings, hours rounded to 2 decimals unless stated):
  - `run`: `id`, `as_of`, `weeks` `[f1, f2]`, `generated_at`, `origin`, `horizons`.
  - `team`: `id`, `name`, `parent_team_id`, `manager_id`, `totals` per week (`week`, `demand`, `capacity`, `planned`, rounded to 1 decimal), `team_capacity` (rows for the two weeks from `data.teamCapacity()`: `week`, `total`, `allocated`; empty when none), `planned_backlog` (per project of the allocation's pieces: `project_id`, `project_key`, `tasks`, `estimated_hours`, `hours_in_window`, `hours_after_window`, plus `candidates` and `candidate_hours` totals).
  - `members`: one object per team member in id order: `id`, `name`, `role`, `job_title`, `history_13w` (the 13 weeks up to the origin: `week`, `hours` = `est_hours` rounded 1, `fresh_hours`, `tasks`), `forecast` (per week: `week`, `demand`, `low`, `high`, `capacity`, `overload`, `open_hours`, `new_hours`, `planned_hours`, `working_days`, `absence_hours`, `due_hours` = remaining of the member's open tasks due that week), `patterns` (`MemberPattern.toMap()` + `cluster`), `open_tasks` (`key`, `title`, `type`, `family`, `priority`, `estimated_hours`, `remaining_hours`, `due_date`, `overdue`, `project_key`, `in_progress`), `reopened_tasks` (keys of the member's tasks with `reopened_from_done`), `logged_hours_4w` (the four weeks up to the origin: `week`, `hours` from `time_logs` by the member), `unlogged_tasks` (keys of the member's finished tasks flagged unlogged), `likely_work`: `planned` (this member's pieces: `key`, `title`, `project_key`, `type`, `estimated_hours`, `share` rounded 2, `expected_date`, `expected_week` or `"after_window"`, `hours_in_window`), `project_roles` (for each project of the team with at least one open or backlog task: `project_key`, `share` = the member's share of that project's tasks assigned in the last 26 weeks, `dominant_types` up to three `{type, count}`, `phase` = `"active"`), `recent_mix` (task-type counts over the last 13 weeks).
  - `projects`: the team's projects (`projectIdsOfTeamAndParent`) with `id`, `key`, `name`, `status`, `open_tasks`, `backlog_tasks`, `first_due`.
  - `model`: `champion`, `champion_mase` (null when NaN), `forced_model`, `mase_by_model`, `backtest_origins`, `horizons`, `interval` (`basis` = `"backtest residuals"`, per horizon `low_offset`, `high_offset`), `unavailable`, `planned_basis` = `"share weights, 26-week window, shrink k=3"` when planned work is enabled else `"disabled"`, `limitations` = `"arrivals during the current partial week are not modelled; open tasks are placed from the first forecast week; backlog work created and assigned inside the window is missed by both components"`, `seconds_by_phase`.
  - `rebalancing_candidates`: `overloaded` (`member_id`, `name`, `overload_hours` summed over the two weeks, rounded 1, when > 0) and `underloaded` (`member_id`, `name`, `spare_hours` when capacity > 0 and demand < 0.7 × capacity over the two weeks).
  - `pending_holidays`: holidays with `active` and not `confirmed` (`title`, `start`, `end`).
  - `data_quality`: `unresolved_assignments` (task keys from the lifecycle, only the team's tasks), `unlogged_tasks` (idem), `history_weeks`.
- Everything is computed from `TeamOutcome` and its `Prepared`; the builder takes nothing else.

- [ ] **Step 1: Write the failing test**

```java
package com.workloadhub.forecast.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FactsBuilderTest {

    static TeamOutcome outcome;
    static Map<String, Object> facts;
    static String json;

    @BeforeAll
    static void run() {
        ForecastData data = SeededData.data();
        ForecastRunner runner = new ForecastRunner(new CapacityRule(40), true);
        Prepared prepared = runner.prepare(data, SeededData.asOf(), null, ForecastRunner.ProgressListener.NONE);
        UUID team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
        outcome = runner.forTeam(prepared, team, null);
        facts = FactsBuilder.build(outcome, UUID.fromString("00000000-0000-0000-0000-000000000001"), LocalDateTime.of(2026, 9, 6, 12, 0));
        json = FactsBuilder.toJson(facts);
    }

    @Test
    void topLevelShapeMatchesThePythonFactsPlusTheAdditions() {
        assertEquals(List.of("run", "team", "members", "projects", "model", "rebalancing_candidates", "pending_holidays", "data_quality"),
                List.copyOf(facts.keySet()));
        Map<?, ?> run = (Map<?, ?>) facts.get("run");
        assertEquals("00000000-0000-0000-0000-000000000001", run.get("id"));
        assertEquals("2026-09-06", run.get("as_of"));
        assertEquals(List.of("2026-09-07", "2026-09-14"), run.get("weeks"));
        Map<?, ?> team = (Map<?, ?>) facts.get("team");
        assertEquals(outcome.teamId().toString(), team.get("id"));
        assertEquals(2, ((List<?>) team.get("totals")).size());
        assertNotNull(team.get("planned_backlog"));
        Map<?, ?> model = (Map<?, ?>) facts.get("model");
        assertEquals(outcome.prepared().champion(), model.get("champion"));
        assertEquals("backtest residuals", ((Map<?, ?>) model.get("interval")).get("basis"));
        assertEquals("share weights, 26-week window, shrink k=3", model.get("planned_basis"));
        Map<?, ?> quality = (Map<?, ?>) facts.get("data_quality");
        assertEquals(outcome.prepared().historyWeeks(), quality.get("history_weeks"));
    }

    @Test
    void everyMemberCarriesHistoryForecastPatternsAndLikelyWork() {
        List<?> members = (List<?>) facts.get("members");
        assertEquals(outcome.members().size(), members.size());
        Map<?, ?> first = (Map<?, ?>) members.get(0);
        assertEquals(outcome.members().get(0).id().toString(), first.get("id"));
        assertEquals(outcome.members().get(0).fullName(), first.get("name"));
        assertEquals(13, ((List<?>) first.get("history_13w")).size());
        List<?> forecast = (List<?>) first.get("forecast");
        assertEquals(2, forecast.size());
        Map<?, ?> week = (Map<?, ?>) forecast.get(0);
        assertEquals(outcome.memberWeeks().get(0).demandHrs(), week.get("demand"));
        assertTrue(week.containsKey("due_hours") && week.containsKey("planned_hours") && week.containsKey("working_days"));
        Map<?, ?> patterns = (Map<?, ?>) first.get("patterns");
        assertTrue(patterns.containsKey("cluster") && patterns.containsKey("hours_per_week_13w"));
        Map<?, ?> likely = (Map<?, ?>) first.get("likely_work");
        assertEquals(List.of("planned", "project_roles", "recent_mix"), List.copyOf(((Map<String, ?>) likely).keySet()));
        assertEquals(4, ((List<?>) first.get("logged_hours_4w")).size());
        assertNotNull(first.get("open_tasks"));
        assertNotNull(first.get("reopened_tasks"));
        assertNotNull(first.get("unlogged_tasks"));
    }

    @Test
    void jsonIsStableHasNoNaNAndUsesKeysNotUuidsForTasks() {
        assertEquals(json, FactsBuilder.toJson(FactsBuilder.build(outcome, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                LocalDateTime.of(2026, 9, 6, 12, 0))));
        assertFalse(json.contains("NaN") || json.contains("Infinity"));
        JsonNode root = JsonMapper.builder().build().readTree(json);
        for (JsonNode task : root.at("/members/0/open_tasks")) {
            assertTrue(task.has("key") && !task.has("id"));
        }
        assertTrue(root.at("/run/generated_at").asText().startsWith("2026-09-06T12:00"));
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=FactsBuilderTest`
Expected: compilation errors.

- [ ] **Step 3: Add the team-capacity rows**

`TeamCapacityRow` is a plain record. In `ForecastData` add a private final `List<TeamCapacityRow> teamCapacity` (empty in the existing constructor, sorted by `(teamId string, weekStart)`), an accessor `teamCapacity()`, and:

```java
    public ForecastData withTeamCapacity(List<TeamCapacityRow> rows) {
        ForecastData copy = new ForecastData(members, teams, projects, tasks, transitions, timeLogs, capacity, absences, holidays, users, statusCategoryByName);
        copy.teamCapacity = rows.stream().sorted(Comparator.comparing((TeamCapacityRow r) -> r.teamId().toString()).thenComparing(TeamCapacityRow::weekStart)).toList();
        return copy;
    }
```

(make the field non-final, assigned in the constructor to `List.of()`). `withProjects` must copy `teamCapacity` too. In `ForecastRepository.loadAll`, after the holidays:

```java
        List<TeamCapacityRow> teamCapacity = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT team_id, week_start, total_capacity_hrs, allocated_hrs FROM team_capacity")) {
            teamCapacity.add(new TeamCapacityRow(uuid(r, "team_id"), date(r, "week_start"), dbl(r, "total_capacity_hrs"), dbl(r, "allocated_hrs")));
        }
        return new ForecastData(...).withTeamCapacity(teamCapacity);
```

In `Truncation.at`, the returned `ForecastData` gets `.withTeamCapacity(data.teamCapacity())`. Add to `ForecastRepositoryTest`: `assertEquals(SeededData.envelope().rows("team_capacity").size(), data.teamCapacity().size())`.

- [ ] **Step 4: Write `FactsBuilder`**

```java
package com.workloadhub.forecast.facts;

import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TeamCapacityRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.features.WeeklySeries;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.planned.PlannedWork;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** The document Copilot reads: every number the run computed, identified by names and task keys. */
public final class FactsBuilder {

    public static final int HISTORY_WEEKS = 13;
    public static final int LOGGED_WEEKS = 4;
    public static final int ROLE_WINDOW_WEEKS = 26;
    public static final double UNDERLOAD_RATIO = 0.7;
    public static final String PLANNED_BASIS = "share weights, 26-week window, shrink k=3";
    public static final String LIMITATIONS = "arrivals during the current partial week are not modelled; open tasks are placed from the first"
            + " forecast week; backlog work created and assigned inside the window is missed by both components";

    private FactsBuilder() {
    }

    public static String toJson(Map<String, Object> facts) {
        return ExportFiles.mapper().writeValueAsString(facts);
    }

    public static Map<String, Object> build(TeamOutcome out, UUID runId, LocalDateTime generatedAt) {
        Prepared p = out.prepared();
        ForecastData data = p.data();
        Lifecycle lc = p.lifecycle();
        LocalDate f1 = p.forecastWeeks()[0];
        LocalDate f2 = p.forecastWeeks()[1];
        Map<UUID, ProjectRow> projects = data.projectById();
        Map<UUID, List<MemberWeekForecast>> rowsByMember = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (MemberWeekForecast r : out.memberWeeks()) {
            rowsByMember.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);
        }
        List<MemberPattern> patterns = Patterns.table(out.members(), lc, data, p.asOf());
        Map<UUID, Integer> clusters = Clustering.assign(patterns);
        Map<UUID, MemberPattern> patternById = patterns.stream().collect(Collectors.toMap(MemberPattern::memberId, x -> x));
        List<LocalDate> historyWeeks = Weeks.between(p.origin().minusWeeks(HISTORY_WEEKS - 1), p.origin());
        WeeklySeries series = WeeklySeries.build(lc, out.members(), historyWeeks);
        Set<UUID> teamProjects = data.projectIdsOfTeamAndParent(out.teamId());

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("run", map("id", str(runId), "as_of", str(p.asOf()), "weeks", List.of(str(f1), str(f2)), "generated_at", generatedAt.toString(),
                "origin", str(p.origin()), "horizons", List.of(p.horizons()[0], p.horizons()[1])));
        facts.put("team", team(out, data, f1, f2, projects));
        List<Object> members = new ArrayList<>();
        for (MemberRow m : out.members()) {
            members.add(member(m, out, rowsByMember.getOrDefault(m.id(), List.of()), patternById.get(m.id()), clusters.getOrDefault(m.id(), 0),
                    series, historyWeeks, projects, teamProjects));
        }
        facts.put("members", members);
        facts.put("projects", projectFacts(out, teamProjects, projects));
        facts.put("model", model(out));
        facts.put("rebalancing_candidates", rebalancing(out, rowsByMember));
        List<Object> pending = new ArrayList<>();
        for (HolidayRow h : data.holidays()) {
            if (h.active() && !h.confirmed()) {
                pending.add(map("title", h.title(), "start", str(h.start()), "end", str(h.end())));
            }
        }
        facts.put("pending_holidays", pending);
        Set<UUID> memberIds = out.members().stream().map(MemberRow::id).collect(Collectors.toSet());
        Set<String> teamKeys = lc.all().stream().filter(f -> f.assignee() != null && memberIds.contains(f.assignee())).map(f -> f.task().key())
                .collect(Collectors.toSet());
        facts.put("data_quality", map(
                "unresolved_assignments", lc.unresolvedAssignments().stream().filter(teamKeys::contains).sorted().toList(),
                "unlogged_tasks", lc.unloggedTasks().stream().filter(teamKeys::contains).sorted().toList(),
                "history_weeks", p.historyWeeks()));
        return facts;
    }

    private static Map<String, Object> team(TeamOutcome out, ForecastData data, LocalDate f1, LocalDate f2, Map<UUID, ProjectRow> projects) {
        TeamRow team = data.teamById().get(out.teamId());
        List<Object> totals = new ArrayList<>();
        for (LocalDate w : List.of(f1, f2)) {
            double demand = 0;
            double capacity = 0;
            double planned = 0;
            for (MemberWeekForecast r : out.memberWeeks()) {
                if (r.weekStart().equals(w)) {
                    demand += r.demandHrs();
                    capacity += r.capacityHrs();
                    planned += r.plannedHrs();
                }
            }
            totals.add(map("week", str(w), "demand", round1(demand), "capacity", round1(capacity), "planned", round1(planned)));
        }
        List<Object> teamCapacity = new ArrayList<>();
        for (TeamCapacityRow r : data.teamCapacity()) {
            if (r.teamId().equals(out.teamId()) && (r.weekStart().equals(f1) || r.weekStart().equals(f2))) {
                teamCapacity.add(map("week", str(r.weekStart()), "total", round2(r.totalCapacity()), "allocated", round2(r.allocated())));
            }
        }
        Map<UUID, double[]> byProject = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        Map<UUID, Set<UUID>> tasksByProject = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (PlannedWork.Piece piece : out.planned().pieces()) {
            UUID pid = piece.projectId();
            double[] acc = byProject.computeIfAbsent(pid, k -> new double[3]);
            if (tasksByProject.computeIfAbsent(pid, k -> new java.util.HashSet<>()).add(piece.taskId())) {
                acc[0] += piece.estimate();
            }
            acc[1] += piece.hoursInWindow();
            acc[2] += piece.hoursAfterWindow();
        }
        List<Object> backlog = new ArrayList<>();
        byProject.forEach((pid, acc) -> backlog.add(map("project_id", str(pid), "project_key", projects.containsKey(pid) ? projects.get(pid).key() : null,
                "tasks", tasksByProject.get(pid).size(), "estimated_hours", round2(acc[0]), "hours_in_window", round2(acc[1]),
                "hours_after_window", round2(acc[2]))));
        return map("id", str(out.teamId()), "name", team == null ? null : team.name(), "parent_team_id", team == null ? null : str(team.parentId()),
                "manager_id", team == null ? null : str(team.managerId()), "totals", totals, "team_capacity", teamCapacity,
                "planned_backlog", map("candidates", out.planned().candidateCount(), "candidate_hours", round2(out.planned().candidateHours()),
                        "projects", backlog));
    }

    private static Map<String, Object> member(MemberRow m, TeamOutcome out, List<MemberWeekForecast> rows, MemberPattern pattern, int cluster,
            WeeklySeries series, List<LocalDate> historyWeeks, Map<UUID, ProjectRow> projects, Set<UUID> teamProjects) {
        Prepared p = out.prepared();
        Lifecycle lc = p.lifecycle();
        List<TaskFacts> mine = lc.assignedTo(m.id());
        List<Object> history = new ArrayList<>();
        for (LocalDate w : historyWeeks) {
            WeeklySeries.Cell c = series.cell(m.id(), w);
            history.add(map("week", str(w), "hours", round1(c.estHours()), "fresh_hours", round1(c.freshHours()), "tasks", c.nTasks()));
        }
        List<TaskFacts> open = mine.stream().filter(f -> !f.done()).toList();
        List<Object> forecast = new ArrayList<>();
        for (MemberWeekForecast r : rows) {
            LocalDate end = r.weekStart().plusDays(6);
            double due = 0;
            for (TaskFacts f : open) {
                LocalDate d = f.task().dueDate();
                if (d != null && !d.isBefore(r.weekStart()) && !d.isAfter(end)) {
                    due += f.remaining() != null ? f.remaining() : f.estimate();
                }
            }
            forecast.add(map("week", str(r.weekStart()), "demand", r.demandHrs(), "low", r.lowHrs(), "high", r.highHrs(), "capacity", r.capacityHrs(),
                    "overload", r.overloadHrs(), "open_hours", r.openHrs(), "new_hours", r.newHrs(), "planned_hours", r.plannedHrs(),
                    "working_days", r.workingDays(), "absence_hours", r.absenceHrs(), "due_hours", round2(due)));
        }
        Map<String, Object> patternMap = pattern.toMap();
        patternMap.put("cluster", cluster);
        List<Object> openTasks = new ArrayList<>();
        for (TaskFacts f : open) {
            openTasks.add(map("key", f.task().key(), "title", f.task().title(), "type", f.task().typeName(), "family", f.family().label(),
                    "priority", f.task().priority(), "estimated_hours", f.estimate(), "remaining_hours", f.remaining(), "due_date", str(f.task().dueDate()),
                    "overdue", f.task().dueDate() != null && f.task().dueDate().isBefore(p.asOf()),
                    "project_key", key(projects, f.task().projectId()), "in_progress", f.inProgress()));
        }
        Map<LocalDate, Double> logged = new TreeMap<>();
        for (TimeLogRow l : p.data().timeLogs()) {
            if (l.userId().equals(m.id())) {
                logged.merge(Weeks.mondayOf(l.day()), l.hours(), Double::sum);
            }
        }
        List<Object> loggedWeeks = new ArrayList<>();
        for (LocalDate w : Weeks.between(p.origin().minusWeeks(LOGGED_WEEKS - 1), p.origin())) {
            loggedWeeks.add(map("week", str(w), "hours", round2(logged.getOrDefault(w, 0.0))));
        }
        List<Object> planned = new ArrayList<>();
        for (PlannedWork.Piece piece : out.planned().pieces()) {
            if (piece.member().equals(m.id())) {
                planned.add(map("key", piece.key(), "title", piece.title(), "project_key", key(projects, piece.projectId()), "type", piece.family().label(),
                        "estimated_hours", piece.estimate(), "share", round2(piece.share()), "expected_date", str(piece.expectedDate()),
                        "expected_week", piece.expectedWeek() == null ? "after_window" : str(piece.expectedWeek()), "hours_in_window", round2(piece.hoursInWindow())));
            }
        }
        List<Object> roles = new ArrayList<>();
        LocalDate roleStart = p.asOf().minusWeeks(ROLE_WINDOW_WEEKS);
        Map<UUID, List<TaskFacts>> recentByProject = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (TaskFacts f : lc.all()) {
            if (f.isAssigned() && f.task().projectId() != null && teamProjects.contains(f.task().projectId()) && f.assignedDay().isAfter(roleStart)) {
                recentByProject.computeIfAbsent(f.task().projectId(), k -> new ArrayList<>()).add(f);
            }
        }
        for (Map.Entry<UUID, List<TaskFacts>> e : recentByProject.entrySet()) {
            boolean live = lc.all().stream().anyMatch(f -> e.getKey().equals(f.task().projectId()) && !f.done());
            if (!live) {
                continue;
            }
            List<TaskFacts> own = e.getValue().stream().filter(f -> m.id().equals(f.assignee())).toList();
            if (own.isEmpty()) {
                continue;
            }
            Map<String, Integer> types = new TreeMap<>();
            own.forEach(f -> types.merge(f.task().typeName(), 1, Integer::sum));
            List<Object> dominant = types.entrySet().stream().sorted((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()))
                    .limit(3).map(t -> (Object) map("type", t.getKey(), "count", t.getValue())).toList();
            roles.add(map("project_key", key(projects, e.getKey()), "share", round2((double) own.size() / e.getValue().size()), "dominant_types", dominant, "phase", "active"));
        }
        Map<String, Integer> recentMix = new TreeMap<>();
        LocalDate mixStart = Weeks.mondayOf(p.asOf()).minusWeeks(HISTORY_WEEKS);
        for (TaskFacts f : mine) {
            if (!f.assignedDay().isBefore(mixStart)) {
                recentMix.merge(f.task().typeName(), 1, Integer::sum);
            }
        }
        return map("id", str(m.id()), "name", m.fullName(), "role", m.role(), "job_title", m.jobTitle(), "history_13w", history, "forecast", forecast,
                "patterns", patternMap, "open_tasks", openTasks,
                "reopened_tasks", mine.stream().filter(f -> f.task().reopened()).map(f -> f.task().key()).sorted().toList(),
                "logged_hours_4w", loggedWeeks,
                "unlogged_tasks", mine.stream().filter(TaskFacts::unlogged).map(f -> f.task().key()).sorted().toList(),
                "likely_work", map("planned", planned, "project_roles", roles, "recent_mix", recentMix));
    }

    private static List<Object> projectFacts(TeamOutcome out, Set<UUID> teamProjects, Map<UUID, ProjectRow> projects) {
        Lifecycle lc = out.prepared().lifecycle();
        List<Object> outList = new ArrayList<>();
        for (UUID pid : teamProjects) {
            ProjectRow pr = projects.get(pid);
            int open = 0;
            int backlog = 0;
            LocalDate firstDue = null;
            for (TaskFacts f : lc.all()) {
                if (!pid.equals(f.task().projectId()) || f.done()) {
                    continue;
                }
                if (f.isAssigned()) {
                    open++;
                } else {
                    backlog++;
                }
                LocalDate d = f.task().dueDate();
                if (d != null && (firstDue == null || d.isBefore(firstDue))) {
                    firstDue = d;
                }
            }
            outList.add(map("id", str(pid), "key", pr.key(), "name", pr.name(), "status", pr.status(), "open_tasks", open, "backlog_tasks", backlog,
                    "first_due", str(firstDue)));
        }
        return outList;
    }

    private static Map<String, Object> model(TeamOutcome out) {
        Prepared p = out.prepared();
        Map<String, Object> horizons = new LinkedHashMap<>();
        for (int h : p.horizons()) {
            double[] q = p.bandOffsets().get(h);
            horizons.put(String.valueOf(h), map("low_offset", round2(q[0]), "high_offset", round2(q[1])));
        }
        Map<String, Object> mase = new TreeMap<>();
        p.backtest().meanMaseByModel().forEach((k, v) -> mase.put(k, finite(v)));
        return map("champion", p.champion(), "champion_mase", finite(p.championMase()), "forced_model", p.forcedModel(), "mase_by_model", mase,
                "backtest_origins", p.backtestOrigins().stream().map(FactsBuilder::str).toList(), "horizons", List.of(p.horizons()[0], p.horizons()[1]),
                "interval", map("basis", "backtest residuals", "horizons", horizons), "unavailable", new TreeMap<>(p.backtest().unavailable()),
                "planned_basis", out.plannedWorkEnabled() ? PLANNED_BASIS : "disabled", "limitations", LIMITATIONS,
                "seconds_by_phase", new LinkedHashMap<>(p.secondsByPhase()));
    }

    private static Map<String, Object> rebalancing(TeamOutcome out, Map<UUID, List<MemberWeekForecast>> rowsByMember) {
        List<Object> overloaded = new ArrayList<>();
        List<Object> underloaded = new ArrayList<>();
        for (MemberRow m : out.members()) {
            double demand = 0;
            double capacity = 0;
            double overload = 0;
            for (MemberWeekForecast r : rowsByMember.getOrDefault(m.id(), List.of())) {
                demand += r.demandHrs();
                capacity += r.capacityHrs();
                overload += r.overloadHrs();
            }
            if (overload > 0) {
                overloaded.add(map("member_id", str(m.id()), "name", m.fullName(), "overload_hours", round1(overload)));
            }
            if (capacity > 0 && demand < UNDERLOAD_RATIO * capacity) {
                underloaded.add(map("member_id", str(m.id()), "name", m.fullName(), "spare_hours", round1(capacity - demand)));
            }
        }
        return map("overloaded", overloaded, "underloaded", underloaded);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static String str(Object v) {
        return v == null ? null : v.toString();
    }

    static String key(Map<UUID, ProjectRow> projects, UUID id) {
        return id == null ? null : projects.containsKey(id) ? projects.get(id).key() : id.toString();
    }

    static Double finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static double round2(double v) {
        return ForecastRunner.round2(v);
    }
}
```

`ExportFiles.mapper()` already exists (Task 3 of the foundation plan) and pins LF indentation, so the stored JSON is byte-stable across platforms. `MemberPattern.toMap()` values may contain `Double` NaN only through `trendHoursPerWeek` when `weekly` is constant zero (slope returns 0, fine) and the medians (never NaN); the builder's `finite` guards the model block, and the test asserts the string has no `NaN`.

- [ ] **Step 5: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='FactsBuilderTest,ForecastRepositoryTest,TruncationTest'`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/facts/FactsBuilder.java server/forecast-core/src/main/java/com/workloadhub/forecast/data server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Truncation.java server/forecast-core/src/test/java/com/workloadhub/forecast/facts/FactsBuilderTest.java server/forecast-core/src/test/java/com/workloadhub/forecast/data/ForecastRepositoryTest.java
git commit -m "feat(server): the facts document for the narrative, with team capacity rows

The Python shape plus due hours, reopened and unlogged tasks, logged
hours, the team's own capacity plan, pending holidays, likely work and
a data-quality block; task keys and names, never UUIDs, in the text."
```

---

### Task 5: `JdbcRunStore`: runs, member weeks and facts in the module's tables

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/JdbcRunStore.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/JdbcRunStoreTest.java`

**Interfaces:**
- `JdbcRunStore(DataSource dataSource, Dialect dialect)`; every method opens its own `JdbcClient` and, for `finish`, one transaction through `DataSourceTransactionManager`/`TransactionTemplate` (Spring JDBC, already on the classpath).
- `UUID create(RunRequest request, LocalDateTime createdAt)` inserts a `QUEUED` row (`requested_by` = the request's or the nil UUID `00000000-0000-0000-0000-000000000000` when null) and returns the new id (`UUID.randomUUID()`).
- `void markRunning(UUID runId)`; `void fail(UUID runId, String error, LocalDateTime finishedAt)` (error trimmed to its first line, at most 500 characters); `void finish(UUID runId, String champion, double championMase, String backtestJson, List<MemberWeekForecast> rows, String factsJson, LocalDateTime finishedAt)` in one transaction: update the run to `DONE` with champion, `champion_mase` (null when NaN), `backtest_json`, `finished_at`; insert the member-week rows (batches of 200); insert the facts row.
- `Optional<RunSummary> find(UUID runId)`; `List<RunSummary> list(UUID teamId, int limit)` newest first (`created_at DESC, id`); `List<MemberWeekForecast> memberWeeks(UUID runId)` sorted by user id string then week; `Optional<String> facts(UUID runId)`; `Optional<String> backtestJson(UUID runId)`.
- Column types for placeholders: `id/team_id/requested_by/run_id/user_id` uuid, `as_of/week_start` date, `created_at/finished_at` timestamp; booleans do not occur. Text timestamps are written as `LocalDateTime.toString()` without nanoseconds (`withNano(0)`), dates as ISO.

- [ ] **Step 1: Write the failing test**

```java
package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcRunStoreTest {

    static final UUID TEAM = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 6, 10, 0);

    static DataSource sqlite() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        ForecastMigrations.run(ds);
        return ds;
    }

    static List<MemberWeekForecast> rows() {
        return List.of(
                new MemberWeekForecast(USER, LocalDate.of(2026, 9, 7), 10, 5.5, 2, 17.5, 15, 20, 40, 0, 5, 0),
                new MemberWeekForecast(USER, LocalDate.of(2026, 9, 14), 4, 6, 0, 10, 10, 12, 32, 0, 4, 8));
    }

    void lifecycle(DataSource ds) {
        JdbcRunStore store = new JdbcRunStore(ds, Dialect.of(ds));
        UUID id = store.create(new RunRequest(TEAM, USER, LocalDate.of(2026, 9, 6), null, null), T0);
        RunSummary queued = store.find(id).orElseThrow();
        assertEquals(RunStatus.QUEUED, queued.status());
        assertEquals(TEAM, queued.teamId());
        assertEquals(LocalDate.of(2026, 9, 6), queued.asOf());
        assertEquals(T0, queued.createdAt());
        store.markRunning(id);
        assertEquals(RunStatus.RUNNING, store.find(id).orElseThrow().status());
        store.finish(id, "xgboost", 0.83, "{\"scores\":[]}", rows(), "{\"run\":{}}", T0.plusMinutes(1));
        RunSummary done = store.find(id).orElseThrow();
        assertEquals(RunStatus.DONE, done.status());
        assertEquals("xgboost", done.championModel());
        assertEquals(0.83, done.championMase(), 1e-9);
        assertEquals(T0.plusMinutes(1), done.finishedAt());
        assertEquals(rows(), store.memberWeeks(id));
        assertEquals("{\"run\":{}}", store.facts(id).orElseThrow());
        assertEquals("{\"scores\":[]}", store.backtestJson(id).orElseThrow());

        UUID failed = store.create(new RunRequest(TEAM, null, LocalDate.of(2026, 9, 6), "xgboost", false), T0.plusMinutes(2));
        store.fail(failed, "boom\nstack line 2", T0.plusMinutes(3));
        RunSummary f = store.find(failed).orElseThrow();
        assertEquals(RunStatus.FAILED, f.status());
        assertEquals("boom", f.error());
        assertEquals("xgboost", f.forcedModel());
        assertNull(f.championModel());
        assertTrue(store.facts(failed).isEmpty());

        List<RunSummary> list = store.list(TEAM, 10);
        assertEquals(List.of(failed, id), list.stream().map(RunSummary::id).toList(), "newest first");
        assertEquals(1, store.list(TEAM, 1).size());
        assertTrue(store.list(UUID.randomUUID(), 10).isEmpty());
        assertFalse(store.find(UUID.randomUUID()).isPresent());
        UUID nanRun = store.create(new RunRequest(TEAM, USER, LocalDate.of(2026, 9, 6), null, null), T0.plusMinutes(4));
        store.finish(nanRun, "seasonal_naive", Double.NaN, "{}", List.of(), "{}", T0.plusMinutes(5));
        assertNull(store.find(nanRun).orElseThrow().championMase(), "NaN is stored as null");
    }

    @Test
    void sqliteLifecycle() {
        lifecycle(sqlite());
    }

    @Test
    void postgresLifecycle() {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ForecastMigrations.run(ds);
        lifecycle(ds);
    }
}
```

`postgresOrSkip` uses JUnit assumptions and the shared container as in `JdbcGitHubTokenStoreTest`; read that test for the exact setup lines (schema creation is idempotent there through `WorkloadHubSchema.createPostgresql`; if the shared container already holds the schema, mirror what that test does).

- [ ] **Step 2: Run the test to see it fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=JdbcRunStoreTest`
Expected: compilation errors.

- [ ] **Step 3: Write `JdbcRunStore`**

```java
package com.workloadhub.forecast.store;

import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The module's own tables: one row per run, the member-week table and the facts, on SQLite or PostgreSQL. */
public final class JdbcRunStore {

    public static final UUID NIL = new UUID(0L, 0L);
    static final int BATCH = 200;
    static final int ERROR_MAX = 500;

    private final JdbcClient jdbc;
    private final Dialect dialect;
    private final TransactionTemplate tx;

    public JdbcRunStore(DataSource dataSource, Dialect dialect) {
        this.jdbc = JdbcClient.create(dataSource);
        this.dialect = dialect;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private String ph(String type) {
        return dialect.placeholder(type);
    }

    private static String ts(LocalDateTime t) {
        return t == null ? null : t.withNano(0).toString();
    }

    public UUID create(RunRequest request, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO forecast_runs (id, team_id, requested_by, as_of, status, forced_model, created_at) VALUES ("
                + ph("uuid") + ", " + ph("uuid") + ", " + ph("uuid") + ", " + ph("date") + ", ?, ?, " + ph("timestamp") + ")")
                .param(id.toString()).param(request.teamId().toString())
                .param((request.requestedBy() == null ? NIL : request.requestedBy()).toString())
                .param(request.asOf().toString()).param(RunStatus.QUEUED.name()).param(request.forcedModel()).param(ts(createdAt))
                .update();
        return id;
    }

    public void markRunning(UUID runId) {
        jdbc.sql("UPDATE forecast_runs SET status = ? WHERE id = " + ph("uuid")).param(RunStatus.RUNNING.name()).param(runId.toString()).update();
    }

    public void fail(UUID runId, String error, LocalDateTime finishedAt) {
        String line = error == null ? "" : error.strip().lines().findFirst().orElse("");
        if (line.length() > ERROR_MAX) {
            line = line.substring(0, ERROR_MAX);
        }
        jdbc.sql("UPDATE forecast_runs SET status = ?, error = ?, finished_at = " + ph("timestamp") + " WHERE id = " + ph("uuid"))
                .param(RunStatus.FAILED.name()).param(line).param(ts(finishedAt)).param(runId.toString()).update();
    }

    public void finish(UUID runId, String champion, double championMase, String backtestJson, List<MemberWeekForecast> rows, String factsJson,
            LocalDateTime finishedAt) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("UPDATE forecast_runs SET status = ?, champion_model = ?, champion_mase = ?, backtest_json = ?, finished_at = " + ph("timestamp")
                    + " WHERE id = " + ph("uuid"))
                    .param(RunStatus.DONE.name()).param(champion).param(Double.isNaN(championMase) ? null : championMase).param(backtestJson)
                    .param(ts(finishedAt)).param(runId.toString()).update();
            String insert = "INSERT INTO forecast_member_weeks (run_id, user_id, week_start, open_hrs, new_hrs, planned_hrs, low_hrs, high_hrs,"
                    + " capacity_hrs, overload_hrs, working_days, absence_hrs) VALUES (" + ph("uuid") + ", " + ph("uuid") + ", " + ph("date")
                    + ", ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            for (MemberWeekForecast r : rows) {
                jdbc.sql(insert).param(runId.toString()).param(r.userId().toString()).param(r.weekStart().toString())
                        .param(r.openHrs()).param(r.newHrs()).param(r.plannedHrs()).param(r.lowHrs()).param(r.highHrs())
                        .param(r.capacityHrs()).param(r.overloadHrs()).param(r.workingDays()).param(r.absenceHrs()).update();
            }
            jdbc.sql("INSERT INTO forecast_facts (run_id, facts_json, created_at) VALUES (" + ph("uuid") + ", ?, " + ph("timestamp") + ")")
                    .param(runId.toString()).param(factsJson).param(ts(finishedAt)).update();
        });
    }

    public Optional<RunSummary> find(UUID runId) {
        return jdbc.sql("SELECT id, team_id, requested_by, as_of, status, forced_model, champion_model, champion_mase, error, created_at, finished_at"
                + " FROM forecast_runs WHERE id = " + ph("uuid")).param(runId.toString()).query().listOfRows().stream().findFirst().map(JdbcRunStore::summary);
    }

    public List<RunSummary> list(UUID teamId, int limit) {
        return jdbc.sql("SELECT id, team_id, requested_by, as_of, status, forced_model, champion_model, champion_mase, error, created_at, finished_at"
                + " FROM forecast_runs WHERE team_id = " + ph("uuid") + " ORDER BY created_at DESC, id LIMIT " + Math.max(1, limit))
                .param(teamId.toString()).query().listOfRows().stream().map(JdbcRunStore::summary).toList();
    }

    public List<MemberWeekForecast> memberWeeks(UUID runId) {
        List<MemberWeekForecast> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT user_id, week_start, open_hrs, new_hrs, planned_hrs, low_hrs, high_hrs, capacity_hrs, overload_hrs,"
                + " working_days, absence_hrs FROM forecast_member_weeks WHERE run_id = " + ph("uuid") + " ORDER BY user_id, week_start")
                .param(runId.toString()).query().listOfRows()) {
            double open = num(r, "open_hrs");
            double fresh = num(r, "new_hrs");
            double planned = num(r, "planned_hrs");
            out.add(new MemberWeekForecast(UUID.fromString(str(r, "user_id")), date(r, "week_start"), open, fresh, planned,
                    Math.round((open + fresh + planned) * 100.0) / 100.0, num(r, "low_hrs"), num(r, "high_hrs"), num(r, "capacity_hrs"),
                    num(r, "overload_hrs"), (int) num(r, "working_days"), num(r, "absence_hrs")));
        }
        out.sort((a, b) -> a.userId().toString().equals(b.userId().toString()) ? a.weekStart().compareTo(b.weekStart())
                : a.userId().toString().compareTo(b.userId().toString()));
        return out;
    }

    public Optional<String> facts(UUID runId) {
        return jdbc.sql("SELECT facts_json FROM forecast_facts WHERE run_id = " + ph("uuid")).param(runId.toString())
                .query().listOfRows().stream().findFirst().map(r -> str(r, "facts_json"));
    }

    public Optional<String> backtestJson(UUID runId) {
        return jdbc.sql("SELECT backtest_json FROM forecast_runs WHERE id = " + ph("uuid")).param(runId.toString())
                .query().listOfRows().stream().findFirst().map(r -> str(r, "backtest_json"));
    }

    private static RunSummary summary(Map<String, Object> r) {
        Object mase = r.get("champion_mase");
        return new RunSummary(UUID.fromString(str(r, "id")), UUID.fromString(str(r, "team_id")), UUID.fromString(str(r, "requested_by")),
                date(r, "as_of"), RunStatus.valueOf(str(r, "status")), str(r, "forced_model"), str(r, "champion_model"),
                mase == null ? null : ((Number) mase).doubleValue(), str(r, "error"), dateTime(r, "created_at"), dateTime(r, "finished_at"));
    }

    private static String str(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? null : v.toString();
    }

    private static double num(Map<String, Object> r, String col) {
        return ((Number) r.get(col)).doubleValue();
    }

    private static LocalDate date(Map<String, Object> r, String col) {
        Object v = r.get(col);
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        String s = v.toString();
        return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    private static LocalDateTime dateTime(Map<String, Object> r, String col) {
        Object v = r.get(col);
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        return LocalDateTime.parse(v.toString().replace(' ', 'T'));
    }
}
```

`demand_hrs` is not a column of `forecast_member_weeks` (the DDL of the foundation plan stores the components), so `memberWeeks` recomputes `demand = open + new + planned` rounded, which is the invariant. The `Dialect.placeholder("date")` cast handles PostgreSQL's `date` columns; the `ORDER BY user_id` string order differs between SQLite text and PostgreSQL uuid, hence the explicit sort after reading.

- [ ] **Step 4: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest=JdbcRunStoreTest`
Expected: SQLite passes; PostgreSQL passes or is skipped with a visible message when Docker is absent.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/store/JdbcRunStore.java server/forecast-core/src/test/java/com/workloadhub/forecast/store/JdbcRunStoreTest.java
git commit -m "feat(server): persist runs, member weeks and facts in the module's tables

Queued, running, done and failed rows; a finished run writes its
member-week table and facts in one transaction on SQLite or PostgreSQL."
```

---

### Task 6: `DefaultForecastService`, progress, and the Spring wiring

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/service/RunProgressTracker.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/service/DefaultForecastService.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/ForecastAutoConfiguration.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/service/DefaultForecastServiceTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/ForecastAutoConfigurationTest.java`

**Interfaces:**
- `RunProgressTracker`: `void start(UUID runId)` (phase `QUEUED`, 0 %), `void update(UUID runId, String phase, int percent, String message)`, `void done(UUID runId)` (`DONE`, 100), `void failed(UUID runId, String message)` (`FAILED`, 100), `Optional<RunProgress> get(UUID runId)`; a `ConcurrentHashMap`; entries kept for the JVM's life (a few hundred bytes each).
- `DefaultForecastService(DataSource dataSource, Dialect dialect, ForecastRunner runner, JdbcRunStore store, RunProgressTracker progress, int threads, boolean plannedWorkDefault)` implements `ForecastService` and `AutoCloseable` (shuts the executor down):
  - `startRun`: validates the team exists (`SELECT 1 FROM teams WHERE id = ?` → `TEAM_NOT_FOUND`), inserts the `QUEUED` row, submits the run and returns the id immediately; `runNow(RunRequest)` (public, used by the CLI and tests) does the same work synchronously and returns the `RunResult`.
  - The work: `markRunning` + progress `LOADING`; `new ForecastRepository(JdbcClient.create(dataSource), dialect).loadAll()`; `runner.prepare(data, asOf, forced, listener)` where the listener forwards phases to the tracker; `runner.forTeam(prepared, teamId, request.plannedWork())`; progress `FACTS` 85 %; `FactsBuilder.build/toJson`; progress `PERSIST` 95 %; `store.finish(...)` with `backtestJson` = `{"scores":[{model, origin, horizon, mae, mase}...], "mase_by_model": {...}, "unavailable": {...}, "origins": [...]}` written with `ExportFiles.mapper()` (NaN as null); `done`. Any exception: `store.fail(runId, message)`, `progress.failed`, the stack trace to the log (`java.util.logging` or `org.slf4j` — use `org.slf4j.LoggerFactory`, Spring Boot brings it), and `runNow` rethrows as `ForecastException("RUN_FAILED", message)` for a `ForecastException`/`ModelUnavailable` and as the original code otherwise.
  - `getRun`: `RUN_NOT_FOUND` when absent; `RUN_NOT_DONE` when the status is not `DONE` (the summary is still returned inside the exception's message? no: throw); otherwise `RunResult(summary, scores parsed from backtest_json, maseByModel, unavailable, memberWeeks, factsJson)`.
  - `listRuns(teamId, limit)`: `store.list`, `limit` clamped to `[1, 200]`.
  - `progress(runId)`: the tracker's entry, else a `RunProgress` derived from the stored status (`QUEUED` 0, `RUNNING` 50, `DONE` 100, `FAILED` 100 with the error), else `RUN_NOT_FOUND`.
  - `narrate`, `narrative`, `copilotStatus`: `throw ForecastException.of("COPILOT_UNAVAILABLE", "narration is not part of this build yet")`.
- `ForecastAutoConfiguration` gains `@ConditionalOnMissingBean` beans: `CapacityRule` (from `whf.default-weekly-hours`), `ForecastRunner` (planned-work default from properties), `JdbcRunStore`, `RunProgressTracker`, `ForecastService` (`DefaultForecastService` with `whf.run-threads`), all depending on the `ForecastMigrationsRunner` marker so the tables exist.
- A Spring test boots the auto-configuration with an in-memory SQLite `DataSource` (`ApplicationContextRunner` from `spring-boot-test`) and asserts a `ForecastService` bean exists.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcRunStore;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DefaultForecastServiceTest {

    static DefaultForecastService service;
    static UUID team;

    @BeforeAll
    static void boot() {
        DataSource ds = SeededData.dataSource();
        ForecastMigrations.run(ds);
        Dialect dialect = Dialect.of(ds);
        service = new DefaultForecastService(ds, dialect, new ForecastRunner(new CapacityRule(40), true), new JdbcRunStore(ds, dialect),
                new RunProgressTracker(), 1, true);
        ForecastData data = SeededData.data();
        team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
    }

    @AfterAll
    static void stop() throws Exception {
        service.close();
    }

    @Test
    void runNowPersistsAndReturnsTheWholeResult() {
        RunResult r = service.runNow(new RunRequest(team, null, SeededData.asOf(), null, null));
        assertEquals(RunStatus.DONE, r.run().status());
        assertFalse(r.memberWeeks().isEmpty());
        assertTrue(r.factsJson().startsWith("{"));
        assertTrue(r.maseByModel().containsKey("seasonal_naive"));
        assertFalse(r.scores().isEmpty());
        RunResult again = service.getRun(r.run().id());
        assertEquals(r.memberWeeks(), again.memberWeeks());
        assertEquals(r.factsJson(), again.factsJson());
        assertEquals(r.scores(), again.scores());
        assertEquals(100, service.progress(r.run().id()).percent());
        assertEquals(r.run().id(), service.listRuns(team, 5).get(0).id());
    }

    @Test
    void startRunReturnsImmediatelyAndFinishesInTheBackground() throws Exception {
        UUID id = service.startRun(new RunRequest(team, null, SeededData.asOf(), "seasonal_naive", false));
        RunProgress first = service.progress(id);
        assertTrue(List.of("QUEUED", "LOADING", "FEATURES", "BACKTEST", "FORECAST", "FACTS", "PERSIST", "DONE").contains(first.phase()));
        long deadline = System.currentTimeMillis() + 120_000;
        while (service.progress(id).percent() < 100 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertEquals("DONE", service.progress(id).phase());
        RunResult r = service.getRun(id);
        assertEquals("seasonal_naive", r.run().championModel());
        assertTrue(r.memberWeeks().stream().allMatch(w -> w.plannedHrs() == 0.0));
    }

    @Test
    void errorsCarryTheSpecCodes() {
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class,
                () -> service.startRun(new RunRequest(UUID.randomUUID(), null, SeededData.asOf(), null, null))).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ForecastException.class, () -> service.getRun(UUID.randomUUID())).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ForecastException.class, () -> service.progress(UUID.randomUUID())).code());
        assertEquals("COPILOT_UNAVAILABLE", assertThrows(ForecastException.class, () -> service.copilotStatus(UUID.randomUUID())).code());
        assertEquals("COPILOT_UNAVAILABLE", assertThrows(ForecastException.class, () -> service.narrative(UUID.randomUUID(), "en")).code());
    }

    @Test
    void aFailedRunIsRecordedNotSwallowed() {
        UUID emptyTeam = SeededData.data().teams().stream().filter(t -> SeededData.data().membersOfTeam(t.id()).isEmpty()).map(TeamRow::id)
                .findFirst().orElseThrow();
        ForecastException ex = assertThrows(ForecastException.class, () -> service.runNow(new RunRequest(emptyTeam, null, LocalDate.of(2026, 9, 6), null, null)));
        assertEquals("TEAM_NOT_FOUND", ex.code());
        assertEquals(RunStatus.FAILED, service.listRuns(emptyTeam, 1).get(0).status());
        assertEquals("RUN_NOT_DONE", assertThrows(ForecastException.class, () -> service.getRun(service.listRuns(emptyTeam, 1).get(0).id())).code());
    }
}
```

`SeededData.dataSource()` exists (foundation plan); it holds the WorkloadHub tables and the seeded rows. The seed always has at least one team with no counted member ("Unassigned"); if not, create one by inserting a `teams` row in the test.

```java
package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ForecastAutoConfigurationTest {

    @Test
    void registersTheServiceOnTopOfTheHostDataSource() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ForecastAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withPropertyValues("whf.run-threads=1")
                .run(context -> {
                    assertNotNull(context.getBean(ForecastService.class));
                    assertNotNull(context.getBean(com.workloadhub.forecast.run.ForecastRunner.class));
                });
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='DefaultForecastServiceTest,ForecastAutoConfigurationTest'`
Expected: compilation errors.

- [ ] **Step 3: Write the tracker and the service**

```java
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
```

```java
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
            row.put("mae", FactsBuilder_finite(s.mae()));
            row.put("mase", FactsBuilder_finite(s.mase()));
            scores.add(row);
        }
        root.put("scores", scores);
        Map<String, Object> mase = new TreeMap<>();
        p.backtest().meanMaseByModel().forEach((k, v) -> mase.put(k, FactsBuilder_finite(v)));
        root.put("mase_by_model", mase);
        root.put("unavailable", new TreeMap<>(p.backtest().unavailable()));
        root.put("origins", p.backtestOrigins().stream().map(LocalDate::toString).toList());
        return ExportFiles.mapper().writeValueAsString(root);
    }

    private static Double FactsBuilder_finite(double v) {
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
                    s.path("mae").isNull() ? Double.NaN : s.path("mae").asDouble(), s.path("mase").isNull() ? Double.NaN : s.path("mase").asDouble()));
        }
        Map<String, Double> mase = new TreeMap<>();
        bt.path("mase_by_model").properties().forEach(e -> mase.put(e.getKey(), e.getValue().isNull() ? Double.NaN : e.getValue().asDouble()));
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
```

Rename the helper `FactsBuilder_finite` to a plain private `finite` (the name above only shows where the idea comes from). In Jackson 3, `JsonNode.properties()` returns the entry set of an object node; if the compiler disagrees, iterate `bt.path("mase_by_model").propertyNames()` and `get(name)`.

Add to `ForecastAutoConfiguration`:

```java
    @Bean
    @ConditionalOnMissingBean
    CapacityRule capacityRule(ForecastProperties properties) {
        return new CapacityRule(properties.getDefaultWeeklyHours());
    }

    @Bean
    @ConditionalOnMissingBean
    ForecastRunner forecastRunner(CapacityRule capacityRule, ForecastProperties properties) {
        return new ForecastRunner(capacityRule, properties.getPlannedWork().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcRunStore jdbcRunStore(DataSource dataSource, Dialect dialect, ForecastMigrationsRunner migrated) {
        return new JdbcRunStore(dataSource, dialect);
    }

    @Bean
    @ConditionalOnMissingBean
    RunProgressTracker runProgressTracker() {
        return new RunProgressTracker();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    ForecastService forecastService(DataSource dataSource, Dialect dialect, ForecastRunner runner, JdbcRunStore store, RunProgressTracker progress,
            ForecastProperties properties) {
        return new DefaultForecastService(dataSource, dialect, runner, store, progress, properties.getRunThreads(),
                properties.getPlannedWork().isEnabled());
    }
```

- [ ] **Step 4: Run the tests**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='DefaultForecastServiceTest,ForecastAutoConfigurationTest'`
Expected: 5 passed. The auto-configuration test runs the migrations against a fresh SQLite (`whf.flyway.enabled` defaults to true), which is what a host would do.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/service server/forecast-core/src/main/java/com/workloadhub/forecast/ForecastAutoConfiguration.java server/forecast-core/src/test/java/com/workloadhub/forecast/service server/forecast-core/src/test/java/com/workloadhub/forecast/ForecastAutoConfigurationTest.java
git commit -m "feat(server): ForecastService over the store, the runner and a bounded executor

Runs are queued, executed on whf.run-threads threads with live progress,
persisted with their facts, and failures land on the run row with the
stack trace in the log; the auto-configuration registers every bean."
```

---

### Task 7: CLI `run` and `runs`

**Files:**
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/TeamArg.java`
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/RunCommand.java`
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/RunsCommand.java`
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/Services.java`
- Modify: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/ForecastCli.java` (register the subcommands)
- Create: `server/forecast-cli/src/test/java/com/workloadhub/forecast/cli/RunCommandTest.java`

**Interfaces:**
- `Services.open(DataSource ds)` → a small record `Services(DefaultForecastService service, Dialect dialect, JdbcClient jdbc)` that runs `ForecastMigrations.run(ds)` (idempotent), builds `CapacityRule(40)`, `ForecastRunner(rule, true)`, `JdbcRunStore`, `RunProgressTracker`, `DefaultForecastService(..., 1, true)`; `AutoCloseable`.
- `TeamArg.resolve(JdbcClient jdbc, Dialect dialect, String nameOrId)` → `UUID`: an exact UUID, else the unique team whose `name` equals the argument (case-insensitive); throws `IllegalArgumentException` with the candidates when none or several match.
- `run` options: `--team` (required), `--as-of` (default today), `--model` (`xgboost` or `seasonal_naive`), `--user` (name or id of the requesting user, optional), `--no-planned`, `--json` (print the `RunResult` as JSON instead of tables). Prints: `Run <id>: champion <name> (MASE <x.xx>), <n> members, weeks <f1> and <f2>`; a scores table `model | horizon | mean MASE`; the member-week table `member | week | open | new | planned | demand | low | high | capacity | overload` with names from `users`; unavailable models, if any. Exit 0; 2 on a usage error (unknown team or model, bad date); 1 when the run fails (`ForecastException` other than the usage codes) with the message on stderr.
- `runs` options: `--team` (required), `--limit` (default 20). Prints `id | as_of | status | champion | mase | created_at | error`.

- [ ] **Step 1: Write the failing test**

```java
package com.workloadhub.forecast.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class RunCommandTest {

    static String capture(CommandLine cli, int expectedExit, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true));
        try {
            assertEquals(expectedExit, cli.execute(args), () -> "exit code for " + String.join(" ", args) + "\n" + out);
        } finally {
            System.setOut(old);
        }
        return out.toString();
    }

    @Test
    void seedThenRunThenList(@TempDir Path dir) {
        Path db = dir.resolve("r.db");
        Path seeded = dir.resolve("seeded.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "14", "--weeks", "20", "--seed", "3", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        String teams = capture(cli, 0, "teams", "--db", db.toString());
        String team = teams.lines().filter(l -> !l.isBlank() && !l.startsWith("id")).map(l -> l.split("\\s{2,}")[0]).findFirst().orElseThrow();
        String run = capture(cli, 0, "run", "--db", db.toString(), "--team", team, "--as-of", "2026-09-06", "--model", "seasonal_naive");
        assertTrue(run.contains("champion seasonal_naive"), run);
        assertTrue(run.contains("capacity"), run);
        String runs = capture(cli, 0, "runs", "--db", db.toString(), "--team", team);
        assertTrue(runs.contains("DONE"), runs);
        assertEquals(2, cli.execute("run", "--db", db.toString(), "--team", "no-such-team", "--as-of", "2026-09-06"));
        assertEquals(2, cli.execute("run", "--db", db.toString(), "--team", team, "--as-of", "2026-09-06", "--model", "gbm"));
        String json = capture(cli, 0, "run", "--db", db.toString(), "--team", team, "--as-of", "2026-09-06", "--json");
        assertTrue(json.trim().startsWith("{") && json.contains("\"memberWeeks\""), json);
    }
}
```

The test uses a `teams` subcommand (`teams --db` printing `id  name  members` per team, sorted by name) — add it in this task as a third small command, `TeamsCommand`, since resolving a team by name is what `--team` is for and the test needs an id without parsing the seed file.

- [ ] **Step 2: Run the test to see it fail**

Run: `cd server && mvn -B -q test -pl forecast-cli -Dtest=RunCommandTest`
Expected: compilation errors.

- [ ] **Step 3: Write the commands**

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.service.DefaultForecastService;
import com.workloadhub.forecast.service.RunProgressTracker;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcRunStore;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The module's services on a CLI-owned SQLite file: one thread, planned work on, default capacity 40. */
record Services(DefaultForecastService service, Dialect dialect, JdbcClient jdbc, ForecastRunner runner) implements AutoCloseable {

    static Services open(DataSource ds) {
        ForecastMigrations.run(ds);
        Dialect dialect = Dialect.of(ds);
        ForecastRunner runner = new ForecastRunner(new CapacityRule(40), true);
        DefaultForecastService service = new DefaultForecastService(ds, dialect, runner, new JdbcRunStore(ds, dialect), new RunProgressTracker(), 1, true);
        return new Services(service, dialect, JdbcClient.create(ds), runner);
    }

    @Override
    public void close() throws Exception {
        service.close();
    }
}
```

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.store.Dialect;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** --team accepts a UUID or a team name. */
final class TeamArg {

    private TeamArg() {
    }

    static UUID resolve(JdbcClient jdbc, Dialect dialect, String nameOrId) {
        try {
            UUID id = UUID.fromString(nameOrId.trim());
            if (!jdbc.sql("SELECT id FROM teams WHERE id = " + dialect.placeholder("uuid")).param(id.toString()).query().listOfRows().isEmpty()) {
                return id;
            }
            throw new IllegalArgumentException("no team with id " + id);
        } catch (IllegalArgumentException notAUuid) {
            if (notAUuid.getMessage() != null && notAUuid.getMessage().startsWith("no team")) {
                throw notAUuid;
            }
        }
        List<Map<String, Object>> rows = jdbc.sql("SELECT id, name FROM teams WHERE LOWER(name) = LOWER(?) ORDER BY name").param(nameOrId.trim())
                .query().listOfRows();
        if (rows.size() == 1) {
            return UUID.fromString(rows.get(0).get("id").toString());
        }
        List<String> names = jdbc.sql("SELECT name FROM teams ORDER BY name").query().listOfRows().stream().map(r -> r.get("name").toString()).toList();
        throw new IllegalArgumentException(rows.isEmpty() ? "no team named '" + nameOrId + "'; teams: " + names
                : rows.size() + " teams named '" + nameOrId + "', use the id");
    }

    static UUID resolveUser(JdbcClient jdbc, Dialect dialect, String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(nameOrId.trim());
        } catch (IllegalArgumentException notAUuid) {
            List<Map<String, Object>> rows = jdbc.sql("SELECT id FROM users WHERE LOWER(full_name) = LOWER(?) OR LOWER(email) = LOWER(?)")
                    .param(nameOrId.trim()).param(nameOrId.trim()).query().listOfRows();
            if (rows.size() != 1) {
                throw new IllegalArgumentException(rows.isEmpty() ? "no user '" + nameOrId + "'" : "several users match '" + nameOrId + "', use the id");
            }
            return UUID.fromString(rows.get(0).get("id").toString());
        }
    }
}
```

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.data.ExportFiles;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "run", description = "Run a forecast for one team as of a date and print the champion, the scores and the member-week table.")
public class RunCommand implements Callable<Integer> {

    private static final Set<String> USAGE_CODES = Set.of("TEAM_NOT_FOUND", "INVALID_REQUEST");

    @Mixin DbOptions db;

    @Option(names = "--team", required = true, description = "Team name or id")
    String team;

    @Option(names = "--as-of", description = "As-of date, ISO (default: today)")
    String asOf;

    @Option(names = "--model", description = "Force a model: xgboost or seasonal_naive")
    String model;

    @Option(names = "--user", description = "Requesting user, name or id (optional)")
    String user;

    @Option(names = "--no-planned", description = "Switch the planned-work allocation off for this run")
    boolean noPlanned;

    @Option(names = "--json", description = "Print the result as JSON")
    boolean json;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            UUID teamId;
            UUID userId;
            LocalDate date;
            try {
                teamId = TeamArg.resolve(s.jdbc(), s.dialect(), team);
                userId = TeamArg.resolveUser(s.jdbc(), s.dialect(), user);
                date = asOf == null ? LocalDate.now() : LocalDate.parse(asOf);
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            RunResult result;
            try {
                result = s.service().runNow(new RunRequest(teamId, userId, date, model, noPlanned ? Boolean.FALSE : null));
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return USAGE_CODES.contains(e.code()) ? 2 : 1;
            }
            if (json) {
                System.out.println(ExportFiles.mapper().writeValueAsString(result));
                return 0;
            }
            print(result, s);
            return 0;
        }
    }

    private void print(RunResult r, Services s) {
        Map<UUID, String> names = new HashMap<>();
        s.jdbc().sql("SELECT id, full_name FROM users").query().listOfRows()
                .forEach(row -> names.put(UUID.fromString(row.get("id").toString()), String.valueOf(row.get("full_name"))));
        String mase = r.run().championMase() == null ? "n/a" : String.format("%.2f", r.run().championMase());
        long members = r.memberWeeks().stream().map(MemberWeekForecast::userId).distinct().count();
        System.out.printf("Run %s: champion %s (MASE %s), %d members, weeks %s and %s%n", r.run().id(), r.run().championModel(), mase, members,
                r.memberWeeks().isEmpty() ? "?" : r.memberWeeks().get(0).weekStart(),
                r.memberWeeks().isEmpty() ? "?" : r.memberWeeks().get(r.memberWeeks().size() - 1).weekStart());
        System.out.println();
        System.out.printf("%-16s %8s %10s%n", "model", "horizon", "mean MASE");
        r.scores().stream().collect(java.util.stream.Collectors.groupingBy(sc -> sc.model() + "|" + sc.horizon(), java.util.TreeMap::new,
                java.util.stream.Collectors.averagingDouble(sc -> Double.isNaN(sc.mase()) ? 0 : sc.mase())))
                .forEach((key, v) -> System.out.printf("%-16s %8s %10.3f%n", key.split("\\|")[0], key.split("\\|")[1], v));
        if (!r.unavailable().isEmpty()) {
            r.unavailable().forEach((k, v) -> System.out.println("unavailable: " + k + ": " + v));
        }
        System.out.println();
        System.out.printf("%-28s %-10s %7s %7s %7s %7s %7s %7s %8s %8s%n", "member", "week", "open", "new", "planned", "demand", "low", "high", "capacity", "overload");
        for (MemberWeekForecast w : r.memberWeeks()) {
            System.out.printf("%-28s %-10s %7.1f %7.1f %7.1f %7.1f %7.1f %7.1f %8.1f %8.1f%n", names.getOrDefault(w.userId(), w.userId().toString()),
                    w.weekStart(), w.openHrs(), w.newHrs(), w.plannedHrs(), w.demandHrs(), w.lowHrs(), w.highHrs(), w.capacityHrs(), w.overloadHrs());
        }
    }
}
```

The `--json` output serialises the `RunResult` record with `ExportFiles.mapper()`; Jackson 3 serialises records and `LocalDate`/`LocalDateTime` as ISO strings by default (`WRITE_DATES_AS_TIMESTAMPS` is off in 3.x); if the mapper still writes arrays for dates, disable `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` on a mapper local to this command.

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.RunSummary;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "runs", description = "List the runs of a team, newest first.")
public class RunsCommand implements Callable<Integer> {

    @Mixin DbOptions db;

    @Option(names = "--team", required = true, description = "Team name or id")
    String team;

    @Option(names = "--limit", defaultValue = "20", description = "Rows to print (default: ${DEFAULT-VALUE})")
    int limit;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            UUID teamId;
            try {
                teamId = TeamArg.resolve(s.jdbc(), s.dialect(), team);
            } catch (IllegalArgumentException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            System.out.printf("%-36s %-10s %-8s %-15s %6s %-19s %s%n", "id", "as_of", "status", "champion", "mase", "created_at", "error");
            for (RunSummary r : s.service().listRuns(teamId, limit)) {
                System.out.printf("%-36s %-10s %-8s %-15s %6s %-19s %s%n", r.id(), r.asOf(), r.status(), r.championModel() == null ? "-" : r.championModel(),
                        r.championMase() == null ? "-" : String.format("%.2f", r.championMase()), r.createdAt(), r.error() == null ? "" : r.error());
            }
            return 0;
        }
    }
}
```

```java
package com.workloadhub.forecast.cli;

import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "teams", description = "List the teams with their counted member count.")
public class TeamsCommand implements Callable<Integer> {

    @Mixin DbOptions db;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            System.out.printf("%-36s  %-40s  %s%n", "id", "name", "members");
            for (Map<String, Object> r : s.jdbc().sql("SELECT t.id, t.name, (SELECT COUNT(*) FROM team_members tm JOIN users u ON u.id = tm.user_id"
                    + " WHERE tm.team_id = t.id AND u.active = " + s.dialect().boolLiteral(true) + " AND u.role IN ('MEMBER', 'TEAM_LEADER')) AS members"
                    + " FROM teams t ORDER BY t.name").query().listOfRows()) {
                System.out.printf("%-36s  %-40s  %s%n", r.get("id"), r.get("name"), r.get("members"));
            }
            return 0;
        }
    }
}
```

Register `RunCommand.class, RunsCommand.class, TeamsCommand.class` in the `subcommands` of `ForecastCli.Root`.

- [ ] **Step 4: Run the test**

Run: `cd server && mvn -B -q test -pl forecast-cli -Dtest=RunCommandTest`
Expected: 1 passed (a 20-week seed with 14 users gives the run a few backtest origins; the whole test takes well under a minute).

- [ ] **Step 5: Commit**

```bash
git add server/forecast-cli/src/main/java/com/workloadhub/forecast/cli server/forecast-cli/src/test/java/com/workloadhub/forecast/cli/RunCommandTest.java
git commit -m "feat(cli): run, runs and teams commands on the SQLite file

A run resolves the team by name or id, executes synchronously through
the service, and prints the champion, the tournament and the member
week table; runs lists a team's history."
```

---

### Task 8: The evaluation harness and the `eval` command

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/backtest/Backtest.java` (per-origin residual rows; an `origins(last, first, count)` overload)
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/{Truth,Metrics,EvalConfig,EvalResult,Harness,Report}.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/eval/{MetricsTest,TruthTest,HarnessTest,ReportTest}.java`
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/EvalCommand.java`
- Modify: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/ForecastCli.java` (register `eval`)
- Modify: `server/forecast-cli/src/test/java/com/workloadhub/forecast/cli/RunCommandTest.java` (an `eval` case)

**Interfaces:**
- `Backtest`: `record Residual(LocalDate origin, double y, double residual)`; `Result` gains `Map<String, Map<Integer, List<Residual>>> residualRows()` (same purge rule as `residuals`) and `List<Residual> residualRows(String model, int h)` (empty when unknown); `static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int count)` (the existing method delegates with `ORIGIN_COUNT`).
- `Truth.realisedHours(ForecastData data)` → `SortedMap<MemberWeek, Double>`: `time_logs.hours` summed per `(user_id, Weeks.mondayOf(log_date))`, rounded to 6 decimals; `Truth.SOURCE = "time logs"`.
- `Metrics`: `static double mae(double[] y, double[] p)`, `bias`, `coverage(y, low, high)`, `weightedQuantileLoss(y, Map<Double, double[]> quantiles)` (mean over quantiles of `2·mean(max(q·d, (q−1)·d))` with `d = y − pred`, divided by `mean(|y|)`, NaN when that is 0), `double[] overloadPrecisionRecall(boolean[] trueOver, boolean[] predOver)` (NaN when a denominator is 0); every function returns NaN on empty input.
- `record EvalConfig(LocalDate asOf, int origins, List<String> models, List<UUID> teams)` (`models` empty = all known; `teams` empty = every team with a counted member); `record ScoreRow(String model, int horizon, LocalDate origin, String metric, double value)`; `record DemandRow(String model, LocalDate origin, UUID teamId, UUID memberId, LocalDate weekStart, double forecast, double truth, double capacity, double openHours, double newHours, double plannedHours)`; `record EvalResult(List<ScoreRow> scores, List<DemandRow> demand, Map<String, String> skipped, String truthSource, double elapsedSeconds, List<LocalDate> origins)`.
- `Harness(ForecastRunner runner, CapacityRule rule)`; `EvalResult evaluate(ForecastData data, EvalConfig config)`:
  - `origin = Weeks.lastCompleteWeek(asOf)`; features on every member; `origins = Backtest.origins(origin, firstRowWeek, config.origins)`; `HORIZONS = {1, 2}`.
  - Arrival level: `Backtest.run(features, factories, origins, HORIZONS)` with `factories` = the chosen models from `ModelRegistry.factories(null)` (the floor is always scored); per `Score`: rows `mae`, `mase`, `beats_naive` (`mase < 1` as 1.0/0.0, NaN when MASE is NaN), `coverage80` and `wql` from the leave-one-origin-out band: `q = Backtest.intervalBounds(residuals of the same model and horizon at every other origin)`, NaN when there is no other origin, else `low = point + q10`, `high = point + q90` with `point = y − residual` on this origin's rows, `coverage(y, low, high)` and `wql(y, {0.1: low, 0.5: point, 0.9: high})`; `seconds` = the model's total seconds divided by the number of origins it scored, on horizon 1 only (NaN on horizon 2).
  - Demand level: for each origin in order: `asOf' = origin + 7 days`; `replay = Truncation.at(data, asOf')`; for each chosen model not yet skipped: `prepared = runner.prepare(replay, asOf', model, NONE)` (a `ModelUnavailable` marks the model skipped and removes its demand rows); for each team (config or all with counted members, sorted by id string): `runner.forTeam(prepared, team, null)` (`TEAM_NOT_FOUND` → skip the team), one `DemandRow` per member-week with `truth = realisedHours.getOrDefault(key, 0)`.
  - Models named in `config.models` that are unknown → `ForecastException.invalidRequest`.
- `Report.write(EvalResult result, EvalConfig config, Map<String, String> fingerprint, Map<String, String> versions, Path outDir)` → writes `scores.csv` (`model,horizon,origin,metric,value`), `demand.csv` (`model,origin,team_id,member_id,week_start,forecast,truth,capacity,open_hours,new_hours,planned_hours`), `summary.md` with: title `# Forecast evaluation, as of <date>`; truth, origins, models, teams; elapsed seconds and CPU; `## Level A: arrival accuracy per model and horizon (means over origins)` with columns `model | horizon | mae | mase | beats_naive | coverage80 | wql | seconds` (means over origins, NaN ignored, 3 decimals); `## Level B: demand accuracy per model (all origins, teams, members, weeks)` with `model | mae | bias | open_only_mae | overload_precision | overload_recall | rows`; `## Skipped models`; `## Truth and replay assumptions` (truth = time logs summed per week, which deflates the last weeks for work still in progress; the replay is a Monday-morning evaluation: each origin replayed as of the Monday after it; only horizons 1 and 2 are scored; a single-origin run reports NaN coverage and wql); `## Data fingerprint`; `## Versions`; `Generated <timestamp>.` NaN is written as an empty cell in CSV and as `nan` in the tables. Values formatted with `Locale.ROOT`.
- `EvalCommand`: `eval [--as-of] [--origins 6] [--models a,b] [--teams a,b] [--out dir]`; `--as-of` default = the latest `created_date` among tasks (the seed's end), printed; `--out` default `./eval/<as-of>`; `--teams` accept names or ids; prints the Level A table and the output directory; exit 2 on a usage error.

- [ ] **Step 1: Write the failing tests**

```java
package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsTest {

    @Test
    void pointMetrics() {
        assertEquals(1.0, Metrics.mae(new double[] {1, 2, 3}, new double[] {2, 3, 4}), 1e-9);
        assertEquals(1.0, Metrics.bias(new double[] {1, 2, 3}, new double[] {2, 3, 4}), 1e-9);
        assertTrue(Double.isNaN(Metrics.mae(new double[0], new double[0])));
        assertEquals(2.0 / 3, Metrics.coverage(new double[] {1, 2, 3}, new double[] {0, 0, 4}, new double[] {2, 2, 5}), 1e-9);
    }

    @Test
    void weightedQuantileLossMatchesTheDefinition() {
        double[] y = {10, 20};
        double v = Metrics.weightedQuantileLoss(y, Map.of(0.5, new double[] {12, 18}));
        assertEquals(2.0 * ((0.5 * 2 + 0.5 * 2) / 2) / 15.0, v, 1e-9, "pinball at the median is half the absolute error");
        assertTrue(Double.isNaN(Metrics.weightedQuantileLoss(new double[] {0, 0}, Map.of(0.5, new double[] {0, 0}))));
    }

    @Test
    void overloadPrecisionAndRecall() {
        assertArrayEquals(new double[] {0.5, 1.0}, Metrics.overloadPrecisionRecall(new boolean[] {true, false, false}, new boolean[] {true, true, false}), 1e-9);
        double[] none = Metrics.overloadPrecisionRecall(new boolean[] {false}, new boolean[] {false});
        assertTrue(Double.isNaN(none[0]) && Double.isNaN(none[1]));
    }
}
```

```java
package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.util.List;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;

class TruthTest {

    @Test
    void sumsLogsPerUserAndMondayWeek() {
        MemberRow ana = TestData.member("ana", TestData.TEAM);
        TaskRow t = TestData.task("1", ana.id(), LocalDate.of(2026, 8, 3).atTime(9, 0), 8);
        ForecastData data = TestData.data(List.of(ana), List.of(t), List.of(), List.of(
                TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 5), 3),
                TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 9), 1.5),
                TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 10), 2)));
        SortedMap<MemberWeek, Double> truth = Truth.realisedHours(data);
        assertEquals(4.5, truth.get(new MemberWeek(ana.id(), LocalDate.of(2026, 8, 3))), 1e-9, "Sunday belongs to the week that started Monday");
        assertEquals(2.0, truth.get(new MemberWeek(ana.id(), LocalDate.of(2026, 8, 10))), 1e-9);
        assertEquals(2, truth.size());
    }

    @Test
    void seededTruthCoversEveryLog() {
        ForecastData data = SeededData.data();
        double total = Truth.realisedHours(data).values().stream().mapToDouble(Double::doubleValue).sum();
        double logs = data.timeLogs().stream().mapToDouble(l -> l.hours()).sum();
        assertEquals(logs, total, 1e-3);
        assertTrue(total > 0);
    }
}
```

```java
package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.model.XgboostArrival;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.testing.SeededData;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HarnessTest {

    static EvalResult result;
    static ForecastData data;

    @BeforeAll
    static void evaluate() {
        data = SeededData.data();
        CapacityRule rule = new CapacityRule(40);
        List<UUID> teams = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).limit(2).toList();
        result = new Harness(new ForecastRunner(rule, true), rule)
                .evaluate(data, new EvalConfig(SeededData.asOf(), 2, List.of(), teams));
    }

    @Test
    void arrivalLevelHasEveryMetricForEveryModelHorizonAndOrigin() {
        assertEquals(2, result.origins().size());
        Set<String> metrics = Set.of("mae", "mase", "beats_naive", "coverage80", "wql", "seconds");
        for (String model : List.of(Backtest.FLOOR, XgboostArrival.NAME)) {
            for (int h : new int[] {1, 2}) {
                for (var origin : result.origins()) {
                    Set<String> got = result.scores().stream().filter(s -> s.model().equals(model) && s.horizon() == h && s.origin().equals(origin))
                            .map(ScoreRow::metric).collect(java.util.stream.Collectors.toSet());
                    assertEquals(metrics, got, model + " h" + h + " " + origin);
                }
            }
        }
        assertTrue(result.scores().stream().filter(s -> s.model().equals(Backtest.FLOOR) && s.metric().equals("mase")).allMatch(s -> Math.abs(s.value() - 1.0) < 1e-9));
        assertTrue(result.scores().stream().filter(s -> s.metric().equals("seconds") && s.horizon() == 2).allMatch(s -> Double.isNaN(s.value())));
        assertTrue(result.scores().stream().filter(s -> s.metric().equals("coverage80")).allMatch(s -> !Double.isNaN(s.value())), "two origins: leave-one-out bands exist");
    }

    @Test
    void demandLevelReplaysEveryOriginModelAndTeam() {
        assertFalse(result.demand().isEmpty());
        assertEquals(Set.of(Backtest.FLOOR, XgboostArrival.NAME), result.demand().stream().map(DemandRow::model).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, result.demand().stream().map(DemandRow::teamId).distinct().count());
        for (DemandRow r : result.demand()) {
            assertEquals(r.forecast(), ForecastRunner.round2(r.openHours() + r.newHours() + r.plannedHours()), 1e-9);
            assertTrue(r.truth() >= 0 && r.capacity() >= 0);
            assertTrue(r.weekStart().equals(r.origin().plusWeeks(1)) || r.weekStart().equals(r.origin().plusWeeks(2)), "the Monday after the origin and the next");
        }
        assertTrue(result.skipped().isEmpty());
        assertEquals(Truth.SOURCE, result.truthSource());
        assertTrue(result.elapsedSeconds() > 0);
    }

    @Test
    void unknownModelIsRejected() {
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> new Harness(new ForecastRunner(new CapacityRule(40), true), new CapacityRule(40))
                .evaluate(data, new EvalConfig(SeededData.asOf(), 1, List.of("gbm"), List.of()))).code());
    }
}
```

```java
package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportTest {

    @Test
    void writesTheThreeFilesWithThePythonColumns(@TempDir Path dir) throws Exception {
        LocalDate origin = LocalDate.of(2026, 8, 17);
        UUID team = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        EvalResult result = new EvalResult(
                List.of(new ScoreRow("xgboost", 1, origin, "mase", 0.8), new ScoreRow("xgboost", 1, origin, "seconds", 1.5),
                        new ScoreRow("xgboost", 2, origin, "mase", 0.9), new ScoreRow("xgboost", 2, origin, "seconds", Double.NaN),
                        new ScoreRow("seasonal_naive", 1, origin, "mase", 1.0)),
                List.of(new DemandRow("xgboost", origin, team, member, origin.plusWeeks(1), 30, 28, 40, 20, 8, 2),
                        new DemandRow("xgboost", origin, team, member, origin.plusWeeks(2), 45, 30, 40, 40, 5, 0)),
                Map.of(), Truth.SOURCE, 3.2, List.of(origin));
        Report.write(result, new EvalConfig(LocalDate.of(2026, 9, 6), 1, List.of(), List.of()), Map.of("tasks", "2"), Map.of("java", "21"), dir);
        List<String> scores = Files.readAllLines(dir.resolve("scores.csv"));
        assertEquals("model,horizon,origin,metric,value", scores.get(0));
        assertEquals("xgboost,1,2026-08-17,mase,0.8", scores.get(1));
        assertTrue(scores.stream().anyMatch(l -> l.equals("xgboost,2,2026-08-17,seconds,")), "NaN is an empty cell");
        List<String> demand = Files.readAllLines(dir.resolve("demand.csv"));
        assertEquals("model,origin,team_id,member_id,week_start,forecast,truth,capacity,open_hours,new_hours,planned_hours", demand.get(0));
        assertEquals(3, demand.size());
        String summary = Files.readString(dir.resolve("summary.md"));
        assertTrue(summary.startsWith("# Forecast evaluation, as of 2026-09-06"));
        assertTrue(summary.contains("| xgboost | 1 |") && summary.contains("| seasonal_naive | 1 |"));
        assertTrue(summary.contains("## Level B") && summary.contains("overload_precision"));
        assertTrue(summary.contains("- tasks: 2") && summary.contains("- java: 21"));
        assertTrue(summary.contains("| xgboost | 8.500 |"), "demand MAE (2 + 15) / 2 in Level B");
    }
}
```

Append to `RunCommandTest` (uses the same seeded database as `seedThenRunThenList`; extract the seed-and-import lines into a helper if you prefer):

```java
    @Test
    void evalWritesTheHarnessFiles(@TempDir Path dir) {
        Path db = dir.resolve("e.db");
        Path seeded = dir.resolve("seeded.json");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "14", "--weeks", "24", "--seed", "5", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        Path out = dir.resolve("eval");
        String text = capture(cli, 0, "eval", "--db", db.toString(), "--as-of", "2026-09-06", "--origins", "2", "--models", "seasonal_naive", "--out", out.toString());
        assertTrue(text.contains("seasonal_naive"), text);
        assertTrue(java.nio.file.Files.exists(out.resolve("scores.csv")) && java.nio.file.Files.exists(out.resolve("demand.csv"))
                && java.nio.file.Files.exists(out.resolve("summary.md")));
        assertEquals(2, cli.execute("eval", "--db", db.toString(), "--models", "gbm", "--out", out.toString()));
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `cd server && mvn -B -q test -pl forecast-core -Dtest='MetricsTest,TruthTest,HarnessTest,ReportTest'`
Expected: compilation errors.

- [ ] **Step 3: Extend `Backtest` with per-origin residual rows and the origins overload**

In `Backtest`: add `public record Residual(LocalDate origin, double y, double residual) {}`; add a component `Map<String, Map<Integer, List<Residual>>> residualRows` to `Result` (after `residuals`) with the accessor `residualRows(String model, int h)` returning `List.of()` when unknown; in `run`, beside `pool.add(y[i] − yHat[i])`, add `new Residual(origin, y[i], y[i] − yHat[i])` to a parallel structure; purge it with the unavailable models; build the immutable map at the end. Add:

```java
    public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek) {
        return origins(lastCompleteWeek, firstWeek, ORIGIN_COUNT);
    }

    public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int count) {
        List<LocalDate> out = new ArrayList<>();
        for (int k = count; k >= 1; k--) {
            LocalDate origin = lastCompleteWeek.minusWeeks((long) k * ORIGIN_STEP_WEEKS);
            if (ChronoUnit.WEEKS.between(firstWeek, origin) >= MIN_HISTORY_WEEKS) {
                out.add(origin);
            }
        }
        return out;
    }
```

Update every `new Result(...)` call (there is one) and `BacktestTest` compiles unchanged because it uses accessors only; add one assertion there: `assertEquals(8 * 3, r.residualRows(XgboostArrival.NAME, 1).size())` and that every residual row's `origin` is one of the three origins.

- [ ] **Step 4: Write `Truth`, `Metrics`, the records and `Harness`**

```java
package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.features.MemberWeek;
import java.util.SortedMap;
import java.util.TreeMap;

/** What the demand forecast is measured against: the hours people logged, per member and week. */
public final class Truth {

    public static final String SOURCE = "time logs";

    private Truth() {
    }

    public static SortedMap<MemberWeek, Double> realisedHours(ForecastData data) {
        SortedMap<MemberWeek, Double> out = new TreeMap<>();
        for (TimeLogRow l : data.timeLogs()) {
            out.merge(new MemberWeek(l.userId(), Weeks.mondayOf(l.day())), l.hours(), Double::sum);
        }
        out.replaceAll((k, v) -> Math.round(v * 1e6) / 1e6);
        return out;
    }
}
```

```java
package com.workloadhub.forecast.eval;

import java.util.Map;

/** Pure metric functions over equal-length arrays; NaN on empty input. */
public final class Metrics {

    private Metrics() {
    }

    public static double mae(double[] y, double[] p) {
        if (y.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += Math.abs(y[i] - p[i]);
        }
        return s / y.length;
    }

    /** Mean of forecast minus truth: positive means the forecast runs high. */
    public static double bias(double[] y, double[] p) {
        if (y.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += p[i] - y[i];
        }
        return s / y.length;
    }

    public static double coverage(double[] y, double[] low, double[] high) {
        if (y.length == 0) {
            return Double.NaN;
        }
        int in = 0;
        for (int i = 0; i < y.length; i++) {
            if (low[i] <= y[i] && y[i] <= high[i]) {
                in++;
            }
        }
        return (double) in / y.length;
    }

    /** Mean over quantiles of the scaled pinball loss, as the GIFT-Eval benchmark defines it. */
    public static double weightedQuantileLoss(double[] y, Map<Double, double[]> quantiles) {
        if (y.length == 0 || quantiles.isEmpty()) {
            return Double.NaN;
        }
        double scale = 0;
        for (double v : y) {
            scale += Math.abs(v) / y.length;
        }
        if (scale == 0) {
            return Double.NaN;
        }
        double total = 0;
        for (Map.Entry<Double, double[]> e : quantiles.entrySet()) {
            double q = e.getKey();
            double[] p = e.getValue();
            double loss = 0;
            for (int i = 0; i < y.length; i++) {
                double d = y[i] - p[i];
                loss += Math.max(q * d, (q - 1.0) * d) / y.length;
            }
            total += 2.0 * loss;
        }
        return total / quantiles.size() / scale;
    }

    public static double[] overloadPrecisionRecall(boolean[] trueOver, boolean[] predOver) {
        int tp = 0;
        int predicted = 0;
        int actual = 0;
        for (int i = 0; i < trueOver.length; i++) {
            if (predOver[i]) {
                predicted++;
            }
            if (trueOver[i]) {
                actual++;
            }
            if (trueOver[i] && predOver[i]) {
                tp++;
            }
        }
        return new double[] {predicted == 0 ? Double.NaN : (double) tp / predicted, actual == 0 ? Double.NaN : (double) tp / actual};
    }
}
```

The four records (`EvalConfig`, `ScoreRow`, `DemandRow`, `EvalResult`) are plain records with the components of Interfaces; `EvalConfig`'s compact constructor replaces null lists with empty ones.

```java
package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.features.FeatureBuilder;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.Truncation;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.ModelUnavailable;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.ModelRegistry;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import java.util.function.Supplier;

/** Two-level evaluation: arrival accuracy per model, and demand accuracy of the whole pipeline per model. */
public final class Harness {

    public static final int[] HORIZONS = {1, 2};

    private final ForecastRunner runner;
    private final CapacityRule rule;

    public Harness(ForecastRunner runner, CapacityRule rule) {
        this.runner = runner;
        this.rule = rule;
    }

    public EvalResult evaluate(ForecastData data, EvalConfig config) {
        long started = System.nanoTime();
        for (String name : config.models()) {
            if (!ModelRegistry.isKnown(name)) {
                throw ForecastException.invalidRequest("unknown model " + name + "; known: " + ModelRegistry.NAMES);
            }
        }
        Map<String, Supplier<ArrivalModel>> factories = new LinkedHashMap<>();
        ModelRegistry.factories(null).forEach((name, f) -> {
            if (config.models().isEmpty() || config.models().contains(name) || name.equals(Backtest.FLOOR)) {
                factories.put(name, f);
            }
        });
        LocalDate origin = Weeks.lastCompleteWeek(config.asOf());
        Lifecycle lc = Lifecycle.derive(data);
        WorkingCalendar cal = WorkingCalendar.fromHolidays(data.holidays());
        FeatureMatrix features = new FeatureBuilder(data, lc, cal, rule).build(data.members(), origin);
        LocalDate firstWeek = features.keys().stream().map(MemberWeek::week).min(LocalDate::compareTo).orElse(origin);
        List<LocalDate> origins = features.rowCount() == 0 ? List.of() : Backtest.origins(origin, firstWeek, config.origins());
        Backtest.Result bt = Backtest.run(features, factories, origins, HORIZONS);
        List<ScoreRow> scores = arrivalLevel(bt);
        Map<String, String> skipped = new LinkedHashMap<>(bt.unavailable());
        List<DemandRow> demand = demandLevel(data, factories, origins, config.teams(), skipped);
        return new EvalResult(scores, demand, skipped, Truth.SOURCE, (System.nanoTime() - started) / 1e9, origins);
    }

    static List<ScoreRow> arrivalLevel(Backtest.Result bt) {
        List<ScoreRow> rows = new ArrayList<>();
        Map<String, Long> scoredOrigins = new LinkedHashMap<>();
        for (Backtest.Score s : bt.scores()) {
            scoredOrigins.merge(s.model(), 0L, Long::sum);
        }
        bt.scores().stream().map(s -> s.model() + "|" + s.origin()).distinct().forEach(k -> scoredOrigins.merge(k.split("\\|")[0], 1L, Long::sum));
        for (Backtest.Score s : bt.scores()) {
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "mae", s.mae()));
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "mase", s.mase()));
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "beats_naive", Double.isNaN(s.mase()) ? Double.NaN : (s.mase() < 1.0 ? 1.0 : 0.0)));
            List<Backtest.Residual> all = bt.residualRows(s.model(), s.horizon());
            double[] others = all.stream().filter(r -> !r.origin().equals(s.origin())).mapToDouble(Backtest.Residual::residual).toArray();
            List<Backtest.Residual> mine = all.stream().filter(r -> r.origin().equals(s.origin())).toList();
            double coverage = Double.NaN;
            double wql = Double.NaN;
            if (others.length > 0 && !mine.isEmpty()) {
                double[] q = Backtest.intervalBounds(others);
                double[] y = mine.stream().mapToDouble(Backtest.Residual::y).toArray();
                double[] point = mine.stream().mapToDouble(r -> r.y() - r.residual()).toArray();
                double[] low = new double[y.length];
                double[] high = new double[y.length];
                for (int i = 0; i < y.length; i++) {
                    low[i] = point[i] + q[0];
                    high[i] = point[i] + q[1];
                }
                coverage = Metrics.coverage(y, low, high);
                wql = Metrics.weightedQuantileLoss(y, Map.of(0.1, low, 0.5, point, 0.9, high));
            }
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "coverage80", coverage));
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "wql", wql));
            double seconds = bt.secondsPerModel().getOrDefault(s.model(), Double.NaN) / Math.max(1, scoredOrigins.getOrDefault(s.model(), 1L));
            rows.add(new ScoreRow(s.model(), s.horizon(), s.origin(), "seconds", s.horizon() == HORIZONS[0] ? seconds : Double.NaN));
        }
        return rows;
    }

    private List<DemandRow> demandLevel(ForecastData data, Map<String, Supplier<ArrivalModel>> factories, List<LocalDate> origins, List<UUID> teamsIn,
            Map<String, String> skipped) {
        SortedMap<MemberWeek, Double> truth = Truth.realisedHours(data);
        List<DemandRow> rows = new ArrayList<>();
        List<UUID> teams = teamsIn.isEmpty()
                ? data.teams().stream().map(TeamRow::id).filter(t -> !data.membersOfTeam(t).isEmpty()).sorted((a, b) -> a.toString().compareTo(b.toString())).toList()
                : teamsIn;
        for (LocalDate origin : origins) {
            LocalDate asOf = origin.plusWeeks(1);
            ForecastData replay = Truncation.at(data, asOf);
            for (String model : factories.keySet()) {
                if (skipped.containsKey(model)) {
                    continue;
                }
                Prepared prepared;
                try {
                    prepared = runner.prepare(replay, asOf, model, ForecastRunner.ProgressListener.NONE);
                } catch (ModelUnavailable e) {
                    skipped.put(model, e.getMessage());
                    continue;
                }
                for (UUID team : teams) {
                    TeamOutcome outcome;
                    try {
                        outcome = runner.forTeam(prepared, team, null);
                    } catch (ForecastException e) {
                        if ("TEAM_NOT_FOUND".equals(e.code())) {
                            continue;
                        }
                        throw e;
                    }
                    for (MemberWeekForecast w : outcome.memberWeeks()) {
                        rows.add(new DemandRow(model, origin, team, w.userId(), w.weekStart(), w.demandHrs(),
                                truth.getOrDefault(new MemberWeek(w.userId(), w.weekStart()), 0.0), w.capacityHrs(), w.openHrs(), w.newHrs(), w.plannedHrs()));
                    }
                }
            }
        }
        rows.removeIf(r -> skipped.containsKey(r.model()));
        return rows;
    }
}
```

The `scoredOrigins` computation counts distinct origins per model (the first loop only seeds the keys); simplify it if you see a cleaner way, keeping "seconds divided by the origins the model scored".

- [ ] **Step 5: Write `Report`**

```java
package com.workloadhub.forecast.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** scores.csv, demand.csv and summary.md in the columns of the Python harness. */
public final class Report {

    static final List<String> LEVEL_A = List.of("mae", "mase", "beats_naive", "coverage80", "wql", "seconds");
    static final String LEVEL_A_CAPTION = "`coverage80` and `wql` are scored on the leave-one-origin-out residual band the run would show, so every model"
            + " is measured on the interval a user actually sees. `seconds` is fit plus predict for the whole backtest divided by the origins that model"
            + " scored; it is reported on the first horizon row and left empty on the others.";
    static final List<String> ASSUMPTIONS = List.of(
            "Truth is the hours logged in time_logs, summed per member and Monday week; work still in progress at export time has few logs, so the"
                    + " most recent origins are deflated and every model looks high there.",
            "The replay is a Monday-morning evaluation: each origin is replayed as of the Monday after it, so a task assigned on the first forecast"
                    + " Monday counts as open work rather than as an arrival.",
            "Only horizons 1 and 2 are scored, the two weeks a run forecasts.",
            "A single-origin run reports NaN interval coverage and NaN weighted quantile loss: the leave-one-origin-out band needs another origin.");

    private Report() {
    }

    public static Path write(EvalResult result, EvalConfig config, Map<String, String> fingerprint, Map<String, String> versions, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        StringBuilder scores = new StringBuilder("model,horizon,origin,metric,value\n");
        for (ScoreRow r : result.scores()) {
            scores.append(r.model()).append(',').append(r.horizon()).append(',').append(r.origin()).append(',').append(r.metric()).append(',').append(csv(r.value())).append('\n');
        }
        Files.writeString(outDir.resolve("scores.csv"), scores.toString(), StandardCharsets.UTF_8);
        StringBuilder demand = new StringBuilder("model,origin,team_id,member_id,week_start,forecast,truth,capacity,open_hours,new_hours,planned_hours\n");
        for (DemandRow r : result.demand()) {
            demand.append(r.model()).append(',').append(r.origin()).append(',').append(r.teamId()).append(',').append(r.memberId()).append(',').append(r.weekStart())
                    .append(',').append(csv(r.forecast())).append(',').append(csv(r.truth())).append(',').append(csv(r.capacity())).append(',').append(csv(r.openHours()))
                    .append(',').append(csv(r.newHours())).append(',').append(csv(r.plannedHours())).append('\n');
        }
        Files.writeString(outDir.resolve("demand.csv"), demand.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("summary.md"), summary(result, config, fingerprint, versions), StandardCharsets.UTF_8);
        return outDir;
    }

    static String summary(EvalResult result, EvalConfig config, Map<String, String> fingerprint, Map<String, String> versions) {
        List<String> parts = new ArrayList<>();
        parts.add("# Forecast evaluation, as of " + config.asOf());
        parts.add("");
        parts.add("Truth: " + result.truthSource() + ". Origins: " + (result.origins().isEmpty() ? "none"
                : result.origins().stream().map(Object::toString).collect(Collectors.joining(", "))) + ".");
        parts.add("Models requested: " + (config.models().isEmpty() ? "all" : String.join(", ", config.models())) + ". Teams: "
                + (config.teams().isEmpty() ? "all" : config.teams().stream().map(Object::toString).collect(Collectors.joining(", "))) + ".");
        parts.add(String.format(Locale.ROOT, "Elapsed: %.1f s on %s, %d logical CPUs.", result.elapsedSeconds(), cpuName(), Runtime.getRuntime().availableProcessors()));
        parts.add("");
        parts.add("## Level A: arrival accuracy per model and horizon (means over origins)");
        parts.add("");
        parts.add(LEVEL_A_CAPTION);
        parts.add("");
        parts.add(levelA(result));
        parts.add("## Level B: demand accuracy per model (all origins, teams, members, weeks)");
        parts.add("");
        parts.add(levelB(result));
        parts.add("## Skipped models");
        parts.add("");
        parts.add(result.skipped().isEmpty() ? "none" : result.skipped().entrySet().stream().map(e -> "- " + e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n")));
        parts.add("");
        parts.add("## Truth and replay assumptions");
        parts.add("");
        parts.add(ASSUMPTIONS.stream().map(a -> "- " + a).collect(Collectors.joining("\n")));
        parts.add("");
        parts.add("## Data fingerprint");
        parts.add("");
        parts.add(fingerprint.entrySet().stream().map(e -> "- " + e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n")));
        parts.add("");
        parts.add("## Versions");
        parts.add("");
        parts.add(versions.entrySet().stream().map(e -> "- " + e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n")));
        parts.add("");
        parts.add("Generated " + LocalDateTime.now().withNano(0) + ".");
        parts.add("");
        return String.join("\n", parts);
    }

    static String levelA(EvalResult result) {
        Map<String, Map<String, double[]>> acc = new TreeMap<>();
        for (ScoreRow r : result.scores()) {
            if (Double.isNaN(r.value())) {
                continue;
            }
            double[] cell = acc.computeIfAbsent(r.model() + "|" + r.horizon(), k -> new LinkedHashMap<>()).computeIfAbsent(r.metric(), k -> new double[2]);
            cell[0] += r.value();
            cell[1] += 1;
        }
        if (acc.isEmpty()) {
            return "(no rows)\n";
        }
        StringBuilder sb = new StringBuilder("| model | horizon | " + String.join(" | ", LEVEL_A) + " |\n|---|---|" + "---|".repeat(LEVEL_A.size()) + "\n");
        acc.forEach((key, metrics) -> {
            String[] mh = key.split("\\|");
            sb.append("| ").append(mh[0]).append(" | ").append(mh[1]);
            for (String metric : LEVEL_A) {
                double[] cell = metrics.get(metric);
                sb.append(" | ").append(cell == null ? "nan" : fmt(cell[0] / cell[1]));
            }
            sb.append(" |\n");
        });
        return sb.toString();
    }

    static String levelB(EvalResult result) {
        Map<String, List<DemandRow>> byModel = result.demand().stream().collect(Collectors.groupingBy(DemandRow::model, TreeMap::new, Collectors.toList()));
        if (byModel.isEmpty()) {
            return "(no rows)\n";
        }
        StringBuilder sb = new StringBuilder("| model | mae | bias | open_only_mae | overload_precision | overload_recall | rows |\n|---|---|---|---|---|---|---|\n");
        byModel.forEach((model, rows) -> {
            double[] y = rows.stream().mapToDouble(DemandRow::truth).toArray();
            double[] p = rows.stream().mapToDouble(DemandRow::forecast).toArray();
            double[] open = rows.stream().mapToDouble(DemandRow::openHours).toArray();
            boolean[] trueOver = new boolean[rows.size()];
            boolean[] predOver = new boolean[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                trueOver[i] = y[i] > rows.get(i).capacity();
                predOver[i] = p[i] > rows.get(i).capacity();
            }
            double[] pr = Metrics.overloadPrecisionRecall(trueOver, predOver);
            sb.append("| ").append(model).append(" | ").append(fmt(Metrics.mae(y, p))).append(" | ").append(fmt(Metrics.bias(y, p))).append(" | ")
                    .append(fmt(Metrics.mae(y, open))).append(" | ").append(fmt(pr[0])).append(" | ").append(fmt(pr[1])).append(" | ").append(rows.size()).append(" |\n");
        });
        return sb.toString();
    }

    static String fmt(double v) {
        return Double.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.3f", v);
    }

    static String csv(double v) {
        if (Double.isNaN(v)) {
            return "";
        }
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.format(Locale.ROOT, "%.1f", v).replaceAll("\\.0$", "") : Double.toString(v);
    }

    static String cpuName() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux, or unreadable: fall through
        }
        return System.getProperty("os.arch");
    }
}
```

`csv(0.8)` must print `0.8` (the test asserts the line `xgboost,1,2026-08-17,mase,0.8`); `Double.toString(0.8)` does, and whole numbers print without a trailing `.0` so `30` stays `30`; if `Double.toString` yields scientific notation for very small values that is acceptable (Python's CSV writer does the same).

- [ ] **Step 6: Write `EvalCommand`**

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.ForecastRepository;
import com.workloadhub.forecast.eval.EvalConfig;
import com.workloadhub.forecast.eval.EvalResult;
import com.workloadhub.forecast.eval.Harness;
import com.workloadhub.forecast.eval.Report;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "eval", description = "Score every model at every origin (arrival level) and replay whole runs per team (demand level); writes scores.csv, demand.csv and summary.md.")
public class EvalCommand implements Callable<Integer> {

    @Mixin DbOptions db;

    @Option(names = "--as-of", description = "As-of date, ISO (default: the latest task creation date)")
    String asOf;

    @Option(names = "--origins", defaultValue = "6", description = "Backtest origins, two weeks apart (default: ${DEFAULT-VALUE})")
    int origins;

    @Option(names = "--models", description = "Comma-separated model names (default: all)")
    String models;

    @Option(names = "--teams", description = "Comma-separated team names or ids (default: all)")
    String teams;

    @Option(names = "--out", description = "Output folder (default: ./eval/<as-of>)")
    Path out;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            ForecastData data = new ForecastRepository(s.jdbc(), s.dialect()).loadAll();
            LocalDate date;
            List<UUID> teamIds = new ArrayList<>();
            List<String> modelNames = models == null ? List.of() : Arrays.stream(models.split(",")).map(String::trim).filter(m -> !m.isEmpty()).toList();
            try {
                date = asOf != null ? LocalDate.parse(asOf) : data.tasks().stream().map(t -> t.createdDate().toLocalDate()).max(LocalDate::compareTo).orElse(LocalDate.now());
                if (teams != null) {
                    for (String t : teams.split(",")) {
                        teamIds.add(TeamArg.resolve(s.jdbc(), s.dialect(), t.trim()));
                    }
                }
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            Path outDir = out != null ? out : Path.of("eval", date.toString());
            System.out.println("Evaluating as of " + date + " with " + origins + " origins, models " + (modelNames.isEmpty() ? "all" : modelNames) + ", teams "
                    + (teamIds.isEmpty() ? "all" : teamIds.size()));
            EvalResult result;
            try {
                result = new Harness(s.runner(), new CapacityRule(40)).evaluate(data, new EvalConfig(date, origins, modelNames, teamIds));
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return "INVALID_REQUEST".equals(e.code()) ? 2 : 1;
            }
            Map<String, String> fingerprint = new LinkedHashMap<>();
            fingerprint.put("members", String.valueOf(data.members().size()));
            fingerprint.put("teams", String.valueOf(data.teams().size()));
            fingerprint.put("tasks", String.valueOf(data.tasks().size()));
            fingerprint.put("time_logs", String.valueOf(data.timeLogs().size()));
            fingerprint.put("first_created", data.tasks().stream().map(t -> t.createdDate().toLocalDate()).min(LocalDate::compareTo).map(Object::toString).orElse("none"));
            fingerprint.put("last_created", data.tasks().stream().map(t -> t.createdDate().toLocalDate()).max(LocalDate::compareTo).map(Object::toString).orElse("none"));
            Map<String, String> versions = new LinkedHashMap<>();
            versions.put("java", System.getProperty("java.version"));
            versions.put("xgboost4j", "3.4.0");
            versions.put("forecast-cli", "0.1.0");
            Report.write(result, new EvalConfig(date, origins, modelNames, teamIds), fingerprint, versions, outDir);
            System.out.println(Report.levelA(result));
            System.out.println("Wrote " + outDir.toAbsolutePath());
            return 0;
        }
    }
}
```

Make `Report.levelA` public. Register `EvalCommand.class` in `ForecastCli.Root`.

- [ ] **Step 7: Run the tests**

Run: `cd server && mvn -B -q verify`
Expected: green; the harness test (two origins, two models, two teams on the 36-user seed) and the CLI eval test add well under a minute together. Report the wall-clock time; if the gate exceeds three minutes, lower `HarnessTest` to one team and `--origins 1` in the CLI test (which also exercises the NaN band path).

- [ ] **Step 8: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/backtest/Backtest.java server/forecast-core/src/main/java/com/workloadhub/forecast/eval server/forecast-core/src/test/java/com/workloadhub/forecast/eval server/forecast-core/src/test/java/com/workloadhub/forecast/backtest/BacktestTest.java server/forecast-cli/src/main/java/com/workloadhub/forecast/cli server/forecast-cli/src/test/java/com/workloadhub/forecast/cli/RunCommandTest.java
git commit -m "feat(server): evaluation harness with the Python harness's files and an eval command

Arrival level with leave-one-origin-out bands, demand level replayed
per origin and team from the truncated database against logged hours,
written as scores.csv, demand.csv and summary.md."
```

---

### Task 9: Python converter `whf import-workloadhub` (the parity oracle's input)

**Files:**
- Create: `service/src/whf/data/workloadhub.py`
- Modify: `service/src/whf/cli.py` (an `import-workloadhub` command)
- Create: `service/tests/test_workloadhub_import.py`

**Interfaces:**
- `import_workloadhub(conn: sqlite3.Connection, export: dict, *, arrivals: str = "fresh") -> dict[str, int]`: writes a WorkloadHub export (the JSON the Java `seed`/`export` commands produce: `{"database", "schema", "exportedAt", "excludedTables", "data": {table: [rows]}}`) into the whf schema of the open connection and returns row counts per whf table. `arrivals` is `"fresh"` (keep only tasks whose assignment lag is below `BACKLOG_LAG_DAYS = 2` days, so the Python `est_hours` series equals the Java `fresh_hours` series) or `"all"`.
- Mapping (the Java lifecycle rules, restated for Python):
  - Integer ids: every UUID of `users`, `teams`, `projects`, `tasks` maps to `1..n` in sorted-UUID-string order per table (`Ids.of(table, uuid)`).
  - `departments`: one per team with `parent_team_id` null (`id` = the team's int id, `name`, `skill_team_leader_id` = the manager's int id when the manager is a user). `teams`: every team (`department_id` = the parent's id when set, else its own; `team_leader_id` = manager int id).
  - `members`: users with `active` true and role `MEMBER` or `TEAM_LEADER` and at least one `team_members` row; `team_id` = the member's team with a non-null parent with the smallest UUID string, else the smallest; `department_id` = that team's department; `role` = `team_leader`/`member`; `counted_in_workload` 1; `active_from` = earliest `joined_at` date; `active_to` = `deactivated_at` date or null; `name` = `full_name`.
  - Assignment date: `changed_at` of the latest `task_history` row with `field_name == "assignee"` whose `new_value` resolves to the task's assignee (UUID, else email, else full name; members first, then all users; `None`, empty, `null` unassigned; ambiguous or unresolved → `created_date`).
  - `tasks`: non-archived tasks whose assignee is a member; skipped when `arrivals == "fresh"` and `(assigned.date − created.date).days >= 2`; columns `title`, `project_id`, `assignee_id`, `team_id` (the member's team), `type` (task type name), `priority`, `status` (`DONE` → `done`, `IN_PROGRESS` → `in_progress`, else `todo` from `task_statuses.category`), `created_at` (date), `assigned_at` (date), `due_date`, `completed_at` (`finished_date` date when `DONE`, else the first status transition into a `DONE` category, else null), `estimated_hours` (`original_estimate_hrs` or 0), `actual_hours` (sum of the assignee's `time_logs.hours`, else `original − remaining` when done, else null), `created_by` (the reporter's int id when a member), `assignment_mode` (`self_picked` when reporter = assignee, `project` when `parent_task_id` set, else `manual`).
  - `projects`: projects referenced by kept tasks or owned by a team: `department_id` from the owning team (else the first department), `start_date` = the earliest `created_date` of its kept tasks (else the export's latest task date), `deadline` = the latest `due_date` of its tasks (else `start_date + 90 days`), `type` `delivery`, `status` = the WorkloadHub status lower-cased. `project_teams`: the owning team plus every team with a kept task in the project.
  - `holidays`: confirmed, active holidays expanded day by day (`date`, `name` = title, `country` `MA`); `vacations`: absences of members with `hours >= 8` (`start_date = end_date = date`, type `absence`); `capacity_defaults.weekly_hours` = 40.
- CLI: `whf import-workloadhub <export.json> --db <path> [--arrivals fresh|all]` prints the counts and the members/tasks totals; refuses a `--db` path that already has tasks unless `--replace` (which deletes `tasks, projects, project_teams, members, teams, departments, holidays, vacations, capacity_overrides, runs, forecasts, run_facts, run_narratives` first).

- [ ] **Step 1: Write the failing test**

```python
"""The WorkloadHub export becomes the whf schema the Python harness reads."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from whf.data.workloadhub import BACKLOG_LAG_DAYS, import_workloadhub
from whf.db.connection import connect
from whf.db.repo import read_df

FIXTURE = Path(__file__).resolve().parents[2] / "server" / "forecast-core" / "src" / "test" / "resources" / "fixtures" / "mini-export.json"


@pytest.fixture
def export() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def test_members_teams_and_departments_follow_the_export(export: dict) -> None:
    conn = connect(":memory:")
    counts = import_workloadhub(conn, export)
    members = read_df(conn, "SELECT * FROM members ORDER BY id")
    assert counts["members"] == 2
    assert set(members["role"]) <= {"member", "team_leader"}
    assert members["counted_in_workload"].eq(1).all()
    teams = read_df(conn, "SELECT * FROM teams")
    assert len(teams) >= 1
    assert set(members["team_id"]) <= set(teams["id"])
    departments = read_df(conn, "SELECT * FROM departments")
    assert set(teams["department_id"]) <= set(departments["id"])
    assert float(read_df(conn, "SELECT weekly_hours FROM capacity_defaults")["weekly_hours"][0]) == 40.0


def test_fresh_keeps_only_arrivals_assigned_within_the_lag(export: dict) -> None:
    conn = connect(":memory:")
    import_workloadhub(conn, export, arrivals="fresh")
    fresh = read_df(conn, "SELECT created_at, assigned_at, status, actual_hours, estimated_hours, assignment_mode FROM tasks")
    assert len(fresh) > 0
    for created, assigned in zip(fresh["created_at"], fresh["assigned_at"], strict=True):
        assert (json_date(assigned) - json_date(created)).days < BACKLOG_LAG_DAYS
    assert set(fresh["status"]) <= {"todo", "in_progress", "done"}
    assert set(fresh["assignment_mode"].dropna()) <= {"manual", "self_picked", "project"}
    done = fresh[fresh["status"] == "done"]
    assert done["actual_hours"].notna().all()
    conn_all = connect(":memory:")
    import_workloadhub(conn_all, export, arrivals="all")
    assert len(read_df(conn_all, "SELECT id FROM tasks")) >= len(fresh)


def test_projects_holidays_and_vacations_are_present(export: dict) -> None:
    conn = connect(":memory:")
    import_workloadhub(conn, export)
    projects = read_df(conn, "SELECT * FROM projects")
    assert len(projects) >= 1
    assert (projects["deadline"] >= projects["start_date"]).all()
    assert len(read_df(conn, "SELECT * FROM project_teams")) >= len(projects)
    holidays = read_df(conn, "SELECT * FROM holidays")
    assert len(holidays) == sum(
        1 for h in export["data"]["holidays"] if h["status"] == "CONFIRMED" and h["active"] for _ in _days(h["start_date"], h["end_date"])
    )
    vacations = read_df(conn, "SELECT * FROM vacations")
    assert (vacations["start_date"] == vacations["end_date"]).all()


def json_date(value: str):
    import datetime as dt

    return dt.date.fromisoformat(str(value)[:10])


def _days(start: str, end: str):
    import datetime as dt

    d, last = json_date(start), json_date(end)
    while d <= last:
        yield d
        d += dt.timedelta(days=1)
```

- [ ] **Step 2: Run the test to see it fail**

Run: `cd service && uv run pytest tests/test_workloadhub_import.py -q`
Expected: `ModuleNotFoundError: whf.data.workloadhub`.

- [ ] **Step 3: Write the converter**

```python
"""Load a WorkloadHub export (real or seeded) into the whf schema, so the Python harness can score the same data as the Java module."""

from __future__ import annotations

import datetime as dt
import sqlite3
from collections import defaultdict
from typing import Any

from whf.db.repo import insert_rows

BACKLOG_LAG_DAYS = 2
COUNTED_ROLES = {"MEMBER", "TEAM_LEADER"}
UNASSIGNED = {"", "none", "null"}
FULL_DAY_HOURS = 8.0
DEFAULT_WEEKLY_HOURS = 40.0
REPLACED_TABLES = (
    "run_narratives", "run_facts", "forecasts", "runs", "vacations", "capacity_overrides", "holidays",
    "project_teams", "tasks", "projects", "members", "teams", "departments",
)


def _date(value: Any) -> dt.date | None:
    return None if value in (None, "") else dt.date.fromisoformat(str(value)[:10])


def _datetime(value: Any) -> dt.datetime | None:
    if value in (None, ""):
        return None
    text = str(value).replace(" ", "T")
    return dt.datetime.fromisoformat(text[:26]) if "T" in text else dt.datetime.combine(dt.date.fromisoformat(text[:10]), dt.time())


class Ids:
    """UUID -> small integer per table, in sorted UUID order, so two imports of the same export agree."""

    def __init__(self, data: dict[str, list[dict]]) -> None:
        self._maps: dict[str, dict[str, int]] = {}
        for table in ("users", "teams", "projects", "tasks"):
            ids = sorted(str(r["id"]) for r in data.get(table, []))
            self._maps[table] = {u: i + 1 for i, u in enumerate(ids)}

    def of(self, table: str, uuid: Any) -> int | None:
        return None if uuid is None else self._maps[table].get(str(uuid))


class _Resolver:
    def __init__(self, users: list[dict], member_ids: set[str]) -> None:
        self.user_ids = {str(u["id"]) for u in users}
        self.by_email: dict[str, list[str]] = defaultdict(list)
        self.by_name: dict[str, list[str]] = defaultdict(list)
        self.member_by_email: dict[str, list[str]] = defaultdict(list)
        self.member_by_name: dict[str, list[str]] = defaultdict(list)
        for u in users:
            uid = str(u["id"])
            for key, index, member_index in (
                (u.get("email"), self.by_email, self.member_by_email),
                (u.get("full_name"), self.by_name, self.member_by_name),
            ):
                if key:
                    index[str(key).strip().lower()].append(uid)
                    if uid in member_ids:
                        member_index[str(key).strip().lower()].append(uid)

    def resolve(self, value: Any) -> str | None:
        if value is None or str(value).strip().lower() in UNASSIGNED:
            return None
        key = str(value).strip().lower()
        if key in self.user_ids:
            return key
        for index in (self.member_by_email, self.member_by_name, self.by_email, self.by_name):
            hits = index.get(key)
            if hits:
                return hits[0] if len(hits) == 1 else None
        return None


def _primary_team(team_ids: list[str], parent_of: dict[str, str | None]) -> str:
    ordered = sorted(team_ids)
    with_parent = [t for t in ordered if parent_of.get(t)]
    return with_parent[0] if with_parent else ordered[0]


def import_workloadhub(conn: sqlite3.Connection, export: dict, *, arrivals: str = "fresh") -> dict[str, int]:
    if arrivals not in ("fresh", "all"):
        raise ValueError(f"arrivals must be 'fresh' or 'all', not {arrivals!r}")
    data: dict[str, list[dict]] = export["data"]
    ids = Ids(data)
    teams = data.get("teams", [])
    users = data.get("users", [])
    parent_of = {str(t["id"]): (str(t["parent_team_id"]) if t.get("parent_team_id") else None) for t in teams}
    department_of_team: dict[str, int] = {}
    for t in teams:
        tid = str(t["id"])
        department_of_team[tid] = ids.of("teams", parent_of[tid] or tid)

    # directory
    teams_of_user: dict[str, list[str]] = defaultdict(list)
    joined_of_user: dict[str, dt.date] = {}
    for tm in data.get("team_members", []):
        uid, tid = str(tm["user_id"]), str(tm["team_id"])
        if tid in parent_of:
            teams_of_user[uid].append(tid)
            joined = _date(tm.get("joined_at")) or dt.date.today()
            joined_of_user[uid] = min(joined_of_user.get(uid, joined), joined)
    members: dict[str, dict] = {}
    for u in users:
        uid = str(u["id"])
        if not u.get("active") or u.get("role") not in COUNTED_ROLES or not teams_of_user.get(uid):
            continue
        team = _primary_team(teams_of_user[uid], parent_of)
        members[uid] = {
            "id": ids.of("users", uid),
            "name": u.get("full_name") or u.get("email") or uid,
            "team_id": ids.of("teams", team),
            "department_id": department_of_team[team],
            "role": "team_leader" if u.get("role") == "TEAM_LEADER" else "member",
            "counted_in_workload": 1,
            "active_from": joined_of_user[uid],
            "active_to": _date(u.get("deactivated_at")),
        }
    user_int = {str(u["id"]): ids.of("users", u["id"]) for u in users}
    department_rows = [
        {
            "id": ids.of("teams", t["id"]),
            "name": t["name"],
            "skill_team_leader_id": user_int.get(str(t.get("manager_id"))) if t.get("manager_id") else None,
        }
        for t in sorted(teams, key=lambda t: str(t["id"]))
        if not parent_of[str(t["id"])]
    ]
    if not department_rows:
        department_rows = [{"id": 1, "name": "Unassigned", "skill_team_leader_id": None}]
        department_of_team = {tid: 1 for tid in parent_of}
    team_rows = [
        {
            "id": ids.of("teams", t["id"]),
            "department_id": department_of_team[str(t["id"])],
            "name": t["name"],
            "team_leader_id": user_int.get(str(t.get("manager_id"))) if t.get("manager_id") else None,
        }
        for t in sorted(teams, key=lambda t: str(t["id"]))
    ]

    # lifecycle
    category_by_status_id = {str(s["id"]): s["category"] for s in data.get("task_statuses", [])}
    category_by_status_name = {s["name"]: s["category"] for s in data.get("task_statuses", [])}
    type_name = {str(t["id"]): t["name"] for t in data.get("task_types", [])}
    history: dict[str, list[dict]] = defaultdict(list)
    for h in data.get("task_history", []):
        history[str(h["task_id"])].append(h)
    for rows in history.values():
        rows.sort(key=lambda h: (_datetime(h["changed_at"]) or dt.datetime.min))
    logs: dict[tuple[str, str], float] = defaultdict(float)
    for log in data.get("time_logs", []):
        logs[(str(log["task_id"]), str(log["user_id"]))] += float(log.get("hours") or 0.0)
    resolver = _Resolver(users, set(members))

    task_rows: list[dict] = []
    project_first: dict[str, dt.date] = {}
    project_last_due: dict[str, dt.date] = {}
    project_teams: dict[str, set[int]] = defaultdict(set)
    latest_created = max((_date(t["created_date"]) for t in data.get("tasks", []) if t.get("created_date")), default=dt.date.today())
    for t in sorted(data.get("tasks", []), key=lambda t: str(t["id"])):
        tid = str(t["id"])
        assignee = str(t["assignee_id"]) if t.get("assignee_id") else None
        if t.get("archived") or assignee not in members:
            continue
        created = _datetime(t["created_date"])
        assigned = created
        for h in reversed(history.get(tid, [])):
            if h.get("field_name") == "assignee" and resolver.resolve(h.get("new_value")) == assignee:
                assigned = _datetime(h["changed_at"])
                break
        lag = (assigned.date() - created.date()).days
        if arrivals == "fresh" and lag >= BACKLOG_LAG_DAYS:
            continue
        category = category_by_status_id.get(str(t.get("task_status_id")), "TO_DO")
        completed = None
        if category == "DONE":
            completed = _datetime(t.get("finished_date"))
            if completed is None:
                for h in history.get(tid, []):
                    if h.get("field_name") == "status" and category_by_status_name.get(h.get("new_value")) == "DONE":
                        completed = _datetime(h["changed_at"])
                        break
        estimate = float(t.get("original_estimate_hrs") or 0.0)
        actual = logs.get((tid, assignee))
        if not actual and category == "DONE":
            actual = max(0.0, estimate - float(t.get("remaining_estimate_hrs") or 0.0))
        reporter = str(t["reporter_id"]) if t.get("reporter_id") else None
        mode = "self_picked" if reporter == assignee else ("project" if t.get("parent_task_id") else "manual")
        project = str(t["project_id"]) if t.get("project_id") else None
        task_rows.append(
            {
                "id": ids.of("tasks", tid),
                "title": t.get("title") or t.get("key") or tid,
                "project_id": ids.of("projects", project) if project else None,
                "assignee_id": members[assignee]["id"],
                "team_id": members[assignee]["team_id"],
                "type": type_name.get(str(t.get("task_type_id")), "Task"),
                "priority": t.get("priority") or "MEDIUM",
                "status": {"DONE": "done", "IN_PROGRESS": "in_progress"}.get(category, "todo"),
                "created_at": created.date(),
                "assigned_at": assigned.date(),
                "due_date": _date(t.get("due_date")),
                "completed_at": completed.date() if completed else None,
                "estimated_hours": estimate,
                "actual_hours": None if actual is None else float(actual),
                "created_by": members[reporter]["id"] if reporter in members else None,
                "assignment_mode": mode,
            }
        )
        if project:
            project_first[project] = min(project_first.get(project, created.date()), created.date())
            project_teams[project].add(members[assignee]["team_id"])
            if t.get("due_date"):
                project_last_due[project] = max(project_last_due.get(project, _date(t["due_date"])), _date(t["due_date"]))

    project_rows: list[dict] = []
    project_team_rows: list[dict] = []
    for p in sorted(data.get("projects", []), key=lambda p: str(p["id"])):
        pid = str(p["id"])
        owner = str(p["team_id"]) if p.get("team_id") and str(p["team_id"]) in parent_of else None
        if pid not in project_first and owner is None:
            continue
        start = project_first.get(pid, latest_created)
        project_rows.append(
            {
                "id": ids.of("projects", pid),
                "name": p.get("name") or p.get("key") or pid,
                "department_id": department_of_team[owner] if owner else department_rows[0]["id"],
                "start_date": start,
                "deadline": max(project_last_due.get(pid, start + dt.timedelta(days=90)), start),
                "type": "delivery",
                "status": str(p.get("status") or "active").lower(),
                "created_by": None,
            }
        )
        linked = set(project_teams.get(pid, set()))
        if owner:
            linked.add(ids.of("teams", owner))
        for team_id in sorted(linked):
            project_team_rows.append({"project_id": ids.of("projects", pid), "team_id": team_id})

    holiday_rows: list[dict] = []
    for h in data.get("holidays", []):
        if h.get("status") != "CONFIRMED" or not h.get("active"):
            continue
        day, last = _date(h["start_date"]), _date(h["end_date"])
        while day <= last:
            holiday_rows.append({"date": day, "name": h.get("title") or "holiday", "country": "MA"})
            day += dt.timedelta(days=1)
    seen_holidays: set[dt.date] = set()
    holiday_rows = [r for r in holiday_rows if not (r["date"] in seen_holidays or seen_holidays.add(r["date"]))]
    vacation_rows = [
        {"member_id": members[str(a["user_id"])]["id"], "start_date": _date(a["date"]), "end_date": _date(a["date"]), "type": "absence"}
        for a in data.get("absences", [])
        if str(a.get("user_id")) in members and float(a.get("hours") or 0.0) >= FULL_DAY_HOURS
    ]

    conn.execute("PRAGMA foreign_keys = OFF")
    counts: dict[str, int] = {}
    for table, rows in (
        ("departments", department_rows),
        ("teams", team_rows),
        ("members", sorted(members.values(), key=lambda m: m["id"])),
        ("projects", project_rows),
        ("project_teams", project_team_rows),
        ("tasks", task_rows),
        ("holidays", holiday_rows),
        ("vacations", vacation_rows),
    ):
        counts[table] = insert_rows(conn, table, rows, commit=False)
    conn.execute("UPDATE capacity_defaults SET weekly_hours = ? WHERE id = 1", (DEFAULT_WEEKLY_HOURS,))
    conn.commit()
    conn.execute("PRAGMA foreign_keys = ON")
    return counts


def clear_for_replace(conn: sqlite3.Connection) -> None:
    conn.execute("PRAGMA foreign_keys = OFF")
    for table in REPLACED_TABLES:
        conn.execute(f"DELETE FROM {table}")
    conn.commit()
    conn.execute("PRAGMA foreign_keys = ON")
```

`holiday_rows` de-duplication with a set-side-effect comprehension is compact but opaque; write it as a plain loop if `ruff` objects (`B` rules).

- [ ] **Step 4: Add the CLI command**

In `service/src/whf/cli.py`, after `data_generate`:

```python
@app.command("import-workloadhub")
def import_workloadhub_cmd(
    file: Annotated[Path, typer.Argument(help="A WorkloadHub JSON export (real or seeded)")],
    db: DbOption = None,
    arrivals: Annotated[str, typer.Option("--arrivals", help="fresh: keep arrivals assigned within two days (the series the Java module forecasts); all: every assigned task")] = "fresh",
    replace: Annotated[bool, typer.Option("--replace", help="Delete the existing organisation and tasks first")] = False,
) -> None:
    """Load a WorkloadHub export into this database's schema for the parity check against the Java module."""
    from whf.data.workloadhub import clear_for_replace, import_workloadhub

    conn = _conn(db)
    if conn.execute("SELECT COUNT(*) FROM tasks").fetchone()[0] and not replace:
        typer.echo("error: the database already holds tasks; pass --replace to overwrite")
        raise typer.Exit(code=2)
    if replace:
        clear_for_replace(conn)
    export = json.loads(Path(file).read_text(encoding="utf-8"))
    counts = import_workloadhub(conn, export, arrivals=arrivals)
    for table, n in counts.items():
        typer.echo(f"{table:<16}{n:>8}")
    typer.echo(f"Imported {counts['members']} members and {counts['tasks']} tasks ({arrivals} arrivals) from {file}")
```

Add a CLI test to `tests/test_workloadhub_import.py`:

```python
def test_cli_imports_and_refuses_to_overwrite(tmp_path: Path) -> None:
    from typer.testing import CliRunner

    from whf.cli import app

    db = tmp_path / "w.db"
    result = CliRunner().invoke(app, ["import-workloadhub", str(FIXTURE), "--db", str(db)])
    assert result.exit_code == 0, result.output
    assert "Imported 2 members" in result.output
    again = CliRunner().invoke(app, ["import-workloadhub", str(FIXTURE), "--db", str(db)])
    assert again.exit_code == 2
    replaced = CliRunner().invoke(app, ["import-workloadhub", str(FIXTURE), "--db", str(db), "--replace", "--arrivals", "all"])
    assert replaced.exit_code == 0, replaced.output
```

- [ ] **Step 5: Run the tests and the linters**

Run: `cd service && uv run pytest tests/test_workloadhub_import.py -q && uv run ruff check src tests && uv run ruff format --check src tests && uv run ty check src`
Expected: 4 passed, no lint or type findings (fix what `ruff` and `ty` report before committing; the rest of the suite is unchanged).

- [ ] **Step 6: Commit**

```bash
git add service/src/whf/data/workloadhub.py service/src/whf/cli.py service/tests/test_workloadhub_import.py
git commit -m "feat(service): import a WorkloadHub export into the Python schema for the parity check

Directory, lifecycle dates, actual hours, holidays and absences follow
the Java module's rules; fresh arrivals only by default so both sides
forecast the same series."
```

---

### Task 10: The parity comparison, the procedure, and the first synthetic result

**Files:**
- Create: `server/tools/parity_compare.py`
- Create: `server/tools/parity.sh`
- Modify: `server/README.md` (CLI commands `run`, `runs`, `teams`, `eval`; the parity procedure)
- Modify: `docs/backlog.md` (Java migration entries)
- Create: `docs/eval/2026-09-10-java-parity-synthetic/{java-summary.md,python-summary.md,parity.md}`
- Create: `server/tools/test_parity_compare.py` (pytest, run with the service's `uv`)

**Interfaces:**
- `parity_compare.py PYTHON_SCORES JAVA_SCORES [--tolerance 0.10] [--python-booster gbm] [--java-booster xgboost] [--out parity.md]`: reads the two `scores.csv` files (stdlib `csv` only), keeps `metric == mase` rows at horizons 1 and 2, computes per side: the booster's mean MASE, the floor's mean MASE (a sanity check, expected 1.0), the number of origins and the champion decision (`booster` when the booster's mean is below 1.0, else `floor`); prints a Markdown table (`side | booster | mean MASE | origins | champion`), the absolute difference, and `PASS` or `FAIL` (fail when the difference exceeds the tolerance or the champions differ or either side has no booster rows); exit code 0 on pass, 1 on fail, 2 on a usage error; `--out` writes the same text.
- `parity.sh EXPORT_JSON OUT_DIR [AS_OF]`: from `server/`, runs the whole procedure: `forecast init-db/import/eval` into `OUT_DIR/java` (models `xgboost,seasonal_naive`) and, from `../service`, `uv run whf import-workloadhub` then `uv run whf eval --models gbm,seasonal_naive` into `OUT_DIR/python`, then `parity_compare.py`. `AS_OF` defaults to the export's latest task date, computed by `forecast eval` itself and passed explicitly to the Python side (read it from the Java summary's first line). Uses the CLI jar built by `mvn -B -q -DskipTests package` when `target/*.jar` is missing.
- Test: `test_parity_compare.py` builds two small CSVs in `tmp_path` and asserts the pass/fail rules and the exit codes through `subprocess`.

- [ ] **Step 1: Write the failing test**

```python
"""The parity gate: same data, Python harness against Java harness, mean MASE within tolerance and the same champion."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "parity_compare.py"
HEADER = "model,horizon,origin,metric,value\n"


def scores(path: Path, booster: str, values: list[float]) -> Path:
    lines = [HEADER]
    for i, v in enumerate(values):
        origin = f"2026-0{1 + i % 8}-0{1 + i % 7}"
        lines.append(f"{booster},1,{origin},mase,{v}\n")
        lines.append(f"{booster},2,{origin},mase,{v + 0.02}\n")
        lines.append(f"seasonal_naive,1,{origin},mase,1.0\n")
        lines.append(f"{booster},1,{origin},mae,3.0\n")
    path.write_text("".join(lines), encoding="utf-8")
    return path


def run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, check=False)


def test_passes_within_tolerance_and_same_champion(tmp_path: Path) -> None:
    py = scores(tmp_path / "py.csv", "gbm", [0.80, 0.85, 0.90])
    java = scores(tmp_path / "java.csv", "xgboost", [0.86, 0.90, 0.93])
    result = run(str(py), str(java))
    assert result.returncode == 0, result.stdout + result.stderr
    assert "PASS" in result.stdout and "| python | gbm |" in result.stdout and "| java | xgboost |" in result.stdout


def test_fails_when_the_gap_exceeds_the_tolerance_or_champions_differ(tmp_path: Path) -> None:
    py = scores(tmp_path / "py.csv", "gbm", [0.80, 0.85])
    far = scores(tmp_path / "far.csv", "xgboost", [0.95, 0.99])
    assert run(str(py), str(far)).returncode == 1
    floor = scores(tmp_path / "floor.csv", "xgboost", [1.02, 1.05])
    assert "champion" in run(str(py), str(floor)).stdout and run(str(py), str(floor)).returncode == 1
    assert run(str(py), str(far), "--tolerance", "0.2").returncode == 0


def test_usage_errors(tmp_path: Path) -> None:
    assert run(str(tmp_path / "missing.csv"), str(tmp_path / "missing2.csv")).returncode == 2
    out = tmp_path / "parity.md"
    py = scores(tmp_path / "py.csv", "gbm", [0.8])
    java = scores(tmp_path / "java.csv", "xgboost", [0.8])
    assert run(str(py), str(java), "--out", str(out)).returncode == 0
    assert out.read_text(encoding="utf-8").startswith("# Parity")
```

- [ ] **Step 2: Run the test to see it fail**

Run: `cd service && uv run pytest ../server/tools/test_parity_compare.py -q`
Expected: failures (the script does not exist; the runs return a non-zero code with a traceback).

- [ ] **Step 3: Write the comparison script**

```python
#!/usr/bin/env python3
"""Compare the Python harness's scores.csv with the Java harness's: the parity gate of the Java module design, section 13.

Usage: parity_compare.py PYTHON_SCORES JAVA_SCORES [--tolerance 0.10] [--python-booster gbm] [--java-booster xgboost] [--out parity.md]
Exit 0 when the booster's mean MASE (horizons 1 and 2, every origin) agrees within the tolerance and both sides pick the same
champion; 1 otherwise; 2 on a usage error. Standard library only.
"""

from __future__ import annotations

import argparse
import csv
import math
import sys
from pathlib import Path

FLOOR = "seasonal_naive"
HORIZONS = {"1", "2"}


def side(path: Path, booster: str) -> dict:
    rows = [r for r in csv.DictReader(path.open(encoding="utf-8")) if r["metric"] == "mase" and r["horizon"] in HORIZONS and r["value"] != ""]
    mine = [float(r["value"]) for r in rows if r["model"] == booster and not math.isnan(float(r["value"]))]
    floor = [float(r["value"]) for r in rows if r["model"] == FLOOR and not math.isnan(float(r["value"]))]
    origins = {r["origin"] for r in rows if r["model"] == booster}
    mean = sum(mine) / len(mine) if mine else float("nan")
    return {
        "booster": booster,
        "mean": mean,
        "floor_mean": sum(floor) / len(floor) if floor else float("nan"),
        "origins": len(origins),
        "champion": "floor" if not mine or mean >= 1.0 else "booster",
    }


def report(py: dict, java: dict, tolerance: float) -> tuple[str, bool]:
    gap = abs(py["mean"] - java["mean"]) if not (math.isnan(py["mean"]) or math.isnan(java["mean"])) else float("nan")
    ok = not math.isnan(gap) and gap <= tolerance and py["champion"] == java["champion"]
    lines = [
        "# Parity: Python harness against Java harness",
        "",
        "| side | booster | mean MASE (h1, h2) | floor mean MASE | origins | champion |",
        "|---|---|---|---|---|---|",
        f"| python | {py['booster']} | {py['mean']:.3f} | {py['floor_mean']:.3f} | {py['origins']} | {py['champion']} |",
        f"| java | {java['booster']} | {java['mean']:.3f} | {java['floor_mean']:.3f} | {java['origins']} | {java['champion']} |",
        "",
        f"Absolute difference of the booster's mean MASE: {gap:.3f} (tolerance {tolerance:.2f}).",
        f"Champion decision: python {py['champion']}, java {java['champion']}.",
        "",
        "PASS" if ok else "FAIL: " + ("no booster rows on one side" if math.isnan(gap) else "gap above tolerance" if gap > tolerance else "champions differ"),
        "",
    ]
    return "\n".join(lines), ok


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("python_scores", type=Path)
    parser.add_argument("java_scores", type=Path)
    parser.add_argument("--tolerance", type=float, default=0.10)
    parser.add_argument("--python-booster", default="gbm")
    parser.add_argument("--java-booster", default="xgboost")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    for path in (args.python_scores, args.java_scores):
        if not path.is_file():
            print(f"error: {path} is not a file", file=sys.stderr)
            return 2
    text, ok = report(side(args.python_scores, args.python_booster), side(args.java_scores, args.java_booster), args.tolerance)
    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 4: Write `parity.sh`**

```bash
#!/usr/bin/env bash
# Run the parity procedure on one WorkloadHub export: Java eval and Python eval on the same data, then compare.
# Usage: server/tools/parity.sh EXPORT_JSON OUT_DIR [AS_OF]
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
export_json="$1"; out="$2"; as_of="${3:-}"
mkdir -p "$out/java" "$out/python"
jar="$(ls "$here"/forecast-cli/target/workloadhub-forecast-cli-*.jar 2>/dev/null | head -n 1 || true)"
if [ -z "$jar" ]; then (cd "$here" && mvn -B -q -DskipTests package); jar="$(ls "$here"/forecast-cli/target/workloadhub-forecast-cli-*.jar | head -n 1); fi
db="$out/java/parity.db"
rm -f "$db"
java -jar "$jar" init-db --db "$db"
java -jar "$jar" import --db "$db" "$export_json"
if [ -n "$as_of" ]; then java -jar "$jar" eval --db "$db" --as-of "$as_of" --models xgboost,seasonal_naive --out "$out/java";
else java -jar "$jar" eval --db "$db" --models xgboost,seasonal_naive --out "$out/java"; fi
as_of="$(sed -n '1s/.*as of //p' "$out/java/summary.md")"
pydb="$out/python/parity.db"
rm -f "$pydb"
(cd "$here/../service" && uv run whf import-workloadhub "$export_json" --db "$pydb" \
  && uv run whf eval --db "$pydb" --as-of "$as_of" --models gbm,seasonal_naive --out "$out/python")
python3 "$here/tools/parity_compare.py" "$out/python/scores.csv" "$out/java/scores.csv" --out "$out/parity.md"
```

Check the jar's real name under `forecast-cli/target/` after `mvn package` (the `spring-boot-maven-plugin` repackage may name it `workloadhub-forecast-cli-0.1.0-SNAPSHOT.jar`) and adjust the glob. `chmod +x server/tools/parity.sh`.

- [ ] **Step 5: Run the parity procedure on a synthetic seed and record the result**

```bash
cd server
java -jar forecast-cli/target/workloadhub-forecast-cli-*.jar seed --synthetic --users 36 --weeks 52 --seed 11 --end 2026-09-06 --out /tmp/parity-seed.json
tools/parity.sh /tmp/parity-seed.json /tmp/parity-out 2026-09-06
```

Copy `/tmp/parity-out/java/summary.md` to `docs/eval/2026-09-10-java-parity-synthetic/java-summary.md`, `/tmp/parity-out/python/summary.md` to `python-summary.md` and `/tmp/parity-out/parity.md` to `parity.md` in the same folder. Never run this step on the real export inside the repository (`/root/.claude/uploads/...` or the owner's file): the real-mode result goes in the owner's own folder, outside git. If the synthetic gate fails (`FAIL` in `parity.md`), do not adjust the tolerance: write the numbers into `parity.md` as they are, report `DONE_WITH_CONCERNS` with both means, and leave the investigation to the closing review (the design says a larger gap is investigated, not accepted).

- [ ] **Step 6: Documentation**

`server/README.md`: add the four commands to the command table (`teams`, `run`, `runs`, `eval` with their options as in Tasks 7 and 8), a "Parity check" section describing `tools/parity.sh`, what the gate is (mean MASE of the booster over horizons 1 and 2 within 0.10 and the same champion, arrival level, global), why the Python import keeps fresh arrivals only, and a link to `docs/eval/2026-09-10-java-parity-synthetic/parity.md`.

`docs/backlog.md`, under "## Java migration", add: "The parity gate is measured at the arrival level over all counted members (the harness's global backtest); a per-team gate needs per-team champions in `demand.csv`, not produced by either harness." and "Narration (`narrate`, `narrative`, `copilotStatus`) throws `COPILOT_UNAVAILABLE` until the Copilot plan lands." and "`whf import-workloadhub` sets no capacity overrides: Python capacity is the 40-hour default over working days minus vacations, Java uses `user_capacity`; demand-level numbers differ for that reason and are not part of the gate."

- [ ] **Step 7: Run every gate**

Run: `cd server && mvn -B -q verify` and `cd service && uv run pytest tests/test_workloadhub_import.py ../server/tools/test_parity_compare.py -q && uv run ruff check src tests ../server/tools && uv run ruff format --check src tests ../server/tools`
Expected: green.

- [ ] **Step 8: Commit**

```bash
git add server/tools/parity_compare.py server/tools/parity.sh server/tools/test_parity_compare.py server/README.md docs/backlog.md docs/eval/2026-09-10-java-parity-synthetic
git commit -m "feat(tools): parity comparison of the Python and Java harnesses, with the first synthetic result

One script runs both evaluations on the same export and compares the
booster's mean MASE and the champion decision within the design's tolerance."
```

---

## Closing notes for the executor and the reviewer

**Rulings this plan takes, to report to the owner at the end:**

1. The parity gate is global (the harness's arrival-level backtest over all counted members), not per team: neither harness produces per-team champions. Recorded in the backlog.
2. The Python side forecasts the same series as Java by importing fresh arrivals only (`--arrivals fresh`); the Python harness itself is not changed.
3. Patterns drop `deadline_proximity_corr` (no project dates in the WorkloadHub schema) and key `cycle_days_by_type` by family; `similar_projects` of the likely-work design is not built (projects have no `type`), `project_roles` uses the single phase `active`.
4. `forecast_member_weeks` has no `demand_hrs` column (foundation DDL); demand is recomputed as `open + new + planned` on read, which is the invariant.
5. Progress lives in the JVM that runs the run; a run that is `RUNNING` in another instance reports 50 % (spec section 9 accepted this).
6. `startRun` validates the team's existence before queuing; a team without counted members fails the run (`TEAM_NOT_FOUND` on the row) rather than being refused at submission, so the failure is recorded.
7. Narration methods throw `COPILOT_UNAVAILABLE` until the Copilot plan lands.

**Docs step:** part of Task 10 (README, backlog, the synthetic parity result). `CLAUDE.md` is left for the migration plan, which rewrites it for the Java-only repository.

**What the next plan takes from here:** `ForecastService` and `JdbcRunStore` (`forecast_narratives` is still empty), the facts JSON as the tool payload for Copilot, `RunProgressTracker` with its `thinking`/`answer` fields for streaming, `GitHubTokenStore` from the foundation plan, and the CLI `Services` factory for `narrate` and `copilot status`.
