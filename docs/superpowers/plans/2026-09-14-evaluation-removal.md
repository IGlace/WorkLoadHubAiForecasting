# Evaluation Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the offline evaluation harness — `ForecastService.evaluate(EvalConfig)`, `Harness`, `Report`, their row types and the experiment driver's `eval` command — leaving `accuracy(teamId, from, to)` as the module's only evaluation surface.

**Architecture:** This is a removal, not a feature. Work from the leaf inwards so the tree compiles after every task: the driver first (it is the only caller of `evaluate`), then the public method and the harness behind it, then the orphaned `AccuracyReport`, then the members of `Metrics` and `Backtest` whose only caller was the harness, then the documents. The per-run backtest inside `ForecastRunner.prepare` — which produces each window's `lowHrs`/`highHrs`, the run's `mae` and three facts Copilot reads — is **not** part of this removal and its behaviour must not change.

**Tech Stack:** Java 21, Maven 3.9 (single module `forecast-core`), JUnit 6, jqwik, Spring Boot 4.1 auto-configuration, XGBoost4J, SQLite/PostgreSQL through Flyway.

**Spec:** `docs/superpowers/specs/2026-09-14-evaluation-removal-design.md`

## Global Constraints

- Branch: `claude/evaluation-system-workflow-m5jp68`. All work lands there; never push elsewhere.
- The per-run backtest's behaviour is untouched. `Backtest.origins` (3-arg), `Result.residuals`, `Result.meanMae`, `Result.meanActualHours` and `intervalBounds` keep working exactly as they do; every number a run produces stays identical.
- No schema change, no facts-contract change, no change to `accuracy()`, the narration or the number verifier.
- The gate is one command, run by hand: `cd server && mvn -B -q verify` (about seventeen minutes). CI is paused, so this is the only gate. Read results from `server/forecast-core/target/surefire-reports/TEST-*.xml`, never by summing the `*.txt` files, and wipe that directory before a run you intend to trust.
- Per-task checks use the fast commands given in each task, not the full gate. The full gate runs once, in Task 6.
- Every text file stays LF (`.gitattributes`).
- Commit messages: imperative subject, short body explaining why, then the two attribution lines shown in each commit step.
- Documents dated before 2026-09-09 describe the archived Python version; do not edit them.
- On the owner's Windows machine every command below runs inside the development container (`bash scripts/devbox.sh shell`), which bind-mounts the repository at `/work`. In a Linux container with `mvn` and `java` already on `PATH`, run them directly.

## File Structure

**Deleted outright (9 files):**

| Path | Role |
|---|---|
| `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/Harness.java` | the harness |
| `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/EvalConfig.java` | its request |
| `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/EvalResult.java` | its result |
| `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/ScoreRow.java` | arrival-level row |
| `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/DemandRow.java` | demand-level row |
| `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/Report.java` | writes the three report files |
| `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/AccuracyReport.java` | orphan since the 2026-09-12 CLI trim |
| `server/forecast-core/src/test/java/com/workloadhub/forecast/eval/HarnessTest.java` | tests the harness |
| `server/forecast-core/src/test/java/com/workloadhub/forecast/eval/ReportTest.java` | tests the report writer |
| `server/forecast-core/src/test/java/com/workloadhub/forecast/eval/AccuracyReportTest.java` | tests the orphan |

**Modified:**

| Path | Change |
|---|---|
| `server/tools/Experiment.java` | drop the `eval` command and everything that served only it |
| `server/forecast-core/src/main/java/com/workloadhub/forecast/api/ForecastService.java` | drop `evaluate` and its two `eval` imports |
| `server/forecast-core/src/main/java/com/workloadhub/forecast/service/DefaultForecastService.java` | drop the `evaluate` implementation |
| `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/Metrics.java` | drop `coverage` and `weightedQuantileLoss` |
| `server/forecast-core/src/main/java/com/workloadhub/forecast/backtest/Backtest.java` | drop `Residual`, `Result.residualRows`, the 4-arg `origins` |
| `server/forecast-core/src/test/java/com/workloadhub/forecast/ExperimentFlowTest.java` | drop the eval case, assert `eval` is now refused |
| `server/forecast-core/src/test/java/com/workloadhub/forecast/service/DefaultForecastServiceTest.java` | drop the `evaluate` case |
| `server/forecast-core/src/test/java/com/workloadhub/forecast/eval/MetricsTest.java` | drop the `coverage` and `wql` assertions |
| `server/README.md`, `CLAUDE.md`, `docs/backlog.md`, four specs, `docs/eval/…` | the document pass |

