# Evaluation removal design

Date: 2026-09-14. Status: approved by the owner in chat (the scope question and the `AccuracyReport` question
below were both answered there). Amends the Java module design
(`2026-09-09-java-forecast-module-design.md`, section 11 public API and section 13 evaluation) and the
weekly-hours forecast design (`2026-09-13-weekly-hours-forecast-design.md`, its harness sections). Leaves the
accuracy evaluation design (`2026-09-11-accuracy-evaluation-design.md`) in force: everything it describes stays.

## 1. Goal

Delete the offline evaluation harness. The features are settled and the model is locked, so the machinery
that existed to measure one configuration against another has no remaining purpose. What stays is
`accuracy(teamId, from, to)`, which grades the forecasts the module actually made against the hours members
actually logged.

## 2. The two things called "evaluation"

The word covered two mechanisms that share a package and nothing else. Only the first is removed.

- **The offline harness** — `ForecastService.evaluate(EvalConfig)` → `Harness`, run from the experiment
  driver's `eval` command against a seeded SQLite database. It picks past origins, replays whole runs at each,
  and scores the booster (arrival level) and the pipeline (demand level) into `scores.csv`, `demand.csv` and
  `summary.md`. This is the benchmark, and it goes.
- **The per-run backtest** — `ForecastRunner.prepare` calls `Backtest.run` on every live run. Its residuals
  give each member-window its `lowHrs`/`highHrs` interval, its mean gives the run's `mae`, and three facts
  Copilot reads (`mae`, `mean_actual_hours`, `interval.basis`) come from it. This is uncertainty
  quantification for the run in hand, not model selection, and it is **not** touched.

The point forecast never depended on the backtest: `ForecastRunner.prepare` fits the booster it predicts with
on the full feature matrix, independently of any origin it scored.

## 3. What is deleted

`forecast-core` main sources:

| File | Why |
|---|---|
| `eval/Harness.java` | the harness |
| `eval/EvalConfig.java` | its request |
| `eval/EvalResult.java` | its result |
| `eval/ScoreRow.java` | arrival-level rows |
| `eval/DemandRow.java` | demand-level rows |
| `eval/Report.java` | writes `scores.csv`, `demand.csv`, `summary.md` |
| `eval/AccuracyReport.java` | orphan: its only caller was the `AccuracyCommand` deleted on 2026-09-12 |

and the method itself, from `api/ForecastService` and from `service/DefaultForecastService`.

`AccuracyReport` goes with them by the owner's ruling: `accuracy()` returns `AccuracyResult` — rows and scores
as data — and a host formats that however it likes. Keeping a file writer nobody calls is the shape the CLI
trim already rejected.

After this, `eval/` holds four files, every one of them with a live caller: `Accuracy` (the accuracy
computation), `RunDayForecast` (its per-run day row), `Metrics` (its arithmetic) and `Truth` (logged hours per
member and day, which `accuracy()` and several tests read).

## 4. What is pruned

Members whose only caller was the harness, removed with it. None of this changes behaviour.

- `eval/Metrics`: `coverage` and `weightedQuantileLoss` go. `mae`, `bias` and `overloadPrecisionRecall` stay —
  `Accuracy.score` uses all three.
- `backtest/Backtest`: the `Residual` record, the `residualRows` component of `Result` with its accessor, and
  the four-argument `origins(lastCompleteWeek, firstWeek, count, maxHorizon)` overload. The three-argument
  `origins` becomes the only entry point and keeps `ORIGIN_COUNT`. The live run path uses `origins`,
  `Result.residuals`, `Result.meanMae`, `Result.meanActualHours` and `intervalBounds`, all untouched.

This is the one edit inside `backtest/`, a package `CLAUDE.md` guards behind a design document; it is recorded
here for that reason. It removes dead members only — the per-run backtest computes exactly what it computed
before, and every number it produces is unchanged.

## 5. What does not change

The per-run backtest's behaviour and its `BACKTEST` progress phase; `lowHrs`/`highHrs` on every `MemberWindowForecast`;
`forecast_runs.mae` and `forecast_runs.backtest_json`; `RunResult.scores` and `api/BacktestScore`; the facts
contract, including `mae`, `mean_actual_hours` and `interval.basis = "backtest residuals"`; every migration;
`lifecycle/Truncation`, which the harness used for its replay but which four test classes also use; and
`accuracy()` in full, down to its MASE baseline.

No schema change. No facts-contract change. No change to what Copilot is told or how its numbers are verified.

## 6. A dependency edge that improves

`api/ForecastService` imports `eval.EvalConfig` and `eval.EvalResult` today, so the public API package depends
on the eval package while `eval` depends back on `api`. Removing the method removes that edge, and the
dependency runs one way only afterwards: `eval → api`. This is the same kind of cycle the fix wave of
2026-09-14 broke between `run` and `facts`.

## 7. The experiment driver afterwards

