> Note, 2026-09-14: the procedure that produced this report — `server/tools/parity.sh` and
> `server/tools/parity_compare.py`, comparing the Java harness against the archived Python service — was
> retired by the weekly-hours-forecast design (section 16): there is one model, no champion and no MASE
> comparable across two harnesses any more, so nothing produces a report like this today. This directory is
> kept because it records a run that happened, not as a live procedure's output.
>
> The harness that produced these numbers was removed on 2026-09-14
> (`docs/superpowers/specs/2026-09-14-evaluation-removal-design.md`). This directory stays as the record of
> the run.

# Parity: Python harness against Java harness

| side | booster | mean MASE (h1, h2) | floor mean MASE | origins | champion |
|---|---|---|---|---|---|
| python | gbm | 0.696 | 1.000 | 6 | booster |
| java | xgboost | 0.677 | 1.000 | 6 | booster |

Absolute difference of the booster's mean MASE: 0.019 (tolerance 0.10).
Champion decision: python booster, java booster.

PASS
