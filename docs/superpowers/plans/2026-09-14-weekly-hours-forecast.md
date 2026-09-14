# Weekly Hours Forecast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retarget the forecast at the hours members actually log, delete everything that existed only to bridge estimated arrivals to logged hours, make the number of forecast windows configurable, correct the capacity default to 44 hours, teach the seed generator to produce overtime and weekday habits, and add the three pressure facts a forecast of logged hours cannot show by itself.

**Architecture:** Eleven tasks in the order section 23 of the spec fixes. Parity retires first, because its scripts compare Java output against arrival-hours behaviour and would go red the moment the retarget lands. The seed changes second, so every later task is written against data that can show overload, weekday shape and under-logging. Then the retarget itself, the deletions it licenses, the schema, the facts, the configurable window count, the weekday split, the pressure facts, and the documents. `cd server && mvn -B -q verify` must be green at the end of every task (about six minutes without Docker, so use a 600000 ms timeout; jqwik prints an "If you are an AI Agent..." banner that is library output to ignore).

**Tech Stack:** Java 21, Maven 3.9, Spring Boot 4.1 auto-configuration, Spring `JdbcClient`, Flyway, XGBoost4J, JUnit 6, jqwik, SQLite and PostgreSQL (Testcontainers when Docker is present).

**Spec:** `docs/superpowers/specs/2026-09-13-weekly-hours-forecast-design.md` — read it first, and read its section 18 (the owner's twelve rulings) before any other section. Every task below cites the spec sections it implements.

## Global Constraints

Project-wide requirements. Every task's requirements implicitly include this section.

**Paths and packages**

- Main: `server/forecast-core/src/main/java/com/workloadhub/forecast/...`; tests: `server/forecast-core/src/test/java/com/workloadhub/forecast/...`; product skills: `server/forecast-core/src/main/resources/skills/whf-*/SKILL.md`; migrations: `server/forecast-core/src/main/resources/db/forecast/postgresql/` and `.../sqlite/`.
- `server/tools/Experiment.java` and `server/examples/HostExample.java` are single-file Java programs run by their `.sh` launchers, not built by Maven. They still have to compile: the launchers run them.

**The quantity being forecast (spec 3)**

- The target is **logged hours per member-week**: the sum of `time_logs.hours` for a member over the Monday-week, which is exactly what `eval.Truth.realisedHours` already computes.
- A member-week with no logged hours has a target of `0.0`, not `NaN`. Weeks before the member's start are excluded by the existing `startIndex`, unchanged.
- **No hour is ever logged on a weekend, a public holiday or a day the member is absent** (ruling 18.4). WorkloadHub does not record them. So no day filter is applied when summing a week, and `Truth` keeps its current shape.

**Numbers, exactly (spec 7, 8.1, 9)**

- `band(double demand, double q10, double q90, double capacity) -> Band(double demand, double low, double high, double overload)`, where `d = round2(max(0, demand))`, `low = round2(max(0, d + q10))`, `high = round2(d + q90)`, `overload = round2(max(0, d - capacity))`. The `ratio` argument is gone.
- `backlog_excess_hrs(k) = round2(max(0.0, open_est_hours - Σ demand_hrs(1..k)))` — cumulative over windows 1 to *k*, measured against **this run's predicted demand**, not capacity (rulings 18.2 and 18.10). Non-increasing across a run's windows.
- `due_excess_hrs = round2(max(0.0, due_hours - capacity_hrs))` — that window's own `due_hours` against that window's own capacity, not cumulative (ruling 18.5).
- `overdue_hrs` — the remaining hours of the member's open tasks already past their due date at the run day. **Per member, not per window** (ruling 18.11). Lives on `MemberPattern`.
- `backlog_pressed` lists the members whose `backlog_excess_hrs` is above zero in the **last** window; `deadline_pressed` lists those whose `due_excess_hrs` is above zero in **any** window.
- Every formula above rounds **last**. So "positive exactly when A exceeds B" is false as a property and must be written with the rounding in it: positive exactly when `A > B + 0.005`. jqwik will find the naive form.

**Windows and horizons (spec 4, 3.2)**

- Property `whf.forecast.windows`, default `2`, range `1` to `6` inclusive. Out of range **fails start-up** with a message naming the property and the range; never clamped.
- `Horizon.MIN_WINDOWS = 1`, `Horizon.MAX_WINDOWS = 6`; `Horizon.WINDOWS` is deleted.
- The maximum horizon of a run is `windows + 1` for every run day.
- `Backtest.MIN_HISTORY_WEEKS` becomes `10 + maxHorizon` (ruling 18.1): 13 at two windows, exactly today's value, 17 at six.
- `Features.HORIZONS` stops being the constant `{1, 2, 3}` and is derived from the window count (ruling 18.3). A feature matrix is therefore tied to the count it was built with; `Backtest` checks each horizon it is asked for has its `target_h{h}` column and fails naming the missing horizon.
- **Lag columns are off by one and stay that way.** `lag1` is the row's *own* week (`j = i - (lag - 1)`), not the week before. It is leakage-safe because every target is a strictly later week. The new `arrival_hrs_lag1..4` follow the same convention so one meaning of "lag 1" holds across the matrix.

**Capacity (spec 10, 22)**

- `whf.default-weekly-hours` default becomes `44.0` (requirement C1; the working day is 44 ÷ 5 = 8.8 h).
- The seed's `CapacityWriter.BASE_HOURS` becomes `44.0` and its present working day becomes `8.8`, or the property is never reached and seeded teams stay on 40.
- `CapacityRule.FULL_DAY_HOURS` is deleted: a full-absence day is one whose absence hours meet **that day's own capacity**, because a fixed `8.0` is wrong beside an 8.8-hour day.
- `CapacityRule.offDays` is **kept**, against the spec's first draft. It is the only producer of a member's full-absence days and the weekday split needs them.

**Naming**

- `XgboostArrival` becomes `XgboostHours`, a plain class implementing nothing. `ModelScore` becomes `BacktestScore`. `Prepared.predictedEst` becomes `predictedHours`.
- Domain vocabulary in code, tests, skills and documents: demand, capacity, overload, backtest, window, horizon, target. The words *arrival model*, *effort model*, *champion model*, *planned work* survive only where they name something being deleted.

**Language**

- English and French are both fully supported in the narrative; no third language. The experiment driver's own messages and help are English only.

**Process**

- Test-driven: the failing test comes first in every task, and its failure is observed before any implementation.
- jqwik property tests for arithmetic invariants.
- The gate is `cd server && mvn -B -q verify` run by hand; CI is paused and will not catch anything.
- Commit messages: imperative subject, a short body saying why, then after a blank line the two trailer lines the dispatch names, verbatim. **Do not push.**
- No model identifier anywhere else in committed content — not in code, comments, documents, skills or test names. **The attribution trailer is the sole exception**: it is mandated for this session and every commit on this branch already carries one, so it is not a violation of the line above. This wording was corrected after Task 1's review read the two rules as contradicting each other; they do not, but the earlier phrasing said they did.

---

## File structure

| Task | Creates | Modifies | Deletes |
|---|---|---|---|
| 1 | `Numbers.java`, test `NumbersTest.java`, `NumbersPropertyTest.java` | `backtest/Backtest.java`, `capacity/CapacityRule.java`, `run/ForecastRunner.java`, `facts/FactsBuilder.java`, `eval/Metrics.java`, `eval/Accuracy.java`, `scripts/check.sh`, `scripts/check.ps1`, `scripts/release.sh`, `scripts/release.ps1`, `.github/workflows/ci.yml` | `server/tools/parity.sh`, `server/tools/parity_compare.py`, `server/tools/tests/` |
| 2 | | `seed/AbsencePlanner.java`, `seed/CapacityWriter.java`, tests `seed/AbsencePlannerTest.java`, `seed/CapacityWriterTest.java`, `seed/WorkQueueTest.java`, `seed/SeedGeneratorTest.java`, `capacity/CapacityRuleTest.java`, `testing/SeededFacts.java`, `ForecastProperties.java` | |
| 3 | `seed/WorkStyle.java`, test `seed/WorkStyleTest.java`, `seed/WorkStylePropertyTest.java` | `seed/AbsencePlanner.java`, `seed/WorkQueue.java`, `seed/SeedGenerator.java`, tests `seed/WorkQueueTest.java`, `seed/SeedGeneratorTest.java` | |
| 4 | test `features/RetargetTest.java` | `features/WeeklySeries.java`, `features/Features.java`, `features/FeatureBuilder.java`, `eval/Truth.java`, tests `features/WeeklySeriesTest.java`, `features/FeaturesTest.java`, `features/FeatureBuilderTest.java`, `features/SyntheticMatrix.java`, `eval/TruthTest.java` | |
| 5 | `model/XgboostHours.java`, test `model/XgboostHoursTest.java` | `backtest/Backtest.java`, `run/ForecastRunner.java`, `run/Prepared.java`, `run/TeamOutcome.java`, tests `backtest/BacktestTest.java`, `run/ForecastRunnerTest.java`, `run/ForecastRunnerPropertyTest.java` | `model/EffortModel.java`, `model/SeasonalNaive.java`, `model/ArrivalModel.java`, `model/XgboostArrival.java`, `run/ModelRegistry.java`, `planned/`, `calendar/HourPlacement.java`, and their test suites |
| 6 | `api/BacktestScore.java`, migration `V4__weekly_hours.sql` (both dialects) | `api/RunRequest.java`, `api/RunResult.java`, `api/RunSummary.java`, `api/MemberWindowForecast.java`, `api/MemberDayForecast.java`, `api/CurrentDayForecast.java`, `web/RunRequestBody.java`, `web/ForecastController.java`, `store/JdbcRunStore.java`, `service/DefaultForecastService.java`, their tests | `api/ModelScore.java` |
| 7 | | `facts/FactsBuilder.java`, `facts/MemberPattern.java`, `facts/Patterns.java`, `ai/FactsTools.java`, the five `whf-*` skills, tests `facts/FactsBuilderTest.java`, `facts/PatternsTest.java`, `ai/FactsToolsTest.java`, `ai/SkillTextsTest.java`, `ai/NarrativeContractTest.java`, `ai/NumberVerifierTest.java`, `ai/PromptsTest.java` | |
| 8 | | `calendar/Horizon.java`, `calendar/ForecastWindow.java`, `features/Features.java`, `features/FeatureBuilder.java`, `backtest/Backtest.java`, `run/ForecastRunner.java`, `ForecastProperties.java`, `ForecastAutoConfiguration.java`, tests `calendar/HorizonTest.java`, `backtest/BacktestTest.java`, `features/FeaturesTest.java` | |
| 9 | | `facts/Patterns.java`, `facts/MemberPattern.java`, `capacity/CapacityRule.java`, `run/ForecastRunner.java`, tests `facts/PatternsTest.java`, `run/ForecastRunnerTest.java`, `run/ForecastRunnerPropertyTest.java` | |
| 10 | | `run/ForecastRunner.java`, `facts/FactsBuilder.java`, `facts/Patterns.java`, `facts/MemberPattern.java`, `api/MemberWindowForecast.java`, `store/JdbcRunStore.java`, migration `V5__pressure_facts.sql` (both dialects), `eval/DemandRow.java`, `eval/Harness.java`, the skills, their tests | |
| 11 | | `eval/EvalConfig.java`, `eval/EvalResult.java`, `eval/ScoreRow.java`, `eval/Harness.java`, `eval/Report.java`, `server/tools/Experiment.java`, `server/tools/experiment.sh`, `server/examples/HostExample.java`, `server/README.md`, `CLAUDE.md`, `docs/backlog.md`, the four amended specs | |

---

### Task 1: Retire parity, make the gate one step, and give the arithmetic one home

Spec sections 16 and 9. This task deletes the Python cross-check and its one test, collapses the gate to `mvn verify`, and pulls the three copies of `round2` and the two of `mae` into a neutral class. It comes first because the parity scripts compare Java output against **arrival-hours** behaviour: the moment task 4 lands they are wrong, and fixing something about to be deleted is waste. Every later task also gets a gate that no longer needs `uv`.

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/Numbers.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/NumbersTest.java`, `.../NumbersPropertyTest.java`
- Modify: `.../backtest/Backtest.java`, `.../capacity/CapacityRule.java`, `.../run/ForecastRunner.java`, `.../facts/FactsBuilder.java`, `.../eval/Metrics.java`, `.../eval/Accuracy.java`, `scripts/check.sh`, `scripts/check.ps1`, `scripts/release.sh`, `scripts/release.ps1`, `.github/workflows/ci.yml`
- Delete: `server/tools/parity.sh`, `server/tools/parity_compare.py`, `server/tools/tests/` (the whole directory, holding `test_parity_compare.py`)

**Interfaces:**
- Consumes: nothing from earlier tasks; this is the first.
- Produces: `com.workloadhub.forecast.Numbers` with exactly three public statics — `static double round2(double v)`, `static double mae(double[] y, double[] yHat)`, `static double mase(double[] y, double[] yHat, double[] yNaive)`. Every later task calls these and never rewrites them.

**Why `Numbers` and not `eval/Metrics`:** `eval` already depends on `backtest`, so moving `Backtest.mae` into `Metrics` would close a package cycle. `Numbers` sits in the root package, which everything already imports.

- [ ] **Step 1: Write the failing unit test**

Create `server/forecast-core/src/test/java/com/workloadhub/forecast/NumbersTest.java`:

```java
package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NumbersTest {

    @Test
    void roundsToTwoDecimals() {
        assertEquals(1.23, Numbers.round2(1.234), 1e-9);
        assertEquals(1.24, Numbers.round2(1.235), 1e-9);
        assertEquals(-1.23, Numbers.round2(-1.234), 1e-9);
        assertEquals(0.0, Numbers.round2(0.0), 1e-9);
    }

    @Test
    void meanAbsoluteError() {
        assertEquals(0.0, Numbers.mae(new double[] {1, 2, 3}, new double[] {1, 2, 3}), 1e-9);
        assertEquals(2.0, Numbers.mae(new double[] {1, 2, 3}, new double[] {3, 4, 5}), 1e-9);
    }

    @Test
    void maeOfNothingIsNotANumber() {
        assertTrue(Double.isNaN(Numbers.mae(new double[] {}, new double[] {})));
    }

    @Test
    void meanAbsoluteScaledError() {
        // forecast off by 1 each step, naive off by 2 each step: 3/6.
        assertEquals(0.5, Numbers.mase(new double[] {1, 2, 3}, new double[] {2, 3, 4}, new double[] {3, 4, 5}), 1e-9);
    }

    @Test
    void maseAgainstAPerfectNaiveIsNotANumber() {
        assertTrue(Double.isNaN(Numbers.mase(new double[] {1, 2}, new double[] {5, 5}, new double[] {1, 2})));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=NumbersTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure — `Numbers` does not exist.

- [ ] **Step 3: Write `Numbers`**

Create `server/forecast-core/src/main/java/com/workloadhub/forecast/Numbers.java`. The bodies are lifted verbatim from `CapacityRule.round2`, `Backtest.mae` and `Backtest.mase`, so behaviour does not move:

```java
package com.workloadhub.forecast;

/**
 * The arithmetic every part of the module shares: one rounding, one error measure, one scaled error measure.
 *
 * <p>It lives in the root package rather than in {@code eval/Metrics}, the obvious home, because {@code eval}
 * already depends on {@code backtest}: moving {@code Backtest.mae} into {@code Metrics} would close a package
 * cycle. Everything already imports the root package.
 */
public final class Numbers {

    private Numbers() {
    }

    /** Two decimals, half away from zero, as every stored and reported figure is rounded. */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Mean absolute error in the unit of the inputs; NaN on empty input. */
    public static double mae(double[] y, double[] yHat) {
        if (y.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += Math.abs(y[i] - yHat[i]);
        }
        return s / y.length;
    }

    /** Mean absolute error over the naive's mean absolute error; NaN when the naive is exact. */
    public static double mase(double[] y, double[] yHat, double[] yNaive) {
        double num = 0;
        double den = 0;
        for (int i = 0; i < y.length; i++) {
            num += Math.abs(y[i] - yHat[i]);
            den += Math.abs(y[i] - yNaive[i]);
        }
        return den == 0.0 ? Double.NaN : num / den;
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=NumbersTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 5: Write the property test**

Create `server/forecast-core/src/test/java/com/workloadhub/forecast/NumbersPropertyTest.java`:

```java
package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Size;

class NumbersPropertyTest {

    @Property
    void round2IsIdempotent(@ForAll @DoubleRange(min = -1e6, max = 1e6) double v) {
        assertEquals(Numbers.round2(v), Numbers.round2(Numbers.round2(v)), 0.0);
    }

    @Property
    void round2MovesByLessThanHalfACent(@ForAll @DoubleRange(min = -1e6, max = 1e6) double v) {
        assertTrue(Math.abs(Numbers.round2(v) - v) <= 0.005 + 1e-9);
    }

    @Property
    void maeIsNonNegative(@ForAll @Size(min = 1, max = 50) double[] y, @ForAll @Size(min = 1, max = 50) double[] yHat) {
        int n = Math.min(y.length, yHat.length);
        double[] a = java.util.Arrays.copyOf(y, n);
        double[] b = java.util.Arrays.copyOf(yHat, n);
        double m = Numbers.mae(a, b);
        assertTrue(m >= 0.0 || Double.isNaN(m));
    }

    @Property
    void maeIsZeroExactlyWhenTheForecastIsTheTruth(@ForAll @Size(min = 1, max = 50) double[] y) {
        assertEquals(0.0, Numbers.mae(y, y.clone()), 0.0);
    }
}
```

The `@Size` generators can hand back arrays of different lengths, which is why the third property truncates both to the shorter one; `mae` reads `yHat` by `y`'s index and would otherwise throw.

- [ ] **Step 6: Run it**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest='NumbersTest,NumbersPropertyTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS (jqwik prints its banner; ignore it).

- [ ] **Step 7: Point every caller at `Numbers` and delete the copies**

Five edits, each a delete-and-redirect. Do not change any behaviour.

1. `capacity/CapacityRule.java`: delete the private `static double round2(double v)` at line 139 and its body; add `import com.workloadhub.forecast.Numbers;`; replace every `round2(` call in the file with `Numbers.round2(`.
2. `run/ForecastRunner.java`: delete the `public static double round2(double v)` at line 66; add the import; replace every `round2(` call with `Numbers.round2(`. **It was public**, so grep the whole module for `ForecastRunner.round2` and redirect those callers too.
3. `facts/FactsBuilder.java`: delete the package-private `round2` at line 358; add the import; replace every call.
4. `backtest/Backtest.java`: delete both `mase` (line 93) and `mae` (line 103); add the import; replace every internal `mae(`/`mase(` call with the `Numbers.` form. `Backtest.mase` was public — grep for `Backtest.mase` and redirect; `eval/Accuracy.java` is the caller the spec names.
5. `eval/Metrics.java`: delete its `mae` and make it delegate, so one definition remains:

```java
    public static double mae(double[] y, double[] p) {
        return com.workloadhub.forecast.Numbers.mae(y, p);
    }
```

Keep `Metrics.bias`, `Metrics.coverage`, `Metrics.weightedQuantileLoss` and `Metrics.overloadPrecisionRecall` exactly as they are.

- [ ] **Step 8: Prove nothing moved**

Run: `cd server && mvn -B -q verify` (600000 ms).
Expected: exit 0. Every existing test still passes — this step changed no behaviour, only where the functions live. If a test fails here, a call was redirected to the wrong function; do not adjust the test.

- [ ] **Step 9: Delete the parity procedure**

```bash
git rm server/tools/parity.sh server/tools/parity_compare.py
git rm -r server/tools/tests
```

`server/tools/translate-schema.py` **stays** — it is not part of parity.

- [ ] **Step 10: Make the gate one step**

In `scripts/check.sh`, delete the whole `uv` block (the `if command -v uv ...` through its `fi`), and change the final guard so it fires only on a missing `mvn`:

```bash
if [ "$ran" -eq 0 ]; then
    echo "gate ran nothing: install mvn"
    exit 1
fi
```

Rewrite the header comment: the gate is the Java module's `mvn verify` alone. Make the same two changes in `scripts/check.ps1` (delete its parity step and its skip branch, correct its header).

In `scripts/release.sh` and `scripts/release.ps1`, drop `uv` from the preconditions so they require `mvn` alone.

In `.github/workflows/ci.yml`, delete the whole `tools:` job (the `astral-sh/setup-uv` step, the pytest run and the release-script self-test line belong to it). Leave the `workflow_dispatch`-only trigger and the comment block explaining the pause exactly as they are: CI stays paused. The `server:` job is untouched.

- [ ] **Step 11: Check the gate still runs**

Run: `bash scripts/check.sh`
Expected: one step, `server (mvn verify)`, OK, and exit 0. It must not print a skip line about `uv`.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "refactor(server): retire parity, one-step gate, one home for the arithmetic" -m "The parity scripts compare Java output against arrival hours, which the retarget is about to change, so they go before the code they measure. round2 had three copies and mae two; Numbers holds one of each, in the root package because eval already depends on backtest."
```

---

### Task 2: The seed agrees with the requirement — an 8.8-hour day and a 44-hour week

Spec section 22.2 item 1, and section 10. `docs/requirements/requirements-v1.md` C1 has always said 44 hours a week, 8.8 a day. The module defaults to 40 and the seed writes 40, so they agree with each other and with nothing else. This task moves all three to 44.

It matters more than a constant usually would. `CapacityRule` prefers a member's own `user_capacity` row over the property, and the seed writes a row for **every member and every employed week**. So changing the property alone would leave every seeded team on 40 and change nothing at all — the spec's first draft claimed otherwise and was wrong.

**Files:**
- Modify: `.../seed/AbsencePlanner.java`, `.../seed/CapacityWriter.java`, `.../ForecastProperties.java`
- Test: `.../seed/AbsencePlannerTest.java`, `.../seed/CapacityWriterTest.java`, `.../capacity/CapacityRuleTest.java`, `.../testing/SeededFacts.java`, and any suite whose assertion moves (find them by running the gate, step 6)

**Interfaces:**
- Consumes: `Numbers.round2` from task 1.
- Produces: `AbsencePlanner.HOURS_PER_DAY = 8.8` and `CapacityWriter.BASE_HOURS = 44.0`, both public constants that later tasks and tests read rather than writing the literal. `ForecastProperties.defaultWeeklyHours` defaults to `44.0`.

- [ ] **Step 1: Write the failing test**

Add to `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/CapacityWriterTest.java`:

```java
    @Test
    void theWeekIsFortyFourHoursAcrossFiveDays() {
        assertEquals(44.0, CapacityWriter.BASE_HOURS, 1e-9);
        assertEquals(8.8, AbsencePlanner.HOURS_PER_DAY, 1e-9);
        assertEquals(CapacityWriter.BASE_HOURS / 5.0, AbsencePlanner.HOURS_PER_DAY, 1e-9,
                "requirement C1: a 44-hour week is 8.8 hours on each of five working days");
    }
```

The third assertion is the one that matters: it ties the two constants together so neither can drift again.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=CapacityWriterTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure — `AbsencePlanner.HOURS_PER_DAY` does not exist.

- [ ] **Step 3: Name the day in `AbsencePlanner` and use it everywhere**

In `.../seed/AbsencePlanner.java`, add the constant to the class and replace all four `8.0` literals with it:

```java
public final class AbsencePlanner {

    /** A present working day, requirement C1: a 44-hour week over five days. */
    public static final double HOURS_PER_DAY = 8.8;

    public record Plan(Set<LocalDate> absentDays, List<LinkedHashMap<String, Object>> absenceRows,
            List<LinkedHashMap<String, Object>> leaveRows, SeedCalendar calendar) {

        public double hoursPresent(Person p, LocalDate day) {
            return calendar.isWorkingDay(day) && p.employedOn(day) && !absentDays.contains(day) ? HOURS_PER_DAY : 0.0;
        }

        public double absenceHours(LocalDate monday) {
            int n = 0;
            for (LocalDate d : calendar.workingDaysOf(monday)) {
                if (absentDays.contains(d)) {
                    n++;
                }
            }
            return HOURS_PER_DAY * n;
        }
    }
```

and in the block that writes the absence and leave rows, `a.put("hours", 8.0)` becomes `a.put("hours", HOURS_PER_DAY)` and `l.put("absence_hours", 8.0 * days.size())` becomes `l.put("absence_hours", HOURS_PER_DAY * days.size())`.

**All four must change together.** A full-day absence is recognised by its hours meeting the day's capacity; leave 8.0 in the rows and an absence day would stop counting as full once the day is 8.8.

- [ ] **Step 4: Move the seed's capacity base**

In `.../seed/CapacityWriter.java`:

```java
    public static final double BASE_HOURS = 44.0;
```

Nothing else in the file changes: `available = BASE_HOURS * cal.workingDays(monday) / 5.0 - absence` already derives the day from the base.

- [ ] **Step 5: Move the module default**

In `.../ForecastProperties.java`, line 11:

```java
    private double defaultWeeklyHours = 44.0;
```

- [ ] **Step 6: Run the test, then find every assertion that moved**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=CapacityWriterTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

Then run the whole suite to find the fallout: `cd server && mvn -B -q verify` (600000 ms).
Expected: **failures**, and that is the point of this step. Roughly 175 to 200 tests stand on seeded data. Write down every failing assertion before changing any of them.

- [ ] **Step 7: Update each failing assertion, and only the assertions**

Work through the list from step 6. For each one, decide which kind it is:

1. **A hardcoded 40 or 8.0 that means "the capacity".** Replace with `CapacityWriter.BASE_HOURS` or `AbsencePlanner.HOURS_PER_DAY`, so it cannot drift again. `.../testing/SeededFacts.java:28` is one of these: `new ForecastRunner(new CapacityRule(40), true)` becomes `new ForecastRunner(new CapacityRule(CapacityWriter.BASE_HOURS), true)`.
2. **A derived number that simply moved** — a total, a mean, an available-hours figure. Recompute it from the new constants and write the new number, with a comment saying what it is derived from.
3. **An assertion that now passes for the wrong reason, or fails in a way the constants do not explain.** Stop. That is a real defect this change surfaced; report it rather than papering over it.

`.../seed/AbsencePlannerTest.java` lines 56 and 58 are kind 1: both `8.0` become `AbsencePlanner.HOURS_PER_DAY`.

Add one case to `.../capacity/CapacityRuleTest.java`, because the property's whole purpose is a member the seed never produces:

```java
    @Test
    void aMemberWithNoCapacityRowFallsBackToFortyFourHours() {
        CapacityRule rule = new CapacityRule(44.0);
        // A member with no user_capacity row at all, in a week with five working days and no absence.
        assertEquals(44.0, rule.capacity(memberWithNoRows, monday, data, cal), 1e-9);
        assertEquals(8.8, rule.dayCapacity(memberWithNoRows, monday, data, cal), 1e-9);
    }
```

**Do not weaken a tolerance to make a test pass.** If an equality now needs a wider epsilon, it is kind 3.

- [ ] **Step 8: Run the gate**

Run: `cd server && mvn -B -q verify` (600000 ms).
Expected: exit 0.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "fix(server): a working week is 44 hours over five 8.8-hour days" -m "Requirement C1 has always said 44; the module defaulted to 40 and the seed wrote 40, so the two agreed with each other and with nothing else. The seed writes a capacity row for every member and week, so moving the property alone would have changed nothing."
```

---

### Task 3: The seed learns weekday habits, logging discipline and overtime

Spec section 22.2 items 2 to 4. Three mechanisms, one new class. Without them the retarget ships measured against data that cannot exhibit the behaviour it exists to describe:

- **no weekday shape**, so the split in task 9 always falls through to its even-split fallback;
- **no logging discipline**, so logged hours are the estimates times one per-member ratio and a retargeted model still learns arrivals in disguise;
- **no overtime**, so predicted demand is capped at exactly capacity and `overload` is arithmetically zero for every seeded member, forever. This is the opposite of the real system: WorkloadHub does not cap logging (ruling 18.6).

**Files:**
- Create: `.../seed/WorkStyle.java`
- Test: `.../seed/WorkStyleTest.java`, `.../seed/WorkStylePropertyTest.java`
- Modify: `.../seed/AbsencePlanner.java`, `.../seed/WorkQueue.java`, `.../seed/SeedGenerator.java`, tests `.../seed/WorkQueueTest.java`, `.../seed/SeedGeneratorTest.java`

**Interfaces:**
- Consumes: `AbsencePlanner.HOURS_PER_DAY` and `CapacityWriter.BASE_HOURS` from task 2; `SeedRandom` (the existing seeded generator).
- Produces: `seed.WorkStyle`, a per-member record drawn once:

```java
public record WorkStyle(double[] weekdayWeights, double discipline, double overtimeChance, double overtimeFactor)
```

with `static WorkStyle draw(SeedRandom rnd)`, `double hoursOn(DayOfWeek dow, double dayHours, SeedRandom rnd)` and `double logged(double worked)`. `WorkQueue` holds one per person.

- [ ] **Step 1: Write the failing unit test**

Create `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkStyleTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import org.junit.jupiter.api.Test;

class WorkStyleTest {

    @Test
    void theWeekdayWeightsAverageToOneSoTheWeekKeepsItsSize() {
        WorkStyle s = WorkStyle.draw(new SeedRandom(1));
        double sum = 0;
        for (double w : s.weekdayWeights()) {
            sum += w;
        }
        assertEquals(5.0, sum, 1e-9, "five weekdays, mean weight 1, so a full week is still five day-lengths");
    }

    @Test
    void twoMembersGetDifferentShapes() {
        WorkStyle a = WorkStyle.draw(new SeedRandom(1));
        WorkStyle b = WorkStyle.draw(new SeedRandom(2));
        assertNotEquals(a.weekdayWeights()[0], b.weekdayWeights()[0]);
    }

    @Test
    void aDrawIsDeterministicForTheSameSeed() {
        assertEquals(WorkStyle.draw(new SeedRandom(7)).discipline(), WorkStyle.draw(new SeedRandom(7)).discipline(), 0.0);
    }

    @Test
    void disciplineNeverInventsHours() {
        WorkStyle s = WorkStyle.draw(new SeedRandom(3));
        assertTrue(s.logged(10.0) <= 10.0 + 1e-9, "a member logs at most what they worked");
        assertTrue(s.logged(10.0) > 0.0);
    }

    @Test
    void aNormalDayIsTheDayLengthTimesItsWeekdayWeight() {
        // overtimeChance 0 means the day is exactly weight x dayHours.
        WorkStyle s = new WorkStyle(new double[] {1.2, 1.1, 1.0, 0.9, 0.8}, 1.0, 0.0, 1.0);
        assertEquals(8.8 * 1.2, s.hoursOn(DayOfWeek.MONDAY, 8.8, new SeedRandom(1)), 1e-9);
        assertEquals(8.8 * 0.8, s.hoursOn(DayOfWeek.FRIDAY, 8.8, new SeedRandom(1)), 1e-9);
    }

    @Test
    void aCertainOvertimeDayRunsPastTheDayLength() {
        WorkStyle s = new WorkStyle(new double[] {1, 1, 1, 1, 1}, 1.0, 1.0, 1.5);
        assertTrue(s.hoursOn(DayOfWeek.MONDAY, 8.8, new SeedRandom(1)) > 8.8);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=WorkStyleTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure — `WorkStyle` does not exist.

- [ ] **Step 3: Write `WorkStyle`**

Create `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/WorkStyle.java`:

```java
package com.workloadhub.forecast.seed;

import java.time.DayOfWeek;

/**
 * How one member works and records it: the shape of their week, how much of what they work they log, and how
 * often they run long.
 *
 * <p>Drawn once per member and fixed for the whole history, the way {@code ratio} in {@link WorkQueue} is.
 * The three are deliberately independent of the estimate ratio: estimation bias and logging discipline are
 * different causes of the same visible gap, and a forecast of logged hours can only be told apart from a
 * forecast of estimates when they can move separately.
 *
 * @param weekdayWeights Monday to Friday, mean 1, so a full week is still five day-lengths
 * @param discipline the fraction of worked hours the member actually records
 * @param overtimeChance the probability that a given day runs long
 * @param overtimeFactor how much longer such a day runs
 */
public record WorkStyle(double[] weekdayWeights, double discipline, double overtimeChance, double overtimeFactor) {

    /** A quiet Friday is worth about three quarters of a busy Monday; beyond that the shape stops being credible. */
    private static final double WEEKDAY_SIGMA = 0.18;
    private static final double DISCIPLINE_MIN = 0.70;
    private static final double DISCIPLINE_MAX = 1.00;
    /** One member in six runs long often; the rest rarely. */
    private static final double HEAVY_SHARE = 0.17;

    public static WorkStyle draw(SeedRandom rnd) {
        double[] w = new double[5];
        double sum = 0;
        for (int i = 0; i < 5; i++) {
            w[i] = Math.max(0.4, 1.0 + WEEKDAY_SIGMA * rnd.gaussian());
            sum += w[i];
        }
        for (int i = 0; i < 5; i++) {
            w[i] = w[i] * 5.0 / sum;
        }
        double discipline = Math.max(DISCIPLINE_MIN, Math.min(DISCIPLINE_MAX, 0.95 + 0.08 * rnd.gaussian()));
        boolean heavy = rnd.chance(HEAVY_SHARE);
        return new WorkStyle(w, discipline, heavy ? 0.30 : 0.06, heavy ? 1.35 : 1.20);
    }

    /** The hours this member works on one present day, before any discipline is applied. */
    public double hoursOn(DayOfWeek dow, double dayHours, SeedRandom rnd) {
        double base = dayHours * weekdayWeights[dow.getValue() - 1];
        return rnd.chance(overtimeChance) ? base * overtimeFactor : base;
    }

    /** The hours the member records, of the hours they worked. */
    public double logged(double worked) {
        return worked * discipline;
    }
}
```

Check `SeedRandom` really offers `gaussian()` and `chance(double)` with these names before writing this; task 1's grep of `Rhythm.java` shows both in use. If a name differs, use the real one and keep the semantics.

- [ ] **Step 4: Run the test and watch it pass**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=WorkStyleTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 5: Write the property test**

Create `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkStylePropertyTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

class WorkStylePropertyTest {

    @Property(tries = 200)
    void everyDrawKeepsTheWeekTheSameSize(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        double[] w = WorkStyle.draw(new SeedRandom(seed)).weekdayWeights();
        double sum = 0;
        for (double v : w) {
            sum += v;
            assertTrue(v > 0.0, "a weekday weight is never zero or negative");
        }
        assertEquals(5.0, sum, 1e-9);
    }

    @Property(tries = 200)
    void disciplineIsAFractionOfWhatWasWorked(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        WorkStyle s = WorkStyle.draw(new SeedRandom(seed));
        assertTrue(s.discipline() > 0.0 && s.discipline() <= 1.0);
        assertTrue(s.logged(8.8) <= 8.8 + 1e-9);
    }

    @Property(tries = 200)
    void everyMemberCanRunLongAndNoneAlways(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        WorkStyle s = WorkStyle.draw(new SeedRandom(seed));
        assertTrue(s.overtimeChance() > 0.0, "overload must be reachable for every member");
        assertTrue(s.overtimeChance() < 1.0, "a member who always runs long is not a member, it is a constant");
        assertTrue(s.overtimeFactor() > 1.0);
    }
}
```

- [ ] **Step 6: Run it**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest='WorkStyleTest,WorkStylePropertyTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 7: Give each person a style and spend their day through it**

In `.../seed/WorkQueue.java`, inside `simulate(Person p)`, draw the style next to the existing `ratio` draw (about line 243) so the seeded order stays fixed:

```java
        double ratio = Math.max(0.6, Math.min(1.6, rnd.lognormal(1.0, 0.25)));
        WorkStyle style = WorkStyle.draw(rnd);
```

Then, in the day loop (about line 252), the day's budget becomes the style's day rather than the flat presence:

```java
            double present = plan.hoursPresent(p, day);
            double hours = present > 0 ? style.hoursOn(day.getDayOfWeek(), present, rnd) : 0.0;
            boolean present_ = hours > 0;
```

Keep the existing variable names if they read better; what must be true is that `hours` is now the style's day and the `present` test still asks whether the member is there at all.

Finally, in `logDay(Person p, Deque<Work> queue, LocalDate day, double hours)`, the hours **worked** drive the queue exactly as today, but the row written records only what the member logs. At the line that adds the row:

```java
            timeLogRows.add(Rows.timeLog(rnd.uuid(), w.id, p.id(), Numbers.round2(style.logged(give)), day, "Work on " + w.row.get("key")));
```

`logDay` needs the style, so give it a parameter and pass it from `simulate`. **`w.logged += give` stays as it is**: the task still progresses by the hours worked, not by the hours recorded. That asymmetry is the whole point — it is what makes a member who works a full week and logs less than they worked, which the forecast then has to live with.

- [ ] **Step 8: Pin the three behaviours on a generated database**

Add to `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkQueueTest.java`:

```java
    @Test
    void someMemberLogsMoreThanADayAndMoreThanAWeek() {
        SeedConfig cfg = new SeedConfig(30, LocalDate.of(2026, 9, 4), 11, true, 36);
        // build the dataset the way the other tests in this class do, then:
        Map<UUID, Map<LocalDate, Double>> byMemberDay = new HashMap<>();
        for (LinkedHashMap<String, Object> row : result.timeLogRows()) {
            UUID u = UUID.fromString((String) row.get("user_id"));
            LocalDate d = LocalDate.parse((String) row.get("day"));
            byMemberDay.computeIfAbsent(u, k -> new HashMap<>()).merge(d, (Double) row.get("hours"), Double::sum);
        }
        boolean anyLongDay = byMemberDay.values().stream().flatMap(m -> m.values().stream())
                .anyMatch(h -> h > AbsencePlanner.HOURS_PER_DAY);
        assertTrue(anyLongDay, "no seeded member ever runs long, so overload can never fire");
    }

    @Test
    void aMembersWeekHasAShape() {
        // Over a long history one member's logged hours are not the same on every weekday.
        // Pick the member with the most rows, bucket their hours by DayOfWeek, and assert the
        // largest bucket is at least 15% above the smallest.
    }

    @Test
    void loggedHoursFallShortOfWorkedHoursForSomeone() {
        // Sum a member's time_logs against the sum of `actual` on their finished tasks;
        // for at least one member the logs are the smaller number.
    }
```

Replace each comment with the real assertion, following the shape of the first. Read the existing tests in the class first: they already build a `WorkQueue.Result` and name its accessors, so reuse that setup rather than inventing one.

- [ ] **Step 9: Run them and watch them fail, then pass**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=WorkQueueTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected before step 7's edits: FAIL on all three. After: PASS.

There is an existing assertion in this class, at about line 340, that logged hours never exceed presence. **It is now wrong** and must be changed, not deleted: the bound becomes presence times the largest overtime factor. Keep a bound — an unbounded day would hide a real bug.

- [ ] **Step 10: Run the gate and absorb the fallout**

Run: `cd server && mvn -B -q verify` (600000 ms).
Expected: failures again, in the same way task 2 produced them, because every seeded number has moved. Work through them with the same three-way test from task 2 step 7. `SeedGeneratorTest`'s determinism properties must still pass unchanged — if one fails, the new draws were not made in a fixed order and that is a real defect.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat(seed): weekday habits, logging discipline and overtime" -m "A seeded member worked a flat eight-hour day and logged every hour of it, so a logged-hours forecast could never predict above capacity, had no weekday shape to learn, and saw logged hours that were the estimates in disguise. The day now follows a per-member shape, can run long, and is recorded through a discipline factor drawn independently of the estimate ratio."
```

---

### Task 4: The target becomes logged hours

Spec sections 3 and 3.1, the heart of the change. Today the booster predicts **fresh estimated arrival hours** — `FeatureBuilder` writes `fresh[i + h]` into `target_h{h}` — while `accuracy()` scores logged hours. This task makes them the same quantity. Arrivals do not disappear; they stop being the thing predicted and become explanatory columns under an honest name.

**Files:**
- Create: test `.../features/RetargetTest.java`
- Modify: `.../features/WeeklySeries.java`, `.../features/Features.java`, `.../features/FeatureBuilder.java`, `.../eval/Truth.java`
- Test: `.../features/WeeklySeriesTest.java`, `.../features/FeaturesTest.java`, `.../features/FeatureBuilderTest.java`, `.../features/SyntheticMatrix.java`, `.../eval/TruthTest.java`

**Interfaces:**
- Consumes: `seed.WorkStyle` from task 3 only through the seeded fixtures; nothing at the type level.
- Produces: `WeeklySeries.build(Lifecycle lc, ForecastData data, List<MemberRow> members, List<LocalDate> weeks)` — **one more parameter than today** — and `double[] WeeklySeries.logged(UUID member)`. `Features.FRESH` and `Features.EST` are gone; the shared columns gain `arrival_hrs_lag1..4` and lose `logged_hours_lag1..4`. Task 8 changes `Features.HORIZONS`; this task leaves it alone.

**The one place a cycle could form:** `eval` already depends on `features` (the harness uses `FeatureMatrix`), so the weekly sum lives in `features/WeeklySeries` and `eval/Truth.realisedHours` delegates to it. Never the other way round.

- [ ] **Step 1: Write the failing test for the series**

Add to `server/forecast-core/src/test/java/com/workloadhub/forecast/features/WeeklySeriesTest.java`:

```java
    @Test
    void loggedHoursArePerMemberAndWeekAndZeroWhenNothingWasLogged() {
        // Build the same Lifecycle and ForecastData this class already builds, then:
        WeeklySeries s = WeeklySeries.build(lc, data, members, weeks);
        double[] logged = s.logged(member.id());
        assertEquals(weeks.size(), logged.length);
        for (double h : logged) {
            assertFalse(Double.isNaN(h), "a week with no logs is 0.0, never NaN");
            assertTrue(h >= 0.0);
        }
        double fromSeries = 0;
        for (double h : logged) {
            fromSeries += h;
        }
        double fromTimeLogs = data.timeLogs().stream()
                .filter(l -> l.userId().equals(member.id()))
                .filter(l -> weeks.contains(Weeks.mondayOf(l.day())))
                .mapToDouble(TimeLogRow::hours).sum();
        assertEquals(fromTimeLogs, fromSeries, 1e-6, "the series is the time logs, bucketed by Monday");
    }

    @Test
    void theSeriesAgreesWithTruth() {
        WeeklySeries s = WeeklySeries.build(lc, data, members, weeks);
        SortedMap<MemberWeek, Double> truth = Truth.realisedHours(data);
        for (int i = 0; i < weeks.size(); i++) {
            double expected = truth.getOrDefault(new MemberWeek(member.id(), weeks.get(i)), 0.0);
            assertEquals(expected, s.logged(member.id())[i], 1e-6);
        }
    }
```

Read the existing tests in this class first and reuse their fixture setup rather than inventing one; they already have `lc`, `members` and `weeks` in scope under some name.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=WeeklySeriesTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure — `build` takes three arguments, and `logged` does not exist.

- [ ] **Step 3: Teach `WeeklySeries` the time logs**

In `.../features/WeeklySeries.java`: add `import com.workloadhub.forecast.data.ForecastData;`, `import com.workloadhub.forecast.data.rows.TimeLogRow;` and `import com.workloadhub.forecast.calendar.Weeks;`. Add a second map beside `cells`, built from the logs:

```java
    private final Map<MemberWeek, Cell> cells;
    private final Map<MemberWeek, Double> logged;

    private WeeklySeries(List<MemberRow> members, List<LocalDate> weeks, Map<MemberWeek, Cell> cells, Map<MemberWeek, Double> logged) {
        this.members = List.copyOf(members);
        this.weeks = List.copyOf(weeks);
        this.cells = Map.copyOf(cells);
        this.logged = Map.copyOf(logged);
    }

    public static WeeklySeries build(Lifecycle lc, ForecastData data, List<MemberRow> members, List<LocalDate> weeks) {
        Set<UUID> ids = members.stream().map(MemberRow::id).collect(Collectors.toSet());
        Set<LocalDate> inRange = Set.copyOf(weeks);
        Map<MemberWeek, Cell> cells = new HashMap<>();
        for (TaskFacts f : lc.all()) {
            if (!f.isAssigned() || !ids.contains(f.assignee())) {
                continue;
            }
            LocalDate week = f.assignedWeek();
            if (!inRange.contains(week)) {
                continue;
            }
            cells.merge(new MemberWeek(f.assignee(), week), Cell.ZERO.plus(f), (a, b) -> a.plus(f));
        }
        Map<MemberWeek, Double> logged = new HashMap<>();
        for (TimeLogRow l : data.timeLogs()) {
            if (!ids.contains(l.userId())) {
                continue;
            }
            LocalDate week = Weeks.mondayOf(l.day());
            if (!inRange.contains(week)) {
                continue;
            }
            logged.merge(new MemberWeek(l.userId(), week), l.hours(), Double::sum);
        }
        logged.replaceAll((k, v) -> Math.round(v * 1e6) / 1e6);
        return new WeeklySeries(members, weeks, cells, logged);
    }

    /** Hours this member logged in each week, 0.0 where they logged none: the forecast's target. */
    public double[] logged(UUID member) {
        return weeks.stream().mapToDouble(w -> logged.getOrDefault(new MemberWeek(member, w), 0.0)).toArray();
    }
```

The `replaceAll` line reproduces `Truth`'s rounding exactly, so the two cannot disagree in the sixth decimal. **No day filter**: by ruling 18.4 every logged hour already falls on a working day.

Update the class comment: it says "Arrivals per member and week" and now also carries the target.

- [ ] **Step 4: Make `Truth` delegate**

In `.../eval/Truth.java`, `realisedHours` becomes a thin wrapper so one summation exists:

```java
    public static SortedMap<MemberWeek, Double> realisedHours(ForecastData data) {
        SortedMap<MemberWeek, Double> out = new TreeMap<>();
        for (TimeLogRow l : data.timeLogs()) {
            out.merge(new MemberWeek(l.userId(), Weeks.mondayOf(l.day())), l.hours(), Double::sum);
        }
        out.replaceAll((k, v) -> Math.round(v * 1e6) / 1e6);
        return out;
    }
```

That is what it already does, and it stays. `WeeklySeries` is scoped to a member list and a week range, so it cannot serve `Truth`'s "every member, every week" contract without building a throwaway instance. **The single definition is the arithmetic, not the method**: both bucket by `Weeks.mondayOf` and round to six decimals, and `WeeklySeriesTest.theSeriesAgreesWithTruth` from step 1 is what holds them together. Leave a comment in each pointing at the other and at that test.

`realisedHoursByDay` is unchanged.

- [ ] **Step 5: Run the series tests**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest='WeeklySeriesTest,TruthTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 6: Write the regression test that proves the retarget happened**

This is the most important test in the plan. Without it nothing distinguishes a forecast of logged hours from a forecast of estimates. Create `server/forecast-core/src/test/java/com/workloadhub/forecast/features/RetargetTest.java`:

```java
package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A member whose logged hours and assigned estimates deliberately diverge: they are assigned tasks
 * estimated at 20 hours a week and log 10. The target must follow the 10, not the 20.
 */
class RetargetTest {

    @Test
    void theTargetTracksLoggedHoursNotEstimates() {
        // Build a small fixture by hand, not from the seed: one member, eight weeks,
        // each week one task estimated at 20.0 assigned on the Monday, and time_logs
        // totalling 10.0 for that member in that week.
        WeeklySeries s = WeeklySeries.build(lc, data, members, weeks);
        double[] logged = s.logged(member.id());
        double[] fresh = s.fresh(member.id());
        for (int i = 0; i < weeks.size(); i++) {
            assertEquals(10.0, logged[i], 1e-6, "week " + weeks.get(i));
            assertEquals(20.0, fresh[i], 1e-6, "the arrival series still reports the estimates");
        }

        FeatureMatrix m = new FeatureBuilder(data, lc, cal, rule).build(members, origin);
        int row = m.keys().indexOf(new MemberWeek(member.id(), origin.minusWeeks(4)));
        assertTrue(row >= 0);
        assertEquals(10.0, m.get(row, Features.target(1)), 1e-6,
                "target_h1 is the logged hours of the following week, not its estimates");
        assertEquals(20.0, m.get(row, "arrival_hrs_lag1"), 1e-6,
                "the estimates are still available, as an explanatory column");
    }
}
```

Replace the first comment with the real fixture. `FeatureMatrix`'s accessors are `get(int row, String column)`, `keys()`, `key(int)`, `columnIndex(String)`, `column(String)`, `target(int)`, `rowCount()` and `columns()` — there is no `values()` or `index()`.

- [ ] **Step 7: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=RetargetTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `target_h1` is 20.0 (the estimates), and `arrival_hrs_lag1` does not exist.

- [ ] **Step 8: Swap the columns in `Features`**

In `.../features/Features.java`, inside `build()`, delete the four `logged_hours_lag` columns and add the four arrival ones in their place:

```java
        for (int k = 1; k <= 4; k++) {
            c.add("arrival_hrs_lag" + k);
        }
```

Delete the two constants and their use in `allColumns()`:

```java
    public static final String FRESH = "fresh_hours";
    public static final String EST = "est_hours";
```

and the two lines `c.add(FRESH); c.add(EST);` at the end of `allColumns()`.

The shared count stays 42: four out, four in. `Features.EST` is already dead — written and read by nothing — so it costs nothing.

`Features.FRESH` was read only by `SeasonalNaive` (at about line 25), which task 5 deletes. **Repoint it at `"lag1"` in this task**, with a one-line comment saying the class is deleted in task 5, so this task ends with the module still compiling as far as the model package. Do not leave the compile error standing for a task and a half: the alternative buys nothing and hides any other break behind it.

Add the comment the next reader needs, above `LAGS`:

```java
    /**
     * Note the off-by-one, which is deliberate and load-bearing: {@code ownHistory} computes
     * {@code j = i - (lag - 1)}, so {@code lag1} is the row's OWN week, not the week before it. It is
     * leakage-safe because every target is a strictly later week. {@code arrival_hrs_lag1..4} follow the same
     * convention so that one meaning of "lag 1" holds across the whole matrix.
     */
    public static final int[] LAGS = {1, 2, 3, 4, 8, 13};
```

- [ ] **Step 9: Point the builder at the logged series**

In `.../features/FeatureBuilder.java`:

1. `build(...)` calls the new signature: `WeeklySeries series = WeeklySeries.build(lc, data, members, weeks);`
2. In the member loop, add the logged array beside the two it already has:

```java
            double[] fresh = series.fresh(m.id());
            double[] est = series.est(m.id());
            double[] logged = series.logged(m.id());
```

3. `ownHistory` now reads the **logged** series: `ownHistory(r, col, logged, start, i);`. The method body does not change at all — it takes the array as a parameter. Rename its parameter from `f` to `series` if it helps, and change its javadoc to say it describes the target's own history.
4. The target write becomes:

```java
                    r[col.get(Features.target(h))] = i + h <= originIndex ? logged[i + h] : Double.NaN;
```

5. `weeks_since_last_arrival` must keep reading **arrivals**, because it is about arrivals. `ownHistory` computes it from the array it was given, which is now the logged one. Move that block out of `ownHistory` into its own small private method taking `fresh`, and call it from the row loop:

```java
                ownHistory(r, col, logged, start, i);
                weeksSinceLastArrival(r, col, fresh, start, i);
```

6. In `throughput`, delete the four `logged_hours_lag` writes and add the arrival ones in their place. `throughput` does not have the series, so pass it: `throughput(r, col, mc, w, fresh, start, i)` and

```java
        for (int k = 1; k <= 4; k++) {
            int j = i - (k - 1);
            r[col.get("arrival_hrs_lag" + k)] = j >= start ? f[j] : Double.NaN;
        }
```

Same off-by-one as `ownHistory`, deliberately.

7. Delete the two lines writing `Features.FRESH` and `Features.EST`. `est` is now unused in the loop — delete its declaration too.

- [ ] **Step 10: Run the regression test and watch it pass**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=RetargetTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS. If `target_h1` still reads 20.0, step 9 item 4 did not take.

- [ ] **Step 11: Update the column tests and the synthetic matrix**

`.../features/FeaturesTest.java`: the column list changed names, not size. Assert the shared count is still 42, that `arrival_hrs_lag1..4` are present, and that `logged_hours_lag1`, `fresh_hours` and `est_hours` are absent.

`.../features/SyntheticMatrix.java` writes `Features.FRESH`; delete those writes. If it needs a value for `arrival_hrs_lag1`, plant the same signal it planted before.

`.../features/FeatureBuilderTest.java` and `.../features/FeatureLeakageTest.java`: update any assertion naming a removed column. **The leakage test is the one to read most carefully** — it exists to prove no column at row *i* can see week *i + h*, and the target has just changed. If it passes without modification, confirm by hand that it is actually exercising the new target before believing it.

- [ ] **Step 12: Run the gate**

Run: `cd server && mvn -B -q verify` (600000 ms).
Expected: failures in the model and backtest suites, which task 5 fixes, and **nothing else**. If a facts or store test fails here, a column name leaked into a place this task did not mean to touch. Do not fix model or backtest failures here; note them and carry them into task 5.

At this point the module does not build cleanly end to end. That is expected and is why tasks 4 and 5 are adjacent. Commit anyway: the history should show the retarget as its own change.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat(server): forecast the hours members log, not the hours assigned to them" -m "The booster trained on fresh estimated arrival hours while accuracy() scored logged hours, so training and measurement were different quantities. The target, the lags and the rolling windows now read the logged series; the arrival series stays as arrival_hrs_lag1..4, explanatory rather than predicted."
```

---

### Task 5: Delete the bridge, and let the run predict hours directly

Spec sections 6, 7 and 9. Everything deleted here existed for one reason: the booster predicted estimated arrival hours and something had to turn those into hours worked. `EffortModel` learned an estimate-to-actual ratio and spread it over days; `PlannedWork` pushed unassigned backlog through the same converter; `HourPlacement` did the spreading. Task 4 removed the reason. This task removes the machinery, and with it the model tournament the single-model design already closed.

This is the largest task in the plan. Work through it in the step order given: the deletions compile-break a lot, and the order keeps the breakage in one place at a time.

**Files:**
- Create: `.../model/XgboostHours.java`, test `.../model/XgboostHoursTest.java`
- Modify: `.../backtest/Backtest.java`, `.../run/ForecastRunner.java`, `.../run/Prepared.java`, `.../run/TeamOutcome.java`, tests `.../backtest/BacktestTest.java`, `.../run/ForecastRunnerTest.java`, `.../run/ForecastRunnerPropertyTest.java`
- Delete: `.../model/EffortModel.java`, `.../model/SeasonalNaive.java`, `.../model/ArrivalModel.java`, `.../model/XgboostArrival.java`, `.../run/ModelRegistry.java`, the whole `.../planned/` package, `.../calendar/HourPlacement.java`, and the suites `EffortModelTest`, `EffortModelPropertyTest`, `PlannedWorkTest`, `PlannedWorkPropertyTest`, `HourPlacementTest`, `SeasonalNaiveTest`, `ModelRegistryTest`, `XgboostArrivalTest`

**Interfaces:**
- Consumes: `Numbers.round2` and `Numbers.mae` (task 1); the logged target (task 4).
- Produces:
  - `model.XgboostHours` — a plain class, implementing nothing, with `void fit(FeatureMatrix train, int[] horizons)`, `double[] predict(FeatureMatrix rows, int horizon)` and whatever `AutoCloseable` handling `XgboostArrival` has today. It still throws `ModelUnavailable` from `fit` and `predict`.
  - `Backtest.run(FeatureMatrix feat, List<LocalDate> origins, int[] horizons) -> Result` — **no factories map**.
  - `Backtest.Score(LocalDate origin, int horizon, double mae)` and `Backtest.Result(List<Score> scores, Map<Integer, double[]> residuals, Map<Integer, List<Residual>> residualRows, double seconds, double meanActualHours)` with one method, `double meanMae()`. `Residual(LocalDate origin, double y, double residual)` is unchanged. Note the shape change: today both maps are keyed by **model then horizon** and `Result` exposes `residuals(String model, int h)` and `residualRows(String model, int h)`; with one model there is no outer key, so both accessors lose their first argument.
  - `ForecastRunner.band(double demand, double q10, double q90, double capacity) -> Band(double demand, double low, double high, double overload)`.
  - `ForecastRunner(CapacityRule capacityRule)` — the `boolean plannedWorkDefault` argument goes with `PlannedWork`. Task 8 adds an `int windows` argument to the same constructor.
  - `ForecastRunner.prepare(ForecastData data, LocalDate asOf, ProgressListener progress)` — the `String forcedModel` argument goes.
  - `ForecastRunner.forTeam(Prepared p, UUID teamId)` — the `Boolean plannedWork` argument goes.
  - `Prepared` with 15 components (below); `TeamOutcome` with 5.

- [ ] **Step 1: Write the failing test for the new band**

Add to `server/forecast-core/src/test/java/com/workloadhub/forecast/run/ForecastRunnerTest.java`:

```java
    @Test
    void theBandIsDemandWithItsResidualOffsets() {
        ForecastRunner.Band b = ForecastRunner.band(30.0, -3.1, 4.0, 44.0);
        assertEquals(30.0, b.demand(), 1e-9);
        assertEquals(26.9, b.low(), 1e-9);
        assertEquals(34.0, b.high(), 1e-9);
        assertEquals(0.0, b.overload(), 1e-9, "30 hours against 44 of capacity is not overload");
    }

    @Test
    void overloadIsWhatDemandExceedsCapacityBy() {
        assertEquals(6.0, ForecastRunner.band(50.0, -3.0, 3.0, 44.0).overload(), 1e-9);
    }

    @Test
    void theLowEndNeverGoesNegative() {
        assertEquals(0.0, ForecastRunner.band(2.0, -9.0, 1.0, 44.0).low(), 1e-9,
                "a member cannot log negative hours");
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=ForecastRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure — `band` takes seven arguments and `Band` has seven components.

- [ ] **Step 3: Collapse the band**

In `.../run/ForecastRunner.java`, replace the record and the method:

```java
    /** A window's predicted hours with the interval the backtest residuals give it, and what it exceeds capacity by. */
    public record Band(double demand, double low, double high, double overload) {
    }

    public static Band band(double demand, double q10, double q90, double capacity) {
        double d = Numbers.round2(Math.max(0.0, demand));
        return new Band(d, Numbers.round2(Math.max(0.0, d + q10)), Numbers.round2(d + q90),
                Numbers.round2(Math.max(0.0, d - capacity)));
    }
```

`q10 <= 0 <= q90` is guaranteed by the caller, which already clamps the offsets, so `low <= demand <= high` holds. `low` is floored at zero because a member cannot log negative hours; `high` is not, because it cannot go below `demand`.

- [ ] **Step 4: Run the band tests**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=ForecastRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: the three band tests PASS. Other tests in the class will still fail to compile; that is step 8.

- [ ] **Step 5: Write the failing test for the tournament-free backtest**

Rewrite `server/forecast-core/src/test/java/com/workloadhub/forecast/backtest/BacktestTest.java`'s core case, and **delete** its champion-selection, forced-model and flaky-model cases outright:

```java
    @Test
    void oneScorePerOriginAndHorizon() {
        FeatureMatrix m = SyntheticMatrix.plantedSignal();
        List<LocalDate> origins = List.of(ORIGIN_A, ORIGIN_B);
        Backtest.Result r = Backtest.run(m, origins, new int[] {1, 2, 3});
        assertEquals(6, r.scores().size(), "two origins by three horizons, no model dimension");
        for (Backtest.Score s : r.scores()) {
            assertTrue(s.mae() >= 0.0, "MAE is in hours and never negative");
        }
        assertTrue(r.meanMae() >= 0.0);
        assertTrue(r.meanActualHours() > 0.0, "the mean of the backtest targets, for scale beside the MAE");
    }

    @Test
    void aHorizonWithoutItsTargetColumnFailsByName() {
        FeatureMatrix narrow = SyntheticMatrix.plantedSignal();  // built for horizons 1..3
        ForecastException e = assertThrows(ForecastException.class,
                () -> Backtest.run(narrow, List.of(ORIGIN_A), new int[] {1, 7}));
        assertTrue(e.getMessage().contains("target_h7"), "the message names the horizon that is missing");
    }
```

The second test is the guard task 8 relies on: once the window count is configurable, a matrix built at one count must not be silently read at another.

- [ ] **Step 6: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=BacktestTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure — `run` takes four arguments and `Score` has five components.

- [ ] **Step 7: Make the backtest a measurement, not a tournament**

In `.../backtest/Backtest.java`:

1. Delete `FLOOR`, the `Champion` record, `selectChampion`, `meanMase` and `meanMaseByModel`. `mase` already moved to `Numbers` in task 1.
2. The records become:

```java
    public record Score(LocalDate origin, int horizon, double mae) {
    }

    public record Result(List<Score> scores, Map<Integer, double[]> residuals, Map<Integer, Integer> residualRows,
            double seconds, double meanActualHours) {

        public double meanMae() {
            double s = 0;
            int n = 0;
            for (Score sc : scores) {
                if (!Double.isNaN(sc.mae())) {
                    s += sc.mae();
                    n++;
                }
            }
            return n == 0 ? Double.NaN : s / n;
        }
    }
```

`residuals` and `residualRows` are keyed by **horizon** alone now, not by model then horizon. `meanActualHours` is the mean of the backtest targets in logged hours — the average week a member of this team actually logs — and is a record component because it is computed while the targets are in hand.

3. `run` loses the factories:

```java
    public static Result run(FeatureMatrix feat, List<LocalDate> origins, int[] horizons) {
```

Inside, delete the `withFloor` construction and the whole per-model loop; fit **one** `XgboostHours` per origin. Delete the extra `new SeasonalNaive().fit(train, horizons)` that existed only as the MASE denominator. Before the loop, validate the matrix:

```java
        for (int h : horizons) {
            if (!feat.columns().contains(Features.target(h))) {
                throw ForecastException.of("INVALID_REQUEST",
                        "the feature matrix has no " + Features.target(h) + ": it was built for a different window count");
            }
        }
```

Check `FeatureMatrix`'s real accessor for its column list before writing that line.

4. Scores are `new Score(origin, h, Numbers.mae(y, yHat))`. The `NaN`-target skip for a horizon at an origin stays exactly as it is.

- [ ] **Step 8: Run the backtest tests**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=BacktestTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 9: Rename the booster**

```bash
git mv server/forecast-core/src/main/java/com/workloadhub/forecast/model/XgboostArrival.java \
       server/forecast-core/src/main/java/com/workloadhub/forecast/model/XgboostHours.java
git mv server/forecast-core/src/test/java/com/workloadhub/forecast/model/XgboostArrivalTest.java \
       server/forecast-core/src/test/java/com/workloadhub/forecast/model/XgboostHoursTest.java
```

Rename the class and constructor inside, drop `implements ArrivalModel`, and keep every method signature, the `AutoCloseable` handling and the `ModelUnavailable` throws exactly as they are. Update the class javadoc: it forecasts logged hours per member-week, one Poisson booster per horizon.

In `XgboostHoursTest`, the comparison at about line 69 is against `SeasonalNaive`, which is being deleted. Replace it with a two-line baseline computed in the test — each member's mean of the training target — that the booster must beat on the planted-signal fixture:

```java
    private static double[] meanBaseline(FeatureMatrix train, FeatureMatrix rows, int horizon) {
        double sum = 0;
        int n = 0;
        double[] targets = train.target(horizon);
        for (int r = 0; r < targets.length; r++) {
            double t = targets[r];
            if (!Double.isNaN(t)) {
                sum += t;
                n++;
            }
        }
        double mean = n == 0 ? 0.0 : sum / n;
        double[] out = new double[rows.rowCount()];
        java.util.Arrays.fill(out, mean);
        return out;
    }
```

Same protection, no production class to keep alive for it.

- [ ] **Step 10: Delete the bridge**

```bash
git rm server/forecast-core/src/main/java/com/workloadhub/forecast/model/EffortModel.java
git rm server/forecast-core/src/main/java/com/workloadhub/forecast/model/SeasonalNaive.java
git rm server/forecast-core/src/main/java/com/workloadhub/forecast/model/ArrivalModel.java
git rm server/forecast-core/src/main/java/com/workloadhub/forecast/run/ModelRegistry.java
git rm server/forecast-core/src/main/java/com/workloadhub/forecast/calendar/HourPlacement.java
git rm -r server/forecast-core/src/main/java/com/workloadhub/forecast/planned
git rm server/forecast-core/src/test/java/com/workloadhub/forecast/model/EffortModelTest.java
git rm server/forecast-core/src/test/java/com/workloadhub/forecast/model/EffortModelPropertyTest.java
git rm server/forecast-core/src/test/java/com/workloadhub/forecast/model/SeasonalNaiveTest.java
git rm server/forecast-core/src/test/java/com/workloadhub/forecast/run/ModelRegistryTest.java
git rm server/forecast-core/src/test/java/com/workloadhub/forecast/calendar/HourPlacementTest.java
git rm -r server/forecast-core/src/test/java/com/workloadhub/forecast/planned
```

`model/ModelUnavailable.java` **stays** — `XgboostHours` still throws it. Check each test path exists before running the command; adjust to the real names.

**`CapacityRule.offDays` is not deleted**, though its only callers were the two placements just removed. Ruling 18.4 and task 9 need it: it is the only producer of a member's full-absence days, and without it predicted hours land on days with zero capacity and read as pure overload. Leave it, and add a comment saying task 9's weekday split is its caller. `CapacityRule.FULL_DAY_HOURS` **is** deleted; replace its use inside `offDays` with a comparison against that day's own capacity:

```java
    public static Set<LocalDate> offDays(UUID member, ForecastData data) {
        // A full day off is one whose absence hours meet that day's capacity. A fixed 8.0 was wrong the moment
        // the working day became 8.8 (requirement C1).
```

- [ ] **Step 11: Reshape the two run records**

`.../run/Prepared.java` — 15 components, losing `champion`, `championMase`, `forcedModel` and `effort`, gaining `mae` and `meanActualHours`, with `predictedEst` renamed:

```java
public record Prepared(
        ForecastData data,
        Lifecycle lifecycle,
        WorkingCalendar calendar,
        LocalDate asOf,
        LocalDate origin,
        List<ForecastWindow> windows,
        int[] horizons,
        FeatureMatrix features,
        List<LocalDate> backtestOrigins,
        Backtest.Result backtest,
        Double mae,
        Double meanActualHours,
        Map<Integer, double[]> bandOffsets,
        Map<MemberWeek, Double> predictedHours,
        int historyWeeks,
        Map<String, Double> secondsByPhase) {
}
```

`mae` and `meanActualHours` are boxed `Double` because a thin-history run has no scored origin and reports null for both.

`.../run/TeamOutcome.java` — five components:

```java
public record TeamOutcome(
        Prepared prepared,
        UUID teamId,
        List<MemberRow> members,
        List<MemberWindowForecast> memberWindows,
        List<MemberDayForecast> memberDays) {
}
```

Delete the `PlannedWork` import from both.

- [ ] **Step 12: Simplify `prepare`**

In `ForecastRunner.prepare`, delete the `forcedModel` parameter, the `ModelRegistry.factories` call, the whole champion block and the `EffortModel.fit` call. What remains:

```java
    public Prepared prepare(ForecastData data, LocalDate asOf, ProgressListener progress) {
        ...
        Backtest.Result backtest = Backtest.run(features, origins, horizons);
        Double mae = origins.isEmpty() ? null : backtest.meanMae();
        Double meanActual = origins.isEmpty() ? null : backtest.meanActualHours();
        ...
        progress.phase("FORECAST", 60, "fitting the booster and predicting");
        Map<Integer, double[]> offsets = new TreeMap<>();
        for (int h : horizons) {
            double[] q = Backtest.intervalBounds(backtest.residuals().getOrDefault(h, new double[0]));
            offsets.put(h, new double[] {Math.min(0.0, q[0]), Math.max(0.0, q[1])});
        }
```

A thin-history run reaches `intervalBounds` with no residuals and must come back `{0, 0}`, so the band has zero width and `interval.basis` reads `"none: no scored origins"` in the facts (task 7). Check `intervalBounds` already does that on an empty array; if it does not, make it, and add a test.

The prediction block keeps its shape: `new XgboostHours()` in place of `ModelRegistry.create(champion)`, the `AutoCloseable` handling unchanged, and `predicted` renamed to feed `predictedHours`.

- [ ] **Step 13: Make the team half place one prediction**

In `forTeam`, delete `planEnabled`, the `openHours` placement, the `newHours` placement, the `PlannedWork.allocate` call and the per-member `ratio` lookup. The predicted week's hours land on that week's working days inside the horizon — **evenly for now**; task 9 replaces the even split with the member's weekday shares, and doing it here would mix two changes in one review.

```java
        SortedMap<MemberDay, Double> demandByDay = new TreeMap<>();
        p.predictedHours().forEach((k, v) -> {
            if (!byId.containsKey(k.member())) {
                return;
            }
            LocalDate monday = k.week();
            int working = p.calendar().workingDaysInWeek(monday);
            if (working == 0) {
                return;
            }
            for (LocalDate d = monday; !d.isAfter(monday.plusDays(6)); d = d.plusDays(1)) {
                if (p.calendar().isWorkingDay(d) && !d.isBefore(first) && !d.isAfter(last)) {
                    demandByDay.merge(new MemberDay(k.member(), d), v / working, Double::sum);
                }
            }
        });
```

The per-member, per-window loop keeps its shape with one figure instead of three: `demand` accumulates `demandByDay`, `capacity`, `absence`, `workingDays`, `q10` and `q90` are untouched, and the day row becomes

```java
                    double demand = Numbers.round2(demandByDay.getOrDefault(key, 0.0));
                    dayRows.add(new MemberDayForecast(m.id(), d, w.index(), demand, cap,
                            Numbers.round2(Math.max(0.0, demand - cap)), workingDay));
```

Rewrite the comment above it. It currently explains that day rows round **each component** so the stored figures add up; there are no components now. It becomes: the day figure is rounded, the window figure is the rounded raw sum, so a window and the sum of its days can differ by a few hundredths of an hour.

The window row calls the new `band(demandSum, q10, q90, capacity)`.

- [ ] **Step 14: Update the runner's tests**

`ForecastRunnerTest` and `ForecastRunnerPropertyTest`: delete every case about the champion, the forced model, the open/new/planned split and planned work. Keep and update everything about windows, days, capacity and rounding. Add the band property:

```java
    @Property(tries = 500)
    void theBandHoldsItsInvariants(@ForAll @DoubleRange(min = 0, max = 200) double demand,
            @ForAll @DoubleRange(min = -20, max = 0) double q10,
            @ForAll @DoubleRange(min = 0, max = 20) double q90,
            @ForAll @DoubleRange(min = 0, max = 60) double capacity) {
        ForecastRunner.Band b = ForecastRunner.band(demand, q10, q90, capacity);
        assertTrue(b.demand() >= 0.0);
        assertTrue(b.low() >= 0.0);
        assertTrue(b.low() <= b.demand() + 1e-9);
        assertTrue(b.demand() <= b.high() + 1e-9);
        assertTrue(b.overload() >= 0.0);
        // The formula rounds LAST, so "zero exactly when demand <= capacity" is false: a demand of
        // capacity + 0.004 rounds its overload to 0.0. The property has to carry the rounding.
        if (b.overload() > 0.0) {
            assertTrue(b.demand() > capacity + 0.004);
        }
    }
```

- [ ] **Step 14b: Fix the shared facts fixture**

`.../testing/SeededFacts.java` builds its forecast with `runner.prepare(data, asOf, "seasonal_naive", ...)` and `runner.forTeam(prepared, team, null)`. Both signatures just changed and the forced model no longer exists, so it becomes

```java
            ForecastRunner runner = new ForecastRunner(new CapacityRule(CapacityWriter.BASE_HOURS));
            Prepared prepared = runner.prepare(data, SeededData.asOf(), (phase, percent, message) -> { });
            TeamOutcome outcome = runner.forTeam(prepared, team);
```

Its class comment says "seasonal-naive forced, planned work on" — rewrite it. This one file feeds the narration suites (`FactsToolsTest`, `NarratorTest`, `SdkCopilotGatewayTest`, about forty tests between them), so leaving it broken hides everything downstream of it.

Task 8 adds an `int windows` argument to that constructor; pass `2` there and the fixture's numbers stay pinned to the default.

- [ ] **Step 15: Run everything that should now compile**

Run: `cd server && mvn -B -q verify` (600000 ms).
Expected: failures only in `api`, `store`, `service`, `web`, `facts`, `ai` and `eval` — every one of them a compile error about a record component or a method that this task removed. Tasks 6 and 7 close them. If a failure is an **assertion** rather than a compile error, read it: this task changed arithmetic, and an assertion failure here may be a real defect.

- [ ] **Step 16: Commit**

```bash
git add -A
git commit -m "refactor(server): delete the bridge between estimates and hours worked" -m "EffortModel converted estimated arrivals into hours and spread them over days, PlannedWork pushed backlog through the same converter, HourPlacement did the spreading. Task 4 removed the reason for all three. The tournament goes with them: one model, one target, MAE in hours reported beside the mean week a member actually logs."
```

---

### Task 6: The public API and the schema follow the single figure

Spec sections 7 and 11. Task 5 left the module compiling only as far as `run`. This task carries the collapse outward: the records a host sees, the tables they are stored in, the store that writes them, the service that orchestrates it and the REST body.

This is a **breaking API and schema change**, and that is accepted: there is no deployed database. The experiment database is rebuilt by `init-db`, and the server's own integration code is not written yet. The migration is still written as a migration so a development database upgrades cleanly.

**Files:**
- Create: `.../api/BacktestScore.java`, `.../resources/db/forecast/postgresql/V4__weekly_hours.sql`, `.../resources/db/forecast/sqlite/V4__weekly_hours.sql`
- Modify: `.../api/RunRequest.java`, `.../api/RunResult.java`, `.../api/RunSummary.java`, `.../api/MemberWindowForecast.java`, `.../api/MemberDayForecast.java`, `.../api/CurrentDayForecast.java`, `.../web/RunRequestBody.java`, `.../web/ForecastController.java`, `.../store/JdbcRunStore.java`, `.../service/DefaultForecastService.java`, `.../ForecastAutoConfiguration.java`, and their test suites
- Delete: `.../api/ModelScore.java`

**Interfaces:**
- Consumes: `Prepared`, `TeamOutcome` and `Backtest.Result` as task 5 reshaped them.
- Produces the records exactly as the spec's section 7 fixes them:

```java
public record MemberDayForecast(UUID userId, LocalDate day, int windowIndex, double demandHrs, double capacityHrs,
        double overloadHrs, boolean workingDay) {}

public record MemberWindowForecast(UUID userId, int windowIndex, LocalDate windowStart, LocalDate windowEnd,
        double demandHrs, double lowHrs, double highHrs, double capacityHrs, double overloadHrs, int workingDays,
        double absenceHrs) {}

public record CurrentDayForecast(UUID teamId, UUID userId, LocalDate day, UUID runId, double demandHrs,
        double capacityHrs, double overloadHrs, LocalDateTime forecastAt) {}

public record RunRequest(UUID teamId, UUID requestedBy) {}

public record RunSummary(UUID id, UUID teamId, UUID requestedBy, LocalDate asOf, RunStatus status, Double mae,
        String error, LocalDateTime createdAt, LocalDateTime finishedAt) {}

public record BacktestScore(LocalDate origin, int horizon, Double mae) {}

public record RunResult(RunSummary run, List<BacktestScore> scores, List<MemberWindowForecast> memberWindows,
        List<MemberDayForecast> memberDays, String factsJson) {}
```

`MemberWindowForecast` gains two more fields in task 10; it is left at eleven components here so this task stays about the collapse.

- [ ] **Step 1: Write the failing test for the request**

Rewrite `server/forecast-core/src/test/java/com/workloadhub/forecast/api/RunRequestTest.java` down to its one surviving case:

```java
    @Test
    void aTeamIsRequired() {
        ForecastException e = assertThrows(ForecastException.class, () -> new RunRequest(null, UUID.randomUUID()));
        assertEquals("INVALID_REQUEST", e.code());
    }
```

Delete every case about `forcedModel` and `plannedWork`: both fields are gone, and with `ModelRegistry` deleted there is nothing left to validate a model name against.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=RunRequestTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure — `RunRequest` takes four arguments.

- [ ] **Step 3: Collapse the records**

Write the eight records exactly as the Interfaces block above gives them. `RunRequest` keeps only its null check and loses `plannedWorkOr`. Delete `api/ModelScore.java` after `BacktestScore` exists. Update each record's javadoc: `MemberWindowForecast` is "one member in one forecast window: the hours they are predicted to log, the band around it, and the capacity it is measured against".

`web/RunRequestBody.java` becomes `record RunRequestBody(UUID teamId, UUID requestedBy, LocalDate asOf)`. Keep `asOf`: it exists only to be rejected, and `ForecastController` rejects it. Delete `forcedModel` and `plannedWork` from the record and from the controller's mapping.

- [ ] **Step 4: Write the migration, both dialects**

`server/forecast-core/src/main/resources/db/forecast/sqlite/V4__weekly_hours.sql`:

```sql
-- The forecast is of hours logged, one figure per member and window, so the open/new/planned split goes
-- (design 2026-09-13). The tournament goes with it: no forced model, no champion, no MASE; the backtest
-- reports MAE in hours.
ALTER TABLE forecast_runs DROP COLUMN forced_model;
ALTER TABLE forecast_runs DROP COLUMN champion_model;
ALTER TABLE forecast_runs DROP COLUMN champion_mase;
ALTER TABLE forecast_runs ADD COLUMN mae REAL;

ALTER TABLE forecast_member_windows DROP COLUMN open_hrs;
ALTER TABLE forecast_member_windows DROP COLUMN new_hrs;
ALTER TABLE forecast_member_windows DROP COLUMN planned_hrs;

ALTER TABLE forecast_member_days DROP COLUMN open_hrs;
ALTER TABLE forecast_member_days DROP COLUMN new_hrs;
ALTER TABLE forecast_member_days DROP COLUMN planned_hrs;

ALTER TABLE forecast_current_days DROP COLUMN open_hrs;
ALTER TABLE forecast_current_days DROP COLUMN new_hrs;
ALTER TABLE forecast_current_days DROP COLUMN planned_hrs;
```

The PostgreSQL file is the same statements with `double precision` for `mae`. Check the real column names in `V1` and `V3` before writing: this file names nine drops and one add, and a typo in any of them fails the migration at start-up.

**SQLite has supported `ALTER TABLE ... DROP COLUMN` since 3.35 (2021).** Confirm the bundled driver's version first:

Run: `cd server && mvn -B -q dependency:tree -Dincludes=org.xerial:sqlite-jdbc`

If it is older, rebuild each table instead — create the new shape, `INSERT INTO ... SELECT` the kept columns, drop the old, rename. `forecast_current_days_team_idx` is on `(team_id, day)`, so it does not block a drop either way. Whichever form is used, both dialects must end with the same columns.

- [ ] **Step 5: Follow through the store**

`store/JdbcRunStore.java`: every INSERT, every SELECT list, `finish(...)` and every row mapper. Work column by column against the migration; a mapper reading a dropped column fails at run time, not compile time, so the store's own test is what catches it.

`service/DefaultForecastService.java`: drop the `forcedModel` argument from the `prepare` call and the `plannedWork` default from the constructor; `backtestJson` keeps its envelope with the model key dropped from each score row and `mase_by_model` replaced by `mean_mae`. `ForecastAutoConfiguration` drops the constructor argument that fed the planned-work default.

- [ ] **Step 6: Run the store and service suites**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest='JdbcRunStoreTest,DefaultForecastServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS on SQLite; PostgreSQL skipped without Docker with a message, passing with it. **Run this with Docker available at least once** — the two dialects have separate migration files and only this test compares them.

- [ ] **Step 6b: Pin what happens when the booster cannot fit**

The XGBoost native library can be missing, and `XgboostHours.fit` throws `ModelUnavailable` when it is. With `SeasonalNaive` deleted there is no fallback, so the run must fail cleanly rather than produce a forecast of nothing:

```java
    @Test
    void aRunWhoseBoosterCannotFitEndsFailed() {
        // Stub the model so fit throws ModelUnavailable with a known message.
        UUID runId = service.run(new RunRequest(teamId, requestedBy)).run().id();
        RunSummary s = service.findRun(runId).orElseThrow();
        assertEquals(RunStatus.FAILED, s.status());
        assertTrue(s.error().contains("xgboost"), "the stored error names what was unavailable");
    }
```

`DefaultForecastService` already turns an exception during a run into a `FAILED` row with the message; this test pins that it still does once the floor model is gone. If the class cannot be stubbed without a seam, say so and cover it at the `Backtest` level instead rather than adding a seam for a test.

- [ ] **Step 7: Run the gate**

Run: `cd server && mvn -B -q verify` (600000 ms).
Expected: failures only in `facts` and `ai`, which task 7 closes.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(server): one demand figure through the API and the schema" -m "The open, new and planned split existed because three components were summed into demand; there is one prediction now. The tournament's columns go with it, and the run reports MAE in hours. Breaking, and safe to break: no deployment stores these tables."
```

---

### Task 7: The facts contract and the product skills

Spec sections 12 and 13. Copilot reads facts through tools and writes narrative; every number it writes is checked against those facts by `NumberVerifier`. Six keys no longer exist and the model block is a different shape, so the contract, the tool descriptions and the five skills move together. They must move in **one commit**: a skill telling Copilot to cite `open_hours` after the fact has gone produces an `UNVERIFIED` narrative, which is stored and reported.

**Files:**
- Modify: `.../facts/FactsBuilder.java`, `.../facts/MemberPattern.java`, `.../facts/Patterns.java`, `.../ai/FactsTools.java`, `.../resources/skills/whf-domain/SKILL.md`, `.../whf-forecast-interpretation/SKILL.md`, `.../whf-likely-work/SKILL.md`, `.../whf-pattern-discovery/SKILL.md`, `.../whf-rebalancing-advice/SKILL.md`
- Test: `.../facts/FactsBuilderTest.java`, `.../facts/PatternsTest.java`, `.../ai/FactsToolsTest.java`, `.../ai/SkillTextsTest.java`, `.../ai/NarrativeContractTest.java`, `.../ai/NumberVerifierTest.java`, `.../ai/PromptsTest.java`

**Interfaces:**
- Consumes: `Prepared.mae`, `Prepared.meanActualHours`, `TeamOutcome` as task 5 reshaped them.
- Produces the `model` map that task 11's documents describe:

```json
"model": {
  "name": "xgboost",
  "target": "logged hours per member-week",
  "mae": 2.31,
  "mean_actual_hours": 31.4,
  "confidence": "scored",
  "backtest_origins": ["2026-05-04", "2026-05-18"],
  "horizons": [1, 2, 3],
  "windows": 2,
  "interval": {"basis": "backtest residuals", "horizons": {"1": {"low_offset": -3.1, "high_offset": 4.0}}},
  "limitations": "...",
  "seconds_by_phase": {"features": 0.4, "backtest": 12.1, "forecast": 3.3}
}
```

- [ ] **Step 1: Write the failing test for the contract**

Add to `server/forecast-core/src/test/java/com/workloadhub/forecast/facts/FactsBuilderTest.java`:

```java
    @Test
    void theRemovedKeysAreGone() {
        String json = FactsBuilder.build(outcome, ...).toString();   // use the class's existing builder call
        for (String key : List.of("open_hours", "new_hours", "planned_hours", "planned_backlog",
                "planned_basis", "champion", "champion_mase", "forced_model", "mase_by_model", "unavailable")) {
            assertFalse(json.contains("\"" + key + "\""), key + " is still in the facts");
        }
    }

    @Test
    void theModelBlockNamesItsTargetAndItsScale() {
        Map<String, Object> model = ...;  // the "model" map from the built facts
        assertEquals("xgboost", model.get("name"));
        assertEquals("logged hours per member-week", model.get("target"));
        assertTrue(model.containsKey("mae"));
        assertTrue(model.containsKey("mean_actual_hours"));
        assertEquals(2, model.get("windows"), "a narrative can say how far ahead it is reading");
        assertEquals("scored", model.get("confidence"));
    }

    @Test
    void aThinHistoryRunIsUnscoredAndSaysSo() {
        // Build a run over a team with too little history, then:
        assertNull(model.get("mae"));
        assertNull(model.get("mean_actual_hours"));
        assertEquals("thin_history", model.get("confidence"));
        assertEquals("none: no scored origins", interval.get("basis"));
        // and the band has zero width, which must never read as certainty.
    }

    @Test
    void theHistoryBlockReportsWhatIsForecast() {
        // history_13w rows are {week, logged_hours, arrival_hours, tasks}
        assertTrue(row.containsKey("logged_hours"));
        assertTrue(row.containsKey("arrival_hours"));
        assertFalse(row.containsKey("fresh_hours"));
    }
```

Fill the `...` from the class's existing setup.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=FactsBuilderTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure first (the builder's arguments moved in task 5), then assertion failures.

- [ ] **Step 3: Rebuild the facts**

In `.../facts/FactsBuilder.java`:

- Member `forecast` rows: delete `open_hours`, `new_hours`, `planned_hours`. Keep `demand`, `low`, `high`, `capacity`, `overload`, `working_days`, `absence_hours`, `due_hours`. Task 10 adds the pressure keys.
- `days` rows: delete the same three.
- `FactsBuilder.team`: delete `planned_backlog`.
- Member facts: delete `likely_work.planned`.
- The `model` map: delete `champion`, `champion_mase`, `forced_model`, `mase_by_model`, `unavailable`, `planned_basis`; add `name`, `target`, `mae`, `mean_actual_hours`, `windows`.
- `history_13w`: rows become `{week, logged_hours, arrival_hours, tasks}`. `fresh_hours` goes.
- `LIMITATIONS` is rewritten: predicted hours are spread over a week's working days by the member's logged-hours weekday shares (task 9 makes that true; write it now and task 9 delivers it); days already past are not re-forecast; the forecast is of hours logged, so a member who logs less than they work is forecast to work less; and it is a forecast of what someone will get through rather than of what is waiting for them.

`ai/FactsTools.java`: rename `get_planned_work` to `get_likely_work`, returning only `project_roles` and `recent_mix` per member. Update the tool list, every `ToolSpec` description naming "open, new and planned hours", and the ordering sentence at about line 77.

- [ ] **Step 4: Run the facts tests**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest='FactsBuilderTest,FactsToolsTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 5: Move the five skills**

`SkillTextsTest` pins the vocabulary, so it fails until each is right. Keep them factual, short and specific to this domain, in English.

- `whf-domain`: the **demand** bullet loses the decomposition and becomes "the hours a member is predicted to log in the window, never capped by capacity". The **champion** bullet becomes a **model** bullet naming `xgboost`, its target, `mae` beside `mean_actual_hours`, and `confidence`. The window sentence says a run covers between one and six contiguous windows and that `model.windows` says how many. `planned_basis` and `planned_backlog` go.
- `whf-forecast-interpretation`: the "where the demand comes from" rule goes — there is no decomposition to describe. The "below 1.0 is reliable" rule goes with MASE; in its place, state `mae` next to `mean_actual_hours` and let the reader judge, never calling the forecast good or bad. A `thin_history` run is described plainly as unscored. The `due_hours` above capacity rule keeps its meaning.
- `whf-likely-work`: the planned-allocation source and its `high` confidence rule go. The top confidence becomes `medium` — a live project matching a role the member already holds — and the skill says so.
- `whf-pattern-discovery`: unchanged in this task; task 9 adds `logged_weekday_shares`.
- `whf-rebalancing-advice`: unchanged in this task; task 10 adds the pressure lists.

- [ ] **Step 6: Fix the narration fixtures**

`NarrativeContractTest` (about line 53), `NumberVerifierTest` (about line 26) and `PromptsTest` (about line 21) hardcode a champion in their fixture facts. Replace with the new model block. These are fixtures, not assertions about behaviour: change them to match the contract, not the contract to match them.

- [ ] **Step 7: Run the gate**

Run: `cd server && mvn -B -q verify` (600000 ms).
Expected: **exit 0**. This is the first green gate since task 3, and the module is coherent again: a run forecasts logged hours end to end, stores them, and narrates them. Everything after this point adds to a working module.

If it is not green, do not start task 8. A red gate carried forward is what makes a long branch expensive.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(server): the facts and the skills describe a forecast of logged hours" -m "Six keys no longer exist and the model block reports MAE beside the mean week a member logs, so the contract, the tool descriptions and the five skills move in one commit: a skill citing a fact that has gone produces an UNVERIFIED narrative."
```

---

### Task 8: The window count becomes a setting

Spec sections 4 and 3.2. A run covers two windows because `Horizon.WINDOWS` is the compile-time constant `2`. It becomes the property `whf.forecast.windows`, default `2`, one to six.

**This task carries the plan's one real unknown.** Everything downstream of the window list already derives from it, but two things upstream assume two: `Features.HORIZONS`, the hardcoded `{1, 2, 3}`, and `Backtest.MIN_HISTORY_WEEKS`, the fixed `13`. Both become functions of the count, which means **a feature matrix is tied to the count it was built with**. Task 5 already built the guard that catches a mismatch.

**Files:**
- Modify: `.../calendar/Horizon.java`, `.../calendar/ForecastWindow.java`, `.../features/Features.java`, `.../features/FeatureBuilder.java`, `.../backtest/Backtest.java`, `.../run/ForecastRunner.java`, `.../ForecastProperties.java`, `.../ForecastAutoConfiguration.java`
- Test: `.../calendar/HorizonTest.java`, `.../backtest/BacktestTest.java`, `.../features/FeaturesTest.java`, and the auto-configuration test in the sample host

**Interfaces:**
- Consumes: the tournament-free `Backtest` and the reshaped `Prepared` from task 5.
- Produces:
  - `Horizon.MIN_WINDOWS = 1`, `Horizon.MAX_WINDOWS = 6`, `Horizon.windows(LocalDate asOf, int windows)`, `Horizon.maxHorizon(LocalDate origin, int windows)`. `Horizon.WINDOWS` is deleted.
  - `Features.horizons(int windows) -> int[]`, `Features.allColumns(int windows)`, `Features.featureColumns(int h)` unchanged in shape.
  - `Backtest.MIN_HISTORY_WEEKS` becomes `Backtest.minHistoryWeeks(int maxHorizon) -> int`, returning `10 + maxHorizon`.
  - `Backtest.origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int maxHorizon)` and `origins(LocalDate, LocalDate, int count, int maxHorizon)`.
  - `ForecastRunner(CapacityRule capacityRule, int windows)`.
  - `ForecastProperties.getForecast().getWindows()`, key `whf.forecast.windows`.

- [ ] **Step 1: Write the failing property test**

Add to `server/forecast-core/src/test/java/com/workloadhub/forecast/calendar/HorizonTest.java`:

```java
    @Property(tries = 400)
    void anyCountGivesThatManyContiguousWindowsOfFiveWeekdays(
            @ForAll @IntRange(min = 1, max = 6) int count,
            @ForAll("runDays") LocalDate asOf) {
        List<ForecastWindow> ws = Horizon.windows(asOf, count);
        assertEquals(count, ws.size());
        LocalDate previous = null;
        for (ForecastWindow w : ws) {
            assertEquals(5, w.weekdays().size());
            for (LocalDate d : w.weekdays()) {
                assertTrue(Horizon.isWeekday(d), d + " is a weekend");
            }
            if (previous != null) {
                assertTrue(w.start().isAfter(previous), "windows are contiguous and ordered");
            }
            previous = w.end();
        }
        assertEquals(Horizon.firstDay(asOf), ws.get(0).start(), "the horizon starts the first weekday after the run");
    }

    @Property(tries = 400)
    void theMaximumHorizonIsAlwaysOneMoreThanTheWindowCount(
            @ForAll @IntRange(min = 1, max = 6) int count,
            @ForAll("runDays") LocalDate asOf) {
        LocalDate origin = Weeks.lastCompleteWeek(asOf);
        int[] hs = Horizon.horizons(origin, Horizon.windows(asOf, count));
        assertEquals(count + 1, hs[hs.length - 1]);
        assertEquals(count + 1, Horizon.maxHorizon(origin, count));
    }
```

Provide a `@Provide("runDays")` arbitrary over a year of dates so every weekday of the week is exercised, including Friday, Saturday and Sunday runs.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=HorizonTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure — `windows` takes one argument and `maxHorizon` does not exist.

- [ ] **Step 3: Give `Horizon` the count**

```java
    public static final int MIN_WINDOWS = 1;
    public static final int MAX_WINDOWS = 6;
    public static final int WEEKDAYS_PER_WINDOW = 5;

    public static List<ForecastWindow> windows(LocalDate asOf, int windows) {
        List<ForecastWindow> out = new ArrayList<>(windows);
        LocalDate d = firstDay(asOf);
        for (int i = 1; i <= windows; i++) {
            List<LocalDate> days = new ArrayList<>(WEEKDAYS_PER_WINDOW);
            while (days.size() < WEEKDAYS_PER_WINDOW) {
                if (!isWeekend(d)) {
                    days.add(d);
                }
                d = d.plusDays(1);
            }
            out.add(new ForecastWindow(i, days.get(0), days.get(days.size() - 1), days));
        }
        return List.copyOf(out);
    }

    /**
     * The largest horizon a run of this many windows reaches, for every run day.
     *
     * <p>{@code origin} is always the previous week's Monday, so the run day's own week is horizon 1, and
     * {@code 5 * windows} weekdays starting inside horizon 1 or 2 end no later than the last weekday of
     * horizon {@code windows + 1}.
     */
    public static int maxHorizon(LocalDate origin, int windows) {
        return windows + 1;
    }
```

Delete `WINDOWS`. `days(...)` and `horizons(...)` already take the window list and do not change. Update the class javadoc and `ForecastWindow`'s, which says "index (1 or 2)".

- [ ] **Step 4: Run the property test**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=HorizonTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS. If `maxHorizon` fails for some run day, the arithmetic above is wrong and the **test is right** — fix `maxHorizon`, not the property.

- [ ] **Step 5: Size the horizon columns from the count**

In `.../features/Features.java`, `HORIZONS` stops being a constant:

```java
    /** The horizons a run of this many windows fits: 1 to windows + 1. */
    public static int[] horizons(int windows) {
        int[] out = new int[windows + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = i + 1;
        }
        return out;
    }

    /** Every stored column for a matrix built at this window count. */
    public static List<String> allColumns(int windows) {
        List<String> c = new ArrayList<>(SHARED);
        for (int h : horizons(windows)) {
            c.addAll(horizonColumns(h));
        }
        for (int h : horizons(windows)) {
            c.add(target(h));
        }
        return List.copyOf(c);
    }
```

`SHARED`, `featureColumns(int h)`, `horizonColumns(int h)` and `target(int h)` are unchanged: the shared block does not depend on the count, and a single horizon's columns never did.

`FeatureBuilder` takes the count: `public FeatureBuilder(ForecastData data, Lifecycle lc, WorkingCalendar cal, CapacityRule rule, int windows)`, uses `Features.allColumns(windows)` and loops `for (int h : Features.horizons(windows))`.

- [ ] **Step 6: Scale the history gate**

In `.../backtest/Backtest.java`, replace the constant:

```java
    /**
     * The history an origin needs behind it, ruling 18.1 of the 2026-09-13 design: 13 weeks at two windows,
     * exactly the old fixed value, and 17 at six. Scaling it keeps the usable training span constant, because
     * {@code run} trains on weeks up to {@code origin - maxHorizon} so a training row's target cannot overlap
     * the test week.
     */
    public static int minHistoryWeeks(int maxHorizon) {
        return 10 + maxHorizon;
    }

    public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int maxHorizon) {
        return origins(lastCompleteWeek, firstWeek, ORIGIN_COUNT, maxHorizon);
    }

    public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int count, int maxHorizon) {
        List<LocalDate> out = new ArrayList<>();
        for (int k = count; k >= 1; k--) {
            LocalDate origin = lastCompleteWeek.minusWeeks((long) k * ORIGIN_STEP_WEEKS);
            if (ChronoUnit.WEEKS.between(firstWeek, origin) >= minHistoryWeeks(maxHorizon)) {
                out.add(origin);
            }
        }
        return out;
    }
```

The two-argument overload gains the maximum horizon rather than defaulting it. **Do not add a default**: a silent one would reintroduce the leak the hold-out exists to prevent.

`ORIGIN_COUNT = 6` and `ORIGIN_STEP_WEEKS = 2` are unchanged.

- [ ] **Step 7: Add the property and fail start-up on a bad value**

In `.../ForecastProperties.java`, delete the nested `PlannedWork` class and its getter (task 5 removed its only consumer) and add, in the same shape as `Copilot`, `Web` and `Flyway`:

```java
    private final Forecast forecast = new Forecast();

    public Forecast getForecast() { return forecast; }

    public static class Forecast {
        private int windows = 2;
        public int getWindows() { return windows; }
        public void setWindows(int windows) { this.windows = windows; }
    }
```

In `.../ForecastAutoConfiguration.java`, validate where `ForecastRunner` is built:

```java
        int windows = properties.getForecast().getWindows();
        if (windows < Horizon.MIN_WINDOWS || windows > Horizon.MAX_WINDOWS) {
            throw new IllegalStateException("whf.forecast.windows must be between " + Horizon.MIN_WINDOWS
                    + " and " + Horizon.MAX_WINDOWS + ", but was " + windows);
        }
        return new ForecastRunner(capacityRule, windows);
```

**Fail, never clamp.** A horizon silently reduced to something the operator did not ask for is worse than a refusal at boot.

`ForecastRunner` takes `(CapacityRule capacityRule, int windows)`, stores the count, and `prepare` uses it for `Horizon.windows(asOf, windows)`, `new FeatureBuilder(..., windows)` and `Backtest.origins(origin, firstWeek, Horizon.maxHorizon(origin, windows))`.

- [ ] **Step 8: Write the start-up and six-window cases**

```java
    @Test
    void aWindowCountOutsideTheRangeRefusesToStart() {
        for (int bad : new int[] {0, -1, 7, 99}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> startContextWith(bad));
            assertTrue(e.getMessage().contains("whf.forecast.windows"), "the message names the property");
            assertTrue(e.getMessage().contains("1") && e.getMessage().contains("6"), "and the range");
        }
    }

    @Test
    void sixWindowsProduceThirtyDaysAndSevenHorizons() {
        // asOf is pinned to a MONDAY: the horizon count is run-day dependent (a run fits between
        // w and w + 1 boosters), so a Friday would fit 6 and this case would fail for the wrong reason.
        LocalDate asOf = LocalDate.of(2026, 9, 7);
        RunResult r = serviceWith(6).run(new RunRequest(teamId, requestedBy));
        long members = r.memberWindows().stream().map(MemberWindowForecast::userId).distinct().count();
        assertEquals(6 * members, r.memberWindows().size());
        assertEquals(30 * members, r.memberDays().size());
        assertEquals(7, Horizon.maxHorizon(Weeks.lastCompleteWeek(asOf), 6));
    }
```

Write `startContextWith` and `serviceWith` against the patterns the existing auto-configuration and service tests already use.

- [ ] **Step 9: Run the gate**

Run: `cd server && mvn -B -q verify` (600000 ms).
Expected: exit 0. At the default of two windows the feature matrix is byte-for-byte what it was before this task — that is the check that the derivation is right.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(server): the number of forecast windows is a setting" -m "whf.forecast.windows, default 2, one to six, refused at start-up outside that range rather than clamped. Two things upstream of the window list assumed two: the hardcoded horizons of the feature matrix and the fixed history gate. Both derive from the count now, so a matrix is tied to the count it was built with."
```

---

### Task 9: A week's hours land where the member actually works

Spec section 5. A predicted week's hours currently land **evenly** on that week's working days. They now land in proportion to the member's own weekday habits, computed from their time logs.

The existing `weekday_shares` cannot serve: it counts the weekday a task was **assigned** on, by task count. That describes when work arrives, not when a member works, and it is the wrong denominator for hours.

**Files:**
- Modify: `.../facts/Patterns.java`, `.../facts/MemberPattern.java`, `.../capacity/CapacityRule.java`, `.../run/ForecastRunner.java`, `.../resources/skills/whf-pattern-discovery/SKILL.md`
- Test: `.../facts/PatternsTest.java`, `.../run/ForecastRunnerTest.java`, `.../run/ForecastRunnerPropertyTest.java`

**Interfaces:**
- Consumes: `CapacityRule.offDays` (kept by task 5), `WorkingCalendar`, the seeded weekday shape from task 3.
- Produces: `MemberPattern.loggedWeekdayShares()` — `List<Double>` of five, Monday to Friday, summing to 1 or all zero — reported as the fact `logged_weekday_shares`; and the split inside `ForecastRunner.forTeam`.

**The rule**, for member *m* and horizon week *W* with prediction *P*:

1. *K* is the working days of *W* per the `WorkingCalendar`, with weekends, holidays **and the member's full-absence days** excluded. If *K* is empty, *W* contributes nothing.
2. *s_d* is *m*'s logged weekday share for day *d*, **renormalised over *K* alone**. If every share over *K* is zero, fall back to an even split over *K* — exactly today's behaviour.
3. Day *d* receives `P × s_d`, but only days inside the run's horizon are emitted.

Step 3 is deliberate and asymmetric: a **holiday or absence** inside the week redistributes its hours onto the remaining days, because step 1 removed it from *K* before the renormalisation. A day **already past** simply drops its hours, because past days are not re-forecast and *K* still contained it. The `limitations` fact says so.

- [ ] **Step 1: Write the failing test for the new series**

Add to `server/forecast-core/src/test/java/com/workloadhub/forecast/facts/PatternsTest.java`:

```java
    @Test
    void loggedWeekdaySharesAreHoursNotTaskCounts() {
        // A member who logs 6 hours every Monday and 2 every Friday, and nothing else.
        MemberPattern p = Patterns.of(member, lc, data, asOf);
        List<Double> s = p.loggedWeekdayShares();
        assertEquals(5, s.size());
        assertEquals(0.75, s.get(0), 1e-6, "Monday");
        assertEquals(0.0, s.get(1), 1e-6);
        assertEquals(0.25, s.get(4), 1e-6, "Friday");
        assertEquals(1.0, s.stream().mapToDouble(Double::doubleValue).sum(), 1e-6);
    }

    @Test
    void aMemberWhoLoggedNothingHasNoShape() {
        MemberPattern p = Patterns.of(memberWithNoLogs, lc, data, asOf);
        assertEquals(List.of(0.0, 0.0, 0.0, 0.0, 0.0), p.loggedWeekdayShares());
    }

    @Test
    void theTwoWeekdaySeriesAreDifferentThings() {
        MemberPattern p = Patterns.of(member, lc, data, asOf);
        assertNotNull(p.weekdayShares(), "task assignment, by count");
        assertNotNull(p.loggedWeekdayShares(), "hours logged, by hours");
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=PatternsTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure — `loggedWeekdayShares` does not exist.

- [ ] **Step 3: Compute the series**

In `.../facts/Patterns.java`, over the same 13-week window the other statistics use, sum each member's `time_logs.hours` by `DayOfWeek` and divide by their total. Add the component to `MemberPattern` and `m.put("logged_weekday_shares", loggedWeekdayShares)` to its map, next to `weekday_shares`.

All zero when the member logged nothing in the window: that is the fallback signal step 2 of the rule reads, and it must be distinguishable from an even split.

- [ ] **Step 4: Make a full-absence day cost that day's capacity**

In `.../capacity/CapacityRule.java`, `offDays` currently calls a day off when its absence hours reach the deleted `FULL_DAY_HOURS`. It becomes that day's own capacity, so an 8.8-hour day is not half-absent at 8.0:

```java
    /**
     * The days a member is fully absent: their absence hours meet that day's capacity.
     *
     * <p>Its caller is the weekday split in {@code ForecastRunner}: predicted hours must not land on a day the
     * member is not there, because the day's capacity is zero and every hour on it would read as pure overload
     * — and by the 2026-09-13 ruling 18.4 no hour is ever logged on such a day.
     */
    public Set<LocalDate> offDays(UUID member, ForecastData data, MemberRow row, WorkingCalendar cal) {
```

It needs the member row and the calendar to ask for a day's capacity, so it stops being static. Update the signature, and note that task 5 already made this its only caller.

- [ ] **Step 5: Write the failing test for the split**

Add to `server/forecast-core/src/test/java/com/workloadhub/forecast/run/ForecastRunnerTest.java`:

```java
    @Test
    void aWeeksHoursFollowTheMembersWeekdayShape() {
        // A member whose shares are Monday 0.4, Tuesday 0.3, Wednesday 0.2, Thursday 0.1, Friday 0.0,
        // predicted 20 hours for a week wholly inside the horizon with no holiday and no absence.
        assertEquals(8.0, dayHours(monday), 1e-6);
        assertEquals(6.0, dayHours(tuesday), 1e-6);
        assertEquals(4.0, dayHours(wednesday), 1e-6);
        assertEquals(2.0, dayHours(thursday), 1e-6);
        assertEquals(0.0, dayHours(friday), 1e-6);
    }

    @Test
    void aHolidayRedistributesOntoTheRestOfTheWeek() {
        // The same member and week, with Wednesday a public holiday: the 0.2 share is removed from the
        // denominator, so the other four days carry the whole 20 hours between them.
        assertEquals(0.0, dayHours(wednesday), 1e-6);
        assertEquals(20.0, dayHours(monday) + dayHours(tuesday) + dayHours(thursday) + dayHours(friday), 1e-6);
    }

    @Test
    void aFullAbsenceDayTakesNoHours() {
        // The same, with Tuesday a full-day absence.
        assertEquals(0.0, dayHours(tuesday), 1e-6);
    }

    @Test
    void aMemberWithNoLoggedHistoryGetsTheEvenSplit() {
        // Shares all zero: 20 hours over five working days is 4.0 each, exactly today's behaviour.
        assertEquals(4.0, dayHours(monday), 1e-6);
    }
```

- [ ] **Step 6: Replace the even split**

In `ForecastRunner.forTeam`, the block task 5 left spreading `v / working` becomes the three-step rule. Fetch each member's shares once, before the loop, from `Patterns`. Keep the emission guard (`!d.isBefore(first) && !d.isAfter(last)`) exactly where it is: that is step 3, and it is what makes a past day drop its hours rather than redistribute them.

- [ ] **Step 7: Write the property test**

```java
    @Property(tries = 300)
    void theSplitConservesTheWeekAndNeverGoesNegative(
            @ForAll @DoubleRange(min = 0, max = 80) double prediction,
            @ForAll("shareVectors") double[] shares) {
        Map<LocalDate, Double> days = split(prediction, shares, WEEK_WHOLLY_INSIDE_THE_HORIZON);
        double sum = 0;
        for (double h : days.values()) {
            assertTrue(h >= 0.0);
            sum += h;
        }
        assertEquals(prediction, sum, 1e-6, "a week wholly inside the horizon keeps all its hours");
        for (LocalDate d : NON_WORKING_DAYS) {
            assertEquals(0.0, days.getOrDefault(d, 0.0), 0.0, "a non-working day receives nothing");
        }
    }
```

Provide `shareVectors` to generate both normal vectors and the all-zero one, so the fallback is exercised by the property rather than only by the example.

- [ ] **Step 8: Update the skill and run the gate**

`whf-pattern-discovery`: add `logged_weekday_shares` and distinguish it from `weekday_shares` in one sentence — one is when work arrives, by task count; the other is when the member works, by hours.

Run: `cd server && mvn -B -q verify` (600000 ms).
Expected: exit 0.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(server): a predicted week lands on the days the member actually works" -m "The hours were spread evenly over a week's working days. They now follow the member's own logged-hours weekday shares, a new series: the existing weekday_shares counts the day a task was assigned on, which is when work arrives, not when someone works. A holiday or full absence redistributes onto the rest of the week; a day already past drops its hours."
```

---

### Task 10: The three pressures a forecast of logged hours cannot show

Spec section 8. A forecast of logged hours says what someone will **get through**, never how much is waiting. Three deterministic facts cover the gap, computed from facts that already exist, so `NumberVerifier` can check anything the narrative says about them.

**Files:**
- Create: migrations `V5__pressure_facts.sql` in both dialects
- Modify: `.../run/ForecastRunner.java`, `.../facts/FactsBuilder.java`, `.../facts/Patterns.java`, `.../facts/MemberPattern.java`, `.../api/MemberWindowForecast.java`, `.../store/JdbcRunStore.java`, `.../eval/DemandRow.java`, `.../eval/Harness.java`, `.../resources/skills/whf-domain/SKILL.md`, `.../whf-forecast-interpretation/SKILL.md`, `.../whf-rebalancing-advice/SKILL.md`
- Test: `.../run/ForecastRunnerTest.java`, `.../run/ForecastRunnerPropertyTest.java`, `.../facts/FactsBuilderTest.java`, `.../facts/PatternsTest.java`, `.../store/JdbcRunStoreTest.java`, `.../ai/SkillTextsTest.java`

**Interfaces:**
- Consumes: the window rows of task 5, `MemberPattern.openEstHours`, the `due_hours` sum of `FactsBuilder`.
- Produces: `MemberWindowForecast` gains `double backlogExcessHrs, double dueExcessHrs` as its last two components; `MemberPattern` gains `overdueHrs`; `rebalancing_candidates` gains `backlog_pressed` and `deadline_pressed`.

**Where they are computed.** Neither input is reachable from where the window row is built: `due_hours` is summed inside `FactsBuilder` from the lifecycle's open tasks, and `open_est_hours` inside `Patterns`. Saying "it is already built" is true and useless — something has to move. **Both sums move to small static helpers beside the window arithmetic, and `FactsBuilder` and `Patterns` call them instead of summing for themselves.** One definition of each quantity, which is the point: a narrative comparing a `due_hours` from one summation against a `due_excess_hrs` from another would eventually contradict itself. `overdue_hrs` is the exception — a member-level fact, summed in `Patterns` beside the `overdue_open` count it matches, needing nothing moved.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void theBacklogFigureIsWhatIsLeftAfterTheForecast() {
        // A member holding 120 hours of open work, forecast 30 hours in window 1 and 25 in window 2.
        assertEquals(90.0, window(1).backlogExcessHrs(), 1e-6, "120 - 30");
        assertEquals(65.0, window(2).backlogExcessHrs(), 1e-6, "120 - (30 + 25), cumulative");
    }

    @Test
    void theBacklogFigureNeverGrowsAcrossAWindow() {
        double previous = Double.MAX_VALUE;
        for (MemberWindowForecast w : windowsOf(member)) {
            assertTrue(w.backlogExcessHrs() <= previous + 1e-9);
            previous = w.backlogExcessHrs();
        }
    }

    @Test
    void theDeadlineGapIsPerWindowAgainstThatWindowsCapacity() {
        // 60 hours due inside window 1, whose capacity is 44.
        assertEquals(16.0, window(1).dueExcessHrs(), 1e-6);
        assertEquals(0.0, window(2).dueExcessHrs(), 1e-6, "a deadline belongs to its window and does not roll forward");
    }

    @Test
    void overdueHoursAreAMemberFactNotAWindowFact() {
        // A task 3 days past its due date with 7 remaining hours.
        assertEquals(7.0, pattern(member).overdueHrs(), 1e-6);
    }

    @Test
    void theTwoListsNameTheMembersToActOn() {
        Map<String, Object> candidates = rebalancingCandidates();
        assertTrue(((List<?>) candidates.get("backlog_pressed")).contains(pressedMemberName));
        assertTrue(((List<?>) candidates.get("deadline_pressed")).contains(lateMemberName));
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=ForecastRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure — `backlogExcessHrs` does not exist on the record.

- [ ] **Step 3: Compute them**

`MemberWindowForecast` gains its two components. In `ForecastRunner.forTeam`, after the band (it reads the final demand figure, so it cannot be computed beside it), keep a running total of demand per member across windows:

```java
                runningDemand += band.demand();
                double backlogExcess = Numbers.round2(Math.max(0.0, openEst - runningDemand));
                double dueExcess = Numbers.round2(Math.max(0.0, dueHours - capacity));
```

`openEst` and `dueHours` come from the two moved helpers.

In `Patterns`, add `overdueHrs`: the remaining hours of the member's open tasks whose due date is before the run day — the same tasks `overdue_open` already counts, summed in hours instead of counted.

In `FactsBuilder`, add the two window keys and `overdue_hrs` to `patterns`, and add the two lists to `rebalancing_candidates`: `backlog_pressed` are the members whose `backlog_excess_hrs` is above zero in the **last** window (the figure is non-increasing, so that is the strictest test and means the backlog does not fit inside the whole run), `deadline_pressed` those whose `due_excess_hrs` is above zero in **any** window.

- [ ] **Step 4: The migration**

`V5__pressure_facts.sql`, both dialects:

```sql
-- A forecast of logged hours says what a member will get through, never what is waiting for them
-- (design 2026-09-13, section 8). The default is what makes these addable: SQLite refuses ADD COLUMN
-- NOT NULL without one, and PostgreSQL refuses it on a table with rows. Every writer supplies the value;
-- zero is the truthful reading of "no pressure recorded".
ALTER TABLE forecast_member_windows ADD COLUMN backlog_excess_hrs REAL NOT NULL DEFAULT 0;
ALTER TABLE forecast_member_windows ADD COLUMN due_excess_hrs REAL NOT NULL DEFAULT 0;
```

`double precision` for PostgreSQL. Follow through `JdbcRunStore`'s INSERT, SELECT list and row mapper.

- [ ] **Step 5: Write the property test, with the rounding in it**

```java
    @Property(tries = 500)
    void thePressureFiguresAreNonNegativeAndRoundLast(
            @ForAll @DoubleRange(min = 0, max = 300) double openEst,
            @ForAll @DoubleRange(min = 0, max = 300) double cumulativeDemand,
            @ForAll @DoubleRange(min = 0, max = 200) double dueHours,
            @ForAll @DoubleRange(min = 0, max = 100) double capacity) {
        double backlog = Numbers.round2(Math.max(0.0, openEst - cumulativeDemand));
        double due = Numbers.round2(Math.max(0.0, dueHours - capacity));
        assertTrue(backlog >= 0.0);
        assertTrue(due >= 0.0);
        // The formula rounds LAST, so "positive exactly when A exceeds B" is false: an excess of 0.004
        // rounds to 0.0. The property has to carry the rounding, or jqwik finds the counterexample.
        if (backlog > 0.0) {
            assertTrue(openEst > cumulativeDemand + 0.004);
        }
        if (due > 0.0) {
            assertTrue(dueHours > capacity + 0.004);
        }
    }
```

- [ ] **Step 6: Carry them into the harness**

`eval/DemandRow` loses `openHrs`, `newHrs` and `plannedHrs` (task 5 left them dangling) and gains `backlogExcessHrs` and `dueExcessHrs`, so `demand.csv` — the only place the harness reports demand — can be read against the pressure the run found. Update the header in `Harness`.

- [ ] **Step 7: The three skills**

- `whf-domain`: document `backlog_excess_hrs`, `due_excess_hrs`, `overdue_hrs`, `backlog_pressed` and `deadline_pressed`. Say plainly that the first is what is left of the queue after the windows so far are forecast, **and that it reads the forecast rather than checking it**, so a member whose forecast is optimistic can be pressed without the number showing it.
- `whf-forecast-interpretation`: two rules. When `overload` is zero but `backlog_excess_hrs` is not, the member is not predicted to exceed their hours yet holds more open work than the coming windows can absorb. When `due_excess_hrs` is above zero, say how many hours more are due in that window than it holds, and name rebalancing as the answer.
- `whf-rebalancing-advice`: `deadline_pressed` is the strongest case for moving work, because those hours cannot be deferred — either they move or the deadline slips — and `due_excess_hrs` says how many. A positive `overdue_hrs` has already slipped and comes first of all. `backlog_pressed` stays the weaker, undated case.

- [ ] **Step 8: Run the gate**

Run: `cd server && mvn -B -q verify` (600000 ms).
Expected: exit 0, including the PostgreSQL tests with Docker available.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(server): report the pressure a forecast of logged hours cannot show" -m "A prediction of hours logged says what someone gets through, not what is waiting. backlog_excess_hrs is what is left of the queue once the windows so far are forecast; due_excess_hrs is the hours due inside a window beyond what it holds, which is what makes a leader rebalance; overdue_hrs is work already late. Copilot can only state a gap the facts contain, so all three are computed rather than left to it."
```

---

### Task 11: The harness, the driver, the sample host and the documents

Spec sections 14, 15 and 16. Everything outside the library that describes or drives it.

**Files:**
- Modify: `.../eval/EvalConfig.java`, `.../eval/EvalResult.java`, `.../eval/ScoreRow.java`, `.../eval/Harness.java`, `.../eval/Report.java`, `server/tools/Experiment.java`, `server/tools/experiment.sh`, `server/examples/HostExample.java`, `server/README.md`, `CLAUDE.md`, `docs/backlog.md`, and the four amended specs
- Test: `.../eval/HarnessTest.java`, `.../samplehost/SampleHostIntegrationTest.java`, `.../samplehost/JavaHostIntegrationTest.java`

**Interfaces:**
- Consumes: everything tasks 1 to 10 produced.
- Produces: `EvalConfig(LocalDate asOf, int origins, List<UUID> teams, int windows)`; `Experiment.java`'s `eval` gaining `--windows N`.

- [ ] **Step 1: Give the harness the window count**

`EvalConfig` becomes `(LocalDate asOf, int origins, List<UUID> teams, int windows)`. `Harness` constructs its own `ForecastRunner` outside Spring, so it cannot read the property; passing the count is the only way to evaluate anything but the default.

Validate in the **compact constructor**, not only in the driver, because `ForecastService.evaluate(EvalConfig)` is a public entry point:

```java
public record EvalConfig(LocalDate asOf, int origins, List<UUID> teams, int windows) {
    public EvalConfig {
        if (windows < Horizon.MIN_WINDOWS || windows > Horizon.MAX_WINDOWS) {
            throw ForecastException.invalidRequest("windows must be between " + Horizon.MIN_WINDOWS
                    + " and " + Horizon.MAX_WINDOWS + ", but was " + windows);
        }
    }
}
```

**No coercion of 0 to 2.** A default that silently accepts an invalid value would contradict the start-up refusal of task 8. Callers that want the default pass `2`.

`Harness.HORIZONS`, the hardcoded `{1, 2}` at about line 37, is the last place outside the library that assumes a window count: derive it from `config.windows()` the way `Features.horizons(int)` does, or delete it if every use can read the run's own horizon list. `Harness.evaluate` drops the `ModelRegistry` validation and the factory filtering; `EvalResult` drops `skipped`; `ScoreRow` loses its `model` column; the `mase` and `beats_naive` metric rows go, leaving `mae`, `coverage80`, `wql` and `seconds`; `Report` drops its "Models requested" line.

- [ ] **Step 2: The experiment driver**

`server/tools/Experiment.java`: `eval` drops `--models` and gains `--windows N`, defaulting to 2 and refusing anything outside one to six **with the same message the auto-configuration uses**. Since a matrix is tied to the count it was built with, `eval` rebuilds rather than reusing one built at another count. `server/tools/experiment.sh` follows in its header comment.

- [ ] **Step 3: The sample host**

`server/examples/HostExample.java`: the `getRun` section stops printing the champion, the per-model MASE and the unavailable map, and prints `mae`, `mean_actual_hours` and `confidence` — the last two read from `RunResult.factsJson`, since `RunSummary` carries only `mae`. **The run-list section cannot print them at all**: it returns `RunSummary` and has no facts to parse, so it prints `mae` alone. Drop the commentary on `forcedModel` as a constructor argument and the explanation of why `seasonal_naive` scores exactly 1.0. Remove the `whf.default-weekly-hours = 44` override — setting a property to its own default only suggests the default is wrong — and add a line setting `whf.forecast.windows` to show where the horizon is chosen.

- [ ] **Step 4: Check both single-file programs still compile**

Run: `bash server/tools/experiment.sh --help` and `bash server/examples/run-host-example.sh --help`
Expected: both print their help. Maven does not build these, so nothing else catches a break.

- [ ] **Step 5: The documents**

- `CLAUDE.md`: "40 h/week default" becomes 44; "covers ten weekdays in two windows" becomes one to six contiguous windows, two by default; the domain vocabulary drops "arrival model, effort model, champion model" for the single model and its target, and drops the open/new/planned gloss on demand; the toolchain section states the one-step gate and drops `uv` from the release preconditions; the layout drops "the parity scripts and their one Python test"; "Where the project stands" records this change.
- `server/README.md`: the property table gets `whf.default-weekly-hours` `44.0` and `whf.forecast.windows` `2`, and loses `whf.planned-work.enabled`; the `POST /runs` body loses `forcedModel` and `plannedWork`; the two-window sentences become the configurable horizon; the `eval` row swaps `--models` for `--windows`; the parity section and its pointer go; the narration section's model facts are refreshed.
- Deviation notes, in the style the repository already uses, on `2026-09-09-java-forecast-module-design.md` sections 4 (the seed), 6, 8, 9, 11 and 13; on `2026-09-10-java-copilot-narration-design.md` for the facts contract; on `2026-09-10-rolling-forecast-windows.md` for the configurable count; on `2026-09-11-accuracy-evaluation-design.md` section 4 for the restored MASE interpretation.
- `docs/eval/2026-09-10-java-parity-synthetic/` is **kept** — it records a run that happened — and gains a note saying the procedure that produced it was retired.
- `docs/backlog.md`: record the rulings under "Java migration", including that the two-model question, the parity procedure and the effort/planned-work decomposition are all closed.

- [ ] **Step 6: Run the gate one last time**

Run: `bash scripts/check.sh`
Expected: one step, OK, exit 0.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs(server): the harness, the driver, the host and the documents follow the retarget" -m "EvalConfig carries the window count because the harness builds its own runner outside Spring and cannot read the property; the driver gains --windows for the same reason. The documents said 40 hours, two windows and a champion model, none of which is true now."
```

---

## Closing notes for the executor and the reviewer

**Rulings this plan takes, to report to the owner at the end:**

1. `Truth.realisedHours` keeps its own summation rather than delegating to `WeeklySeries` (task 4 step 4). The spec asked for delegation, but `WeeklySeries` is scoped to a member list and a week range and cannot serve Truth's "every member, every week" contract without building a throwaway instance. The single definition is the arithmetic, held together by a test that compares them, not the method.
2. `CapacityRule.offDays` stops being static (task 9 step 4): deciding whether a day is a full absence now needs that day's capacity, which needs the member row and the calendar.
3. `EvalConfig` validates in its compact constructor rather than defaulting a zero window count (task 11 step 1), so the public `evaluate` entry point refuses what the auto-configuration refuses.
4. The sample host's run **list** prints `mae` alone (task 11 step 3): `mean_actual_hours` and `confidence` live only in the facts JSON, which a `RunSummary` does not carry. Adding them to the summary and the schema would be a wider change than this plan's scope.
5. The weekday split lands in task 9, not in task 5, even though task 5 rewrites the same block. Two changes to one block in one review is how mistakes get through.

**What to watch for while executing:**

- Tasks 4, 5 and 6 leave the module not building end to end. That is expected and the commits say so. **Task 7 step 7 is the first green gate** — do not start task 8 until it is green.
- Tasks 2 and 3 will each break a large number of seeded assertions. Work through them with the three-way test in task 2 step 7, and treat "an assertion that fails in a way the constants do not explain" as a real defect to report, not to paper over.
- The gate takes about six minutes. Budget 600000 ms and do not interrupt it.
- Do not push. The owner pushes `dev` when a batch is reviewed, and CI is paused, so nothing else will catch a mistake.

**Rollback, if it comes to that (spec section 20):** every deletion is one revert away while the work sits on
`dev`, and `main` is untouched because it only ever fast-forwards. There are **two** migrations here, not the
one the spec assumed, so reverting the schema means dropping `V5` and `V4` — or, more simply, rebuilding the
experiment database with `init-db`, since no deployment holds data these tables lose.

**What comes next, after this lands:** the live Copilot check on a seeded database (`server/README.md`, "Narrating with Copilot"), then the real export through the seed, then the server's own integration code against the sample host.
