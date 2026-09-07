"""Registry of arrival models. The backtest tries every factory and keeps the champion."""

from __future__ import annotations

from collections.abc import Callable

from whf.models.base import ArrivalModel
from whf.models.chronos2 import Chronos2Arrival
from whf.models.gbm import GradientBoostingArrival
from whf.models.naive import SeasonalNaive
from whf.models.tsb import TSB

MODEL_FACTORIES: dict[str, Callable[[], ArrivalModel]] = {
    "seasonal_naive": SeasonalNaive,
    "tsb": TSB,
    "gbm": GradientBoostingArrival,
    "chronos2": Chronos2Arrival,
}

__all__ = ["MODEL_FACTORIES", "ArrivalModel"]
