# Weekly hours forecast design

Date: 2026-09-13. Status: designed in chat with the owner on 2026-09-13 (eight rulings in section 1); written
for review, two points still open (section 18).

**Supersedes `2026-09-12-single-model-simplification-design.md`.** That spec was approved but never
implemented; every ruling in it still holds and is carried forward here, so this document is the only one to
implement from. Where the two differ — its section 13.2 keeps `TeamOutcome.plannedWorkEnabled` and the
`planned_basis` fact, and its section 5 reads `mean_actual_hours` as mean arrival hours — this document wins
and says so at the point of difference.

Amends the Java module design (`2026-09-09-java-forecast-module-design.md`, sections 6 features, 8 models,
9 backtest, 11 public API, 13 parity), the Copilot narration design
(`2026-09-10-java-copilot-narration-design.md`, the facts contract), the rolling windows design
(`2026-09-10-rolling-forecast-windows.md`, the fixed two windows) and the accuracy evaluation design
(`2026-09-11-accuracy-evaluation-design.md`, section 4).

## 1. Goal and the owner's rulings

`forecast-core` forecasts something nobody asked for. The booster's target is *fresh estimated arrival hours*
— the sum of the estimates on tasks newly assigned to a member in a week (`WeeklySeries.Cell.plus`,
`FeatureBuilder:84`). Estimated arrival hours are not hours worked, so a second model, `EffortModel`, converts
them: it learns an estimate-to-actual ratio, a cycle length and a lateness offset per member, and spreads each
task's converted hours across days. A third component, `PlannedWork`, allocates unassigned backlog through the
same converter. Demand is the sum of the three.

Meanwhile `accuracy()` and the evaluation harness score the result against **logged hours** from `time_logs`.
The module trains on one quantity and is measured on another, and `EffortModel` is the undocumented bridge
between them.

The owner's rulings, in the order they were made:

1. **The booster's target becomes logged hours per member-week**, read from `time_logs`. Training and
   measurement become the same quantity.
2. **`EffortModel` and everything that exists to serve it are deleted.** The forecast is future weekly hours
   and nothing else: *"we don't need to know how many hours for each task arriving for the member we only care
   about the forecasting for next weeks hours"*.
3. **The number of windows a run forecasts becomes configurable**: default 2, minimum 1, maximum 6.
4. **The window count is a property only**, `whf.forecast.windows`. It is not a per-request field;
   `RunRequest` keeps only `(teamId, requestedBy)`.
5. **`PlannedWork` becomes a feature, not an addend.** Unassigned team backlog enters the booster as an input
   column; it is no longer allocated to named members and added to demand. `PlannedWork.allocate` is deleted.
6. **A week's predicted hours are spread across its weekdays weighted by the member's weekday habits**, not
   evenly.
7. **`XgboostArrival` is renamed `XgboostHours`** — it no longer forecasts arrivals.
8. **The default weekly capacity is 44 hours**, as `docs/requirements/requirements-v1.md` (C1) has always
   said. The code's `40.0` is a defect (section 10).

Carried forward unchanged from the superseded spec: `SeasonalNaive` is deleted outright and the backtest score
becomes MAE in hours; the parity procedure is retired; the gate becomes `mvn verify` alone; a team with too
little history still gets forecasts, marked unscored; the duplicated `round2`/`mae`/`mase` move to a new
`com.workloadhub.forecast.Numbers`.

The hard rules are untouched. Deterministic code still computes demand, capacity and overload; the language
model still produces no number; demand is still never capped by capacity.

## 2. The pipeline after the change

```
time_logs ──────────────► weekly logged hours per member  ─────► TARGET
tasks, projects, teams ─► arrival, backlog and mix features ┐
capacity, absences, holidays ─► per-horizon capacity features ┼──► XgboostHours, one booster per horizon
                                                             ┘
                                    │
                   predicted hours per member × horizon week
                                    │
                   split across the week's weekdays by the member's logged-hours weekday shares
                                    │
                   demand per member-day  ──► window = sum of its five days
                                    │
                   capacity per member-day (CapacityRule) ──► overload = max(0, demand − capacity)
```

One model, one target, one arithmetic. What used to be three components summed into demand is now one
prediction placed onto days.

## 3. The target

`target_h{h}` for member *m* at origin week *W* becomes the hours *m* logged in week *W + h*: the sum of
`time_logs.hours` over rows whose `user_id` is *m* and whose `Weeks.mondayOf(day)` is *W + h*. This is exactly
`eval/Truth.realisedHours`, which is the yardstick `accuracy()` and the harness already use.

The function must live in one place and be reachable from both `features` and `eval`. `eval` already depends
on `features` (`Harness` uses `FeatureMatrix`), so the reverse direction would close a cycle: the series is
built in `features/WeeklySeries`, and `eval/Truth.realisedHours` delegates to it.

