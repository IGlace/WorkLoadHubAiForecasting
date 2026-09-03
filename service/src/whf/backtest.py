"""Rolling-origin backtest that scores every arrival model and picks the champion."""

from __future__ import annotations

import datetime as dt
from collections.abc import Callable
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from whf.calendar import ONE_WEEK
from whf.models.base import ArrivalModel
from whf.models.naive import SeasonalNaive

FLOOR_MODEL = "seasonal_naive"


def mase(y_true: np.ndarray, y_pred: np.ndarray, y_naive: np.ndarray) -> float:
    y_true, y_pred, y_naive = (np.asarray(a, dtype=float) for a in (y_true, y_pred, y_naive))
    denominator = float(np.mean(np.abs(y_true - y_naive)))
    if denominator == 0.0:
        return float("nan")
    return float(np.mean(np.abs(y_true - y_pred)) / denominator)


def default_origins(last_complete_week: dt.date, count: int = 6, step_weeks: int = 2) -> list[dt.date]:
    return [last_complete_week - k * step_weeks * ONE_WEEK for k in range(1, count + 1)]


@dataclass
class BacktestResult:
    scores: pd.DataFrame
    residuals: dict[tuple[str, int], np.ndarray] = field(default_factory=dict)


def rolling_backtest(
    feat: pd.DataFrame,
    factories: dict[str, Callable[[], ArrivalModel]],
    origins: list[dt.date],
    horizons: tuple[int, ...],
) -> BacktestResult:
    rows: list[dict] = []
    residuals: dict[tuple[str, int], list[float]] = {}
    max_h = max(horizons)
    for origin in origins:
        train = feat[feat["week_start"] <= origin - max_h * ONE_WEEK]
        test = feat[feat["week_start"] == origin]
        if train.empty or test.empty:
            continue
        fitted = {name: factory().fit(train, horizons) for name, factory in factories.items()}
        naive = SeasonalNaive().fit(train, horizons)
        for h in horizons:
            y = test[f"target_h{h}"].to_numpy(dtype=float)
            if np.isnan(y).any():
                continue
            y_naive = naive.predict(test, h)
            for name, model in fitted.items():
                y_hat = np.clip(model.predict(test, h), 0.0, None)
                rows.append(
                    {
                        "model": name,
                        "origin": origin,
                        "horizon": h,
                        "mae": float(np.mean(np.abs(y - y_hat))),
                        "mase": mase(y, y_hat, y_naive),
                    }
                )
                residuals.setdefault((name, h), []).extend((y - y_hat).tolist())
    scores = pd.DataFrame(rows, columns=["model", "origin", "horizon", "mae", "mase"])
    return BacktestResult(scores=scores, residuals={k: np.array(v) for k, v in residuals.items()})


def select_champion(scores: pd.DataFrame, floor: str = FLOOR_MODEL) -> tuple[str, float]:
    if scores.empty:
        return floor, float("nan")
    means = scores.groupby("model")["mase"].mean().dropna()
    if means.empty:
        return floor, float("nan")
    best = str(means.idxmin())
    best_score = float(means[best])
    if best_score >= 1.0 or best == floor:
        return floor, float(means.get(floor, 1.0))
    return best, best_score


def interval_bounds(residuals: np.ndarray, low: float = 0.1, high: float = 0.9) -> tuple[float, float]:
    if len(residuals) == 0:
        return 0.0, 0.0
    return float(np.quantile(residuals, low)), float(np.quantile(residuals, high))
