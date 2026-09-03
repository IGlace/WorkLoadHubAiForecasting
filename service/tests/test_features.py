import datetime as dt

import pandas as pd

from whf.features import HORIZONS, build_feature_matrix, feature_columns, weekly_arrivals

W0 = dt.date(2026, 1, 5)  # a Monday


def _weeks(n: int) -> list[dt.date]:
    return [W0 + dt.timedelta(days=7 * i) for i in range(n)]


def _tasks() -> pd.DataFrame:
    rows = []
    tid = 0
    for i, w in enumerate(_weeks(20)):
        # member 1 gets i tasks of 2h in week i; member 2 gets one 5h task every other week
        for _ in range(i):
            tid += 1
            rows.append(
                {
                    "id": tid,
                    "assignee_id": 1,
                    "assigned_at": w + dt.timedelta(days=1),
                    "estimated_hours": 2.0,
                    "assignment_mode": "manual",
                }
            )
        if i % 2 == 0:
            tid += 1
            rows.append(
                {
                    "id": tid,
                    "assignee_id": 2,
                    "assigned_at": w,
                    "estimated_hours": 5.0,
                    "assignment_mode": "self_picked",
                }
            )
    return pd.DataFrame(rows)


def _members() -> pd.DataFrame:
    return pd.DataFrame([{"id": 1, "team_id": 1}, {"id": 2, "team_id": 1}, {"id": 3, "team_id": 1}])


def _projects() -> tuple[pd.DataFrame, pd.DataFrame]:
    projects = pd.DataFrame(
        [
            {"id": 10, "start_date": W0 + dt.timedelta(days=14), "deadline": W0 + dt.timedelta(days=56)},
        ]
    )
    return projects, pd.DataFrame([{"project_id": 10, "team_id": 1}])


def test_weekly_arrivals_fills_every_member_week() -> None:
    arr = weekly_arrivals(_tasks(), [1, 2, 3], _weeks(20))
    assert len(arr) == 60
    m1 = arr[arr.member_id == 1].sort_values("week_start")
    assert m1.est_hours.tolist()[:4] == [0.0, 2.0, 4.0, 6.0]
    assert m1.n_tasks.tolist()[:4] == [0, 1, 2, 3]
    assert (arr[arr.member_id == 3].est_hours == 0).all()


def test_lags_and_targets_are_aligned() -> None:
    arr = weekly_arrivals(_tasks(), [1, 2, 3], _weeks(20))
    projects, project_teams = _projects()
    feat = build_feature_matrix(arr, _tasks(), projects, project_teams, _members(), set(), {})
    m1 = feat[feat.member_id == 1].set_index("week_start")
    w5 = W0 + dt.timedelta(days=35)
    assert m1.loc[w5, "lag1"] == 10.0  # own week (i=5 -> 5 tasks * 2h)
    assert m1.loc[w5, "lag2"] == 8.0
    assert m1.loc[w5, "target_h1"] == 12.0
    assert m1.loc[w5, "target_h2"] == 14.0
    last = W0 + dt.timedelta(days=7 * 19)
    assert pd.isna(m1.loc[last, "target_h1"])


def test_project_features_reflect_target_week() -> None:
    arr = weekly_arrivals(_tasks(), [1, 2, 3], _weeks(20))
    projects, project_teams = _projects()
    feat = build_feature_matrix(arr, _tasks(), projects, project_teams, _members(), set(), {})
    m1 = feat[feat.member_id == 1].set_index("week_start")
    # project starts in week index 2 (W0+14). From origin week 1, horizon 1 targets week 2 -> project starting.
    w1 = W0 + dt.timedelta(days=7)
    assert m1.loc[w1, "proj_starting_h1"] == 1
    assert m1.loc[w1, "proj_active_h1"] == 1
    assert m1.loc[W0, "proj_active_h1"] == 0
    # deadline W0+56 = week index 8; from origin week 7, horizon 1 targets week 8 -> project ending
    w7 = W0 + dt.timedelta(days=49)
    assert m1.loc[w7, "proj_ending_h1"] == 1
    assert m1.loc[w7, "proj_min_weeks_to_deadline_h1"] == 0.0


def test_style_shares_and_availability_features() -> None:
    arr = weekly_arrivals(_tasks(), [1, 2, 3], _weeks(20))
    projects, project_teams = _projects()
    w3 = W0 + dt.timedelta(days=21)
    holidays = {w3 + dt.timedelta(days=7)}  # Monday of week 4 is a holiday
    vacations = {2: {w3 + dt.timedelta(days=8), w3 + dt.timedelta(days=9)}}
    feat = build_feature_matrix(arr, _tasks(), projects, project_teams, _members(), holidays, vacations)
    m1 = feat[feat.member_id == 1].set_index("week_start")
    m2 = feat[feat.member_id == 2].set_index("week_start")
    assert m1.loc[w3, "share_manual"] == 1.0
    assert m2.loc[w3, "share_self_picked"] == 1.0
    assert m1.loc[w3, "working_days_h1"] == 4
    assert m2.loc[w3, "working_days_h1"] == 2
    assert m2.loc[w3, "vacation_days_h1"] == 2


def test_feature_columns_exist_for_each_horizon() -> None:
    arr = weekly_arrivals(_tasks(), [1, 2, 3], _weeks(20))
    projects, project_teams = _projects()
    feat = build_feature_matrix(arr, _tasks(), projects, project_teams, _members(), set(), {})
    for h in HORIZONS:
        missing = set(feature_columns(h)) - set(feat.columns)
        assert not missing, missing
    assert str(feat["member_id"].dtype) == "category"