`WeeklySeries.build` gains the `ForecastData` it needs for the time logs:

```java
public static WeeklySeries build(Lifecycle lc, ForecastData data, List<MemberRow> members, List<LocalDate> weeks);
public double[] logged(UUID member);   // new: hours logged per week, the target series
public double[] fresh(UUID member);    // kept: fresh estimated arrival hours, now a feature
public double[] est(UUID member);      // kept: all estimated arrival hours, now a feature
```

`FeatureBuilder:84` becomes `r[col.get(Features.target(h))] = i + h <= originIndex ? logged[i + h] : Double.NaN;`.

**A member with no logged hours in a week has a target of 0, not a missing value.** That is the truthful
reading — the member logged nothing — and it matches `Truth`, which reports the same weeks as absent from its
map and zero when asked. Weeks before a member's start are already excluded by `startIndex`.

### 3.1 Features that change

`ownHistory` currently builds `lag{1,2,3,4,8,13}`, `roll_mean_{4,8,13}` and `roll_std_{4,8,13}` from the
arrival series. They move to the logged series: the target's own history is the strongest predictor of the
target, and the shape of the code is unchanged.

That makes `logged_hours_lag1..4` (written by `throughput`) exactly `lag1..lag4`, so those four columns are
deleted. The arrival information does not disappear — it becomes explanatory rather than the thing predicted —
and is added back under an honest name:

| Column | Now | After |
|---|---|---|
| `lag{1,2,3,4,8,13}`, `roll_mean_{4,8,13}`, `roll_std_{4,8,13}` | fresh arrival hours | **logged hours** |
| `logged_hours_lag1..4` | logged hours | **deleted** (now `lag1..lag4`) |
| — | — | **`arrival_hrs_lag1..4`**: fresh estimated hours assigned in each of the last four weeks |
| `weeks_since_last_arrival` | arrival series | unchanged — it is about arrivals |
| `team_backlog_unassigned_hrs` | present, `TeamContext` | unchanged — **this is ruling 5**: the team's unassigned backlog is already a feature column, so `PlannedWork` becoming "a feature" needs no new column, only the deletion of the addend (section 6) |
| `due_hrs_h{h}`, `working_days_h{h}`, `absence_hrs_h{h}`, `available_hrs_h{h}` | per horizon | unchanged, and now more directly relevant: `available_hrs_h{h}` is the ceiling on what a member can log |
| `fresh_hours`, `est_hours` (stored series values) | stored per row | **deleted** — `Features.FRESH` was read only by `SeasonalNaive`, and `lag1` is by definition the row's own week |

The shared column count stays 42 (four removed, four added) and `featureColumns(h)` stays 46, so nothing about
the matrix's shape changes. `Features.FRESH` and `Features.EST` are deleted; `SyntheticMatrix` (the test
helper that writes them) is updated.

`Features.HISTORY_WEEKS` stays 65.

## 4. Choosing how many windows to forecast

A **window** is five weekdays. A run's first window starts the first weekday after the run day; windows are
contiguous. Today `Horizon.WINDOWS = 2` is a compile-time constant.

New property, and the only control:

| Property | Default | Range |
|---|---|---|
| `whf.forecast.windows` | `2` | 1 to 6 |

- `ForecastProperties` gains `private final Forecast forecast = new Forecast();` with a nested
  `public static class Forecast { private int windows = 2; ... }`, in the same shape as the existing `Copilot`,
  `Web` and `Flyway` holders, so the key is `whf.forecast.windows`. The nested `PlannedWork` holder is deleted
  in the same edit (section 6).
- `ForecastAutoConfiguration` validates the value when it builds `ForecastRunner` and **fails start-up** with
  a message naming the property and the allowed range when it is outside 1 to 6. A forecast horizon silently
  clamped to something the operator did not ask for is worse than a refusal at boot.
- `Horizon.WINDOWS` is deleted. `Horizon.windows(LocalDate asOf, int windows)` takes the count;
  `Horizon.MIN_WINDOWS = 1` and `Horizon.MAX_WINDOWS = 6` hold the range so the check and the tests share one
  definition.
- `ForecastRunner`'s constructor becomes `(CapacityRule capacityRule, int windows)` — the
  `boolean plannedWorkDefault` argument goes with `PlannedWork` (section 6). `prepare` reads the field.

Everything downstream already derives from the window list rather than from the constant: `Horizon.days`,
`Horizon.horizons`, `FactsBuilder`'s `run.windows`, the day and window tables, and `Accuracy`. No caller
assumes "two" except in text, which section 14 corrects.

### 4.1 Horizons and the maximum horizon

A **horizon** is a whole-week offset after `origin`, and `origin = Weeks.lastCompleteWeek(asOf)`, which is
always `mondayOf(asOf).minusWeeks(1)`. One booster is fitted per horizon.