**Untouched and still live in `eval/` afterwards:** `Accuracy.java`, `RunDayForecast.java`, `Metrics.java` (pruned), `Truth.java`.

---

### Task 1: Remove the `eval` command from the experiment driver

The driver is the only caller of `ForecastService.evaluate`, so it goes first: after this task nothing calls the method and Task 2 can delete it freely. This is the one task with a real behavioural assertion to drive — `eval` must stop being a command and start being refused like any other unknown word.

**Files:**
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/ExperimentFlowTest.java` (delete lines 79-105, add one assertion near line 112)
- Modify: `server/tools/Experiment.java` (imports; `USAGE` block lines 82-90; dispatch line 124; `eval` lines 227-251; `Module` lines 255-269; `boot` lines 271-287; `resolveTeam` lines 295-314; `tryUuid` lines 316-322; `split` lines 334-338)
- Modify: `server/README.md` (lines 122-123, 132, 134, 143-146)

**Interfaces:**
- Consumes: nothing from earlier tasks — this is the first.
- Produces: a driver with exactly four commands (`init-db`, `import`, `export`, `seed`) and no Spring Boot wiring. Task 2 relies on `ForecastService.evaluate` having no callers left in the repository.

- [ ] **Step 1: Write the failing assertion**

In `ExperimentFlowTest`, find `unknownCommandsAndOptionsAreRefused` (it begins around line 107) and add the `eval` line so the method reads:

```java
    /** A mistyped command or option is a bad request, not a stack trace and not a silent no-op. */
    @Test
    void unknownCommandsAndOptionsAreRefused() throws Exception {
        assertEquals(2, experiment().exit(), "no command at all");
        assertEquals(2, experiment("run", "--team", "x").exit(), "'run' is the host's business, not the driver's");
        assertEquals(2, experiment("eval", "--db", "x.db").exit(), "the evaluation harness was removed on 2026-09-14");
        assertEquals(2, experiment("init-db", "--wat", "x").exit());
        assertEquals(2, experiment("import", "--db", "x.db").exit(), "import needs a file");
        assertEquals(0, experiment("--help").exit());
    }
