import datetime as dt

import pandas as pd

from whf.patterns import cluster_members, member_patterns, pattern_table

AS_OF = dt.date(2026, 9, 3)


def _tasks(
    member_id: int = 1, weekday: int = 0, mode: str = "manual", late_days: int = 0, ratio: float = 1.2
) -> pd.DataFrame:
    rows = []
    for i in range(22):
        assigned = dt.date(2026, 4, 6) + dt.timedelta(days=7 * i + weekday)
        due = assigned + dt.timedelta(days=4)
        rows.append(
            {
                "id": i + 1,
                "assignee_id": member_id,
                "team_id": 1,
                "type": "bug" if i % 2 else "feature",
                "assigned_at": assigned,
                "due_date": due,
                "completed_at": due + dt.timedelta(days=late_days) if i < 20 else None,
                "estimated_hours": 5.0,
                "actual_hours": 5.0 * ratio if i < 20 else None,
                "assignment_mode": mode,
                "project_id": 7 if i % 3 else None,
                "status": "done" if i < 20 else "todo",
                "priority": "medium",
            }
        )
    return pd.DataFrame(rows)


def test_member_patterns_capture_style_weekday_bias_and_lateness() -> None:
    p = member_patterns(_tasks(weekday=1, late_days=2), 1, AS_OF, [(dt.date(2026, 5, 1), dt.date(2026, 8, 31))])
    assert p["member_id"] == 1
    assert p["share_manual"] == 1.0 and p["share_project"] == 0.0
    assert p["top_weekday"] == "Tuesday"
    assert abs(p["estimate_ratio_median"] - 1.2) < 1e-9
    assert p["lateness_days_median"] == 2 and p["share_late"] == 1.0
    assert p["cycle_days_median"] == 7  # (due - assigned) + late + 1
    assert p["open_tasks"] == 2 and p["open_est_hours"] == 10.0
    assert p["tasks_13w"] == 13 and abs(p["hours_per_week_13w"] - 5.0) < 1e-9
    assert set(p["cycle_days_by_type"]) == {"bug", "feature"}
    assert 0.0 <= p["share_with_project"] <= 1.0


def test_member_patterns_handle_member_without_tasks() -> None:
    empty = _tasks().iloc[0:0]
    p = member_patterns(empty, 42, AS_OF, [])
    assert p["tasks_13w"] == 0 and p["hours_per_week_13w"] == 0.0
    assert p["top_weekday"] is None
    assert p["estimate_ratio_median"] is None


def test_pattern_table_and_clustering() -> None:
    frames = []
    for m in range(1, 9):
        frames.append(
            _tasks(member_id=m, weekday=m % 5, mode=["manual", "self_picked", "project"][m % 3], ratio=0.8 + 0.1 * m)
        )
    tasks = pd.concat(frames, ignore_index=True)
    members = pd.DataFrame([{"id": m, "team_id": 1} for m in range(1, 9)])
    table = pattern_table(tasks, members, {1: []}, AS_OF)
    assert len(table) == 8 and "share_manual" in table.columns
    labels = cluster_members(table)
    assert set(labels.index) == set(range(1, 9))
    assert labels.nunique() >= 2


def test_clustering_with_few_members_returns_zeros() -> None:
    table = pd.DataFrame(
        [
            {"member_id": 1, "share_manual": 1.0, "hours_per_week_13w": 3.0},
            {"member_id": 2, "share_manual": 0.0, "hours_per_week_13w": 9.0},
        ]
    )
    labels = cluster_members(table)
    assert labels.tolist() == [0, 0]