For any run day, *w* windows touch horizons whose maximum is exactly **w + 1**. `Weeks.lastCompleteWeek` is
always the previous week's Monday, so the run day's own week is horizon 1; 5*w* weekdays starting somewhere
inside horizon 1 or 2 end no later than the last weekday of horizon *w + 1*. Two windows give a maximum of 3
today; six give 7. The minimum horizon is 1 or 2 depending on the run day, so a run fits between *w* and
*w + 1* boosters.

`Horizon.horizons(origin, windows)` already derives the list from the days and is unchanged. A
`Horizon.maxHorizon(origin, windows)` helper is added for the backtest, and a property test pins it to
`windows + 1` for every run day.

### 4.2 The backtest hold-out and the history a run needs

`Backtest.run` trains on weeks up to `origin − maxH` so a training row's target cannot overlap the test week
(`Backtest:126`). With six windows `maxH` is 7 rather than 3, so a fixed `MIN_HISTORY_WEEKS = 13` would leave
only 6 trainable weeks at the earliest origin instead of 10.

**Proposed** (open for the review, section 18): `MIN_HISTORY_WEEKS` becomes `10 + maxHorizon` — 13 at two
windows, exactly today's value, and 17 at six. `Backtest.origins` takes the maximum horizon:

```java
public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int maxHorizon);
public static List<LocalDate> origins(LocalDate lastCompleteWeek, LocalDate firstWeek, int count, int maxHorizon);
```

The two-argument convenience overload that `ForecastRunner:93` calls today keeps its role, gaining the maximum
horizon rather than defaulting it — a silent default here would reintroduce the leak the hold-out exists to
prevent.

`ORIGIN_COUNT` 6 and `ORIGIN_STEP_WEEKS` 2 are unchanged. The nearest candidate origin sits 2 weeks before the
last complete week, so a team needs about 15 weeks of history to score anything at two windows and about 19 at
six. Below that the run still forecasts and is marked `thin_history` (section 9).

## 5. Splitting a week across its weekdays

Today a predicted week's hours land evenly on that week's working days (`ForecastRunner:169-185`). They now
land in proportion to the member's own weekday habits.

**The basis is a new series.** `Patterns.weekdayShares` counts the weekday a task was *assigned* on, by task
count (`Patterns:47`) — it describes when work arrives, not when the member works, and it is the wrong
denominator for hours. A second series is computed from `time_logs` over the same 13-week window: the share of
the member's logged hours falling on each of Monday to Friday. It is reported as a new pattern fact
`logged_weekday_shares` next to the existing `weekday_shares`, which keeps its meaning and gains a sentence in
`whf-pattern-discovery` distinguishing the two.

The rule, for member *m* and horizon week *W* with prediction *P*:

1. Let *K* be the working days of *W* per the `WorkingCalendar` (weekends and holidays excluded). If *K* is
   empty, *W* contributes nothing.
2. Let *s_d* be *m*'s logged weekday share for day *d*, renormalised to sum to 1 over *K*. If every share over
   *K* is zero — a member with no logged history, or all their mass on a holiday — fall back to an even split,
   which is exactly today's behaviour.
3. Day *d* receives `P × s_d`, but only days inside the run's horizon are emitted.

Step 3 is deliberate and unchanged in substance: a **holiday** inside the week redistributes its hours onto the
remaining working days (step 1 excluded it from *K*), while a day **already past** drops its hours, because
days already past are not re-forecast. The `limitations` fact already states this and keeps doing so.

Rounding is unchanged: each day figure is rounded to two decimals, the window figure is the rounded raw sum,
and the existing comment at `ForecastRunner:210` explaining the few-hundredths difference stays.

## 6. What is deleted

| File | Fate |
|---|---|
| `model/EffortModel.java` | deleted |
| `model/SeasonalNaive.java` | deleted (carried from the superseded spec) |
| `model/ArrivalModel.java` | deleted — one implementation left |
| `model/XgboostArrival.java` | renamed `model/XgboostHours.java`, a plain class implementing nothing |
| `model/ModelUnavailable.java` | kept, still thrown by `fit` and `predict` |
| `run/ModelRegistry.java` | deleted |
| `planned/PlannedWork.java` | deleted, package and all |
| `calendar/HourPlacement.java` | deleted — its only production callers are `EffortModel` and `PlannedWork` |
| `capacity/CapacityRule.offDays` and `FULL_DAY_HOURS` | deleted — `offDays` exists only to feed the two placements above, and `8.0` would be wrong beside an 8.8-hour day (section 10) |

`ForecastProperties` loses the nested `PlannedWork` class and the `whf.planned-work.enabled` property, and
gains `whf.forecast.windows`. `ForecastRunner` loses `plannedWorkDefault`; `DefaultForecastService` loses the
constructor parameter that fed it.

