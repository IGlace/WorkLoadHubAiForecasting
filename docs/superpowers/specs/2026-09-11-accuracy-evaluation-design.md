# Accuracy evaluation design

Date: 2026-09-11. Status: approved by the owner in chat (the two recommendations below: Java interface plus
CLI only, and nothing stored). Amends the Java module design (`2026-09-09-java-forecast-module-design.md`,
section 11 public API and section 12 CLI) and closes the backlog item "Accuracy evaluation, design first".

## 1. Goal

Once real weeks have passed, tell the owner how good the forecasts were: for each member and each weekday
that has gone by, the hours the module forecast for that day before it arrived against the hours the member
actually logged. Deterministic code computes everything; Copilot plays no part.

## 2. Truth and forecast

- **Truth** is the hours logged in `time_logs` per member and day, the same rows and the same sum the
  evaluation harness already uses (`eval.Truth.realisedHoursByDay`). Nothing new is collected.
- **Forecast** is read from two tables written by every run (rolling windows design, 2026-09-10):
  - `forecast_current_days`: per team, member and day, the latest run that covered the day before it
    arrived. This is the "what we told the user" view.
  - `forecast_member_days`: every run's ten days per member. Joined with the run's `as_of`, it gives the
    same day forecast at several distances, so error can be read by **lead**, the number of weekdays between
    the run day and the forecast day (1 to 10).
- **Capacity** and **overload** come from the stored forecast rows (`capacity_hrs`, `overload_hrs`);
  actual overload is `logged > capacity_hrs` of the same row. Capacity is not recomputed.

## 3. Scope of a comparison

`accuracy(teamId, from, to)` compares the days `d` with `from <= d <= to`, `d < today` (the service's clock),
`d` a weekday. Days without a forecast row are skipped; forecast rows whose day has no log are compared
against zero hours (the member logged nothing), the convention the harness uses for level B. A member who
left the team keeps their rows. The default range in the CLI is the last 20 days ending yesterday.

> Amended on 2026-09-11 (final review): a weekday whose stored day row has `working_day` false (a public
> holiday, written with no capacity; a personal absence only zeroes the capacity and stays scored) is not scored in any scope. Counting it scored a
> perfect row for a day nobody was meant to work. Those days are counted instead and reported as
> `AccuracyResult.nonWorkingDays`. A current row is matched to its run's day row on (run, member, day); a
> current row with no such row is skipped altogether, because its lead cannot be computed (a finished run
> writes both tables in one transaction, so this is defensive).

## 4. Metrics

All from `eval.Metrics` and `backtest.Backtest`, unchanged:

- per member: `n` days, `mae`, `bias` (forecast minus truth, positive means high), `mase` against the
  seasonal-naive floor (the truth of the same weekday one week earlier, when it exists; `NaN` otherwise),
  overload precision and recall (forecast overload versus actual overload on the same days);
- per team: the same over every member-day, plus the same by lead (1 to 10) from `forecast_member_days`.

`NaN` where the input is empty, as the harness does.

> Amended on 2026-09-11 (final review): MASE is scored over the rows whose member has a log entry on the same
> weekday seven days earlier (a zero entry that exists counts), and the number of those rows is reported next
> to it as `maseN` (`mase_n` in the reports). A row without such a log is left out of MASE alone: it still
> counts in `n`, `mae`, `bias` and the overload rates. `mase` is `NaN` when `maseN` is 0. The first
> implementation substituted zero hours for a missing prior-week log, which the section never said and which
> made MASE depend on how sparse the logs are rather than on the forecast.

## 5. Public API (Java interface only)

```java
// api
public record AccuracyRow(UUID userId, LocalDate day, UUID runId, int lead, double forecastHrs, double loggedHrs,
        double capacityHrs, boolean forecastOverload, boolean actualOverload) {}
public record AccuracyScore(String scope, String key, int n, double mae, double bias, double mase, int maseN,
        double overloadPrecision, double overloadRecall) {}   // scope: "team" | "member" | "lead"
public record AccuracyResult(UUID teamId, LocalDate from, LocalDate to, LocalDate evaluatedAt,
        List<AccuracyRow> current, List<AccuracyScore> scores, int nonWorkingDays) {}

// ForecastService
AccuracyResult accuracy(UUID teamId, LocalDate from, LocalDate to);
```

Validation as `currentForecast`: `INVALID_REQUEST` when an argument is null or `from` is after `to`,
`TEAM_NOT_FOUND` for an unknown team. `to` after yesterday is clamped, not refused. No REST endpoint (owner
decision; the server calls the Java method). Nothing is stored (owner decision): every call recomputes from
the three tables.

## 6. Where the code lives

- `eval/Accuracy.java`: the pure computation over `List<CurrentDayForecast>`, the per-run day rows with
  their `as_of`, and the truth map; returns `AccuracyResult`. No JDBC.
- `store/JdbcRunStore`: one new query, `runDays(teamId, from, to)` returning `forecast_member_days` rows
  of the team's `DONE` runs in the range joined with `forecast_runs.as_of` (`eval.RunDayForecast`).
- `service/DefaultForecastService.accuracy`: validation, loads truth through `ForecastRepository` (the
  same load a run does), calls `Accuracy`.
- `eval/AccuracyReport.java`: `accuracy.csv` (one row per member and day:
  `member_id,day,run_id,lead,forecast,truth,capacity,forecast_overload,actual_overload`) and
  `summary.md` (the scores table by scope; the truth source and the range at the top).
- CLI `AccuracyCommand` (`accuracy --team <name or id> [--from] [--to] [--db] [--out dir] [--json]`):
  prints the team and member scores as a table; `--out` writes the two files; `--json` prints the
  `AccuracyResult`. Exit 2 on a usage error, 1 on a failed call.

## 7. Testing

- `MetricsTest` gains nothing; `AccuracyTest` pins an example: three members, four days, one run at lead 1
  and one at lead 6, one missing log, one overload hit and one miss; the numbers are computed by hand in
  the test.
- jqwik `AccuracyProperties`: for random forecast and truth arrays, `mae >= 0`, `mae == 0` when forecast
  equals truth, `bias` changes sign when forecast and truth swap, the team `n` equals the sum of member `n`,
  and every `AccuracyRow` day is a weekday before `evaluatedAt`.
- `DefaultForecastServiceTest`: a run on the seeded database with the clock on 2026-08-19 (a Wednesday), then
  `accuracy(team, 2026-08-20, 2026-09-02)` with the clock on 2026-09-06: ten rows per member, `n` equal to
  the number of weekdays with a forecast, validation errors, unknown team. PostgreSQL through Testcontainers
  for the new query, like the other store tests.
- `AccuracyReportTest`: the two files and their columns. CLI: assertions in `RunCommandTest`'s seeded scenario.

## 8. Documents

`server/README.md` (CLI table, "Using the module" guide: one bullet for the accuracy call), the module
design spec (amendment notes in sections 11 and 12), `docs/backlog.md` (the item closes; the rulings under
"Java migration"), `CLAUDE.md` (layout line for the CLI commands, "Where the project stands").

## 9. Non-goals

A REST endpoint, a history table, a chart, an accuracy narrative from Copilot, recomputing capacity from
today's absences, comparing the window sums (the day rows carry everything; a window sum is a client-side
aggregate).
