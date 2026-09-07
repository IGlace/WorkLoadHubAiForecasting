# Forecast evaluation, as of 2026-09-07

Truth: answer key. Origins: 2026-08-17, 2026-08-03, 2026-07-20, 2026-07-06, 2026-06-22, 2026-06-08.
Models requested: all. Teams: all. Fine-tune: False.
Elapsed: 1894.6 s on x86_64 with 4 logical CPUs.

## Level A: arrival accuracy per model and horizon (means over origins)

| model | horizon | mae | mase | beats_naive | coverage80 | wql | seconds |
|---|---|---|---|---|---|---|---|
| chronos2 | 1 | 11.450 | 0.868 | 1.000 | 0.759 | 0.590 | 3.804 |
| chronos2 | 2 | 13.170 | 0.855 | 0.833 | 0.715 | 0.571 | 3.804 |
| gbm | 1 | 10.905 | 0.821 | 1.000 | 0.781 | nan | 1.600 |
| gbm | 2 | 13.224 | 0.859 | 0.833 | 0.793 | nan | 1.600 |
| seasonal_naive | 1 | 13.208 | 1.000 | 0.000 | 0.789 | nan | 0.001 |
| seasonal_naive | 2 | 15.691 | 1.000 | 0.000 | 0.785 | nan | 0.001 |
| tsb | 1 | 14.371 | 1.110 | 0.333 | 0.785 | nan | 0.005 |
| tsb | 2 | 15.141 | 0.990 | 0.500 | 0.781 | nan | 0.005 |

## Level B: demand accuracy per model (all origins, teams, members, weeks)

| model | mae | bias | open_only_mae | overload_precision | overload_recall | rows |
|---|---|---|---|---|---|---|
| chronos2 | 9.077 | 2.710 | 11.210 | 0.000 | nan | 540 |
| gbm | 8.778 | 2.324 | 11.210 | 0.000 | nan | 540 |
| seasonal_naive | 11.291 | 4.824 | 11.210 | 0.000 | nan | 540 |
| tsb | 11.490 | 8.016 | 11.210 | 0.000 | nan | 540 |

## Skipped models

none

## Data fingerprint

- members: 48
- teams: 8
- tasks: 6522
- first_assigned: 2025-09-01
- last_assigned: 2026-09-03
- sha256: 985acf2c8d8ecb8de062dff326482d453c217373858ce1fdf569a117e92a3494

## Versions

- whf: 0.1.0
- torch: 2.14.0+cpu
- chronos-forecasting: 2.3.1
- scikit-learn: 1.9.0
- pandas: 3.0.5

Generated 2026-09-07T05:46:31.