`TeamOutcome` loses `plannedWorkEnabled`, `openHours`, `newHours` and `planned`, leaving
`(Prepared, teamId, members, memberWindows, memberDays)`. **This is where this spec overrides section 13.2 of
the superseded one**, which kept `plannedWorkEnabled` so `planned_basis` could still be built: with
`PlannedWork` gone there is no basis to report and the fact goes too.

`Prepared` loses `champion`, `championMase`, `forcedModel` and `effort`, and gains `mae` and
`meanActualHours`. `predictedEst` is renamed `predictedHours`.

`forcedModel` is removed from `web/RunRequestBody`, `api/RunRequest`, `service/DefaultForecastService`,
`run/ForecastRunner.prepare`, `run/Prepared` and the `forecast_runs.forced_model` column.

A sweep for newly-dead code runs as the last task of the plan: `TaskFacts` accessors, `Lifecycle` helpers and
`Family`/`Mode` uses that lose their only caller are deleted with it, or kept with a one-line reason.

## 7. Demand, the band and the public API

`ForecastRunner.band` loses the `ratio` argument — residuals are now already in logged hours, so there is
nothing to convert — and the three-way split collapses:

```java
public record Band(double demand, double low, double high, double overload) {}

public static Band band(double demand, double q10, double q90, double capacity) {
    double d = round2(Math.max(0.0, demand));
    return new Band(d, round2(Math.max(0.0, d + q10)), round2(d + q90), round2(Math.max(0.0, d - capacity)));
}
```

`q10 ≤ 0 ≤ q90` holds as today (`ForecastRunner:120`), so `low ≤ demand ≤ high`; `low` is floored at zero
because a member cannot log negative hours.

The API records lose the three-way split:

```java
public record MemberDayForecast(UUID userId, LocalDate day, int windowIndex, double demandHrs, double capacityHrs,
        double overloadHrs, boolean workingDay) {}

public record MemberWindowForecast(UUID userId, int windowIndex, LocalDate windowStart, LocalDate windowEnd,
        double demandHrs, double lowHrs, double highHrs, double capacityHrs, double overloadHrs, int workingDays,
        double absenceHrs, double backlogExcessHrs) {}

public record CurrentDayForecast(UUID teamId, UUID userId, LocalDate day, UUID runId, double demandHrs,
        double capacityHrs, double overloadHrs, LocalDateTime forecastAt) {}

public record RunRequest(UUID teamId, UUID requestedBy) {}

public record RunSummary(UUID id, UUID teamId, UUID requestedBy, LocalDate asOf, RunStatus status, Double mae,
        String error, LocalDateTime createdAt, LocalDateTime finishedAt) {}

public record BacktestScore(LocalDate origin, int horizon, Double mae) {}   // was ModelScore

public record RunResult(RunSummary run, List<BacktestScore> scores, List<MemberWindowForecast> memberWindows,
        List<MemberDayForecast> memberDays, String factsJson) {}
```

`backlogExcessHrs` is section 8. `RunResult` drops `maseByModel` and `unavailable`; `ModelScore` is renamed
`BacktestScore` because neither `model` nor `mase` survives in it.

## 8. Backlog pressure, so overload does not go quiet

A logged-hours target is censored by what a person can physically work: nobody logs 60 hours in a 44-hour week,
so a model trained on logged hours will rarely predict above capacity and `overload` will rarely fire. The
arithmetic still obeys the hard rule — demand is not capped, `overload = max(0, demand − capacity)` — but the
signal the feature exists to give would fade.

The owner's ruling is a **separate deterministic backlog-pressure fact**, computed from facts that already
exist, so every number a narrative could state stays verifiable by `NumberVerifier`:

- Each member's window row gains `backlog_excess_hrs = round2(max(0, open_est_hours − capacity_hrs))`, where
  `open_est_hours` is the member's open remaining hours already reported in `patterns` and `capacity_hrs` is
  the window's capacity. It answers "does this person hold more open work than this window can absorb", which
  a censored demand forecast cannot.
- `rebalancing_candidates` gains a `backlog_pressed` list beside `overloaded` and `underloaded`, holding the
  members whose `backlog_excess_hrs` is above zero in any window.

No new source data, no new model, no ratio or threshold invented. `whf-forecast-interpretation` gains the
rule: when `overload` is zero but `backlog_excess_hrs` is not, say the member is not predicted to exceed their
hours but is carrying more open work than the window can absorb.

## 9. The backtest, MAE and thin history

Unchanged from the superseded spec except for the hold-out argument of section 4.2 and the meaning of the
numbers. `Backtest` stops being a tournament:

| Now | After |
|---|---|
| `run(feat, Map<String, Supplier<ArrivalModel>>, origins, horizons)` | `run(feat, origins, horizons)` |
| `Score(model, origin, horizon, mae, mase)` | `Score(origin, horizon, mae)` |
| `Result(scores, residuals, residualRows, unavailable, secondsPerModel)` keyed by model then horizon | `Result(scores, residuals, residualRows, seconds)` keyed by horizon |
| `FLOOR`, `Champion`, `selectChampion`, `mase`, `meanMase`, `meanMaseByModel` | deleted |

`Result` gains `meanMae()` and `meanActualHours()`. The prediction interval is unchanged: pooled residuals per
horizon, the 0.1 and 0.9 linear quantiles.

**`mean_actual_hours` now means the mean of the backtest targets in logged hours** — the average week a member
of this team actually logs. That is a far more useful companion to `mae` than the arrival-hours mean the
superseded spec described, and it makes the narrative's comparison concrete: *"off by about 2.3 hours a week
against a typical 31 hours logged"*. Both are facts and both are verified.

Thin history behaves exactly as the superseded spec ruled: no scores, `mae` and `mean_actual_hours` null,
`confidence` `"thin_history"`, `intervalBounds` returning `{0, 0}` with `interval.basis` set to
`"none: no scored origins"` so a zero-width band is never read as certainty, and the skill saying so. The run
fails only when the booster cannot fit at all, which `DefaultForecastService` already handles.

`accuracy()` **gains** from this change rather than being unaffected. `Accuracy.score` builds its naive from
the logged hours of the same weekday seven days earlier (`Accuracy.java:121`) and compares against
`Truth.realisedHoursByDay`. With the forecast now targeting logged hours, its MASE compares like with like
end to end for the first time. `Backtest.mase` moves to `Numbers.mase` (section 16) and `Accuracy` calls it
there; the accuracy design's phrase "the seasonal-naive floor" refers to that rule, not to the deleted class.

## 10. Capacity: the 44-hour default, and what overload actually is

`docs/requirements/requirements-v1.md` C1 says "Default weekly capacity per person, default **44 hours**", and
line 85 fixes the working day at 44 ÷ 5 = 8.8 h. `ForecastProperties:11` has `40.0`. `docs/backlog.md:414`
records how the two parted: the archived Python importer's converter was set to 40 h *to match the Java
module's default*, aligning the two to each other instead of to the requirement.
`server/examples/HostExample.java:119` has been overriding the property to `44` ever since, which is why nobody
noticed.

The default becomes `44.0`. `server/README.md`'s property table and `CLAUDE.md`'s opening paragraph are
corrected with it, and `HostExample` drops its override, since setting a property to its own default only
suggests the default is wrong.

**How much this changes is small, and the spec should say so.** The default is the last fallback in
`CapacityRule`, not the normal path:

1. The member's own `team_capacity` row for that week → `available` used verbatim.
2. Otherwise the latest earlier row's `base`, scaled by the week's working days over five, minus absence hours.
3. Only when the member has no capacity row at all → `whf.default-weekly-hours`.

On real WorkloadHub data with a capacity plan, 40 → 44 changes nothing for anyone who has rows. It bites on
seeded databases and on members with no plan.

**Overload is confirmed as the owner described it**, and this change does not alter it:

- Per day: `overloadHrs = max(0, demandHrs − dayCapacity)` (`ForecastRunner:213`).
- Per window: `overload = max(0, demand − capacity)` inside `band` (`ForecastRunner:77`), where the window's
  capacity is the **sum of its five days' capacities**, not a weekly figure — a window starts the first weekday
  after the run and straddles two ISO weeks, so each day draws capacity from its own week's row.
- Demand is never capped. A holiday or full-absence day has zero capacity, so any hours landing there are
  entirely overload; step 1 of section 5 keeps predicted hours off those days.

## 11. Schema

One migration, `V4__weekly_hours.sql`, in both `db/forecast/postgresql/` and `db/forecast/sqlite/`:

- `forecast_runs`: drop `forced_model`, `champion_model`, `champion_mase`; add `mae double precision` / `REAL`.
- `forecast_member_windows`: drop `open_hrs`, `new_hrs`, `planned_hrs`; add
  `backlog_excess_hrs double precision NOT NULL`.
- `forecast_member_days` and `forecast_current_days`: drop `open_hrs`, `new_hrs`, `planned_hrs`.

`JdbcRunStore` follows in its INSERTs, SELECT lists, `finish(...)` and row mappers.

SQLite has supported `ALTER TABLE ... DROP COLUMN` since 3.35 (2021); the bundled driver's version is confirmed
when the plan is written, and if it is older the migration rebuilds each table in the create-copy-drop-rename
form. `forecast_current_days_team_idx` is on `(team_id, day)` and so does not block a drop. Either way both
dialects end with the same columns.

