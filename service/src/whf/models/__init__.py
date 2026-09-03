"""Registry of arrival models. The backtest tries every factory and keeps the champion."""

from __future__ import annotations

from collections.abc import Callable

from whf.models.base import ArrivalModel
from whf.models.gbm import GradientBoostingArrival
from whf.models.naive import SeasonalNaive
from whf.models.tsb import TSB

MODEL_FACTORIES: dict[str, Callable[[], ArrivalModel]] = {
    "seasonal_naive": SeasonalNaive,
    "tsb": TSB,
    "gbm": GradientBoostingArrival,
}

__all__ = ["MODEL_FACTORIES", "ArrivalModel"]
