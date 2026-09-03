"""Seasonal naive: last year's same week when known, else the recent four-week mean."""

from __future__ import annotations

import datetime as dt

import numpy as np
import pandas as pd

ONE_YEAR = dt.timedelta(days=364)


class SeasonalNaive:
    name = "seasonal_naive"

    def __init__(self) -> None:
        self._history: dict[tuple[int, dt.date], float] = {}

    def fit(self, train: pd.DataFrame) -> SeasonalNaive:
        self._history = {
            (int(m), w): float(h)
            for m, w, h in zip(train["member_id"], train["week_start"], train["est_hours"], strict=True)
        }
        return self

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        out = []
        for m, w, fallback in zip(rows["member_id"], rows["week_start"], rows["roll_mean_4"], strict=True):
            target = w + dt.timedelta(days=7 * horizon)
            value = self._history.get((int(m), target - ONE_YEAR))
            out.append(max(0.0, value if value is not None else float(fallback)))
        return np.array(out, dtype=float)
