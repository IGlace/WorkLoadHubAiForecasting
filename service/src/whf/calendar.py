"""Week and working-day arithmetic. Weeks start on Monday; working days are Monday to Friday."""

from __future__ import annotations

import datetime as dt
from collections.abc import Iterable

import holidays

ONE_DAY = dt.timedelta(days=1)
ONE_WEEK = dt.timedelta(days=7)
HOLIDAY_COUNTRY = "MA"


def week_start(d: dt.date) -> dt.date:
    return d - dt.timedelta(days=d.weekday())


def last_complete_week(as_of: dt.date) -> dt.date:
    return week_start(as_of) - ONE_WEEK


def forecast_weeks(as_of: dt.date) -> tuple[dt.date, dt.date]:
    """The two Mondays to forecast: this week if as_of is a Monday, otherwise next week; plus one."""
    first = week_start(as_of) if as_of.weekday() == 0 else week_start(as_of) + ONE_WEEK
    return first, first + ONE_WEEK


def morocco_holidays(years: Iterable[int]) -> dict[dt.date, str]:
    cal = holidays.country_holidays(HOLIDAY_COUNTRY, years=list(years), language="en_US")
    return {d: name for d, name in cal.items()}


def working_days(start: dt.date, end: dt.date, off: set[dt.date] | frozenset[dt.date] = frozenset()) -> list[dt.date]:
    days: list[dt.date] = []
    d = start
    while d <= end:
        if d.weekday() < 5 and d not in off:
            days.append(d)
        d += ONE_DAY
    return days


def days_in_ranges(ranges: Iterable[tuple[dt.date, dt.date]]) -> set[dt.date]:
    days: set[dt.date] = set()
    for start, end in ranges:
        d = start
        while d <= end:
            days.add(d)
            d += ONE_DAY
    return days


def weeks_between(first: dt.date, last: dt.date) -> list[dt.date]:
    out: list[dt.date] = []
    w = week_start(first)
    stop = week_start(last)
    while w <= stop:
        out.append(w)
        w += ONE_WEEK
    return out
