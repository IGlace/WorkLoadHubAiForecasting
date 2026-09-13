# Single-model simplification design

> **Superseded on 2026-09-13 by `2026-09-13-weekly-hours-forecast-design.md`.** It was approved but never
> implemented. Every ruling below still holds and is carried forward there, so implement from that document
> and read this one as history. Two points differ and the newer document wins: its section 13.2 keeps
> `TeamOutcome.plannedWorkEnabled` and the `planned_basis` fact, which go with `PlannedWork`; and its
> section 5 reads `mean_actual_hours` as mean arrival hours, which becomes mean logged hours.

Date: 2026-09-12. Status: approved by the owner in chat (five rulings recorded in section 1). Amends the Java
module design (`2026-09-09-java-forecast-module-design.md`, sections 8 models, 9 backtest, 11 public API and
13 parity) and the Copilot narration design (`2026-09-10-java-copilot-narration-design.md`, the facts
contract). Retires the parity procedure.

## 1. Goal and the owner's rulings

`forecast-core` carries a two-model tournament: `SeasonalNaive` and `XgboostArrival` compete at every
backtest origin, `Backtest.selectChampion` picks the winner by mean MASE, and a caller may override the
winner with `forcedModel`. The machinery that supports the competition — `ModelRegistry`, `Backtest.Champion`,
the `Map<String, Supplier<ArrivalModel>>` factories, `forcedModel` threaded through six layers, `mase_by_model`,
`model.unavailable`, `EvalConfig.models`, the `--models` flag — is larger than the models it arbitrates.

The owner's rulings, in the order they were made:

1. **Delete `SeasonalNaive` outright.** Not demoted to a MASE yardstick: the class goes. The backtest score
   becomes MAE in hours. The four consequences (section 15) were put to the owner and accepted.
2. **Retire the parity procedure entirely** rather than retarget it to MAE.
3. **The gate becomes `mvn verify` alone.** Retiring parity empties `server/tools/tests/`, so the pytest step
   and the `uv` precondition have nothing left to run.
4. **Thin history still forecasts.** A team with too little history to score a single origin — about 15 weeks,
   section 6 — gets numbers, marked as unscored, and the narrative says so when it narrates. Only a booster
   that cannot train at all fails the run.
5. **Two adjacent simplifications come along** (section 13), because they touch the same files: one home for
   the duplicated arithmetic, and one control for planned work. The rest of the over-engineering survey stays
   in `docs/backlog.md` for its own brainstorm (section 17).

XGBoost becomes the only arrival model. Nothing about demand, capacity, overload or narration verification
changes; the hard rules are untouched.

## 2. What is deleted from forecast-core

| File | Fate |
|---|---|
| `model/SeasonalNaive.java` | deleted |
| `model/ArrivalModel.java` | deleted — one implementation left, and its only test stub goes with the tournament |
| `run/ModelRegistry.java` | deleted |
| `model/XgboostArrival.java` | kept, becomes a plain class implementing nothing |
| `model/ModelUnavailable.java` | kept, still thrown by `fit` and `predict` |
| `model/EffortModel.java` | untouched — a different model of a different thing |

`ArrivalModel` earns nothing with a single implementation. Its only other user is the anonymous flaky model in
`BacktestTest:102`, which exists to exercise the `unavailable` path that this change removes.

`forcedModel` is removed from every layer it passes through: `web/RunRequestBody`, `api/RunRequest` (and its
`ModelRegistry.isKnown` validation), `service/DefaultForecastService`, `run/ForecastRunner.prepare`,
`run/Prepared`, and the `forecast_runs.forced_model` column.

## 3. Backtest after the change

`Backtest` stops being a tournament and becomes "score the booster over rolling origins". `origins`,
`intervalBounds`, `mae` and `quantile` survive unchanged, as do `ORIGIN_COUNT` 6, `ORIGIN_STEP_WEEKS` 2 and
`MIN_HISTORY_WEEKS` 13.

| Now | After |
|---|---|
| `run(feat, Map<String, Supplier<ArrivalModel>>, origins, horizons)` | `run(feat, origins, horizons)` |
| `Score(model, origin, horizon, mae, mase)` | `Score(origin, horizon, mae)` |
| `Result(scores, residuals, residualRows, unavailable, secondsPerModel)`, each map keyed by model then horizon | `Result(scores, residuals, residualRows, seconds)`, keyed by horizon alone |
| `FLOOR`, `Champion`, `selectChampion`, `mase`, `meanMase`, `meanMaseByModel` | deleted |

