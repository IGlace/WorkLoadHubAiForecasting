import datetime as dt

import numpy as np
import pandas as pd

from whf.features import build_feature_matrix, weekly_arrivals
from whf.models import MODEL_FACTORIES
from whf.models.naive import SeasonalNaive
from whf.models.tsb import TSB

W0 = dt.date(2025, 1, 6)


def _frame(weeks: int = 60) -> pd.DataFrame:
    rows, tid = [], 0
    for i in range(weeks):
        w = W0 + dt.timedelta(days=7 * i)
        hours = [8.0, 0.0, 4.0, 0.0][i % 4]
        if hours:
            tid += 1
            rows.append(
                {"id": tid, "assignee_id": 1, "assigned_at": w, "estimated_hours": hours, "assignment_mode": "manual"}
            )
    tasks = pd.DataFrame(rows)
    arr = weekly_arrivals(tasks, [1], [W0 + dt.timedelta(days=7 * i) for i in range(weeks)])
    members = pd.DataFrame([{"id": 1, "team_id": 1}])
    projects = pd.DataFrame(columns=["id", "start_date", "deadline"])
    project_teams = pd.DataFrame(columns=["project_id", "team_id"])
    return build_feature_matrix(arr, tasks, projects, project_teams, members, set(), {})


def test_seasonal_naive_uses_last_year_when_available() -> None:
    feat = _frame()
    model = SeasonalNaive().fit(feat)
    row = feat[
        feat.week_start == W0 + dt.timedelta(days=7 * 56)
    ]  # i=56, target i=57 -> hours 0.0? pattern index 57%4=1 -> 0.0
    pred = model.predict(row, horizon=1)
    # one year before target week (i=57-52=5) has 0.0 hours (5%4=1)
    assert pred.tolist() == [0.0]
    row2 = feat[feat.week_start == W0 + dt.timedelta(days=7 * 55)]  # target i=56 -> last year i=4 -> 8.0
    assert model.predict(row2, horizon=1).tolist() == [8.0]


def test_seasonal_naive_falls_back_to_recent_mean() -> None:
    feat = _frame(weeks=10)
    model = SeasonalNaive().fit(feat)
    row = feat[feat.week_start == W0 + dt.timedelta(days=7 * 9)]
    assert model.predict(row, horizon=1).tolist() == [row["roll_mean_4"].iloc[0]]


def test_tsb_level_is_positive_and_below_max() -> None:
    feat = _frame()
    model = TSB().fit(feat)
    pred = model.predict(feat.tail(1), horizon=2)
    assert 0.0 < pred[0] < 8.0


def test_tsb_unknown_member_predicts_zero() -> None:
    feat = _frame()
    model = TSB().fit(feat)
    rows = feat.tail(1).copy()
    rows["member_id"] = pd.Categorical([999])
    assert model.predict(rows, horizon=1).tolist() == [0.0]


def test_registry_contains_baselines() -> None:
    assert {"seasonal_naive", "tsb"} <= set(MODEL_FACTORIES)
    for name, factory in MODEL_FACTORIES.items():
        assert factory().name == name


def test_predictions_are_never_negative() -> None:
    feat = _frame()
    for factory in MODEL_FACTORIES.values():
        pred = factory().fit(feat).predict(feat.dropna(subset=["target_h1"]), horizon=1)
        assert np.all(pred >= 0)