```

- [ ] **Step 2: Run it to make sure it fails**

```bash
cd server && mvn -B -q -pl forecast-core test -Dtest=ExperimentFlowTest#unknownCommandsAndOptionsAreRefused
```

Expected: FAIL. `eval` is still a real command, so it runs and exits 0 or 1 rather than 2. (It may take a minute: the test compiles and forks the driver.)

- [ ] **Step 3: Delete the eval test case**

In the same file, delete the whole `evalScoresTheDatabaseAndWritesTheHarnessFiles` method together with the javadoc block above it — everything from the line `    /**` that begins "The reason the driver exists: seed, load, score." down to and including the closing `    }` of the method (lines 79-105 as the file stands). Nothing else in the class refers to it.

- [ ] **Step 4: Delete the `eval` command from the driver**

In `server/tools/Experiment.java`, delete these regions, top to bottom:

1. The `eval` paragraph of the `USAGE` text block (the eight lines starting `              eval     --db FILE [--as-of ISO_DATE] …` and ending `… Default --out is ./eval/<as-of>.`).
2. The dispatch arm, one line inside the `switch`:

```java
                case "eval" -> eval(Args.parse(rest, Set.of("db", "as-of", "origins", "windows", "teams", "out"), Set.of()));
```

3. The `eval` method with its javadoc — from `    /**` above `     * The only command that boots the module.` through the method's closing `    }`.
4. The `// ---- wiring ---` comment line, the `Module` class with its javadoc, and the `boot` method with its javadoc. **Keep `static DataSource dataSource(Path db)`** immediately below them: `init-db`, `import` and `export` all call it.
5. `resolveTeam` with its javadoc, and `tryUuid` below it. Both existed only for `--teams`.
6. `private static List<String> split(String commaSeparated)`. Its only caller was `eval`.

Then delete these imports, which now have no referent:

```java
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.calendar.Horizon;
import com.workloadhub.forecast.eval.EvalConfig;
import com.workloadhub.forecast.eval.EvalResult;
import com.workloadhub.forecast.eval.Report;
import com.workloadhub.forecast.store.Dialect;
import java.util.UUID;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
```

Keep `java.util.ArrayList`, `java.util.LinkedHashMap`, `java.util.Map`, `java.util.List`, `java.util.Arrays`, `java.time.LocalDate`, `javax.sql.DataSource` and `org.sqlite.SQLiteDataSource` — all still used by the remaining verbs or by the `Args` helper.

- [ ] **Step 5: Prove no reference survives in the driver**

```bash
cd server && grep -n "eval\|Spring\|ForecastService\|resolveTeam\|JdbcClient\|Dialect" tools/Experiment.java
```

Expected: no output at all. Any hit is a leftover — remove it before continuing.

- [ ] **Step 6: Run the driver test class to verify it passes**

```bash
cd server && mvn -B -q -pl forecast-core test -Dtest=ExperimentFlowTest
```

Expected: PASS, and visibly faster than before — the deleted case was the one that booted Spring and fitted boosters in a child JVM.

- [ ] **Step 7: Update `server/README.md`**

Three edits.

Delete the last step of the worked example (the comment line and the command under it):

```bash
# 6. score the model on it
$X eval --db ~/whf/workloadhub.db --as-of 2026-09-06 --windows 2 --out ~/whf/eval
```

Delete the whole `| eval | …` row from the command table, and change the sentence under the table from "Those five are the whole of it: they build an experiment database and score the model on it." to:

```markdown
Those four are the whole of it: they build and move an experiment database. Everything a
```

(keep the rest of that sentence — "*host* does — starting a run…" — exactly as it is).

Then replace the `eval` sentence in the paragraph about `tools/experiment.sh`. The text currently running from "File arguments are resolved against your working directory. `eval`" to "…and need no Spring context." becomes:

```markdown
File arguments are resolved against your working directory. Every verb calls `forecast-core` classes
directly and needs no Spring context: the one that booted the module was `eval`, removed on 2026-09-14
with the evaluation harness (`docs/superpowers/specs/2026-09-14-evaluation-removal-design.md`).
```

Leave the sentence after it about `ExperimentFlowTest` untouched.

- [ ] **Step 8: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add server/tools/Experiment.java server/forecast-core/src/test/java/com/workloadhub/forecast/ExperimentFlowTest.java server/README.md
git commit -m "$(cat <<'EOF'
refactor(server): drop the eval command from the experiment driver

It was the only command that booted the module, so the driver stops being
a Spring application and becomes what its four remaining verbs make it: a
tool that builds and moves an experiment database. Removing the only
caller of ForecastService.evaluate first leaves the method free to delete.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GY3dFcwotsAwJV87fCjDnJ
EOF
)"
```

---

### Task 2: Delete `evaluate` and the harness behind it

With the driver clean, the public method has no caller. This removes it from the interface, from the implementation, and deletes the six harness files and their two tests.

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/ForecastService.java` (imports at lines 3-4; the javadoc and method at lines 25-30)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/service/DefaultForecastService.java` (the `evaluate` override at lines 258-275, and the now-unused imports)
- Delete: `eval/Harness.java`, `eval/EvalConfig.java`, `eval/EvalResult.java`, `eval/ScoreRow.java`, `eval/DemandRow.java`, `eval/Report.java`
- Delete: `test/.../eval/HarnessTest.java`, `test/.../eval/ReportTest.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/service/DefaultForecastServiceTest.java` (imports at lines 31-34; the test at lines 427-448; the `scored` helper at lines 450-452)

**Interfaces:**
- Consumes: a driver with no `evaluate` call (Task 1).
- Produces: a `ForecastService` interface whose methods are `startRun`, `getRun`, `listRuns`, `currentForecast`, `accuracy`, `progress`, `narrate`, `narrative`, `copilotStatus`. `api` no longer imports anything from `eval`; the dependency runs one way, `eval → api`. Task 4 relies on `Harness` being gone, since it was the only caller of the members pruned there.

There is no test to write first here: "a method no longer exists" is a compile-time fact, not a runtime assertion. The proof is that `test-compile` succeeds with every reference removed, and that the suite still passes.

- [ ] **Step 1: Delete the six harness files and their two tests**

```bash
cd /home/user/WorkLoadHubAiForecasting/server/forecast-core/src
git rm main/java/com/workloadhub/forecast/eval/Harness.java \
       main/java/com/workloadhub/forecast/eval/EvalConfig.java \
       main/java/com/workloadhub/forecast/eval/EvalResult.java \
       main/java/com/workloadhub/forecast/eval/ScoreRow.java \
       main/java/com/workloadhub/forecast/eval/DemandRow.java \
       main/java/com/workloadhub/forecast/eval/Report.java \
       test/java/com/workloadhub/forecast/eval/HarnessTest.java \
       test/java/com/workloadhub/forecast/eval/ReportTest.java
```

- [ ] **Step 2: Remove the method from the interface**

In `api/ForecastService.java`, delete the two imports:

```java
import com.workloadhub.forecast.eval.EvalConfig;
import com.workloadhub.forecast.eval.EvalResult;
```

and the javadoc block with the method it documents:

```java
    /**
     * Backtests the model against history: arrival accuracy per horizon, and demand accuracy of whole
     * replayed runs per team (spec section 13). This reads history and writes nothing, so it never touches a run.
     * A null {@link EvalConfig#asOf()} means the latest task creation date; {@link EvalResult#resolved()} says which.
     */
    EvalResult evaluate(EvalConfig config);
