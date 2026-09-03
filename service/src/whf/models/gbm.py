"""Global gradient boosting model over member-week rows, one regressor per horizon."""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

from whf.features import HORIZONS, feature_columns


class GradientBoostingArrival:
    name = "gbm"

    def __init__(self, max_iter: int = 300, learning_rate: float = 0.05, random_state: int = 0) -> None:
        self.max_iter = max_iter
        self.learning_rate = learning_rate
        self.random_state = random_state
        self._models: dict[int, HistGradientBoostingRegressor] = {}
        self._columns: dict[int, list[str]] = {}

    def fit(self, train: pd.DataFrame) -> GradientBoostingArrival:
        for h in HORIZONS:
            target = f"target_h{h}"
            rows = train.dropna(subset=[target])
            # scikit-learn rejects columns that are missing everywhere (short histories make lag13 empty)
            columns = [c for c in feature_columns(h) if rows[c].notna().any()]
            model = HistGradientBoostingRegressor(
                loss="poisson",
                categorical_features="from_dtype",
                max_iter=self.max_iter,
                learning_rate=self.learning_rate,
                random_state=self.random_state,
            )
            self._models[h] = model.fit(rows[columns], rows[target].to_numpy(dtype=float))
            self._columns[h] = columns
        return self

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        pred = self._models[horizon].predict(rows[self._columns[horizon]])
        return np.clip(pred, 0.0, None)
