# Weekly hours forecast design

Date: 2026-09-13, reviewed 2026-09-14. Status: designed in chat with the owner on 2026-09-13 (eight rulings in
section 1) and **reviewed by the owner on 2026-09-14**, which closed the two open points and took seven
further rulings, then three more as the review closed, all in section 18. Ready for an implementation plan.

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

**Every logged hour falls on a weekday** (owner, 2026-09-14, section 18.4): WorkloadHub does not record hours
on a Saturday, a Sunday, a public holiday or a day the member is absent. A working week is five days and a
44-hour week is 8.8 hours on each of them. So the week's sum needs no day filter — summing every `time_logs`
row of the week is already a sum over working days — and `Truth` stays exactly as it is. If such a row ever
did appear, it would join its week's total and be spread over that week's working days by section 5, which is
the sane fallback; no code special-cases it.

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
| `fresh_hours`, `est_hours` (stored series values) | stored per row | **deleted** — `Features.FRESH` was read only by `SeasonalNaive`, `Features.EST` by nothing at all, and the arrival value of the row's own week returns as `arrival_hrs_lag1` |

The **shared** column count stays 42 (four removed, four added). `Features.FRESH` and `Features.EST` are
deleted; `SyntheticMatrix` (the test helper that writes them) is updated. `Features.EST` is already dead code
— written at `FeatureBuilder:93` and read by nothing in production — so it costs nothing to remove.

`Features.HISTORY_WEEKS` stays 65.

### 3.2 The horizon columns are not fixed, and `Features.HORIZONS` must follow the window count

An earlier draft of this section claimed "nothing about the matrix's shape changes". That is true of the
shared columns and **false** of the per-horizon ones. `Features.HORIZONS` is the hardcoded array `{1, 2, 3}`
(`Features.java:9`), and section 4 makes the maximum horizon `windows + 1`, which reaches 7 at six windows.
Five column families are per horizon — `target_h{h}`, `due_hrs_h{h}`, `working_days_h{h}`, `absence_hrs_h{h}`
and `available_hrs_h{h}` — so a six-window run needs 35 of them where a two-window run needs 15.

**Ruling (owner, 2026-09-14, section 18.3): the horizon columns are sized from the configured window count.**
`Features.HORIZONS` stops being a constant and becomes a function of that count, as do `allColumns()` and
`featureColumns(h)`. At the default of two windows the matrix is byte-for-byte what it is today, which keeps
the change honest; at six it is wider, and a run pays only for the horizons it asked for. The consequence,
stated so nobody trips on it: **a feature matrix built under one window count cannot be reused under
another**, so the harness, the experiment driver and every test that builds a matrix must pass the same count
they run with. No new field is needed to police this: a matrix already names its columns, so `Backtest`
checks that every horizon it is asked for has its `target_h{h}` column present and fails with a message
naming the missing horizon, rather than silently reading a column that is not there.

**The existing lag columns are off by one and stay that way.** `ownHistory` computes `j = i - (lag - 1)`
(`FeatureBuilder:129`), so `lag1` is the row's *own* week, not the week before it. This is leakage-safe
because every target is a strictly later week, and `logged_hours_lag1` uses the same convention
(`FeatureBuilder:288`), which is why the two coincide and the four `logged_hours_*` columns can go. The new
`arrival_hrs_lag1..4` **follow the same convention**, so that one meaning of "lag 1" holds across the matrix.
The alternative — one true lag beside five off-by-one ones — is worse. `Features` gains a comment saying so,
because the next reader will otherwise assume the plain reading.

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

Everything **downstream of the window list** already derives from it rather than from the constant:
`Horizon.days`, `Horizon.horizons`, `FactsBuilder`'s `run.windows`, the day and window tables, and `Accuracy`.
Two pieces of code upstream of it do assume two, and each has its own section: `Features.HORIZONS`, the
hardcoded `{1, 2, 3}` (section 3.2), and `Backtest.MIN_HISTORY_WEEKS`, the fixed 13 (section 4.2). An earlier
draft of this paragraph claimed no caller assumes two except in text; that was wrong, and those two sections
exist because of it.

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

**Ruled** (owner, 2026-09-14, section 18.1): `MIN_HISTORY_WEEKS` becomes `10 + maxHorizon` — 13 at two
windows, exactly today's value, and 17 at six. The usable training span stays constant at every setting; the
cost is that a six-window run needs about 19 weeks of history before it scores anything, and is marked
`thin_history` until then. `Backtest.origins` takes the maximum horizon:

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