There is no deployed database: the experiment database is rebuilt by `init-db` and the server's own integration
is not written yet. The migration is still written as a migration so a development database upgrades cleanly.

## 12. The facts contract

Removed from the facts, with their producers:

| Key | Where | Why |
|---|---|---|
| `open_hours`, `new_hours`, `planned_hours` | member `forecast` rows and `days` rows | the split no longer exists |
| `team.planned_backlog` | `FactsBuilder.team` | `PlannedWork` deleted |
| `likely_work.planned` | member facts | the per-member allocation is gone |
| `planned_basis` | `model` | no basis to report |
| `champion`, `champion_mase`, `forced_model`, `mase_by_model`, `unavailable` | `model` | no tournament |
| `history_13w[].fresh_hours` | member facts | the history block reports logged hours (below) |

Added or changed:

- Member `forecast` rows gain `backlog_excess_hrs` (section 8).
- `rebalancing_candidates` gains `backlog_pressed` (section 8).
- `patterns` gains `logged_weekday_shares` (section 5).
- `history_13w` reports `{week, logged_hours, arrival_hours, tasks}` — the member's own history of the
  quantity now being forecast, with arrivals kept beside it as context.
- The `model` map:

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

`windows` is added so a narrative can state how far ahead it is reading without counting the window list.

`FactsTools`: `get_planned_work` is renamed `get_likely_work` and returns only `project_roles` and
`recent_mix` per member — `planned_backlog` and the per-member `planned` list are gone. The tool list, the
`ToolSpec` descriptions naming "open, new and planned hours", and the ordering sentence at `FactsTools:77` are
updated. `DefaultForecastService.backtestJson` keeps its envelope with the model key dropped from each score
row and `mase_by_model` replaced by `mean_mae`.

`FactsBuilder.LIMITATIONS` is rewritten: predicted hours are spread over a week's working days by the member's
logged-hours weekday shares; days already past are not re-forecast; the forecast is of hours logged, so a
member who logs less than they work is forecast to work less.

## 13. Product skills

`SkillTextsTest` pins the vocabulary and is updated with them.

- `whf-domain`: the **demand** bullet loses the open/new/planned decomposition and becomes "predicted hours a
  member will log in the window, never capped"; the **champion** bullet becomes a **model** bullet naming
  `xgboost`, its target, `mae` beside `mean_actual_hours` and `confidence`; the window sentence says a run
  covers between one and six contiguous windows and that `model.windows` says how many; `backlog_excess_hrs`
  and `backlog_pressed` are documented; `planned_basis` and `planned_backlog` go.
- `whf-forecast-interpretation`: the "where the demand comes from" rule is replaced by the backlog-pressure
  rule of section 8; the "below 1.0 is reliable" rule is replaced by stating `mae` next to
  `mean_actual_hours` and letting the reader judge, never calling the forecast good or bad; a
  `thin_history` run is described plainly as unscored; `due_hours` above capacity keeps its rule.
- `whf-likely-work`: the planned-allocation source and its `high` confidence rule go. The top confidence
  becomes `medium` (a live project matching a role the member already holds), and the skill says so. This is
  a real loss and section 17 records it.
- `whf-pattern-discovery`: `logged_weekday_shares` is added and distinguished from `weekday_shares` in one
  sentence.

All stay factual, short and specific to this domain, as CLAUDE.md requires.

## 14. Evaluation harness, the experiment driver and the sample host

`EvalConfig` becomes `(LocalDate asOf, int origins, List<UUID> teams, int windows)` with `windows` defaulting
to 2. `Harness` constructs its own `ForecastRunner` outside Spring, so it cannot read the property; passing the
count is the only way to evaluate a horizon other than the default. `Harness.evaluate` drops the
`ModelRegistry` validation and the factory filtering; `EvalResult` drops `skipped`.

`ScoreRow` and `DemandRow` lose their `model` column and `DemandRow` loses `openHrs`, `newHrs` and
`plannedHrs` (`Harness:163`), changing the `scores.csv` and `demand.csv` headers. The `mase` and `beats_naive`
metric rows are deleted; `mae`, `coverage80`, `wql` and `seconds` remain. `Report` drops its "Models
requested" line.

`server/tools/Experiment.java`: `eval` drops `--models` and gains `--windows N` (1 to 6, default 2), refusing
anything outside the range with the same message the auto-configuration uses. `server/tools/experiment.sh`
follows in its header comment. This is the one place the window count is not a property, because the driver is
not a host and has no property source — flagged in section 18.

`server/examples/HostExample.java`: the `getRun` section stops printing the champion, the per-model MASE and
the unavailable map and prints `mae`, `mean_actual_hours` and `confidence`; the run-list section follows; the
commentary on `forcedModel` as a constructor argument goes, as does the explanation of why `seasonal_naive`
scores exactly 1.0; the `whf.default-weekly-hours = 44` override is removed (section 10); a line sets
`whf.forecast.windows` to show where the horizon is chosen.

