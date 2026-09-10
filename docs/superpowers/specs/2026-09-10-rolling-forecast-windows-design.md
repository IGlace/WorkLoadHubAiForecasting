# Rolling forecast windows: design

Date: 2026-09-10. Status: approved by the owner in conversation (design presented and accepted; the three
decisions of section 2 answered). Amends `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`
sections 5, 9, 10.1 and 11, and requirement F1 of `docs/requirements/requirements-v1.md`.

## 1. Goal

A forecast starts the day it is run and covers the next two working weeks: ten weekdays, split into two
windows of five, beginning on the first weekday after the run day. The caller does not choose the start
date. A run made later inside a horizon forecasts the next ten weekdays again and overwrites, per member and
day, the days that have not arrived yet; days already past keep the forecast made before them. A run on a
weekend starts on Monday.

Today the pipeline forecasts two Monday-aligned calendar weeks (`Weeks.forecastWeeks`: next Monday and the
one after, or this week when the run day is a Monday), takes the run day from the caller (`RunRequest.asOf`),
computes demand and capacity per member and week, and stores one row per member and week.

## 2. Owner decisions (2026-09-10)

1. **Holidays inside a window do not move it.** A window is five weekdays (Monday to Friday, whatever the
   holidays); a holiday inside has zero capacity and zero predicted arrivals. Two windows always span the
   same ten weekdays for a given run day.
2. **Per-day current forecast.** Each run stores its ten days per member, and a current-forecast table keyed
   by team, member and day is upserted: days ahead are overwritten, days that have arrived keep the last
   forecast made before them. This table is what the accuracy evaluation will read.
3. **The command line keeps `--as-of`** as an experiment override on seeded databases and backtests. The
   server (the Java interface and the REST surface) always starts from today.

Unchanged and still binding: the language model never produces a forecast number; demand is never capped
by capacity; the arrival models and the feature matrix stay weekly and Monday-aligned; English and French.

## 3. The horizon

New class `com.workloadhub.forecast.calendar.Horizon` and record `ForecastWindow`:

```java
public record ForecastWindow(int index, LocalDate start, LocalDate end, List<LocalDate> weekdays) {
    public boolean contains(LocalDate day);        // weekdays.contains(day)
}
public final class Horizon {
    public static final int WINDOWS = 2;
    public static final int WEEKDAYS_PER_WINDOW = 5;
    public static LocalDate firstDay(LocalDate asOf);          // the first weekday after asOf
    public static List<ForecastWindow> windows(LocalDate asOf); // WINDOWS windows of WEEKDAYS_PER_WINDOW weekdays
    public static List<LocalDate> days(List<ForecastWindow> w); // all weekdays in order
    public static int[] horizons(LocalDate origin, List<ForecastWindow> w); // distinct weeksBetween(origin, mondayOf(day)), ascending
}
```

- `firstDay(asOf)` is `asOf + 1 day`, moved forward to Monday when that lands on Saturday or Sunday. So a
  Wednesday run starts Thursday, a Friday, Saturday or Sunday run starts Monday.