```

- [ ] **Step 3: Remove the implementation**

In `service/DefaultForecastService.java`, delete the javadoc and the whole override — the block that begins:

```java
    /**
     * The harness measures this service's own {@code runner}, with the capacity rule the runner was built with, so an
     * evaluation reports the engine the host actually runs rather than a separately configured copy of it.
     */
    @Override
    public EvalResult evaluate(EvalConfig config) {
```

down to its closing `    }`. Then delete the imports of `EvalConfig`, `EvalResult` and `Harness` from the top of the file.

Keep the `ForecastRepository` and `Truth` imports: `accuracy` immediately above uses both.

- [ ] **Step 4: Remove the evaluate case from the service test**

In `DefaultForecastServiceTest`, delete these four imports:

```java
import com.workloadhub.forecast.eval.EvalConfig;
import com.workloadhub.forecast.eval.EvalResult;
import com.workloadhub.forecast.eval.Harness;
import com.workloadhub.forecast.eval.ScoreRow;
```

Keep `import com.workloadhub.forecast.eval.Truth;` — two accuracy cases use it. Keep the `ForecastRunner` and `CapacityRule` imports; the service is built with both at line 74.

Then delete the javadoc and `evaluateBacktestsThroughTheServicesOwnRunner` test in full, and the private helper below it:

```java
    private static List<ScoreRow> scored(EvalResult result) {
        return result.scores().stream().filter(s -> !s.metric().equals("seconds")).toList();
    }
```

- [ ] **Step 5: Verify nothing references the deleted types**

```bash
cd /home/user/WorkLoadHubAiForecasting
grep -rn "Harness\|EvalConfig\|EvalResult\|ScoreRow\|DemandRow\|eval.Report\|\.evaluate(" server/ --include=*.java
```

Expected: no output. (`Accuracy.evaluate(...)` is a different method on a different class and lives in `eval/Accuracy.java`; if the grep shows those two call sites in `DefaultForecastService.accuracy`, that is correct and expected — they stay.)

- [ ] **Step 6: Compile main and test sources**

```bash
cd server && mvn -B -q -pl forecast-core test-compile
```

Expected: success, no output. A compile error naming one of the deleted types means a reference was missed.

- [ ] **Step 7: Run the service and eval test classes**

```bash
cd server && mvn -B -q -pl forecast-core test -Dtest='DefaultForecastServiceTest,AccuracyTest,AccuracyPropertyTest,TruthTest'
```

Expected: PASS. (`DefaultForecastServiceTest` uses Testcontainers for its PostgreSQL cases; without Docker those skip with a message, which is not a failure.)

- [ ] **Step 8: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add -A server/forecast-core/src
git commit -m "$(cat <<'EOF'
refactor(server): delete the offline evaluation harness

The features are settled and the model is locked, so the machinery that
measured one configuration against another has no purpose left. Removing
evaluate() also breaks the cycle between api and eval: the dependency now
runs one way, eval -> api. The per-run backtest is untouched.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GY3dFcwotsAwJV87fCjDnJ
EOF
)"
```

---

### Task 3: Delete the orphaned `AccuracyReport`

`AccuracyReport` writes `accuracy.csv` and `summary.md`. Its only caller was `AccuracyCommand`, deleted with `forecast-cli` on 2026-09-12; since then only its own test has called it. `accuracy()` returns `AccuracyResult` — rows and scores as data — and a host formats that however it likes.

**Files:**
- Delete: `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/AccuracyReport.java`
- Delete: `server/forecast-core/src/test/java/com/workloadhub/forecast/eval/AccuracyReportTest.java`

**Interfaces:**
- Consumes: nothing from Tasks 1-2; this is independent of them but sequenced here to keep one concern per commit.
- Produces: an `eval` package holding exactly four files — `Accuracy`, `RunDayForecast`, `Metrics`, `Truth`.

- [ ] **Step 1: Confirm it really is an orphan**

```bash
cd /home/user/WorkLoadHubAiForecasting
grep -rn "AccuracyReport" server/ docs/ CLAUDE.md
```

Expected: hits only in `eval/AccuracyReport.java`, `eval/AccuracyReportTest.java`, and prose in `docs/superpowers/specs/2026-09-11-accuracy-evaluation-design.md` (section 6, which Task 5 annotates). No production caller.

- [ ] **Step 2: Delete both files**

