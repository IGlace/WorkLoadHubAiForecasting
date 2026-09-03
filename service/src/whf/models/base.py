"""Common interface for arrival models."""

from __future__ import annotations

from typing import Protocol

import numpy as np
import pandas as pd


class ArrivalModel(Protocol):
    name: str

    def fit(self, train: pd.DataFrame) -> ArrivalModel:
        """Learn from feature-matrix rows (targets may be NaN for the newest rows)."""
        ...

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        """Estimated hours arriving `horizon` weeks after each row's week. Never negative."""
        ...