- Window 1 is the first five weekdays from `firstDay`, window 2 the next five; `start` and `end` are the
  first and last weekday of the window; the two windows are contiguous (window 2 starts on the weekday after
  window 1's end). Examples: run on Wednesday 2026-09-09: window 1 = Thu 09-10 .. Wed 09-16, window 2 =
  Thu 09-17 .. Wed 09-23. Run on Friday 09-11, Saturday 09-12 or Sunday 09-13: window 1 = Mon 09-14 ..
  Fri 09-18, window 2 = Mon 09-21 .. Fri 09-25.
- The origin stays `Weeks.lastCompleteWeek(asOf)` (the Monday of the week before the run day's week), for the
  feature matrix, the backtest and the Python parity of the arrival level. The **horizons used** are the
  weeks the ten days touch, counted from the origin: `{1, 2, 3}` for a Monday to Thursday run, `{2, 3}` for a
  Friday to Sunday run. `Features.HORIZONS` already carries `{1, 2, 3}`. The backtest scores the run's
  horizons, the champion is selected on them, and the interval offsets are computed per horizon as today.
- `Weeks.forecastWeeks` is removed.

## 4. Demand per day

New key `com.workloadhub.forecast.run.MemberDay(UUID member, LocalDate day)` (comparable like `MemberWeek`).

- `HourPlacement.placeHoursByDay(hours, start, end, cal, off)` returns hours per working day between `start`
  and `end` (evenly, as `placeHours` does today before it sums per Monday); when there is no working day in
  the range, all hours land on `start`. `placeHours` (per Monday) is kept as the weekly sum of it.
- **Open hours**: `EffortModel.placeOpenTasksByDay` is `placeOpenTasks` with per-day output, placement
  starting at window 1's `start` (today: the first forecast Monday).
- **New hours**: the champion predicts fresh arrival hours per member for each horizon week (unchanged). For
  each predicted week with `wd` working days (`WorkingCalendar.workingDaysInWeek`), every working day of that
  week that is inside the horizon gets `prediction / wd` hours of arrivals (a holiday gets none); the days of
  the current week that have passed are not forecast (their arrivals are already open tasks).
  `EffortModel.placeNewArrivalsByDay(Map<MemberDay, Double> arrivals, ...)` scales each day's arrival by the
  member's estimate ratio and places it from that day over the member's cycle days, per day.
- **Planned hours**: `PlannedWork.Request(teamId, members, asOf, List<ForecastWindow> windows)`; the window
  boundary is window 2's `end`; hours are kept per `MemberDay`; `Piece.expectedWeek` becomes
  `Integer expectedWindow` (1, 2, or null when after the window).
- A member's **day demand** is open + new + planned on that day, rounded to two decimals; a **window's**
  open, new, planned and demand are the sums over its weekdays. Demand is never capped.
- **Interval**: for window `w`, the low and high offsets are the backtest offsets of the horizons it touches,
  weighted by the share of its five weekdays that fall in each horizon week (a Thursday-to-Wednesday window
  after a Wednesday run: 2/5 of horizon 1 and 3/5 of horizon 2). The band formula of `ForecastRunner.band`
  is then applied to the window's open, new and planned hours as today.

## 5. Capacity per day

`CapacityRule` gains `dayCapacity(member, day, data, cal)` and `dayAbsenceHours(member, day, data)`:

- Saturday, Sunday and a holiday: 0.
- When the application has a `CapacityRow` for the member and the day's Monday week: that row's `available`
  divided by the week's working days (0 when the week has none).
- Else `base / 5 − dayAbsenceHours`, floored at 0, where `base` is the latest `CapacityRow.base` on or
  before that week, else `whf.default-weekly-hours`; `dayAbsenceHours` is the sum of the member's
  `AbsenceRow.hours` on that day.
- A window's capacity, absence hours and working days are the sums over its weekdays (working days count the
  weekdays that are not holidays). A day's overload is `max(0, day demand − day capacity)`; a window's
  overload is `max(0, window demand − window capacity)`, the figure that is reported and narrated.
- The weekly `capacity` and `absenceHours` stay for the feature matrix.

## 6. Run pipeline and types

- `Prepared`: `List<ForecastWindow> windows` replaces `LocalDate[] forecastWeeks`; `int[] horizons` holds the
  horizons used (two or three); `predictedEst` stays keyed by `MemberWeek` (the horizon week's Monday).
- `TeamOutcome`: `SortedMap<MemberDay, Double> openHours, newHours`, the allocation, and both tables:
  `List<MemberWindowForecast> memberWindows` and `List<MemberDayForecast> memberDays`.
- `api.MemberWindowForecast(UUID userId, int windowIndex, LocalDate windowStart, LocalDate windowEnd, double openHrs, double newHrs,
  double plannedHrs, double demandHrs, double lowHrs, double highHrs, double capacityHrs, double overloadHrs, int workingDays, double absenceHrs)`
  replaces `MemberWeekForecast`.
- `api.MemberDayForecast(UUID userId, LocalDate day, int windowIndex, double openHrs, double newHrs, double plannedHrs, double demandHrs,
  double capacityHrs, double overloadHrs, boolean workingDay)`.
- `api.CurrentDayForecast(UUID teamId, UUID userId, LocalDate day, UUID runId, double openHrs, double newHrs, double plannedHrs,
  double demandHrs, double capacityHrs, double overloadHrs, LocalDateTime forecastAt)`.
- `ForecastRunner.prepare(data, asOf, forcedModel, progress)` keeps its signature; `asOf` is the run day.

## 7. Storage: migration V3 (SQLite and PostgreSQL)

`forecast_member_weeks` is dropped (the module is deployed nowhere; no data is migrated) and three tables
are created:

```sql
CREATE TABLE forecast_member_windows (
  run_id, user_id, window_index INTEGER, window_start DATE, window_end DATE,
  open_hrs, new_hrs, planned_hrs, demand_hrs, low_hrs, high_hrs, capacity_hrs, overload_hrs, working_days INTEGER, absence_hrs,
  PRIMARY KEY (run_id, user_id, window_index));
CREATE TABLE forecast_member_days (
  run_id, user_id, day DATE, window_index INTEGER,
  open_hrs, new_hrs, planned_hrs, demand_hrs, capacity_hrs, overload_hrs, working_day (INTEGER 0/1 on SQLite, boolean on PostgreSQL),
  PRIMARY KEY (run_id, user_id, day));
CREATE TABLE forecast_current_days (
  team_id, user_id, day DATE, run_id, open_hrs, new_hrs, planned_hrs, demand_hrs, capacity_hrs, overload_hrs, forecast_at TIMESTAMP,
  PRIMARY KEY (team_id, user_id, day));
CREATE INDEX forecast_current_days_team_idx ON forecast_current_days (team_id, day);
```

Types follow V1's conventions per dialect (`TEXT`/`REAL` on SQLite; `uuid`, `date`, `double precision`,
`timestamp` on PostgreSQL); `run_id` references `forecast_runs(id)` in all three tables (runs are never
deleted, so a current-day row always points at the run that wrote it). `demand_hrs` is stored, closing the
backlog note that it was recomputed.

`JdbcRunStore.finish(runId, champion, mase, backtestJson, windows, days, factsJson, finishedAt)` writes the
run row, the windows, the days, the facts and upserts the current table in one transaction:
`INSERT INTO forecast_current_days ... ON CONFLICT (team_id, user_id, day) DO UPDATE SET run_id = excluded.run_id, ...`
(the same syntax on both dialects). Every day of a run is after its run day by construction, so a run never
overwrites a day that has arrived: the rule "days already past keep the last forecast made before them"
follows from the horizon, not from a date check. Reads: `memberWindows(runId)`, `memberDays(runId)`,
`currentDays(teamId, from, to)` ordered by user and day.

## 8. Public API, REST and CLI

- `RunRequest(UUID teamId, UUID requestedBy, String forcedModel, Boolean plannedWork)`: `asOf` is gone.
- `ForecastService` gains `List<CurrentDayForecast> currentForecast(UUID teamId, LocalDate from, LocalDate to)`
  (`from` and `to` inclusive; `from` after `to` is `INVALID_REQUEST`; an unknown team is `TEAM_NOT_FOUND`).
  `startRun` takes the run day from a `java.time.Clock`: the auto-configuration registers
  `Clock.systemDefaultZone()` when the host has no `Clock` bean, and `DefaultForecastService` takes it as a
  constructor argument. `RunSummary.asOf` remains the run day.
- `DefaultForecastService.runNow(RunRequest)` uses the clock; `runNow(RunRequest, LocalDate asOf)` is the
  experiment entry the CLI uses; neither is on the interface.
- `RunResult(run, scores, maseByModel, unavailable, memberWindows, memberDays, factsJson)`.
- REST: `POST /runs` reads a web-layer body `RunRequestBody(teamId, requestedBy, asOf, forcedModel, plannedWork)`;
  when `asOf` is present the answer is 400 `INVALID_REQUEST` with the message
  `asOf is not accepted: a run always starts from today`; other unknown fields are ignored as before. New
  route `GET /teams/{teamId}/current?from=YYYY-MM-DD&to=YYYY-MM-DD` returns `CurrentDayForecast[]`
  (defaults: `from` = today, `to` = today + 20 days).
- CLI `run`: `--as-of` stays, described as `Run day, ISO (default: today; an experiment override, the server always uses today)`;
  the summary line prints the two windows (`window 1 2026-09-10..2026-09-16, window 2 2026-09-17..2026-09-23`);
  the table is per member and window; `--json` carries `memberDays` too. New command
  `current --team <name or id> [--from] [--to] [--db] [--json]` printing the current forecast per member and
  day (defaults as the REST route), exit 2 on a usage error. `eval` is unchanged in its options.

## 9. Facts, contract, prompt and skills

Facts (`FactsBuilder`):

- `run`: `id`, `as_of`, `windows: [{"index": 1, "start": "2026-09-10", "end": "2026-09-16", "working_days": 5}, ...]`,
  `generated_at`, `origin`, `horizons` (the horizons used). `run.weeks` is gone.
- Per member, `forecast` has one row per window: `window` (index), `start`, `end`, `demand`, `low`, `high`,
  `capacity`, `overload`, `open_hours`, `new_hours`, `planned_hours`, `working_days`, `absence_hours`,
  `due_hours` (remaining hours of open tasks due between `start` and `end`); and a new `days` list with one
  entry per weekday: `day`, `window`, `demand`, `capacity`, `overload`, `open_hours`, `new_hours`,
  `planned_hours`, `working_day`.
- `team.totals`: one entry per window (`window`, `start`, `end`, `demand`, `capacity`, `planned`);
  `team.team_capacity` lists the application's rows for the horizon weeks (still `week`).
- `likely_work.planned[].expected_window`: `1`, `2` or `"after_window"` (replaces `expected_week`).
- `model.horizons`: the horizons used; `model.interval.horizons` per used horizon as today.
- `LIMITATIONS`: `predicted arrivals are spread evenly over a week's working days and the days already past are not re-forecast; open tasks are placed from the first forecast day; backlog work created and assigned inside the horizon is missed by both components`.
- `rebalancing_candidates` unchanged (sums over the horizon).

Contract (`contract.schema.json`, `NarrativeContract`): `move.week` and `adjustment.week` become
`move.window` and `adjustment.window`, an ISO date that must equal one window's `start`
(`"A forecast window's first day, ISO 8601"`); the cross-checks use that window's forecast row (overload of the
source, demand and capacity of the target) and the messages say `in the window starting <date>`.

