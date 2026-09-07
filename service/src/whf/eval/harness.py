"""Two-level evaluation: arrival accuracy per model, and demand accuracy of the whole pipeline per model."""

from __future__ import annotations

import datetime as dt
import sqlite3
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

from whf.backtest import default_origins, interval_bounds, rolling_backtest
from whf.calendar import ONE_WEEK, last_complete_week
from whf.eval.metrics import coverage, weighted_quantile_loss
from whf.eval.truth import realised_hours, truncated_copy, truth_from_answer_key
from whf.models import MODEL_FACTORIES
from whf.models.base import ArrivalModel, ModelUnavailable
from whf.pipeline import MIN_WEEKS_BEFORE_ORIGIN, _load_frames, arrival_feature_matrix, run_forecast

HORIZONS = (1, 2)
METRIC_COLUMNS = ["model", "horizon", "origin", "metric", "value"]
DEMAND_COLUMNS = [
    "model",
    "origin",
    "team_id",
    "member_id",
    "week_start",
    "forecast",
    "truth",
    "capacity",
    "open_hours",
    "new_hours",
]


@dataclass
class EvalConfig:
    as_of: dt.date
    origins: int = 6
    models: tuple[str, ...] = ()
    teams: tuple[int, ...] = ()
    finetune: bool = False
    answer_key: Path | None = None


@dataclass
class EvalResult:
    scores: pd.DataFrame
    demand: pd.DataFrame
    skipped: dict[str, str] = field(default_factory=dict)
    truth_source: str = ""
    elapsed_seconds: float = 0.0
    origins: list[dt.date] = field(default_factory=list)


def _leave_one_out_band(residuals: pd.DataFrame, origin: dt.date) -> tuple[float, float]:
    others = residuals[residuals["origin"] != origin]["residual"].to_numpy(dtype=float)
    return interval_bounds(others) if len(others) else (0.0, 0.0)


def arrival_level(
    feat: pd.DataFrame,
    factories: dict[str, Callable[[], ArrivalModel]],
    origins: list[dt.date],
    horizons: tuple[int, ...],
) -> tuple[pd.DataFrame, dict[str, str]]:
    result = rolling_backtest(feat, factories, origins, horizons)
    rows: list[dict] = []
    per_origin_residuals = result.residual_frames
    for score in result.scores.itertuples(index=False):
        base = {"model": score.model, "horizon": score.horizon, "origin": score.origin}
        rows.append({**base, "metric": "mae", "value": score.mae})
        rows.append({**base, "metric": "mase", "value": score.mase})
        rows.append(
            {
                **base,
                "metric": "beats_naive",
                "value": float(score.mase < 1.0) if not np.isnan(score.mase) else float("nan"),
            }
        )
        key = (score.model, score.horizon)
        res = per_origin_residuals[key]
        mine = res[res.origin == score.origin]["residual"].to_numpy(dtype=float)
        if key in result.quantiles:
            q = result.quantiles[key]
            q = q[q.origin == score.origin]
            y, low, high = (q[c].to_numpy(dtype=float) for c in ("y", "low", "high"))
            point = y - mine
            cov, wql = coverage(y, low, high), weighted_quantile_loss(y, {0.1: low, 0.5: point, 0.9: high})
        else:
            low_off, high_off = _leave_one_out_band(res, score.origin)
            cov = coverage(mine, np.full_like(mine, low_off), np.full_like(mine, high_off))
            wql = float("nan")
        rows.append({**base, "metric": "coverage80", "value": cov})
        rows.append({**base, "metric": "wql", "value": wql})
        rows.append(
            {**base, "metric": "seconds", "value": result.timings.get(score.model, float("nan")) / max(len(origins), 1)}
        )
    return pd.DataFrame(rows, columns=METRIC_COLUMNS), dict(result.unavailable)


def demand_level(
    conn: sqlite3.Connection,
    factories: dict[str, Callable[[], ArrivalModel]],
    origins: list[dt.date],
    teams: tuple[int, ...],
    truth: pd.DataFrame,
) -> tuple[pd.DataFrame, dict[str, str]]:
    skipped: dict[str, str] = {}
    rows: list[dict] = []
    truth_index = {
        (int(m), w): float(h) for m, w, h in zip(truth.member_id, truth.week_start, truth.hours, strict=True)
    }
    for origin in origins:
        as_of = origin + ONE_WEEK
        replay = truncated_copy(conn, as_of)
        team_ids = teams or tuple(int(t) for t in _load_frames(replay)["teams"]["id"])
        for name in factories:
            if name in skipped:
                continue
            for team_id in team_ids:
                try:
                    result = run_forecast(replay, team_id=team_id, as_of=as_of, force_model=name, persist=False)
                except ModelUnavailable as exc:
                    skipped[name] = str(exc)
                    break
                except ValueError:
                    continue  # team without counted members
                for r in result.forecasts.itertuples(index=False):
                    rows.append(
                        {
                            "model": name,
                            "origin": origin,
                            "team_id": team_id,
                            "member_id": int(r.member_id),
                            "week_start": r.week_start,
                            "forecast": float(r.demand_hours),
                            "truth": truth_index.get((int(r.member_id), r.week_start), 0.0),
                            "capacity": float(r.capacity_hours),
                            "open_hours": float(r.open_task_hours),
                            "new_hours": float(r.new_task_hours),
                        }
                    )
    return pd.DataFrame(rows, columns=DEMAND_COLUMNS), skipped


def evaluate(
    conn: sqlite3.Connection,
    config: EvalConfig,
    factories: dict[str, Callable[[], ArrivalModel]] | None = None,
) -> EvalResult:
    started = time.perf_counter()
    factories = factories or MODEL_FACTORIES
    for name in config.models:
        if name not in factories:
            raise ValueError(f"unknown model {name!r}; known: {sorted(factories)}")
    chosen = {name: factories[name] for name in (config.models or tuple(factories))}
    if config.finetune:
        # Harness-only candidate: the same adapter, fine-tuned on the training window of every origin.
        from whf.models.chronos2 import Chronos2Arrival

        chosen["chronos2_ft"] = lambda: Chronos2Arrival(finetune=True)
    frames = _load_frames(conn)
    origin = last_complete_week(config.as_of)
    _, feat, weeks = arrival_feature_matrix(frames, origin)
    origins = [o for o in default_origins(origin, config.origins) if o >= weeks[0] + MIN_WEEKS_BEFORE_ORIGIN * ONE_WEEK]
    scores, skipped_a = arrival_level(feat, chosen, origins, HORIZONS)
    if config.answer_key is not None:
        truth, source = truth_from_answer_key(config.answer_key), "answer key"
    else:
        truth, source = realised_hours(conn), "realised hours"
    demand, skipped_b = demand_level(
        conn, {n: f for n, f in chosen.items() if n not in skipped_a}, origins, config.teams, truth
    )
    return EvalResult(
        scores=scores,
        demand=demand,
        skipped={**skipped_a, **skipped_b},
        truth_source=source,
        elapsed_seconds=time.perf_counter() - started,
        origins=origins,
    )