`Result` gains `meanMae()` (the mean of every scored origin and horizon, `NaN` when there are none) and
`meanActualHours()` (the mean of the backtest targets, for section 5). The prediction interval is unchanged:
pooled residuals per horizon, the 0.1 and 0.9 linear quantiles.

`ForecastRunner.prepare(data, asOf, progress)` loses its `forcedModel` parameter and its champion branch. It
fits one `XgboostArrival` on the full feature matrix and predicts, exactly as it does today for a champion.
`Prepared` loses `champion`, `championMase` and `forcedModel` and gains `mae` and `meanActualHours`.

## 4. Public API and the facts contract

`RunSummary` drops `forcedModel`, `championModel` and `championMase`, and gains `Double mae` — null when the
run was not scored. Field order otherwise unchanged:

```java
public record RunSummary(UUID id, UUID teamId, UUID requestedBy, LocalDate asOf, RunStatus status,
        Double mae, String error, LocalDateTime createdAt, LocalDateTime finishedAt) {}
```

`RunRequest` loses `forcedModel` and `RunRequestBody` loses it too (the body keeps refusing an `asOf` field with
400). Section 13.2 removes `plannedWork` from both as well, leaving `RunRequest(UUID teamId, UUID requestedBy)`.

`RunResult` drops `maseByModel` and `unavailable`, both of which only described a field of competitors:

```java
public record RunResult(RunSummary run, List<BacktestScore> scores, List<MemberWindowForecast> memberWindows,
        List<MemberDayForecast> memberDays, String factsJson) {}
```

`ModelScore` is renamed `BacktestScore` and becomes `(LocalDate origin, int horizon, Double mae)` — `model` is
constant and `mase` is gone, which leaves the name `ModelScore` describing nothing the record holds. The rename
is deliberate churn on an API that this change already breaks; say so if you would rather keep the old name.
`mae` stays `Double` and null (not `NaN`) when the stored backtest could not score the row.

The facts `model` map, built by `FactsBuilder.model`:

```json
"model": {
  "name": "xgboost",
  "mae": 2.31,
  "mean_actual_hours": 14.2,
  "confidence": "scored",
  "backtest_origins": ["2026-05-04", "2026-05-18"],
  "horizons": [1, 2, 3],
  "interval": {"basis": "backtest residuals", "horizons": {"1": {"low_offset": -3.1, "high_offset": 4.0}}},
  "planned_basis": "...",
  "limitations": "...",
  "seconds_by_phase": {"features": 0.4, "backtest": 12.1, "forecast": 3.3}
}
```

Removed keys: `champion`, `champion_mase`, `forced_model`, `mase_by_model`, `unavailable`. `name` is the
constant `"xgboost"` and stays so the narrative can name what produced the numbers. `confidence` is `"scored"`
or `"thin_history"` (section 6).

`DefaultForecastService.backtestJson` keeps the same envelope with the model key dropped from each score row
and `mase_by_model` replaced by `mean_mae`.

## 5. Making MAE interpretable

MAE in hours has no threshold: `2.31` does not say whether the forecast is good, and under the hard rule the
narrator must not guess one. `mean_actual_hours` — the mean of the backtest targets, one line in
`Backtest.run` — gives the narrative a verifiable comparison instead: *"off by about 2.3 hours a week against
a typical 14 hours logged"*. Both numbers are facts, so `NumberVerifier` checks them as it checks every other
number. No ratio is computed and none is stored; the narrative states the pair and the reader judges.

This is the accepted mitigation for losing MASE. It is not a reintroduction of the naive model: the mean of
the observed targets is a property of the data, not a forecast.

## 6. Thin history, and the one case that still fails

`Backtest.origins` keeps a candidate origin only when at least `MIN_HISTORY_WEEKS` (13) weeks separate it from
the first week of history. Candidates sit 2 to 12 weeks before the last complete week, so the nearest one needs
about 15 weeks of history and the list is empty below that. Per ruling 4 the run proceeds anyway:

- `Backtest.Result` has no scores; `mae` and `mean_actual_hours` are null in the facts and the `mae` column is
  null in `forecast_runs`;
- `confidence` is `"thin_history"`;
- there are no pooled residuals, so `intervalBounds` returns `{0, 0}` and the interval band collapses onto the
  point estimate. The facts must not present a zero-width band as certainty, so `interval.basis` becomes
  `"none: no scored origins"` and the per-horizon offsets are omitted;