1. Let *K* be the working days of *W* per the `WorkingCalendar`, **with weekends, holidays and the member's
   full-absence days excluded**. If *K* is empty, *W* contributes nothing.

   The absence part is a defect this document nearly carried forward. Section 6 deletes `CapacityRule.offDays`
   as dead once `EffortModel` and `PlannedWork` go, but it is the only thing that produces a member's
   full-absence days, and today's even split never passes them either (`ForecastRunner:176` passes an empty
   set). Without them, predicted hours land on a day of zero capacity and read as pure overload, and ruling
   18.4 says no hour is ever logged on such a day. So `offDays` **survives**, moved next to the split that now
   uses it, and `FULL_DAY_HOURS` is deleted in favour of comparing a day's absence hours with that day's own
   capacity: a fixed 8.0 would be wrong beside an 8.8-hour day, and the seed writes 8.8-hour absence rows
   after section 22.
2. Let *s_d* be *m*'s logged weekday share for day *d*, **renormalised over *K* alone**, not over the five
   weekdays. If every share over *K* is zero — a member with no logged history, or one whose whole logged mass
   sits on weekdays that are holidays or absences *in this particular week* — fall back to an even split over
   *K*, which is exactly today's behaviour. (Shares are per weekday and, by ruling 18.4, no hour is ever
   logged on a holiday, so the zero case is about this week's calendar, never about the shares themselves.)
3. Day *d* receives `P × s_d`, but only days inside the run's horizon are emitted.

Step 3 is deliberate and unchanged in substance: a **holiday** inside the week redistributes its hours onto the
remaining working days (step 1 excluded it from *K*), while a day **already past** drops its hours, because
days already past are not re-forecast. The `limitations` fact already states this and keeps doing so.

Rounding is unchanged in substance: each day figure is rounded to two decimals and the window figure is the
rounded raw sum, so the two can differ by a few hundredths. The comment at `ForecastRunner:210` explains that
difference in terms of rounding "each component", and the components are the open, new and planned split that
section 7 collapses, so the comment is **rewritten** for the single figure rather than kept.

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
| `capacity/CapacityRule.FULL_DAY_HOURS` | deleted — a fixed `8.0` is wrong beside an 8.8-hour day (section 10); a full-absence day becomes one whose absence hours meet that day's own capacity |
| `capacity/CapacityRule.offDays` | **kept**, against the first draft, which deleted it with its two callers. It is the only producer of a member's full-absence days and section 5 step 1 now needs them |

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
        double absenceHrs, double backlogExcessHrs, double dueExcessHrs) {}

public record CurrentDayForecast(UUID teamId, UUID userId, LocalDate day, UUID runId, double demandHrs,
        double capacityHrs, double overloadHrs, LocalDateTime forecastAt) {}

public record RunRequest(UUID teamId, UUID requestedBy) {}

public record RunSummary(UUID id, UUID teamId, UUID requestedBy, LocalDate asOf, RunStatus status, Double mae,
        String error, LocalDateTime createdAt, LocalDateTime finishedAt) {}

public record BacktestScore(LocalDate origin, int horizon, Double mae) {}   // was ModelScore

public record RunResult(RunSummary run, List<BacktestScore> scores, List<MemberWindowForecast> memberWindows,
        List<MemberDayForecast> memberDays, String factsJson) {}
