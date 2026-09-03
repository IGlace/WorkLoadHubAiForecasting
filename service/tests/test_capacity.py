import datetime as dt

from hypothesis import given
from hypothesis import strategies as st

from whf.capacity import DEFAULT_WEEKLY_HOURS, available_hours, overload_hours, resolve_weekly_hours

MONDAY = dt.date(2026, 9, 7)


def test_default_is_forty() -> None:
    assert DEFAULT_WEEKLY_HOURS == 40.0


def test_resolve_precedence_week_over_permanent_over_default() -> None:
    assert resolve_weekly_hours(40.0, None, None) == 40.0
    assert resolve_weekly_hours(40.0, 32.0, None) == 32.0
    assert resolve_weekly_hours(40.0, 32.0, 20.0) == 20.0
    assert resolve_weekly_hours(40.0, None, 20.0) == 20.0


def test_full_week_gives_weekly_hours() -> None:
    assert available_hours(MONDAY, 40.0, set()) == 40.0


def test_one_holiday_removes_one_fifth() -> None:
    assert available_hours(MONDAY, 40.0, {dt.date(2026, 9, 9)}) == 32.0


def test_full_vacation_week_gives_zero() -> None:
    off = {MONDAY + dt.timedelta(days=i) for i in range(7)}
    assert available_hours(MONDAY, 40.0, off) == 0.0


@given(
    st.floats(min_value=0, max_value=80),
    st.sets(st.integers(min_value=0, max_value=6), max_size=7),
)
def test_available_is_between_zero_and_weekly(weekly: float, off_offsets: set[int]) -> None:
    off = {MONDAY + dt.timedelta(days=i) for i in off_offsets}
    got = available_hours(MONDAY, weekly, off)
    assert 0.0 <= got <= weekly + 0.005  # rounded to two decimals


@given(st.floats(min_value=0, max_value=200), st.floats(min_value=0, max_value=200))
def test_overload_never_negative(demand: float, capacity: float) -> None:
    got = overload_hours(demand, capacity)
    assert got >= 0.0
    assert got == max(0.0, demand - capacity)
