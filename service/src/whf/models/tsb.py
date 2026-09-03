"""TSB (Teunter, Syntetos, Babai) intermittent-demand smoothing, one level per member."""

from __future__ import annotations

import numpy as np
import pandas as pd

from whf.features import HORIZONS


class TSB:
    name = "tsb"

    def __init__(self, alpha: float = 0.1, beta: float = 0.1) -> None:
        self.alpha = alpha
        self.beta = beta
        self._level: dict[int, float] = {}

    def fit(self, train: pd.DataFrame, horizons: tuple[int, ...] = HORIZONS) -> TSB:
        del horizons  # the level is horizon-independent
        ordered = train.sort_values("week_start")
        for member, series in ordered.groupby("member_id", observed=True)["est_hours"]:
            y = series.to_numpy(dtype=float)
            positive = y[y > 0]
            p = float((y > 0).mean()) if len(y) else 0.0
            z = float(positive.mean()) if len(positive) else 0.0
            for v in y:
                if v > 0:
                    p += self.alpha * (1.0 - p)
                    z += self.beta * (v - z)
                else:
                    p += self.alpha * (0.0 - p)
            self._level[int(member)] = max(0.0, p * z)
        return self

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        return np.array([self._level.get(int(m), 0.0) for m in rows["member_id"]], dtype=float)