```

`backlogExcessHrs` and `dueExcessHrs` are section 8. `RunResult` drops `maseByModel` and `unavailable`;
`ModelScore` is renamed `BacktestScore` because neither `model` nor `mase` survives in it.

## 8. Pressure the forecast itself cannot show

This section was written to rescue `overload` from a target that would silence it. That premise did not
survive the review: WorkloadHub puts no cap on logging, so overtime is recorded as it happens, a model
trained on logged hours can predict above capacity, and `overload = max(0, demand − capacity)` keeps firing
(ruling 18.6, and section 19 item 1, which the first draft got wrong).

What the forecast still cannot show is the **queue behind the person**. A prediction of hours logged says what
someone will get through, never how much is waiting: a member handed far more work than a window holds logs a
long week, not the whole pile, so demand reports the overrun and says nothing about the backlog that caused
it. Two facts cover that gap, both deterministic and both computed from facts that already exist, so every
number a narrative could state stays verifiable by `NumberVerifier`:

### 8.1 Two pressures, two facts

The owner's review (2026-09-14) separated two things the first draft had run together. **Undated pressure** is
holding more open work than the coming windows can get through. **Dated pressure** is work that must finish
inside a particular window whatever else happens, which is precisely the trigger for a team leader to move
hours off that member and onto someone with room. Both are deterministic, both are computed from facts that
already exist, and neither invents a ratio or a threshold.

**Undated: `backlog_excess_hrs`, cumulative and measured against the forecast** (rulings 18.2 and 18.10).
Each member's window row gains

```
backlog_excess_hrs(k) = round2(max(0, open_est_hours − Σ demand_hrs(1..k)))
```

where `open_est_hours` is the member's open remaining hours already reported in `patterns` and `demand_hrs` is
this run's own predicted demand for each window.

Two decisions are folded into that one line. **Cumulative** (18.2), because the first draft compared the same
open backlog with each window separately, which prints one number repeatedly — six nearly identical figures at
six windows — and invites a reader to add them up. **Against predicted demand rather than capacity** (18.10),
which was put to the owner as a choice and answered with the reason that settles it: the forecast is not a
fact a team leader trusts outright, it is an analysis that flags who has free hours and who is buried, and
what it misses the narrative is there to catch. So the number a leader reads should be what is left over
after the work the model expects to get done, not an arithmetic remainder against raw capacity. It says: *this
much of the queue is still standing when the coming windows are over.*

The cost, stated because the document should not hide it: this fact now depends on the model. An optimistic
forecast makes the leftover look smaller, where a capacity comparison would have stayed put. It remains a
fact in the JSON and `NumberVerifier` still checks anything the narrative says about it, but it is no longer
an independent check *on* the forecast — it is a reading *of* it. `capacity_hrs` stays on every window row, so
a reader who wants the arithmetic remainder can still see both numbers side by side.

It stays non-increasing across a run's windows, because cumulative demand only grows.

**Dated: `due_excess_hrs`, per window** (ruling, section 18.5). Each member's window row also gains

```
due_excess_hrs = round2(max(0, due_hours − capacity_hrs))
```

against that window's own capacity, because a deadline belongs to its window and does not roll forward.
`due_hours` — the remaining hours of the member's open tasks falling due inside the window — is already built
(`FactsBuilder:203`) and `whf-forecast-interpretation` already tells Copilot that `due_hours` above capacity
means deadlines are the pressure. What it cannot do today is say **by how much**: `NumberVerifier` checks every
number in the narrative against the facts it was given, so a gap Copilot subtracts for itself matches no fact
and is reported `UNVERIFIED`. Computing the subtraction here makes the sentence that matters most to a team
leader both sayable and verified.

**Work already late is reported on its own** (ruling 18.11). Neither number above catches it: the window
filter keeps only tasks whose due date falls inside a window, so a task that went overdue last week belongs to
no window and contributes to no `due_excess_hrs`, even though it is the strongest possible case for moving
work. Rather than fold it into the first window and blur what that window's number means, each member gains a
single `overdue_hrs`, the remaining hours of their open tasks already past their due date at the run day. It
is **per member, not per window** — the work is late as of the run, not late in a particular future window —
and it sits beside `open_est_hours` in `patterns`, where `overdue_open` already counts the same tasks.

**All three feed rebalancing.** `rebalancing_candidates` gains two lists beside `overloaded` and
`underloaded`:
`backlog_pressed`, the members whose `backlog_excess_hrs` is above zero in the **last** window — the
cumulative figure is non-increasing, so that is the strictest test and means the backlog does not fit inside
the whole run — and `deadline_pressed`, those whose `due_excess_hrs` is above zero in **any** window, since a
deadline gap in any single window is enough to act on. `whf-rebalancing-advice` reads the second as the
strongest case for moving work: the hours cannot be deferred, so either they move or the deadline slips, and
a positive `overdue_hrs` is the case that has already slipped.

`whf-forecast-interpretation` gains two rules. When `overload` is zero but `backlog_excess_hrs` is not, the
member is not predicted to exceed their hours yet holds more open work than the coming windows can absorb.
When `due_excess_hrs` is above zero, say how many hours more are due in that window than it holds, and name
rebalancing as the answer.

### 8.2 Where the numbers are computed

`backlog_excess_hrs` and `due_excess_hrs` are fields of `MemberWindowForecast` and `NOT NULL` columns, so
`ForecastRunner` must produce them, and neither input is reachable from there today. `due_hours` is summed
inside `FactsBuilder` from the `Lifecycle`-derived `TaskFacts` of the member's open tasks; `open_est_hours` is
summed inside `Patterns` and surfaces on `MemberPattern`. Saying "it is already built" is true and useless:
something has to move. `overdue_hrs` is the exception — it is a member-level fact, so it is summed in
`Patterns` beside the `overdue_open` count it matches, and needs nothing moved.

`backlog_excess_hrs` also needs the run's own `demand_hrs` per window (ruling 18.10), which `ForecastRunner`
holds directly: the window rows are built there, so the cumulative sum is a running total over the rows it has
just produced. It must be computed after the band, not beside it, since it reads the final demand figure.

Both are one-line sums over the same open-task list that `ForecastRunner` already holds, so **the two sums
move to small static helpers beside the window arithmetic**, and `FactsBuilder` and `Patterns` call them
instead of summing for themselves. That keeps one definition of each quantity, which is the point of
computing the gap at all — a narrative comparing a `due_hours` from one summation with a `due_excess_hrs`
from another would eventually contradict itself. A test pins the two agreeing on the same fixture.

## 9. The backtest, MAE and thin history

Unchanged from the superseded spec except for the hold-out argument of section 4.2 and the meaning of the
numbers. `Backtest` stops being a tournament:

| Now | After |
|---|---|
| `run(feat, Map<String, Supplier<ArrivalModel>>, origins, horizons)` | `run(feat, origins, horizons)` |
| `Score(model, origin, horizon, mae, mase)` | `Score(origin, horizon, mae)` |
| `Result(scores, residuals, residualRows, unavailable, secondsPerModel)` keyed by model then horizon | `Result(scores, residuals, residualRows, seconds)` keyed by horizon |
| `FLOOR`, `Champion`, `selectChampion`, `meanMase`, `meanMaseByModel` | deleted |
| `mase` | **moved, not deleted**: it becomes `Numbers.mase` (section 16) and `Accuracy` calls it there |

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

On real WorkloadHub data with a capacity plan, 40 → 44 changes nothing for anyone who has rows. It bites only
on a member with no capacity row at all.

**An earlier draft said it bites on seeded databases. It does not, and that was wrong.** `CapacityWriter`
writes a `user_capacity` row for every member and every employed week, at `BASE_HOURS = 40.0`, so the property
is never reached in seeded data and changing it alone would leave every seeded team on 40 hours — still
contradicting C1. The seed's own base is corrected with it, in section 22.

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
  `backlog_excess_hrs double precision NOT NULL DEFAULT 0` and `due_excess_hrs double precision NOT NULL
  DEFAULT 0`. The default is what makes them addable at all: SQLite refuses `ADD COLUMN ... NOT NULL` without
  one outright, and PostgreSQL refuses it on a table that has rows. Both keep the default, since every writer
  supplies the value and a default of zero is the truthful reading of "no pressure recorded".
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

- Member `forecast` rows gain `backlog_excess_hrs` and `due_excess_hrs` (section 8). `due_hours` and
  `capacity_hrs` stay as they are; the new keys are gaps, not replacements.
- `patterns` gains `overdue_hrs`, per member, beside the `overdue_open` count (section 8.1).
- `rebalancing_candidates` gains `backlog_pressed` and `deadline_pressed` (section 8).
- `patterns` gains `logged_weekday_shares` (section 5) as well.
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
member who logs less than they work is forecast to work less; and it is a forecast of what someone will get
through rather than of what is waiting for them, which is why `backlog_excess_hrs` and `due_excess_hrs` are
reported beside it.

## 13. Product skills

`SkillTextsTest` pins the vocabulary and is updated with them.

- `whf-domain`: the **demand** bullet loses the open/new/planned decomposition and becomes "predicted hours a
  member will log in the window, never capped"; the **champion** bullet becomes a **model** bullet naming
  `xgboost`, its target, `mae` beside `mean_actual_hours` and `confidence`; the window sentence says a run
  covers between one and six contiguous windows and that `model.windows` says how many; `backlog_excess_hrs`,
  `due_excess_hrs`, `overdue_hrs`, `backlog_pressed` and `deadline_pressed` are documented — the first as what
  is left of the queue after the windows so far are forecast, the second as that window's own deadline gap,
  the third as work already late — with a sentence saying the first reads the forecast rather than checking
  it; `planned_basis` and `planned_backlog` go.
- `whf-rebalancing-advice`: `deadline_pressed` is named as the strongest case for moving work, because those
  hours cannot be deferred — either they move or the deadline slips — and `due_excess_hrs` says how many; a
  positive `overdue_hrs` is the case that has already slipped and comes first of all. `backlog_pressed` stays
  the weaker, undated case, and the skill says it is read off the forecast, so a member whose forecast is
  optimistic can be pressed without the number showing it.
- `whf-forecast-interpretation`: the "where the demand comes from" rule is replaced by the two
  pressure rules of section 8.1; the "below 1.0 is reliable" rule is replaced by stating `mae` next to
  `mean_actual_hours` and letting the reader judge, never calling the forecast good or bad; a
  `thin_history` run is described plainly as unscored; `due_hours` above capacity keeps its rule.
- `whf-likely-work`: the planned-allocation source and its `high` confidence rule go. The top confidence
  becomes `medium` (a live project matching a role the member already holds), and the skill says so. This is
  a real loss and section 19 item 4 records it.
- `whf-pattern-discovery`: `logged_weekday_shares` is added and distinguished from `weekday_shares` in one
  sentence.

All stay factual, short and specific to this domain, as CLAUDE.md requires.

## 14. Evaluation harness, the experiment driver and the sample host

`EvalConfig` becomes `(LocalDate asOf, int origins, List<UUID> teams, int windows)` with `windows` defaulting
to 2. `Harness` constructs its own `ForecastRunner` outside Spring, so it cannot read the property; passing the
count is the only way to evaluate a horizon other than the default. `Harness.evaluate` drops the
`ModelRegistry` validation and the factory filtering; `EvalResult` drops `skipped`.

`ScoreRow` and `DemandRow` lose their `model` column and `DemandRow` loses `openHrs`, `newHrs` and
`plannedHrs` (`Harness:163`), gaining `backlogExcessHrs` and `dueExcessHrs` in their place, so `demand.csv` —
the only place the harness reports demand — can still be read against the pressure the run found. Both
headers change. The `mase` and `beats_naive`
metric rows are deleted; `mae`, `coverage80`, `wql` and `seconds` remain. `Report` drops its "Models
requested" line.

`server/tools/Experiment.java`: `eval` drops `--models` and gains `--windows N` (1 to 6, default 2), refusing
anything outside the range with the same message the auto-configuration uses. `server/tools/experiment.sh`
follows in its header comment. This is the one place the window count is not a property, because the driver is
not a host and has no property source; confirmed at the 2026-09-14 review (section 18.7) as a consequence of
ruling 4 rather than an exception to it. Since section 3.2 ties a matrix to the count it was built with, the
flag also decides the matrix the driver builds, and `eval` rebuilds rather than reusing one built at another
count.

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

- `band`: `demand ≥ 0`, `low ≤ demand ≤ high`, `low ≥ 0`, and `overload = round2(max(0, demand − capacity))`.
  The obvious companion — `overload = 0` exactly when `demand ≤ capacity` — is **false as stated** and must be
  written with the rounding in it: the formula rounds last, so a demand of `capacity + 0.004` gives an
  overload of `0.0` while exceeding capacity. The property is `overload = 0` exactly when
  `demand ≤ capacity + 0.005`. jqwik finds the naive form in a few hundred tries; the same correction applies
  to the two pressure facts below, which round the same way.
- The weekday split: for a week wholly inside the horizon the day hours sum to the week's prediction within
  rounding; no day is negative; a non-working day receives nothing; an all-zero share vector reproduces the
  even split.
- `Horizon`: for every `w` in 1 to 6 and every run day, `windows(asOf, w)` yields `w` windows of exactly five
  weekdays, contiguous and starting the first weekday after the run day, and `maxHorizon(origin, w) == w + 1`.
- `Numbers`: `mae` non-negative and zero exactly when the forecast equals the truth; `mase` likewise against a
  non-degenerate naive; `round2` idempotent and never more than 0.005 from its input.

**New cases**: `whf.forecast.windows` outside 1 to 6 fails start-up with a message naming the property and the
range; a run at `windows = 6` **from a pinned Monday `asOf`** produces 30 day rows and 6 window rows per
member and fits 7 horizons — the horizon count is run-day dependent (section 4.1: a run fits between *w* and
*w + 1* boosters), so the case must pin the weekday or it fails on a Friday; a
thin-history run forecasts with a null `mae`, `confidence` `thin_history` and an interval basis of
`"none: no scored origins"`; a run whose booster cannot fit ends `FAILED` with the `ModelUnavailable` message;
`backlog_excess_hrs` is positive exactly when open remaining hours exceed the summed predicted demand of the
windows up to and including that one, allowing for the rounding above, and is non-increasing across a run's
windows; `overdue_hrs` is positive exactly when the member holds an open task past its due date, and agrees
with the `overdue_open` count on which members it names; `due_excess_hrs` is positive
exactly when that window's `due_hours` exceeds that window's capacity; a member in `deadline_pressed` has a
positive `due_excess_hrs` in at least one window; a matrix built at two windows, handed to a backtest asking
for horizon 7, fails with a message naming the missing horizon rather than reading a column that is not there
(section 3.2); and `CapacityRule` falls back to 44 hours when a member has no capacity row.

`XgboostHoursTest` replaces the `SeasonalNaive` comparison at `XgboostArrivalTest:69` with a two-line baseline
in the test — each member's mean of the training target — which the booster must beat on a planted-signal
fixture. Same protection, no production class.

**Updated**: `BacktestTest`, `FeaturesTest`, `FeatureBuilderTest`, `WeeklySeriesTest`, `ForecastRunnerTest`,
`ForecastRunnerPropertyTest`, `PatternsTest`, `FactsBuilderTest`, `FactsToolsTest`, `HarnessTest`, `TruthTest`,
`DefaultForecastServiceTest`, `JdbcRunStoreTest`, `ForecastControllerTest`, `SampleHostIntegrationTest`,
`SkillTextsTest`, `HorizonTest`, `SyntheticMatrix`, `RunRequestTest` (which keeps only its missing-team case),
and the narration fixtures hardcoding a champion (`NarrativeContractTest:53`, `NumberVerifierTest:26`,
`PromptsTest:21`).

## 18. The owner's review, 2026-09-14

The two open points were closed (items 1 and 7) and ten more rulings were taken: three prompted by findings
that contradicted this document, two by the seed generator being unable to exercise what the design needs, and
three as the review closed, when a self-review pass of this document raised them. They are numbered here and
cited from the sections they change.

1. **`MIN_HISTORY_WEEKS = 10 + maxHorizon`** (section 4.2). Confirmed as proposed. The usable training span
   stays constant at every window count; a six-window run needs about 19 weeks of history before it scores.
2. **`backlog_excess_hrs` is cumulative** (section 8.1). Window *k* measures open work against the summed
   capacity of windows 1 to *k*, not against window *k* alone, so the figure falls as the windows absorb the
   backlog instead of repeating itself once per window.
3. **The horizon columns are sized from the window count** (section 3.2). Raised during the review: this
   document had claimed the matrix shape does not change, which is false once the maximum horizon reaches 7.
   `Features.HORIZONS` becomes a function of the configured count. A matrix is tied to the count it was built
   with and cannot be reused under another.
4. **No hour is ever logged on a non-working day** (section 3). The application records no time on weekends,
   public holidays or absence days: a working week is five days and a 44-hour week is 8.8 hours on each. The
   week's target therefore needs no day filter and `Truth` is unchanged.
5. **The deadline gap becomes a fact, and its members are listed** (section 8.1). `due_excess_hrs` per window,
   and `deadline_pressed` in `rebalancing_candidates`. Work that must finish inside a window at the cost of
   overloading someone is the case a team leader has to act on, so the narrative must be able to state the
   gap in hours and have it verify, and rebalancing advice must be able to find those members directly.
6. **Overtime is recordable, so overload survives the retarget** (section 19). Nothing in WorkloadHub stops a
   member logging more hours than their capacity. The concern that a logged-hours target would silence
   overload was the first draft's largest stated cost and it was overstated; section 19 is rewritten.
7. **`--windows N` on the experiment driver** (section 14). Confirmed, as a consequence of the driver having
   no property source rather than an exception to ruling 4.
8. **The seed generator is changed to model real logging** (section 22). Raised during the review: a seeded
   member can never log more than 8 hours in a day, so a seeded week never exceeds 40 logged hours while
   every seeded member carries an explicit 40-hour capacity row. Under the retarget that makes `overload`
   arithmetically unreachable on seeded data — the opposite of the real system, where nothing caps logging.
   The seed gains an 8.8-hour day and a 44-hour capacity base, per-member weekday profiles, a logging
   discipline factor independent of the estimate ratio, and a minority of members and weeks that genuinely
   run over.
9. **The seed work lands in this plan, as an early task** (section 22). Changing the seed on its own would
   turn the current suite red, because roughly 175 to 200 tests across 29 files assert against today's
   seeded behaviour and those tests are being rewritten by this change anyway. One branch, one green gate.
10. **`backlog_excess_hrs` is measured against predicted demand, not capacity** (section 8.1). Put to the
    owner as a choice between an arithmetic remainder against capacity and what the forecast says is left
    over. The owner's reason decides it: the forecast is not a fact a team leader trusts outright, it is an
    analysis that flags who has free hours and who is buried, and what it misses the narrative exists to
    catch. The accepted cost is that the fact now depends on the model rather than checking it.
11. **Work already overdue gets its own number** (section 8.1). `overdue_hrs` per member, the remaining hours
    of open tasks already past their due date at the run day. It is not folded into the first window, which
    would blur what that window's `due_excess_hrs` means, and not left out, which would hide the most urgent
    case from the list meant to trigger rebalancing.
12. **One plan, not four** (section 23). The review proposed splitting the work into parity retirement, the
    retarget, the configurable windows and the pressure facts. The owner kept it as one plan: nothing lands
    half-done and there is one branch to follow. Section 23 records what that costs and how the standing
    workflow absorbs it.

## 19. What this costs, accepted

Stated plainly, and revised where the 2026-09-14 review overturned an item:

1. **Overload keeps working; this was the first draft's worst-stated cost.** That draft argued logged hours
   are censored by what a person can physically work, so a model trained on them would rarely predict above
   capacity and `overload` would fade. The premise is wrong (ruling 18.6): WorkloadHub does not cap logging,
   a member can record ten hours in a day or fifty in a week, and overtime therefore appears in the training
   data as it actually happens. A forecast of logged hours can predict above capacity because people log
   above capacity. What remains true, and much smaller, is that the target is bounded by what a person
   *does*, not by what is asked of them: someone handed 70 hours of work in a 44-hour window logs their long
   week, not the full 70, so the forecast shows a real overrun rather than the size of the queue behind it.
   Section 8.1 is what covers the queue, and it is a complement now rather than the rescue the draft made it.
2. **The forecast inherits logging discipline.** A member who works a full 44-hour week and logs 25 hours is
   forecast to log 25. The module cannot tell under-logging from under-working, and the narrative must not
   pretend otherwise:
   `data_quality.unlogged_tasks` already flags the tasks, and `limitations` now says it in words. This is the
   sharpest edge of the retarget and it is the direct consequence of measuring what is recorded.
3. **The demand breakdown is gone.** A narrative can no longer say "most of this is work already on their
   plate". `open_est_hours`, `due_hours`, `backlog_excess_hrs` and `due_excess_hrs` recover part of it; the per-window
   attribution does not come back.
4. **"Likely work" weakens.** Without the planned allocation, no task can be named as probably landing on a
   member, and the skill's top confidence drops from `high` to `medium`.
5. **Six windows cost real time and a wider matrix.** Up to 7 boosters per backtest origin instead of 3 —
   about 42 fits per run against 18 — a 7-week hold-out and roughly 19 weeks of history before anything
   scores. Section 3.2 adds the matrix cost the first draft missed: five column families are per horizon, so
   six windows build 35 horizon columns where two build 15, and a matrix built at one count cannot be reused
   at another. The default stays at two windows, where none of this is paid.
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
count, the capacity default, the two pressure facts of section 8.1, the weekday split of section 5, the parity
retirement and one-step gate of section 16, the `Numbers` extraction, and the seed generator changes of
section 22. Nothing else. Section 23 records that it is one plan, and the task order that follows from it.

An earlier draft of this sentence listed only the first four and a single "backlog-pressure fact"; it
understated the document's own contents, which matters because a plan writer scopes from it.

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

## 22. The seed generator

Raised at the 2026-09-14 review and ruled there (18.8 and 18.9). The seed is not a test fixture on the side:
it is production code in the library, roughly 175 to 200 tests across 29 files stand on it, and the evaluation
harness measures whatever world it builds. Retargeting the forecast at logged hours without touching it would
leave the new behaviour measured against data that cannot produce the cases the tool exists to catch.

### 22.1 What is wrong for a logged-hours forecast

| Finding | Where | Why it blocks the retarget |
|---|---|---|
| A present working day gives a member exactly 8 hours to spend, a hard ceiling | `AbsencePlanner.hoursPresent`, and `WorkQueue.logDay` only ever decrements what is left | A seeded week never exceeds 40 logged hours. `WorkQueueTest` asserts the ceiling, so it is deliberate, not incidental |
| Every member and employed week gets a capacity row at a 40-hour base | `CapacityWriter.BASE_HOURS` | Demand is capped at exactly capacity, so `overload` is arithmetically zero for every seeded member, forever |
| Both contradict requirement C1 | `docs/requirements/requirements-v1.md`, C1 and line 85 | The requirement has always said 44 hours and 8.8 a day |
| Logging is flat across the week: the day is 8 hours or nothing, with no weekday branch | `AbsencePlanner.hoursPresent` | `logged_weekday_shares` comes out flat, so the section 5 split always falls through to its even-split fallback and ships untested |
| A task's logged hours are its estimate times a per-member ratio times per-task noise | `WorkQueue.assign`, with the ratio drawn once per member in `simulate` | A model retargeted at logged hours still learns the arrival signal, lagged and smoothed, so the backtest flatters itself |
| Nothing represents logging discipline | nowhere | The sharpest cost in section 19 — a member who works a full week and logs 25 hours — cannot occur in seeded data |

Overload does exist in seeded data today, but only in *assigned estimated* hours: `team_capacity.allocated_hrs`
can exceed `total_capacity_hrs` in an event week. That is the quantity this change stops forecasting.

### 22.2 What changes

1. **The working day becomes 8.8 hours and the capacity base 44**, in `AbsencePlanner` and
   `CapacityWriter.BASE_HOURS`, so the seed, the module default (section 10) and requirement C1 finally agree.
   Absence rows move to 8.8 with them, so a full-day absence still reads as a full day.
2. **A per-member weekday profile.** Each member draws a fixed Monday-to-Friday shape once, so their present
   hours vary by weekday around the 8.8-hour day rather than being flat. This is what gives
   `logged_weekday_shares` something real to describe and the section 5 split something to get right.
3. **A logging discipline factor, drawn per member and independent of the estimate ratio.** A member logs that
   fraction of the hours they actually work. The estimate ratio stays what it is — estimation bias — and
   discipline becomes a separate, unobserved cause of the gap between work done and hours recorded. Without
   two independent factors the retarget cannot be distinguished from the thing it replaces.
4. **Overtime.** A minority of members, and a minority of weeks for everyone, exceed the 8.8-hour day, so
   logged hours can run past capacity and `overload` can fire. The real system has no cap (ruling 18.6); the
   seed must stop pretending otherwise.

The seed stays deterministic given `--seed`: every new factor is drawn from the same seeded generator, in a
fixed order, and the properties already asserted about it still hold.

### 22.3 What it costs

Every test that asserts a seeded number moves. The shared fixtures are the ones to change first — the
per-JVM synthetic dataset and the facts fixture built on it, which hardcodes a 40-hour capacity rule — and the
seed package's own suites pin the new behaviour. This is why ruling 18.9 puts the work inside this plan as an
early task rather than in a plan of its own: on its own it turns the suite red, and here the same tests are
being rewritten anyway.

Two properties are worth adding while the generator is open: a member's logged hours never exceed their
present hours **except** through the overtime path, and over a long enough history every member shows a
non-degenerate weekday shape.

## 23. One plan, and what that costs

The self-review judged this too large for a single plan and proposed four: parity retirement and the one-step
gate first, then the seed and the retarget, then the configurable window count, then the pressure facts. The
owner ruled for **one plan** (18.12): nothing lands half-done, and there is one branch to follow rather than
four to sequence.

The argument for splitting was not wrong, so the plan has to answer it rather than ignore it.

**What makes it large.** Eight production files and a package deleted; six public records reshaped; one
migration; five product skills; the facts contract; the evaluation harness, the experiment driver and the
sample host; four scripts and the paused CI file; eight documents; the seed generator; and roughly twenty
named test suites rewritten, with about 175 to 200 tests standing on seeded data.

**How the standing workflow absorbs it.** A task per section, a review per task, then a whole-branch review
and one fix wave, exactly as the four landed plans did. The size shows up as more tasks, not as bigger ones.

**The order within the plan is not free**, and the review's proposed seams become the task order:

1. **Parity retirement and the one-step gate** (section 16), first. The parity scripts and their Python test
   compare Java output against arrival-hours behaviour, so they go red the moment the retarget lands; deleting
   them first avoids fixing something that is about to be removed, and every later task gets a faster gate.
2. **The seed** (section 22), second, per ruling 18.9, so every later task is written against data that can
   show overload, weekday shape and under-logging.
3. **The retarget and the deletions** (sections 3, 3.1, 6, 7, 9), the heart of it, and the point at which
   training and measurement become the same quantity.
4. **The configurable window count** (sections 4 and 3.2), which carries the one real unknown: turning
   `Features.HORIZONS` and `MIN_HISTORY_WEEKS` from constants into functions of the count.
5. **The weekday split** (section 5) and **the pressure facts** (section 8), both additive, both depending on
   the shape of what comes before. The pressure facts come last because `backlog_excess_hrs` reads the run's
   own demand (section 8.2), which task 3 defines and task 4 may widen.

**The risk the owner accepted**, recorded plainly: a long-lived branch, a single large final review, and a
schema that changes in one migration rather than two, so the rollback of section 20 is all or nothing. The
mitigation is the review per task; if the branch is still red after the fix wave, splitting it then is still
open, and the task order above is already the seam.