Prompt (`Prompts`): rule 5 says `in the same window`; the user prompt lists
`forecast windows 2026-09-10..2026-09-16 and 2026-09-17..2026-09-23`. Tool descriptions (`FactsTools`) say
window where they said week (`get_member_forecast`: `Forecast rows per window (five weekdays each) ... and the
day-by-day rows`; `get_member_capacity`: `per forecast window and per day`).

Skills: `whf-domain` defines the window (`five weekdays starting the first weekday after the run day; two
windows, contiguous; a holiday inside has zero capacity`), describes `forecast` rows per window and the
`days` list, and keeps weeks for history; `whf-forecast-interpretation` says window where it said week
(`overload hours per window`, `overdue open tasks are placed forward from the first forecast day; they
inflate window one`, `risk high when overload is greater than 0 in either window`) and adds `a single day
with demand well above its capacity inside a window is worth naming when the window total hides it`;
`whf-rebalancing-advice` says `in the same window`; `whf-report-style` says `window (its first day as an ISO
date)`, and both languages write window dates in ISO form, never spelled out, so the number verifier keeps
skipping them; `whf-likely-work` says `expected_window`. `SkillTextsTest` pins `window`, `expected_window`
and the absence of `expected_week`.

## 10. Evaluation harness

- The arrival level is unchanged (`Harness.HORIZONS = {1, 2}`, `scores.csv` identical in shape): the
  Python parity gate compares it and stays valid.
