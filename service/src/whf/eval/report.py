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

LEVEL_A_METRIC_COLUMNS = ["model", "horizon", "mae", "mase", "beats_naive", "coverage80", "wql", "seconds"]


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
        f"Elapsed: {result.elapsed_seconds:.1f} s on {platform.processor() or platform.machine()} with {os.cpu_count()} logical CPUs.",
        "",
        "## Level A: arrival accuracy per model and horizon (means over origins)",
        "",
        _markdown(level_a),
        "## Level B: demand accuracy per model (all origins, teams, members, weeks)",
        "",
        _markdown(level_b),
        "## Skipped models",
        "",
        "\n".join(f"- {name}: {reason}" for name, reason in result.skipped.items()) or "none",
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