## 15. Documentation

- `CLAUDE.md`: "40 h/week default" → 44; "covers ten weekdays in two windows" → one to six contiguous windows,
  two by default; the domain vocabulary drops "arrival model, effort model, champion model, backtest" in
  favour of the single model and its target; the toolchain section states the one-step gate and drops `uv`
  from the release preconditions; the layout drops "the parity scripts and their one Python test"; "Where the
  project stands" records this change.
- `server/README.md`: the property table gets `whf.default-weekly-hours` `44.0` and `whf.forecast.windows`
  `2`, and loses `whf.planned-work.enabled`; the `POST /runs` body loses `forcedModel` and `plannedWork`; the
  two-window sentences become the configurable horizon; the `eval` row swaps `--models` for `--windows`; the
  parity section and its pointer go; the narration section's model facts are refreshed.
- Deviation notes, in the style the repository already uses: on
  `2026-09-09-java-forecast-module-design.md` sections 6, 8, 9, 11 and 13; on
  `2026-09-10-java-copilot-narration-design.md` for the facts contract; on
  `2026-09-10-rolling-forecast-windows.md` for the configurable count; on
  `2026-09-11-accuracy-evaluation-design.md` section 4 for the restored MASE interpretation.
- `2026-09-12-single-model-simplification-design.md` was marked superseded by this document in the same commit
  that added it, so the two never disagree in the tree.
- `docs/eval/2026-09-10-java-parity-synthetic/` is kept — it records a run that happened — and gains a note
  that the procedure producing it was retired.
- `docs/backlog.md`: the rulings recorded under "Java migration", including that the two-model question, the
  parity procedure and the effort/planned-work decomposition are all closed.

## 16. Retiring parity, and the gate

Carried from the superseded spec. Deleted: `server/tools/parity.sh`, `server/tools/parity_compare.py`,
`server/tools/tests/`. `server/tools/translate-schema.py` stays.

With `server/tools/tests/` empty the gate has one step. `scripts/check.sh` and `scripts/check.ps1` lose the
pytest step and its skip branch, and the "gate ran nothing" guard fires only when `mvn` is missing;
`.github/workflows/ci.yml` loses `setup-uv` and the pytest run and is kept in step even though it cannot fire;
`scripts/release.{sh,ps1}` require `mvn` alone. `scripts/test-release.sh` needs no change.

A new neutral `com.workloadhub.forecast.Numbers` holds `round2`, `mae` and `mase`. `eval/Metrics` is the
obvious home and the wrong one: `eval` already depends on `backtest`, so moving `Backtest.mae` there would
close a package cycle. `CapacityRule`, `ForecastRunner` and `FactsBuilder` call `Numbers.round2` and drop
their copies; `Backtest` and `Metrics` call `Numbers.mae`; `Accuracy` calls `Numbers.mase`. `Metrics` keeps
`bias`, `coverage`, `weightedQuantileLoss` and `overloadPrecisionRecall`.

## 17. Testing

Test-driven throughout, with jqwik properties for the arithmetic invariants, as CLAUDE.md requires.

**Deleted suites**: `EffortModelTest`, `EffortModelPropertyTest`, `PlannedWorkTest`,
`PlannedWorkPropertyTest`, `HourPlacementTest`, `SeasonalNaiveTest`, `ModelRegistryTest`, and within
`BacktestTest` the champion-selection, forced-model and flaky-model cases.

**The regression test that matters most**: a fixture where a member's logged hours and their assigned
estimates deliberately diverge — logging half of what is estimated — asserting that the target series, the
prediction and the stored demand track the logged hours and not the estimates. Without it, nothing proves the
retarget happened.

**New property tests**:

- `band`: `demand ≥ 0`, `low ≤ demand ≤ high`, `low ≥ 0`, `overload = max(0, demand − capacity)` and
  `overload = 0` exactly when `demand ≤ capacity`.
- The weekday split: for a week wholly inside the horizon the day hours sum to the week's prediction within
  rounding; no day is negative; a non-working day receives nothing; an all-zero share vector reproduces the
  even split.
- `Horizon`: for every `w` in 1 to 6 and every run day, `windows(asOf, w)` yields `w` windows of exactly five
  weekdays, contiguous and starting the first weekday after the run day, and `maxHorizon(origin, w) == w + 1`.
- `Numbers`: `mae` non-negative and zero exactly when the forecast equals the truth; `mase` likewise against a
  non-degenerate naive; `round2` idempotent and never more than 0.005 from its input.

