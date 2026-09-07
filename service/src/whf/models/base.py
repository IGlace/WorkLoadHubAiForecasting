"""Common interface for arrival models."""

from __future__ import annotations

from typing import Protocol

import numpy as np
import pandas as pd

from whf.features import HORIZONS


class ModelUnavailable(RuntimeError):
    """Raised by a model factory when the model cannot run here (missing library or weights).

    The backtest skips the candidate and records the reason; a run never fails because of it.
    """


class ArrivalModel(Protocol):
    name: str

    def fit(self, train: pd.DataFrame, horizons: tuple[int, ...] = HORIZONS) -> ArrivalModel:
        """Learn from feature-matrix rows (targets may be NaN for the newest rows).

        Only the given horizons need to be trained; baselines accept and ignore this.
        """
        ...

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        """Estimated hours arriving `horizon` weeks after each row's week. Never negative.

        A model may also offer `predict_quantiles(rows, horizon) -> (low, high)`, the 0.1 and
        0.9 quantiles in the same units; the pipeline uses them for the demand band when present.
        """
        ...