```bash
cd /home/user/WorkLoadHubAiForecasting/server/forecast-core/src
git rm main/java/com/workloadhub/forecast/eval/AccuracyReport.java \
       test/java/com/workloadhub/forecast/eval/AccuracyReportTest.java
```

- [ ] **Step 3: Verify the package is down to four files**

```bash
cd /home/user/WorkLoadHubAiForecasting && ls server/forecast-core/src/main/java/com/workloadhub/forecast/eval/
```

Expected exactly: `Accuracy.java  Metrics.java  RunDayForecast.java  Truth.java`

- [ ] **Step 4: Compile**

```bash
cd server && mvn -B -q -pl forecast-core test-compile
```

Expected: success, no output.

- [ ] **Step 5: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add -A server/forecast-core/src
git commit -m "$(cat <<'EOF'
refactor(server): delete the orphaned accuracy report writer

Its only caller was the AccuracyCommand removed with forecast-cli on
2026-09-12; since then nothing but its own test has called it. accuracy()
returns rows and scores as data and a host formats them as it likes.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GY3dFcwotsAwJV87fCjDnJ
EOF
)"
```

---

### Task 4: Prune the members whose only caller was the harness

Two files keep members that nothing calls now. Removing them changes no behaviour: every number the live run computes is unchanged. This is the one edit inside `backtest/`, a package `CLAUDE.md` guards behind a design document — section 4 of the spec records why it is in scope.

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/Metrics.java` (delete `coverage` lines 27-38 and `weightedQuantileLoss` lines 40-64, and the `java.util.Map` import at line 3)
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/eval/MetricsTest.java` (one assertion in `pointMetrics`, the whole `weightedQuantileLossMatchesTheDefinition` test, the `Map` import)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/backtest/Backtest.java` (the `Residual` record line 28-29; the `residualRows` component of `Result` and its accessor lines 52-56; the 4-arg `origins` lines 76-85; the `residualRows`/`frozenRows` bookkeeping inside `run`)

**Interfaces:**
- Consumes: a repository with no `Harness` (Task 2) — it was the only caller of all of these.
- Produces: `Metrics` with `mae`, `bias`, `overloadPrecisionRecall`; `Backtest.Result` as `record Result(List<Score> scores, Map<Integer, double[]> residuals, double seconds, double meanActualHours)` with `meanMae()` and `residuals(int)`; `Backtest.origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int maxHorizon)` as the only origins entry point.

- [ ] **Step 1: Prune `Metrics`**

Delete `coverage` and `weightedQuantileLoss` in full, and the now-unused `import java.util.Map;` at the top. The file keeps `mae`, `bias` and `overloadPrecisionRecall` — `Accuracy.score` calls all three.

- [ ] **Step 2: Prune `MetricsTest` to match**

Delete the `coverage` line from `pointMetrics`, so it reads:

```java
    @Test
    void pointMetrics() {
        assertEquals(1.0, Metrics.mae(new double[] {1, 2, 3}, new double[] {2, 3, 4}), 1e-9);
        assertEquals(1.0, Metrics.bias(new double[] {1, 2, 3}, new double[] {2, 3, 4}), 1e-9);
        assertTrue(Double.isNaN(Metrics.mae(new double[0], new double[0])));
    }
```

Delete `weightedQuantileLossMatchesTheDefinition` in full, and the `import java.util.Map;` it was the only user of. Leave `overloadPrecisionAndRecall` alone.

- [ ] **Step 3: Prune `Backtest`**

Three deletions in `backtest/Backtest.java`:

Delete the `Residual` record:

```java
    public record Residual(LocalDate origin, double y, double residual) {
    }
```

Change `Result` to drop `residualRows`, so the record and its accessors read:

```java
    public record Result(List<Score> scores, Map<Integer, double[]> residuals, double seconds, double meanActualHours) {

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

        /** The pooled residuals at one horizon, or an empty array when unknown. */
        public double[] residuals(int h) {
            double[] found = residuals.get(h);
            return found == null ? new double[0] : found;
        }
    }
```

Delete the 4-arg overload and fold `ORIGIN_COUNT` into the surviving method:

```java
    public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int maxHorizon) {
        List<LocalDate> out = new ArrayList<>();
        for (int k = ORIGIN_COUNT; k >= 1; k--) {
            LocalDate origin = lastCompleteWeek.minusWeeks((long) k * ORIGIN_STEP_WEEKS);
            if (ChronoUnit.WEEKS.between(firstWeek, origin) >= minHistoryWeeks(maxHorizon)) {
                out.add(origin);
            }
        }
        return out;
    }
```

- [ ] **Step 4: Drop the residual-row bookkeeping inside `run`**