`server/tools/Experiment.java` loses the `eval` command and its help block. With it go the pieces that existed
only to serve it: `boot(Path, int)`, the `Module` `@Configuration` class, `resolveTeam`, and the imports for
`ForecastService`, `calendar.Horizon`, `store.Dialect`, `JdbcClient` and the seven `org.springframework.*`
types. `dataSource(Path)` and `SQLiteDataSource` stay; `init-db`, `import` and `export` use them through
`ExportImporter` and `ExportExporter`.

`eval` is the only command that boots the module, so the driver stops being a Spring application altogether
and becomes what its four remaining verbs make it: a tool that builds and moves an experiment database.
`server/tools/experiment.sh` and `server/tools/core-classpath.sh` are unchanged — the launcher still compiles
the file against `forecast-core`.

## 8. Testing

Deleted: `eval/HarnessTest`, `eval/ReportTest`, `eval/AccuracyReportTest`. The surviving eval tests are
`AccuracyTest`, `AccuracyPropertyTest`, `MetricsTest` and `TruthTest`.

Trimmed:

- `eval/MetricsTest`: the `coverage` and `weightedQuantileLoss` cases.
- `service/DefaultForecastServiceTest`: the `evaluate` cases. Its `accuracy` cases stay as they are.
- `ExperimentFlowTest`: `evalScoresTheDatabaseAndWritesTheHarnessFiles`. It boots Spring and fits boosters in
  a child JVM, so the class should get materially faster; the other verbs keep their coverage.
- `backtest/BacktestTest`: a search of the test sources found no reference to `Residual`, `residualRows` or
  the four-argument `origins`, so it should need no change; the step is to confirm that, not to expect one.

No test is added: nothing gains behaviour. The gate is `bash scripts/check.sh` run by hand in the development
container, read from `forecast-core/target/surefire-reports/TEST-*.xml` with the directory wiped first. The
count falls from the 421 that stands today; the new figure goes into `CLAUDE.md` once measured. CI is paused,
so the hand-run gate is the only gate.

## 9. Documents

- This spec, committed on the branch.
- `CLAUDE.md`: the layout line for the driver's commands, "Where the project stands", the test count, and the
  reading list entry that points at the harness.
- `server/README.md`: the `eval` row of the driver table, step 6 of the worked example, the "five commands"
  sentence (now four), and the paragraph explaining that `eval` boots the module.
- Dated deviation notes, not deletions, on the four specs that describe the harness — the module design
  (section 13), the weekly-hours design, the rolling-windows design and the 2026-09-06 chronos2 design. The
  project's convention is that a superseded document stays readable as history with a note saying what
  overtook it.
- `docs/backlog.md`: the removal recorded under the Java migration rulings.
- `docs/eval/2026-09-10-java-parity-synthetic/` stays as the record of the run that produced it, with a note
  that the harness which produced it is gone — exactly the treatment the retired parity procedure got.

## 10. The accepted cost

Afterwards nothing in the codebase can answer "is the model good?" before a forecast is live. Two signals
remain: `accuracy()`, which grades real forecasts once real weekdays have passed, and the per-run `mae` fact,
which reports how the booster scored on this run's own origins. Rebuilding a benchmark would mean restoring
the harness from git history.

This is chosen, not lost. The features are settled and the model is locked, so the question the harness
answered is closed; if it reopens, the answer is to bring the harness back deliberately, not to keep it idling.

## 11. Order of work

Each step leaves the tree compiling and the suite runnable.

1. Strip `eval` from `server/tools/Experiment.java` (command, help, `boot`, `Module`, `resolveTeam`, imports)
   and drop the eval case from `ExperimentFlowTest`. The leaf goes first, so nothing still calls the method
   when it disappears.
2. Delete `Harness`, `Report`, `EvalConfig`, `EvalResult`, `ScoreRow`, `DemandRow` with `HarnessTest` and
   `ReportTest`; remove `evaluate` from `ForecastService` and `DefaultForecastService`; trim
   `DefaultForecastServiceTest`.
3. Delete `AccuracyReport` and `AccuracyReportTest`.
4. Prune `Metrics.coverage` and `Metrics.weightedQuantileLoss` with their `MetricsTest` cases; prune
   `Backtest.Residual`, `Result.residualRows` and the four-argument `origins`; adjust `BacktestTest` if needed.
5. Update the documents of section 9.
6. Run the gate by hand in the development container and record the count.

## 12. Non-goals

Renaming the `eval` package (four specs name `eval.Truth.realisedHoursByDay`; accuracy is evaluation and the
name still fits). Deprecating `evaluate` for a release before deleting it (the only caller is in this
repository, there is no external consumer, and the project deletes rather than deprecates — `EffortModel`,
`SeasonalNaive`, `forecast-cli`). Touching the per-run backtest's behaviour. Adding a replacement benchmark.
Changing `accuracy()`, the facts contract, the schema or the narration.
