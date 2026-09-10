# Forecast evaluation, as of 2026-09-06

Truth: time logs. Origins: 2026-06-01, 2026-06-15, 2026-06-29, 2026-07-13, 2026-07-27, 2026-08-10.
Models requested: xgboost, seasonal_naive. Teams: all.
Elapsed: 93.8 s on Intel(R) Xeon(R) Processor @ 2.30GHz, 4 logical CPUs.

## Level A: arrival accuracy per model and horizon (means over origins)

`coverage80` and `wql` are scored on the leave-one-origin-out residual band the run would show, so every model is measured on the interval a user actually sees. `seconds` is fit plus predict for the whole backtest divided by the origins that model scored; it is reported on the first horizon row and left empty on the others.

| model | horizon | mae | mase | beats_naive | coverage80 | wql | seconds |
|---|---|---|---|---|---|---|---|
| seasonal_naive | 1 | 7.077 | 1.000 | 0.000 | 0.776 | 1.700 | 0.000 |
| seasonal_naive | 2 | 6.835 | 1.000 | 0.000 | 0.756 | 2.249 | nan |
| xgboost | 1 | 4.918 | 0.644 | 0.833 | 0.788 | 1.126 | 2.074 |
| xgboost | 2 | 4.797 | 0.709 | 1.000 | 0.788 | 1.077 | nan |

## Level B: demand accuracy per model (all origins, teams, members, weeks)

| model | mae | bias | open_only_mae | overload_precision | overload_recall | rows |
|---|---|---|---|---|---|---|
| seasonal_naive | 10.293 | 6.993 | 6.103 | 0.000 | nan | 408 |
| xgboost | 8.922 | 4.311 | 6.103 | 0.000 | nan | 408 |

## Skipped models

none

## Truth and replay assumptions

- Truth is the hours logged in time_logs, summed per member and Monday week; work still in progress at export time has few logs, so the most recent origins are deflated and every model looks high there.
- The replay is a Monday-morning evaluation: each origin is replayed as of the Monday after it, so a task assigned on the first forecast Monday counts as open work rather than as an arrival.
- Only horizons 1 and 2 are scored, the two weeks a run forecasts.
- A single-origin run reports NaN interval coverage and NaN weighted quantile loss: the leave-one-origin-out band needs another origin.

## Data fingerprint

- members: 26
- teams: 27
- tasks: 1528
- time_logs: 4923
- first_created: 2025-09-01
- last_created: 2026-09-04

## Versions

- java: 21.0.10
- xgboost4j: 3.4.0
- forecast-cli: 0.1.0

Generated 2026-09-10T10:20:31.
