import datetime as dt

from hypothesis import given
from hypothesis import strategies as st

from whf.calendar import (
    days_in_ranges,
    forecast_weeks,
    last_complete_week,
    morocco_holidays,
    week_start,
    weeks_between,
    working_days,
)

dates = st.dates(min_value=dt.date(2020, 1, 1), max_value=dt.date(2030, 12, 31))


@given(dates)
def test_week_start_is_monday_on_or_before(d: dt.date) -> None:
    ws = week_start(d)
    assert ws.weekday() == 0
    assert 0 <= (d - ws).days <= 6


def test_last_complete_week_is_previous_monday() -> None:
    assert last_complete_week(dt.date(2026, 9, 3)) == dt.date(2026, 8, 24)


def test_forecast_weeks_on_a_thursday_start_next_monday() -> None:
    assert forecast_weeks(dt.date(2026, 9, 3)) == (dt.date(2026, 9, 7), dt.date(2026, 9, 14))


def test_forecast_weeks_on_a_monday_include_that_week() -> None:
    assert forecast_weeks(dt.date(2026, 9, 7)) == (dt.date(2026, 9, 7), dt.date(2026, 9, 14))


def test_working_days_skips_weekends_and_off_days() -> None:
    off = {dt.date(2026, 9, 9)}
    days = working_days(dt.date(2026, 9, 5), dt.date(2026, 9, 13), off)  # Sat .. Sun
    assert days == [dt.date(2026, 9, 7), dt.date(2026, 9, 8), dt.date(2026, 9, 10), dt.date(2026, 9, 11)]


def test_working_days_empty_when_end_before_start() -> None:
    assert working_days(dt.date(2026, 9, 10), dt.date(2026, 9, 9)) == []


def test_morocco_holidays_include_new_year() -> None:
    hol = morocco_holidays([2026])
    assert dt.date(2026, 1, 1) in hol
    assert hol[dt.date(2026, 1, 1)] == "New Year's Day"


def test_days_in_ranges_is_inclusive() -> None:
    days = days_in_ranges([(dt.date(2026, 9, 1), dt.date(2026, 9, 3)), (dt.date(2026, 9, 3), dt.date(2026, 9, 4))])
    assert days == {dt.date(2026, 9, 1), dt.date(2026, 9, 2), dt.date(2026, 9, 3), dt.date(2026, 9, 4)}


def test_weeks_between_returns_mondays_inclusive() -> None:
    assert weeks_between(dt.date(2026, 8, 26), dt.date(2026, 9, 8)) == [
        dt.date(2026, 8, 24),
        dt.date(2026, 8, 31),
        dt.date(2026, 9, 7),
    ]