**New cases**: `whf.forecast.windows` outside 1 to 6 fails start-up with a message naming the property and the
range; a run at `windows = 6` produces 30 day rows and 6 window rows per member and fits 7 horizons; a
thin-history run forecasts with a null `mae`, `confidence` `thin_history` and an interval basis of
`"none: no scored origins"`; a run whose booster cannot fit ends `FAILED` with the `ModelUnavailable` message;
`backlog_excess_hrs` is positive exactly when open remaining hours exceed the window's capacity;
`CapacityRule` falls back to 44 hours when a member has no capacity row.

`XgboostHoursTest` replaces the `SeasonalNaive` comparison at `XgboostArrivalTest:69` with a two-line baseline
in the test — each member's mean of the training target — which the booster must beat on a planted-signal
fixture. Same protection, no production class.

**Updated**: `BacktestTest`, `FeaturesTest`, `FeatureBuilderTest`, `WeeklySeriesTest`, `ForecastRunnerTest`,
`ForecastRunnerPropertyTest`, `PatternsTest`, `FactsBuilderTest`, `FactsToolsTest`, `HarnessTest`, `TruthTest`,
`DefaultForecastServiceTest`, `JdbcRunStoreTest`, `ForecastControllerTest`, `SampleHostIntegrationTest`,
`SkillTextsTest`, `HorizonTest`, `SyntheticMatrix`, `RunRequestTest` (which keeps only its missing-team case),
and the narration fixtures hardcoding a champion (`NarrativeContractTest:53`, `NumberVerifierTest:26`,
`PromptsTest:21`).

## 18. Open for this review

Two points were not put to the owner before this document was written:

1. **`MIN_HISTORY_WEEKS = 10 + maxHorizon`** (section 4.2). The alternative is to leave it at 13 and accept
   that a six-window run trains on 6 weeks at its earliest origin. The derivation keeps the usable training
   span constant at the cost of needing about 19 weeks of history before a six-window run scores anything.
2. **`--windows N` on the experiment driver** (section 14). Ruling 4 made the window count a property only;
   the driver has no property source, so it needs a flag or it can only ever evaluate the default. Presented
   as a consequence of the driver not being a host, not as a re-opening of the ruling.

## 19. What this costs, accepted

Stated plainly so the review can weigh them:

1. **Overload goes quieter, and the 44-hour default makes it quieter still.** Logged hours are censored by
   what a person can work; a model trained on them will rarely predict above a 44-hour capacity. Section 8 is
   the mitigation and it now carries essentially the whole overload story rather than sharing it.
2. **The forecast inherits logging discipline.** A member who works 40 hours and logs 25 is forecast to log
   25. The module cannot tell under-logging from under-working, and the narrative must not pretend otherwise:
   `data_quality.unlogged_tasks` already flags the tasks, and `limitations` now says it in words. This is the
   sharpest edge of the retarget and it is the direct consequence of measuring what is recorded.
3. **The demand breakdown is gone.** A narrative can no longer say "most of this is work already on their
   plate". `open_est_hours`, `due_hours` and `backlog_excess_hrs` recover part of it; the per-window
   attribution does not come back.
4. **"Likely work" weakens.** Without the planned allocation, no task can be named as probably landing on a
   member, and the skill's top confidence drops from `high` to `medium`.
5. **Six windows cost real time.** Up to 7 boosters per backtest origin instead of 3 — about 42 fits per run
   against 18 — four more feature columns per horizon, a 7-week hold-out and roughly 19 weeks of history
   before anything scores.
6. **A breaking API and schema change**, and the four costs the superseded spec already recorded and the owner
   accepted: no scale-free backtest score, no independent cross-check once parity retires, a weaker booster
   regression test, and no graceful degradation when the XGBoost native library is missing.

## 20. Rollback

Every deletion is one revert away while the work sits on `dev`: the branch is reverted and `main` is untouched,
since `main` only ever fast-forwards. The schema change is a single migration file, so reverting the code and
dropping `V4` — or rebuilding the experiment database with `init-db` — returns the schema. No data is migrated
and none is lost that a rerun cannot reproduce.

## 21. Out of scope

This spec covers the retarget to logged hours, the deletions that fall out of it, the configurable window
count, the capacity default and the backlog-pressure fact. Nothing else.

The remaining findings of the 2026-09-12 over-engineering survey keep their own brainstorms and are explicitly
**not** here:

- `seed/` shipping inside the library (about 2,300 lines) — the largest remaining win, and structural;
- the Copilot CLI-subprocess path (`whf.copilot.cli-path`), a deliberate ruling worth revisiting;
- `facts/Clustering`, 210 lines of k-means producing one integer per member — note that it reads
  `estimateRatioMedian` and `cycleDaysMedian`, which survive as *pattern statistics* even though `EffortModel`
  is deleted, so it keeps working;
- the speculative settings on `ForecastProperties`.

The `web` REST surface stays: it defaults to disabled, and the host integration design records the sample host
driving it as the demonstrated path. Removing it would be a product decision, not a simplification.