- `whf-forecast-interpretation` instructs the narrative to say the numbers are unscored (section 10).

The run fails only when `XgboostArrival.fit` throws `ModelUnavailable` — no rows with a known target at some
horizon, or the native library is missing. That path already exists: `DefaultForecastService` catches it,
stores `FAILED` with the message in `forecast_runs.error`, and reports it through `getRun`. No new failure
handling is written.

## 7. `accuracy()` is unaffected

`ForecastService.accuracy(teamId, from, to)` keeps its MASE. `Accuracy.score` builds its own naive from the
logged hours of the same weekday seven days earlier (`Accuracy.java:121`) and never touches `SeasonalNaive`;
it calls `Backtest.mase` only as an arithmetic helper. That helper moves into `eval/Metrics` as `mase(y, yHat,
yNaive)` when `Backtest.mase` is deleted, and `Accuracy` calls it there. The accuracy evaluation design
(`2026-09-11-accuracy-evaluation-design.md`, section 4) is unchanged in substance; its phrase "the
seasonal-naive floor" refers to the last-week-same-weekday rule, not to the deleted class.

## 8. Evaluation harness and the experiment driver

`EvalConfig` becomes `(LocalDate asOf, int origins, List<UUID> teams)`. `Harness.evaluate` drops the
`ModelRegistry` validation and the factory filtering. `EvalResult` drops `skipped`, which only ever reported a
model that could not fit — a condition that now fails the evaluation instead.

`ScoreRow` and `DemandRow` lose their `model` column, changing the `scores.csv` and `demand.csv` headers.
The `mase` and `beats_naive` metric rows (`Harness.java:97-98`) are deleted; `mae`, `coverage80`, `wql` and
`seconds` remain. `Report` drops the "Models requested" line (`Report.java:114`).

`tools/Experiment.java` drops `--models` from the `eval` usage text and argument parsing; `tools/experiment.sh`
drops it from its header comment.

## 9. Retiring parity, and the gate

Deleted: `server/tools/parity.sh`, `server/tools/parity_compare.py`, `server/tools/tests/` (whose only file is
`test_parity_compare.py`). `server/tools/translate-schema.py` stays; it is unrelated and has no test today.

`docs/eval/2026-09-10-java-parity-synthetic/` is **kept** — it records a run that happened — and gains a note
saying the procedure that produced it was retired on 2026-09-12.

With `server/tools/tests/` gone the gate has one step:

- `scripts/check.sh` and `scripts/check.ps1`: the pytest step and its "SKIP ... uv not found" branch are
  removed. The "gate ran nothing" guard now fires only when `mvn` is missing.
- `.github/workflows/ci.yml`: the `astral-sh/setup-uv` step and the pytest run are removed. The file still
  cannot fire (no remote) and is kept in step with the two scripts, as CLAUDE.md requires.
- `scripts/release.sh` and `scripts/release.ps1`: the precondition becomes `mvn` alone
  (`release.sh:24-25`). `scripts/test-release.sh` needs no change — it exercises the release script on a
  throwaway repository and does not invoke the gate's Python half.

## 10. Product skills

Two embedded skills name the models and must be rewritten; `SkillTextsTest:27` pins the vocabulary and is
updated with them.

- `whf-domain/SKILL.md:32`: the **champion** bullet is replaced by a **model** bullet — the forecast comes from
  `xgboost`, a gradient booster over the engineered features; `mae` is its mean absolute error in hours over
  the backtest origins, alongside `mean_actual_hours` for scale; `confidence` says whether it was scored.
- `whf-forecast-interpretation/SKILL.md:16`: the "below 1.0 is reliable" rule is replaced by: state `mae` next
  to `mean_actual_hours` and let the reader judge, never call the forecast good or bad on your own; when
  `confidence` is `thin_history`, say plainly that the team has too little history to score the forecast and
  that the numbers carry no measured error.

Both stay factual, short and specific to this domain, as CLAUDE.md requires.

## 11. Schema

A new migration `V4__single_model.sql` in both `db/forecast/postgresql/` and `db/forecast/sqlite/`: drop
`forecast_runs.champion_model`, `champion_mase` and `forced_model`; add `mae double precision` / `REAL`.
`JdbcRunStore` follows in its INSERT, its two SELECT lists, `finish(...)` and the row mapper.

SQLite supports `ALTER TABLE ... DROP COLUMN` from 3.35 (2021). The bundled driver's version is checked when
the plan is written; if it is older, the migration rebuilds `forecast_runs` in the usual create-copy-drop-rename
form. Either way both dialects end with the same columns.

