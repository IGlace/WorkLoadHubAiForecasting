"""Weekly capacity: configured hours reduced by holidays, vacations and overrides."""

from __future__ import annotations

import datetime as dt

from whf.calendar import working_days

DEFAULT_WEEKLY_HOURS = 40.0
WORKING_DAYS_PER_WEEK = 5


def resolve_weekly_hours(default: float, permanent: float | None, week: float | None) -> float:
    """Week override beats permanent override beats default."""
    if week is not None:
        return float(week)
    if permanent is not None:
        return float(permanent)
    return float(default)


def available_hours(week: dt.date, weekly_hours: float, off: set[dt.date] | frozenset[dt.date]) -> float:
    """Hours available in the week starting on `week` (a Monday)."""
    days = working_days(week, week + dt.timedelta(days=6), off)
    return round(weekly_hours * len(days) / WORKING_DAYS_PER_WEEK, 2)


def overload_hours(demand: float, capacity: float) -> float:
    return max(0.0, demand - capacity)
