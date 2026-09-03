import datetime as dt

import numpy as np
import pandas as pd

from whf.features import build_feature_matrix, weekly_arrivals
from whf.models import MODEL_FACTORIES
from whf.models.gbm import GradientBoostingArrival

W0 = dt.date(2025, 1, 6)


def _frame(members: int = 6, weeks: int = 70, seed: int = 0) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    rows, tid = [], 0
    week_list = [W0 + dt.timedelta(days=7 * i) for i in range(weeks)]
    for m in range(1, members + 1):
        rate = 2 + m
        for i, w in enumerate(week_list):
            n = rng.poisson(rate * (1.5 if i % 8 == 7 else 1.0))
            for _ in range(n):
                tid += 1
                rows.append(
                    {
                        "id": tid,
                        "assignee_id": m,
                        "assigned_at": w + dt.timedelta(days=int(rng.integers(0, 5))),
                        "estimated_hours": float(rng.uniform(1, 6)),
                        "assignment_mode": "project",
                    }
                )
    tasks = pd.DataFrame(rows)
    arr = weekly_arrivals(tasks, list(range(1, members + 1)), week_list)
    mem = pd.DataFrame([{"id": m, "team_id": 1} for m in range(1, members + 1)])
    projects = pd.DataFrame([{"id": 1, "start_date": W0, "deadline": W0 + dt.timedelta(days=7 * weeks)}])
    project_teams = pd.DataFrame([{"project_id": 1, "team_id": 1}])
    return build_feature_matrix(arr, tasks, projects, project_teams, mem, set(), {})


def test_gbm_fits_and_predicts_non_negative_for_every_horizon() -> None:
    feat = _frame()
    model = GradientBoostingArrival().fit(feat)
    rows = feat.dropna(subset=["target_h3"])
    for h in (1, 2, 3):
        pred = model.predict(rows, horizon=h)
        assert pred.shape == (len(rows),)
        assert np.all(pred >= 0)


def test_gbm_learns_member_levels() -> None:
    feat = _frame()
    model = GradientBoostingArrival().fit(feat)
    last = feat.groupby("member_id", observed=True).tail(1)
    pred = model.predict(last, horizon=1)
    by_member = dict(zip(last["member_id"].astype(int), pred, strict=True))
    assert by_member[6] > by_member[1]


def test_gbm_is_registered() -> None:
    assert MODEL_FACTORIES["gbm"]().name == "gbm"


def test_gbm_copes_with_short_history_where_long_lags_are_all_missing() -> None:
    feat = _frame(weeks=10)  # lag13 is missing in every row
    model = GradientBoostingArrival().fit(feat)
    assert model.predict(feat.tail(3), horizon=1).shape == (3,)


def test_gbm_is_deterministic() -> None:
    feat = _frame()
    a = GradientBoostingArrival().fit(feat).predict(feat.tail(5), horizon=2)
    b = GradientBoostingArrival().fit(feat).predict(feat.tail(5), horizon=2)
    assert np.allclose(a, b)
