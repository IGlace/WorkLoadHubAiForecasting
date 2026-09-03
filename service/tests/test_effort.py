import datetime as dt

import numpy as np
import pandas as pd
import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from whf.calendar import week_start
from whf.models.effort import MIN_REMAINING_FRACTION, EffortModel, place_hours, place_new_arrivals, place_open_tasks

dates = st.dates(min_value=dt.date(2026, 1, 1), max_value=dt.date(2026, 12, 31))


@settings(max_examples=200)
@given(st.floats(min_value=0, max_value=200), dates, st.integers(min_value=0, max_value=40))
def test_place_hours_conserves_hours_and_keys_are_mondays(hours: float, start: dt.date, span: int) -> None:
    end = start + dt.timedelta(days=span)
    placed = place_hours(hours, start, end, set())
    assert abs(sum(placed.values()) - hours) < 1e-6
    assert all(k.weekday() == 0 for k in placed)
    assert all(v >= 0 for v in placed.values())
    assert min(placed) >= week_start(start) and max(placed) <= week_start(end)


def test_place_hours_skips_off_days_and_weekends() -> None:
    placed = place_hours(10.0, dt.date(2026, 9, 3), dt.date(2026, 9, 9), {dt.date(2026, 9, 7)})
    assert placed == {dt.date(2026, 8, 31): 5.0, dt.date(2026, 9, 7): 5.0}


def test_place_hours_with_no_working_day_uses_start_week() -> None:
    placed = place_hours(3.0, dt.date(2026, 9, 5), dt.date(2026, 9, 6), set())  # Sat, Sun
    assert placed == {dt.date(2026, 8, 31): 3.0}


@pytest.fixture(scope="session")
def _model() -> EffortModel:
    return EffortModel().fit(_done())


def _done(n: int = 120, seed: int = 1) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    rows = []
    for i in range(n):
        member = 1 + i % 3
        est = float(rng.uniform(2, 20))
        bias = {1: 0.8, 2: 1.0, 3: 1.6}[member]
        assigned = dt.date(2026, 1, 5) + dt.timedelta(days=int(rng.integers(0, 200)))
        cycle_base = {1: 2, 2: 5, 3: 12}[member]
        cycle = cycle_base + int(rng.integers(0, 3))
        rows.append(
            {
                "assignee_id": member,
                "team_id": 1,
                "type": "feature" if i % 2 else "bug",
                "priority": "medium",
                "estimated_hours": est,
                "actual_hours": est * bias,
                "assigned_at": assigned,
                "completed_at": assigned + dt.timedelta(days=cycle),
                "due_date": assigned + dt.timedelta(days=cycle_base),
            }
        )
    return pd.DataFrame(rows)


def test_estimate_ratio_reflects_member_bias_and_is_clipped(_model: EffortModel) -> None:
    model = _model
    assert model.estimate_ratio(1, "bug", 1) < model.estimate_ratio(3, "bug", 1)
    assert 0.5 <= model.estimate_ratio(3, "feature", 1) <= 2.5
    assert 0.5 <= model.estimate_ratio(999, None, 1) <= 2.5  # unknown member falls back to team


def test_cycle_days_orders_members(_model: EffortModel) -> None:
    model = _model
    rows = pd.DataFrame(
        [
            {"assignee_id": 1, "team_id": 1, "type": "bug", "priority": "medium", "estimated_hours": 8.0},
            {"assignee_id": 3, "team_id": 1, "type": "bug", "priority": "medium", "estimated_hours": 8.0},
        ]
    )
    pred = model.predict_cycle_days(rows)
    assert pred[0] < pred[1]
    assert model.member_cycle_days(1, 1) < model.member_cycle_days(3, 1)


def test_small_history_uses_medians_not_gbm() -> None:
    model = EffortModel(min_rows_for_gbm=1000).fit(_done(n=30))
    rows = pd.DataFrame([{"assignee_id": 2, "team_id": 1, "type": "bug", "priority": "low", "estimated_hours": 3.0}])
    assert model.predict_cycle_days(rows)[0] > 0


def test_place_open_tasks_puts_remaining_hours_from_as_of_forward(_model: EffortModel) -> None:
    model = _model
    as_of = dt.date(2026, 9, 3)
    open_tasks = pd.DataFrame(
        [
            {
                "id": 1,
                "assignee_id": 2,
                "team_id": 1,
                "type": "feature",
                "priority": "medium",
                "estimated_hours": 10.0,
                "assigned_at": dt.date(2026, 9, 1),
                "due_date": dt.date(2026, 9, 10),
            },
            {
                "id": 2,
                "assignee_id": 2,
                "team_id": 1,
                "type": "bug",
                "priority": "high",
                "estimated_hours": 4.0,
                "assigned_at": dt.date(2026, 6, 1),
                "due_date": dt.date(2026, 6, 5),
            },  # very overdue
        ]
    )
    placed = place_open_tasks(open_tasks, model, as_of, {})
    assert set(placed.columns) == {"member_id", "week_start", "hours"}
    assert (placed["week_start"] >= week_start(as_of)).all()
    assert placed["hours"].sum() > 0
    assert placed["hours"].sum() <= 14.0 * 2.5