Inside `Backtest.run`, delete the `residualRows` map declaration, the `rowPool` lines that fill it, the `frozenRows` copy at the end, and the argument in the returned `Result`. The loop body's residual handling becomes:

```java
                    List<Double> pool = residuals.computeIfAbsent(h, k -> new ArrayList<>());
                    for (int i = 0; i < y.length; i++) {
                        pool.add(y[i] - yHat[i]);
                        actualSum += y[i];
                        actualCount++;
                    }
```

and the return becomes:

```java
        return new Result(List.copyOf(scores), pooled, seconds, meanActual);
```

Then drop `java.util.LinkedHashMap` from the imports if nothing else in the file uses it. Leave `LOW_QUANTILE`, `HIGH_QUANTILE`, `quantile` and `intervalBounds` exactly as they are: `ForecastRunner.prepare` calls `intervalBounds` on every run.

- [ ] **Step 5: Compile**

```bash
cd server && mvn -B -q -pl forecast-core test-compile
```

Expected: success. A compile error here means something outside the harness used a pruned member — stop and report it rather than restoring the member blindly.

- [ ] **Step 6: Run the tests that cover the touched code**

```bash
cd server && mvn -B -q -pl forecast-core test -Dtest='MetricsTest,BacktestTest,ForecastRunnerTest,FactsBuilderTest'
```

Expected: PASS. `ForecastRunnerTest` and `FactsBuilderTest` are the guard that the live run's numbers did not move — the interval bounds, the run `mae` and the model facts all flow through them. A search of the test sources found no reference to `Residual`, `residualRows` or the four-argument `origins`, so `BacktestTest` should need no change; confirm that rather than expecting one.

- [ ] **Step 7: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add server/forecast-core/src/main/java/com/workloadhub/forecast/eval/Metrics.java \
        server/forecast-core/src/main/java/com/workloadhub/forecast/backtest/Backtest.java \
        server/forecast-core/src/test/java/com/workloadhub/forecast/eval/MetricsTest.java
git commit -m "$(cat <<'EOF'
refactor(server): prune the members only the harness called

Metrics.coverage and weightedQuantileLoss, and Backtest's Residual rows
and count-taking origins overload, had exactly one caller between them.
The per-run backtest computes what it always did: origins, pooled
residuals, meanMae, meanActualHours and intervalBounds are untouched.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GY3dFcwotsAwJV87fCjDnJ
EOF
)"
```

---

### Task 5: Update the documents

The code is done; the documents still describe a harness that no longer exists. The project's convention is that a superseded design stays readable as history with a dated note saying what overtook it — so the four specs get notes, not edits.

**Files:**
- Modify: `CLAUDE.md` (the reading list around line 17; "Where the project stands" lines 41-85; the layout block lines 119-120)
- Modify: `docs/backlog.md` (the "Java migration" rulings)
- Modify: `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md` (section 13 and section 11)
- Modify: `docs/superpowers/specs/2026-09-13-weekly-hours-forecast-design.md` (its harness sections)
- Modify: `docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md` (its harness mentions)
- Modify: `docs/superpowers/specs/2026-09-06-forecast-evaluation-and-chronos2-design.md` (already history; one note that the Java harness it led to is gone)
- Modify: `docs/eval/2026-09-10-java-parity-synthetic/` (the README or note file already in that directory)
- `server/README.md` was updated in Task 1; do not touch it again here.

**Interfaces:**
- Consumes: the finished code changes of Tasks 1-4.
- Produces: documents that match the tree. Task 6 adds the measured test count to `CLAUDE.md` after the gate runs, so leave that figure alone here.

- [ ] **Step 1: Note the removal on the four specs**

Add a note at the top of each spec's evaluation section — not a deletion. Use this wording, adjusted to name the section it sits in:

```markdown
> Removed on 2026-09-14. The offline evaluation harness this section describes —
> `ForecastService.evaluate(EvalConfig)`, `Harness`, `Report` and the driver's `eval` command — was deleted
> once the features were settled and the model locked
> (`docs/superpowers/specs/2026-09-14-evaluation-removal-design.md`). Read this section as history. The
> per-run backtest it shares vocabulary with is still there and still feeds every run's intervals and `mae`;
> `accuracy(teamId, from, to)` is unchanged.
```

- [ ] **Step 2: Note it on the parity evaluation record**

`docs/eval/2026-09-10-java-parity-synthetic/` already carries a note saying the parity procedure is gone. Extend it in the same voice:

```markdown
The harness that produced these numbers was removed on 2026-09-14
(`docs/superpowers/specs/2026-09-14-evaluation-removal-design.md`). This directory stays as the record of
the run.
```

- [ ] **Step 3: Update `CLAUDE.md`**

Three places.

The layout block, which lists the driver's verbs:

```text
           through `ForecastService`) and `server/tools/Experiment.java` (experiments on SQLite: init-db,
           import, export, seed)