- The demand level replays each origin with `asOf = origin + 1 week` (a Monday, as today) through the same
  `prepare`/`forTeam`, so the replayed horizon is Tuesday to Monday twice. `DemandRow` carries `windowIndex`,
  `windowStart`, `windowEnd`; truth is the sum of the member's time logs on the window's weekdays
  (`Truth.realisedHours(data, days)`); `demand.csv` columns become
  `model,origin,team_id,member_id,window,window_start,window_end,forecast,truth,capacity,open_hours,new_hours,planned_hours`;
  `summary.md`'s demand section says "per member-window". The Python service's `demand.csv` is no longer
  comparable row for row; the parity document says so.

## 11. Tests (TDD, jqwik properties for the arithmetic)

Properties: `Horizon.windows` yields ten distinct weekdays, none a weekend, the first strictly after the run
day and equal to `firstDay`, contiguous windows, five weekdays each, and a Friday, Saturday or Sunday run
starts on the next Monday; `placeHoursByDay` conserves the hours, lands only on working days not in `off`,
and never before `start`; window sums equal the sums of their days for open, new, planned, demand, capacity,
absence and working days; day capacity is within `[0, base / 5]` and zero on holidays and weekends; the
current table after two runs holds, for every day, the values of the later run whose horizon covers it, and
the earlier run's values for the days only it covered. Examples: the Wednesday, Monday and Sunday windows of
section 3; the interval weights of section 4; a holiday inside window 1 lowering capacity, working days and
new hours without moving the window; `RunRequestBody` with `asOf` → 400; `GET /teams/{id}/current`;
`NarrativeContract` rejecting a `window` that is not a window start; `FactsBuilder` writing `run.windows`,
`forecast[].days` and `expected_window`; V1, V2 then V3 migrating on SQLite and (with Docker) PostgreSQL;
`eval` writing the new `demand.csv` columns. Every test that pinned `forecastWeeks`, `weekStart`,
`run.weeks` or `week` in the contract is rewritten.

## 12. Documentation

- The Java spec's sections 5 (capacity bullet), 9, 10.1 and 11 get a one-line note pointing here; F1 and F2 of
  `docs/requirements/requirements-v1.md` get an amendment line dated 2026-09-10 (horizon = the next ten
  weekdays in two windows, starting the day after the run; reported per window and per day).
- `server/README.md`: the CLI table (`run`, `current`), the REST table, the current-forecast route, the
  `Clock` bean, the parity section's note on `demand.csv`.
- `CLAUDE.md`: vocabulary gains `window` (five weekdays) and `current forecast`; "Where the project stands"
  names this change.
- `docs/backlog.md`: the rulings of this design under "Java migration"; the accuracy-evaluation item now
  says the per-day current forecast is stored.

## 13. Non-goals

No per-day arrival model (the models stay weekly); no change to the feature matrix; no scheduled runs; no
department roll-up; no user interface; no time-zone configuration beyond the host's `Clock` bean.