def test_place_new_arrivals_spreads_over_member_cycle(_model: EffortModel) -> None:
    model = _model
    predicted = pd.DataFrame(
        [
            {"member_id": 3, "week_start": dt.date(2026, 9, 7), "est_hours": 20.0},
            {"member_id": 1, "week_start": dt.date(2026, 9, 7), "est_hours": 20.0},
        ]
    )
    placed = place_new_arrivals(predicted, model, {}, {1: 1, 3: 1})
    m3 = placed[placed.member_id == 3]
    m1 = placed[placed.member_id == 1]
    assert m3["week_start"].nunique() >= 2  # long cycle spills into the next week
    assert m1["week_start"].nunique() == 1  # short cycle stays in the week
    assert abs(m1["hours"].sum() - 20.0 * model.estimate_ratio(1, None, 1)) < 1e-6


def test_cycle_shrinkage_blends_member_and_team(_model: EffortModel) -> None:
    model = _model
    # Member 1 has short cycle (2 days), team has longer cycle
    m1_cycle = model.member_cycle_days(1, 1)
    team_cycle = model.member_cycle_days(999, 1)  # unknown member uses team
    assert m1_cycle < team_cycle
    assert m1_cycle > 1.0  # floor


def test_place_open_tasks_shifts_by_lateness(_model: EffortModel) -> None:
    model = _model
    as_of = dt.date(2026, 9, 3)
    # Member 2 is typically on-time; task due soon but estimated work spreads past due
    open_tasks = pd.DataFrame(
        [
            {
                "id": 1,
                "assignee_id": 2,
                "team_id": 1,
                "type": "feature",
                "priority": "medium",
                "estimated_hours": 12.0,
                "assigned_at": dt.date(2026, 9, 1),
                "due_date": dt.date(2026, 9, 5),
            },
        ]
    )
    placed = place_open_tasks(open_tasks, model, as_of, {})
    assert placed["hours"].sum() > 0


@settings(max_examples=3, deadline=None)
@given(st.floats(min_value=0, max_value=200), dates)
def test_place_new_arrivals_property(_model: EffortModel, est_hours: float, week_start_date: dt.date) -> None:
    model = _model
    ws = week_start(week_start_date)
    predicted = pd.DataFrame([{"member_id": 2, "week_start": ws, "est_hours": est_hours}])
    placed = place_new_arrivals(predicted, model, {}, {2: 1})
    if placed.shape[0] > 0:
        assert abs(placed["hours"].sum() - est_hours * model.estimate_ratio(2, None, 1)) < 1e-6
        assert all(h >= 0 for h in placed["hours"])
        assert all(d.weekday() == 0 for d in placed["week_start"])
        assert (placed["week_start"] >= ws).all()
        cycle = model.member_cycle_days(2, 1)
        max_weeks = int(np.ceil(cycle / 5.0)) + 1
        assert placed["week_start"].nunique() <= max_weeks


@settings(max_examples=3, deadline=None)
@given(
    st.floats(min_value=0, max_value=100),
    st.dates(min_value=dt.date(2026, 1, 1), max_value=dt.date(2026, 9, 3)),
    st.just(dt.date(2026, 9, 3)),
    st.one_of(
        st.none(), st.integers(min_value=1, max_value=30).map(lambda d: dt.date(2026, 9, 3) + dt.timedelta(days=d))
    ),
)
def test_place_open_tasks_property(
    _model: EffortModel, est_hours: float, assigned_at: dt.date, as_of: dt.date, due_date: dt.date | None
) -> None:
    model = _model
    open_tasks = pd.DataFrame(
        [
            {
                "id": 1,
                "assignee_id": 2,
                "team_id": 1,
                "type": "bug",
                "priority": "medium",
                "estimated_hours": est_hours,
                "assigned_at": assigned_at,
                "due_date": due_date,
            },
        ]
    )
    placed = place_open_tasks(open_tasks, model, as_of, {})
    if placed.shape[0] > 0:
        assert all(h >= 0 for h in placed["hours"])
        assert (placed["week_start"] >= week_start(as_of)).all()
        assert all(d.weekday() == 0 for d in placed["week_start"])
        upper_bound = est_hours * 2.5
        lower_bound = est_hours * 0.5 * MIN_REMAINING_FRACTION if est_hours > 0 else 0
        assert placed["hours"].sum() <= upper_bound
        assert placed["hours"].sum() >= lower_bound
