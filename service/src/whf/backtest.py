"""Rolling-origin backtest that scores every arrival model and picks the champion."""

from __future__ import annotations

import datetime as dt
import time
from collections.abc import Callable
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from whf.calendar import ONE_WEEK
from whf.models.base import ArrivalModel, ModelUnavailable
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
    residual_frames: dict[tuple[str, int], pd.DataFrame] = field(default_factory=dict)
    quantiles: dict[tuple[str, int], pd.DataFrame] = field(default_factory=dict)
    unavailable: dict[str, str] = field(default_factory=dict)
    timings: dict[str, float] = field(default_factory=dict)


def rolling_backtest(
    feat: pd.DataFrame,
    factories: dict[str, Callable[[], ArrivalModel]],
    origins: list[dt.date],
    horizons: tuple[int, ...],
) -> BacktestResult:
    rows: list[dict] = []
    residuals: dict[tuple[str, int], list[float]] = {}
    residual_rows: dict[tuple[str, int], list[dict]] = {}
    quantiles: dict[tuple[str, int], list[dict]] = {}
    unavailable: dict[str, str] = {}
    timings: dict[str, float] = {}
    max_h = max(horizons)
    for origin in origins:
        train = feat[feat["week_start"] <= origin - max_h * ONE_WEEK]
        test = feat[feat["week_start"] == origin]
        if train.empty or test.empty:
            continue
        fitted: dict[str, ArrivalModel] = {}
        for name, factory in factories.items():
            if name in unavailable:
                continue
            started = time.perf_counter()
            try:
                fitted[name] = factory().fit(train, horizons)
            except ModelUnavailable as exc:
                unavailable[name] = str(exc)
                continue
            timings[name] = timings.get(name, 0.0) + time.perf_counter() - started
        naive = SeasonalNaive().fit(train, horizons)
        for h in horizons:
            y = test[f"target_h{h}"].to_numpy(dtype=float)
            if np.isnan(y).any():
                continue
            y_naive = naive.predict(test, h)
            for name, model in fitted.items():
                started = time.perf_counter()
                y_hat = np.clip(model.predict(test, h), 0.0, None)
                band = model.predict_quantiles(test, h) if hasattr(model, "predict_quantiles") else None
                timings[name] += time.perf_counter() - started
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
                residual_rows.setdefault((name, h), []).extend(
                    {"origin": origin, "residual": float(v)} for v in (y - y_hat)
                )
                if band is not None:
                    low, high = (np.clip(np.asarray(b, dtype=float), 0.0, None) for b in band)
                    quantiles.setdefault((name, h), []).extend(
                        {"origin": origin, "y": float(a), "low": float(b), "high": float(c)}
                        for a, b, c in zip(y, low, high, strict=True)
                    )
    scores = pd.DataFrame(rows, columns=["model", "origin", "horizon", "mae", "mase"])
    return BacktestResult(
        scores=scores,
        residuals={k: np.array(v) for k, v in residuals.items()},
        residual_frames={k: pd.DataFrame(v, columns=["origin", "residual"]) for k, v in residual_rows.items()},
        quantiles={k: pd.DataFrame(v, columns=["origin", "y", "low", "high"]) for k, v in quantiles.items()},
        unavailable=unavailable,
        timings=timings,
    )


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