```

"Where the project stands": the sentence ending "…and evaluation became a module feature, `ForecastService.evaluate(EvalConfig)`, which is the path the driver's `eval` takes." — keep it as the history it is, and add a sentence after the 2026-09-14 paragraph:

```markdown
Then, on 2026-09-14, the evaluation removal
(`docs/superpowers/specs/2026-09-14-evaluation-removal-design.md`): with the features settled and the model
locked, the offline harness had nothing left to measure, so `ForecastService.evaluate(EvalConfig)`,
`Harness`, `Report`, their row types, the orphaned `AccuracyReport` and the driver's `eval` command are
gone, and the driver is four verbs that build and move an experiment database. `accuracy(teamId, from, to)`
is the module's only evaluation surface; the per-run backtest still gives each window its interval and each
run its `mae`.
```

Leave every sentence about `forecast-cli` and its five commands exactly as it is, in the reading list near line 17 and in the 2026-09-12 paragraph. That is accurate history about a module that really did have five commands before it was deleted; the four-verb count in this plan is about `Experiment.java`, which is a different thing. Changing those numbers would falsify the record.

- [ ] **Step 4: Record it in the backlog**

Add one bullet under the "Java migration" rulings in `docs/backlog.md`:

```markdown
- **Evaluation removed (2026-09-14).** The offline harness is gone; `accuracy(teamId, from, to)` is the only
  evaluation surface. Nothing can now grade the model before a forecast is live — that is the accepted cost
  of locking the features and the model (`2026-09-14-evaluation-removal-design.md`, section 10). Reopening
  the question means restoring the harness from git history, deliberately.
```

- [ ] **Step 5: Check no document still promises the harness**

```bash
cd /home/user/WorkLoadHubAiForecasting
grep -rn "evaluate(EvalConfig)\|experiment.sh eval\|scores.csv\|demand.csv" CLAUDE.md server/README.md docs/ | grep -v "2026-09-14-evaluation-removal"
```

Expected: only lines inside the four annotated specs and the `docs/eval/` record, each now sitting under a note that says it is history. A live instruction anywhere else needs fixing.

- [ ] **Step 6: Commit**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add CLAUDE.md docs/
git commit -m "$(cat <<'EOF'
docs: the documents follow the evaluation removal

The four specs that describe the harness keep their text and gain a dated
note, as history does here; CLAUDE.md, the backlog and the parity record
say what the tree now holds.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GY3dFcwotsAwJV87fCjDnJ
EOF
)"
```

---

### Task 6: Run the gate and record the count

The only gate there is. It takes about seventeen minutes and must be read from the XML, never from the `.txt` files: a class mixing JUnit `@Test` with jqwik `@Property` has both engines write the same `.txt` and the second overwrites the first, so the text total runs about thirty short.

**Files:**
- Modify: `CLAUDE.md` (the test-count sentence, which stands at "421 tests, 0 failures, 13 skipped")

**Interfaces:**
- Consumes: every preceding task.
- Produces: a green gate and a recorded count. Nothing depends on this task; it is the finish line.

- [ ] **Step 1: Wipe the report directory**

```bash
cd /home/user/WorkLoadHubAiForecasting/server && rm -rf forecast-core/target/surefire-reports
```

- [ ] **Step 2: Run the gate**

```bash
cd /home/user/WorkLoadHubAiForecasting && bash scripts/check.sh
```

Expected: exit 0. On the owner's Windows machine this runs inside `bash scripts/devbox.sh shell`; run on the Windows host it would skip the Maven step and still exit 0, reporting success having compiled nothing.

- [ ] **Step 3: Read the real totals from the XML**

```bash
cd /home/user/WorkLoadHubAiForecasting/server/forecast-core/target/surefire-reports
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t = f = e = s = 0
for path in glob.glob("TEST-*.xml"):
    r = ET.parse(path).getroot()
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0))
    e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
print(f"{t} tests, {f} failures, {e} errors, {s} skipped")
PY
```

Expected: 0 failures and 0 errors. The total will be below the 421 that stood before this work — roughly a dozen fewer, from the three deleted test classes and the three trimmed cases. Skipped stays around 13 when Docker is absent.

- [ ] **Step 4: Record the count in `CLAUDE.md`**

Replace "The gate stands at 421 tests, 0 failures, 13 skipped." with the measured figures, in the same shape. Do not guess the number — copy what Step 3 printed.