There is no deployed database to preserve: the experiment database is rebuilt by `init-db` and the server's own
integration is not written yet. The migration is still written as a migration so an existing development
database upgrades cleanly.

## 12. The sample host and the documentation

`server/examples/HostExample.java` is the reference for what a host does and must follow the API. Its `getRun`
section prints the champion, the per-model mean MASE and the unavailable map (lines 265-282), and its run-list
section prints `championModel` and `championMase` (line 334); both become `mae`, `mean_actual_hours` and
`confidence`. The commentary at line 228 describing `forcedModel` as the third constructor argument goes, as
does the explanation at lines 269-271 of why `seasonal_naive` scores a MASE of exactly 1.0.

Documents to update:

- `CLAUDE.md`: the domain vocabulary loses "champion model" and gains the single arrival model; the toolchain
  section states the one-step gate and drops `uv` from the release scripts' preconditions; the layout section
  drops "the parity scripts and their one Python test"; the "Where the project stands" paragraph drops parity
  from the next steps and records this simplification.
- `server/README.md`: the `eval` row of the command table loses `--models`; the REST table's `POST /runs` body
  loses `forcedModel`; the parity section and its pointer to `docs/eval/2026-09-10-java-parity-synthetic/` are
  removed; the narration section's description of the model facts is refreshed.
- `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`: deviation notes on sections 8, 9, 11 and
  13 pointing here, in the style the repository already uses for amended sections.
- `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md`: a deviation note on the facts contract.
- `docs/backlog.md`: the ruling recorded under "Java migration", including that the parity procedure is retired
  and the two-model question is closed.

## 13. Two adjacent simplifications folded in

Approved on 2026-09-12 after the over-engineering survey recorded in `docs/backlog.md`. Both touch files this
change already edits; everything else the survey found keeps its own brainstorm (section 17).

### 13.1 One home for the duplicated arithmetic

`round2` exists three times — `capacity/CapacityRule:139`, `run/ForecastRunner:66` (public static) and
`facts/FactsBuilder:358`, which only delegates to `ForecastRunner`'s. `mae` exists twice, in `backtest/Backtest:103`
and `eval/Metrics:11`.

The obvious home for the shared arithmetic is `eval/Metrics`, and it is the wrong one: `eval` already depends on
`backtest` (`Harness` calls `Backtest.run`), so moving `Backtest.mae` there would make `backtest` depend on `eval`
and close a package cycle. Instead a new neutral `com.workloadhub.forecast.Numbers` holds the three functions that
more than one package needs:

```java
public final class Numbers {
    public static double round2(double v);
    public static double mae(double[] y, double[] yHat);
    public static double mase(double[] y, double[] yHat, double[] yNaive);
}
```

`CapacityRule`, `ForecastRunner` and `FactsBuilder` call `Numbers.round2` and drop their copies; `Backtest` and
`Metrics` call `Numbers.mae`; `Accuracy` calls `Numbers.mase`. `eval/Metrics` keeps what is genuinely
evaluation-specific — `bias`, `coverage`, `weightedQuantileLoss`, `overloadPrecisionRecall`.

This supersedes section 7's statement that `mase` moves into `eval/Metrics`: it moves into `Numbers`, for the same
cycle reason. Section 7 is otherwise unchanged — `accuracy()` keeps its MASE.

### 13.2 One control for planned work

`plannedWork` is overridable at three levels for a setting nobody has asked to vary per request: the property
`whf.planned-work.enabled`, the `ForecastRunner.plannedWorkDefault` field it feeds, and a nullable `Boolean` on
`RunRequest` resolved by `plannedWorkOr(default)`. That is the same shape as the `forcedModel` override this
change deletes.

The property becomes the only control:

- `RunRequest` becomes `(UUID teamId, UUID requestedBy)` and loses `plannedWorkOr`; combined with section 4 it
  keeps only the two fields a caller genuinely supplies.
- `RunRequestBody` loses `plannedWork` alongside `forcedModel`.
- `ForecastRunner.forTeam(Prepared, UUID)` loses its `Boolean` parameter and reads its own `plannedWorkDefault`.
- `TeamOutcome.plannedWorkEnabled` **stays.** `FactsBuilder:308` reports `planned_basis` from it, so the fact is
  still built; it is now sourced from the single setting rather than a per-request override. (The option preview
  shown in chat said this field would go — it cannot, without dropping `planned_basis` from the facts, which is
  not intended.)

