"""Aggregate an EvalResult into tables and write scores.csv, demand.csv and summary.md."""

from __future__ import annotations

import datetime as dt
import os
import platform
from pathlib import Path

import numpy as np
import pandas as pd

from whf.eval.harness import EvalConfig, EvalResult
from whf.eval.metrics import bias, mae, overload_precision_recall
from whf.models.chronos2 import MAX_THREADS

LEVEL_A_METRIC_COLUMNS = ["model", "horizon", "mae", "mase", "beats_naive", "coverage80", "wql", "seconds"]
LEVEL_A_CAPTION = (
    "`coverage80` and `wql` are scored on the model's own quantiles when it has them and on the "
    "leave-one-origin-out residual band the run would show otherwise, so every model is measured on the "
    "interval a user actually sees. `seconds` is fit plus predict for the whole backtest divided by the "
    "origins that model scored; the timing is not split per horizon, so it is reported on the first "
    "horizon row and left empty on the others."
)
TRUTH_ASSUMPTIONS = {
    "realised hours": [
        "Truth is realised hours: each completed task's `actual_hours` spread evenly over the working days "
        "of its window, on the assignee's own calendar (weekdays minus holidays minus that member's "
        "vacation days), which is the calendar the forecast places effort on.",
        "Work still open at export time contributes zero realised hours, so the most recent origins are "
        "deflated and every model looks high there.",
    ],
    "answer key": [
        "Truth is the generator's answer key: the true hours per member and week the simulation produced, "
        "not a reconstruction from tasks.",
    ],
}
REPLAY_ASSUMPTIONS = [
    "The replay is a Monday-morning evaluation: each origin is replayed as of the Monday after it, so a "
    "task assigned on the first forecast Monday counts as open work rather than as an arrival.",
    "Only horizons 1 and 2 are scored, the two weeks a run forecasts.",
    "A single-origin run reports NaN interval coverage and NaN weighted quantile loss for a model without "
    "native quantiles: the leave-one-origin-out band needs at least one other origin to be drawn from.",
]


def summary_tables(result: EvalResult) -> tuple[pd.DataFrame, pd.DataFrame]:
    if result.scores.empty:
        level_a = pd.DataFrame(columns=LEVEL_A_METRIC_COLUMNS)
    else:
        # Default dropna=True here (not False): with a multi-level index, dropna=False on
        # pivot_table also materializes the full cartesian product of index levels, fabricating
        # an all-NaN row for every (model, horizon) pair that never occurred in the scores, e.g.
        # a phantom row for a model that was never run at a given horizon. The reindex below is
        # what restores a metric column that is legitimately NaN for every actual row (e.g. wql
        # when no model in the run produced quantiles) without inventing rows.
        level_a = result.scores.pivot_table(
            index=["model", "horizon"], columns="metric", values="value", aggfunc="mean"
        ).reset_index()
        level_a = level_a.reindex(columns=LEVEL_A_METRIC_COLUMNS)
    rows = []
    for name, g in result.demand.groupby("model"):
        y, p = g["truth"].to_numpy(dtype=float), g["forecast"].to_numpy(dtype=float)
        cap = g["capacity"].to_numpy(dtype=float)
        prec, rec = overload_precision_recall(y > cap, p > cap)
        rows.append(
            {
                "model": name,
                "mae": mae(y, p),
                "bias": bias(y, p),
                "open_only_mae": mae(y, g["open_hours"].to_numpy(dtype=float)),
                "overload_precision": prec,
                "overload_recall": rec,
                "rows": int(len(g)),
            }
        )
    level_b = pd.DataFrame(
        rows, columns=["model", "mae", "bias", "open_only_mae", "overload_precision", "overload_recall", "rows"]
    )
    return level_a, level_b


def _cpu_name() -> str:
    """The CPU as the machine names it. `platform.processor()` degrades to `x86_64` on Linux, which
    says nothing about which machine produced a timing, so read /proc/cpuinfo first where it exists."""
    try:
        for line in Path("/proc/cpuinfo").read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or platform.machine()


def _markdown(df: pd.DataFrame) -> str:
    if df.empty:
        return "(no rows)\n"
    cols = list(df.columns)
    lines = ["| " + " | ".join(str(c) for c in cols) + " |", "|" + "---|" * len(cols)]
    for r in df.itertuples(index=False):
        cells = [f"{v:.3f}" if isinstance(v, float | np.floating) else str(v) for v in r]
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


def write_outputs(
    result: EvalResult, fingerprint: dict, config: EvalConfig, out_dir: Path, versions: dict[str, str]
) -> Path:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    result.scores.to_csv(out_dir / "scores.csv", index=False)
    result.demand.to_csv(out_dir / "demand.csv", index=False)
    level_a, level_b = summary_tables(result)
    parts = [
        f"# Forecast evaluation, as of {config.as_of.isoformat()}",
        "",
        f"Truth: {result.truth_source}. Origins: {', '.join(o.isoformat() for o in result.origins) or 'none'}.",
        f"Models requested: {', '.join(config.models) or 'all'}. Teams: {', '.join(map(str, config.teams)) or 'all'}. Fine-tune: {config.finetune}.",
        f"Elapsed: {result.elapsed_seconds:.1f} s on {_cpu_name()}, {os.cpu_count()} logical CPUs, "
        f"inference thread cap {MAX_THREADS}.",
        "",
        "## Level A: arrival accuracy per model and horizon (means over origins)",
        "",
        LEVEL_A_CAPTION,
        "",
        _markdown(level_a),
        "## Level B: demand accuracy per model (all origins, teams, members, weeks)",
        "",
        _markdown(level_b),
        "## Skipped models",
        "",
        "\n".join(f"- {name}: {reason}" for name, reason in result.skipped.items()) or "none",
        "",
        "## Truth and replay assumptions",
        "",
        "\n".join(
            f"- {line}"
            for line in TRUTH_ASSUMPTIONS.get(result.truth_source, ["Truth source: " + (result.truth_source or "none")])
            + REPLAY_ASSUMPTIONS
        ),
        "",
        "## Data fingerprint",
        "",
        "\n".join(f"- {k}: {v}" for k, v in fingerprint.items()),
        "",
        "## Versions",
        "",
        "\n".join(f"- {k}: {v}" for k, v in versions.items()),
        "",
        f"Generated {dt.datetime.now().isoformat(timespec='seconds')}.",
        "",
    ]
    (out_dir / "summary.md").write_text("\n".join(parts), encoding="utf-8")
    return out_dir
