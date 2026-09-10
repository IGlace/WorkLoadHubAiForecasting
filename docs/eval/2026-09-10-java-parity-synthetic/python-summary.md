# Forecast evaluation, as of 2026-09-06

Truth: realised hours. Origins: 2026-08-10, 2026-07-27, 2026-07-13, 2026-06-29, 2026-06-15, 2026-06-01.
Models requested: gbm, seasonal_naive. Teams: all. Fine-tune: False.
Elapsed: 522.6 s on Intel(R) Xeon(R) Processor @ 2.30GHz, 4 logical CPUs, inference thread cap 4.

## Level A: arrival accuracy per model and horizon (means over origins)

`coverage80` and `wql` are scored on the model's own quantiles when it has them and on the leave-one-origin-out residual band the run would show otherwise, so every model is measured on the interval a user actually sees. `seconds` is fit plus predict for the whole backtest divided by the origins that model scored; the timing is not split per horizon, so it is reported on the first horizon row and left empty on the others.

| model | horizon | mae | mase | beats_naive | coverage80 | wql | seconds |
|---|---|---|---|---|---|---|---|
| gbm | 1 | 5.256 | 0.680 | 1.000 | 0.782 | 1.265 | 0.890 |
| gbm | 2 | 4.924 | 0.712 | 0.667 | 0.763 | 1.129 | nan |
| seasonal_naive | 1 | 7.077 | 1.000 | 0.000 | 0.776 | 1.700 | 0.001 |
| seasonal_naive | 2 | 6.835 | 1.000 | 0.000 | 0.756 | 2.249 | nan |

## Level B: demand accuracy per model (all origins, teams, members, weeks)

| model | mae | bias | open_only_mae | overload_precision | overload_recall | rows |
|---|---|---|---|---|---|---|
| gbm | 3.126 | -0.528 | 4.059 | 0.000 | 0.000 | 312 |
| seasonal_naive | 4.522 | 2.201 | 4.059 | 0.111 | 0.500 | 312 |

## Skipped models

none

## Truth and replay assumptions

- Truth is realised hours: each completed task's `actual_hours` spread evenly over the working days of its window, on the assignee's own calendar (weekdays minus holidays minus that member's vacation days), which is the calendar the forecast places effort on.
- Work still open at export time contributes zero realised hours, so the most recent origins are deflated and every model looks high there.
- The replay is a Monday-morning evaluation: each origin is replayed as of the Monday after it, so a task assigned on the first forecast Monday counts as open work rather than as an arrival.
- Only horizons 1 and 2 are scored, the two weeks a run forecasts.
- A single-origin run reports NaN interval coverage and NaN weighted quantile loss for a model without native quantiles: the leave-one-origin-out band needs at least one other origin to be drawn from.

## Data fingerprint

- members: 26
- teams: 27
- tasks: 943
- first_assigned: 2025-09-22
- last_assigned: 2026-09-04
- sha256: c95aed6242ee9b8623548bef60ec39cc29ea9e4469b18597c9804fd013ef760f

## Versions

- whf: 0.1.0
- torch: 2.14.0+cpu
- chronos-forecasting: 2.3.1
- scikit-learn: 1.9.0
- pandas: 3.0.5
- chronos-2 weights revision: 29ec3766d36d6f73f0696f85560a422f50e8498c
- torch seed: 0

Generated 2026-09-10T10:29:16.