- [ ] **Step 5: Commit and push**

```bash
cd /home/user/WorkLoadHubAiForecasting
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: record the gate after the evaluation removal

Measured from the surefire XML with the report directory wiped first, not
summed from the .txt files.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GY3dFcwotsAwJV87fCjDnJ
EOF
)"
git push -u origin claude/evaluation-system-workflow-m5jp68
```

---

## What must still be true at the end

A reviewer should be able to confirm each of these directly:

- `grep -rn "Harness\|EvalConfig\|EvalResult\|ScoreRow\|DemandRow\|AccuracyReport" server/ --include=*.java` returns nothing.
- `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/` holds four files: `Accuracy`, `Metrics`, `RunDayForecast`, `Truth`.
- `ForecastService` has nine methods and imports nothing from `com.workloadhub.forecast.eval`.
- `server/tools/Experiment.java` has four commands and no `org.springframework` import.
- `ForecastRunner.prepare` still calls `Backtest.run` and `Backtest.intervalBounds`; `MemberWindowForecast` still carries `lowHrs` and `highHrs`; the facts still carry `mae`, `mean_actual_hours` and `interval.basis`.
- No migration changed: `git diff --stat main -- server/forecast-core/src/main/resources/db` is empty.
- The gate is green and `CLAUDE.md` names the measured count.

---

## Closing notes — what actually happened (2026-09-14)

All six tasks landed on `dev`, then one whole-branch review, then one fix wave. Final state after the fix
wave: gate **409 tests, 0 failures, 0 errors, 13 skipped**, `main` not yet fast-forwarded.

**Commits:** `9f6833b` (1) · `92a0d7b` (3) · `ad3fd55` (2) · `8e6613f` (4) · `2e5a264` (5) · `b4c6755` (6).

**The plan's own task order was wrong.** Task 3 had to land before Task 2, against the order stated above.
`AccuracyReport` (Task 3's file) called `Report.csv` and `Report.fmt` unqualified, same package as `Report`
itself, so no import line existed for Task 2 step 5's grep to catch. Deleting `Report.java` while
`AccuracyReport.java` still stood broke the build. The pre-flight conflict scan had called T2/T3 "clean
either order" — that call was wrong; it checked for a shared file and missed the unqualified same-package
call. Resumed the Task 2 implementer with authority to commit Task 3's deletions first (as Task 3's own
commit), then Task 2's. **Lesson: a same-package reference needs a bare-name search, not a qualified-reference
grep** — `grep "eval\.Report"` finds nothing when the caller sits in `eval` too.

**The plan missed three prose sites naming the removed command.** Beyond the deleted files, `eval` survived
in three places the plan's file lists never named: two comments inside `server/tools/Experiment.java` (the
class javadoc's "the five things..." and the "---- the five commands ----" section marker), fixed during
Task 5; and `server/tools/experiment.sh` (its header comment and worked example), fixed in the review's fix
wave, after the final review caught it as finding I-1.

**The gate:** 409 tests, 0 failures, 0 errors, 13 skipped, down from 421. The drop reconciles exactly:
`HarnessTest` (6) + `ReportTest` (2) + `AccuracyReportTest` (1) + three trimmed cases (the `coverage`
assertion in `MetricsTest`, `weightedQuantileLossMatchesTheDefinition`, and `evaluateBacktestsThroughThe
ServicesOwnRunner` in `DefaultForecastServiceTest`) = 12; 421 − 12 = 409.

**Left deliberately alone, and why:**

- Historical plans under `docs/superpowers/plans/` and one older superseded spec still name the removed
  classes (`EvalConfig`, `Harness`, `Report`, …) in past tense. They are records of work done, not promises
  of live capability, and the house convention keeps history readable as written — so they stay.
- Commit trailers on this branch name Sonnet 5 on the implementation commits (`9f6833b`, `92a0d7b`,
  `ad3fd55`, `8e6613f`, `2e5a264`, `b4c6755`) and Opus 5 on the controller's own commits. Both are truthful —
  each names the model that actually authored the commit — and the session URL is identical either way, so
  no rewrite was made.

**Review findings and the fix wave:** the whole-branch review found no Critical issues and confirmed the
live forecast path unchanged; it raised six findings (I-1 through M-3), all fixed in one follow-up wave —
`server/tools/experiment.sh`'s stale `eval` references (I-1), these closing notes (I-2), the two overbroad
"history" banners in the 2026-09-09 module design spec (I-3), the now-dead
`ForecastRunner.capacityRule()` (M-1), the present-tense sentence in `CLAUDE.md` (M-2), and this spec's
missing reading-list entry (M-3).