`ForecastRunner`'s constructor and `DefaultForecastService`'s `plannedWorkDefault` parameter are unchanged.

## 14. Testing

Test-driven, as every change here is. Suites that go: `SeasonalNaiveTest`, `ModelRegistryTest`, and within
`BacktestTest` the champion-selection, forced-model and flaky-model cases.

`XgboostArrivalTest:69` currently asserts the booster beats `SeasonalNaive` on a planted-signal fixture — the
test that catches a booster producing nonsense. Rather than a brittle hardcoded MAE threshold, the baseline
moves into the test: a two-line helper predicting each member's mean of the training target, which the booster
must beat on the fixture. Same protection, no production class.

Updated: `BacktestTest`, `ForecastRunnerTest`, `HarnessTest`, `FactsBuilderTest`, `DefaultForecastServiceTest`,
`JdbcRunStoreTest`, `ForecastControllerTest`, `SampleHostIntegrationTest`, `SkillTextsTest`, and the narration
fixtures that hardcode a champion (`NarrativeContractTest:53`, `NumberVerifierTest:26`, `PromptsTest:21`).

New coverage: a thin-history run produces forecasts with a null `mae`, `confidence` `"thin_history"` and an
interval basis of `"none: no scored origins"`; a run whose booster cannot fit ends `FAILED` with the
`ModelUnavailable` message in `error`. The jqwik property tests on arithmetic invariants are unaffected —
none of them involve model selection.

For section 13: `Numbers` gets its own jqwik property test for the invariants the three functions owe —
`mae` non-negative and zero exactly when the forecast equals the truth, `mase` the same against a non-degenerate
naive, `round2` idempotent and never more than 0.005 away from its input. `AccuracyProperties` is unchanged: it pins the
scores `Accuracy.score` produces end to end, not the helper underneath, so it keeps working through the move.
`RunRequestTest` loses `normalisesBlankModelAndDefaultsPlannedWork` entirely — with neither
`forcedModel` nor `plannedWork` there is nothing left to normalise — and keeps only the missing-team case.
`FactsToolsTest` and `FactsBuilderTest` must still show `planned_basis` in the facts, now driven by the
property alone, which is what proves `TeamOutcome.plannedWorkEnabled` earns its place.

## 15. What this costs, accepted

Put to the owner before the decision, and accepted:

1. **No scale-free score.** MASE is by definition an error ratio against a naive forecast; without one there is
   no ratio. `mae` plus `mean_actual_hours` (section 5) is the mitigation, but it is a pair of numbers the
   reader compares, not a single calibrated one.
2. **No independent cross-check.** The parity gate was the only check that the Java port computes what the
   archived Python service did, and it was never run against the real export. After this the Java pipeline is
   self-verifying only.
3. **A weaker booster regression test.** A constant-mean baseline in the test is a lower bar than the seasonal
   naive it replaces.
4. **No graceful degradation.** A missing XGBoost native library now fails the run rather than falling back.
   The owner judged an honest failure better than a silent naive forecast, and the module targets one Linux
   toolchain.

## 16. Rollback

Every deletion is one revert away while the work sits on `dev`: the branch is reverted and `main` is untouched,
since `main` only ever fast-forwards. The database migration is additive-then-subtractive in one file, so
reverting the code and dropping `V4` (or restoring the experiment database with `init-db`) returns the schema.
No data is migrated and none is lost that a rerun cannot reproduce.

## 17. Out of scope

The owner described this as the first of several simplifications ("starting from"). This spec covers the
two-model tournament, what falls out of it, and the two adjacent items of section 13. Nothing else.

An over-engineering survey of `forecast-core` ran on 2026-09-12 and is recorded in `docs/backlog.md` under
"Java migration". Its remaining findings each get their own brainstorm and are explicitly **not** in this plan:

- `seed/` shipping inside the library (2,300 lines, about a fifth of the module's main source) — the largest
  win, and structural rather than behavioural;
- the Copilot CLI-subprocess path (`whf.copilot.cli-path`), a deliberate ruling of the 2026-09-10 narration
  plan that is worth revisiting rather than an accident;
- `facts/Clustering`, 210 lines of k-means producing one integer per member;
- the speculative settings on `ForecastProperties`.

The `web` REST surface was examined and ruled **not** a cleanup target: it defaults to disabled, but the host
integration design records the sample host driving it as the demonstrated integration path. Removing it would be
a product decision, not a simplification.
