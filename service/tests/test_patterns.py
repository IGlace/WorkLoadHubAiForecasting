import datetime as dt

import pandas as pd

from whf.calendar import ONE_WEEK, week_start
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


def test_hours_by_project_shares_sum_to_one_and_reflect_window() -> None:
    window_start = week_start(AS_OF) - 13 * ONE_WEEK

    rows = []
    # Inside window: project 7 with 30 hours (6 tasks of 5 hours each)
    for i in range(6):
        assigned = window_start + dt.timedelta(days=7 * i)
        rows.append(
            {
                "id": i + 1,
                "assignee_id": 1,
                "team_id": 1,
                "type": "feature",
                "assigned_at": assigned,
                "due_date": assigned + dt.timedelta(days=4),
                "completed_at": assigned + dt.timedelta(days=5),
                "estimated_hours": 5.0,
                "actual_hours": 5.0,
                "assignment_mode": "manual",
                "project_id": 7,
                "status": "done",
                "priority": "medium",
            }
        )
    # Inside window: project 8 with 10 hours (2 tasks of 5 hours each)
    for i in range(2):
        assigned = window_start + dt.timedelta(days=7 * (6 + i))
        rows.append(
            {
                "id": 7 + i,
                "assignee_id": 1,
                "team_id": 1,
                "type": "feature",
                "assigned_at": assigned,
                "due_date": assigned + dt.timedelta(days=4),
                "completed_at": assigned + dt.timedelta(days=5),
                "estimated_hours": 5.0,
                "actual_hours": 5.0,
                "assignment_mode": "manual",
                "project_id": 8,
                "status": "done",
                "priority": "medium",
            }
        )
    # Outside window (before): project 9 with 100 hours (20 tasks of 5 hours each)
    for i in range(20):
        assigned = window_start - dt.timedelta(days=7 * (20 - i))
        rows.append(
            {
                "id": 9 + i,
                "assignee_id": 1,
                "team_id": 1,
                "type": "feature",
                "assigned_at": assigned,
                "due_date": assigned + dt.timedelta(days=4),
                "completed_at": assigned + dt.timedelta(days=5),
                "estimated_hours": 5.0,
                "actual_hours": 5.0,
                "assignment_mode": "manual",
                "project_id": 9,
                "status": "done",
                "priority": "medium",
            }
        )
    tasks = pd.DataFrame(rows)
    p = member_patterns(tasks, 1, AS_OF, [])
    assert p["hours_by_project"] == {"7": 0.75, "8": 0.25}
    assert "9" not in p["hours_by_project"]


def test_deadline_proximity_corr_is_positive_when_work_rises_near_deadline() -> None:
    window_start = week_start(AS_OF) - 13 * ONE_WEEK
    deadline = window_start + dt.timedelta(days=70)  # Deadline inside window

    rows = []
    # Early weeks (far from deadline): 2 hours per week
    for i in range(6):
        assigned = window_start + dt.timedelta(days=7 * i)
        rows.append(
            {
                "id": i + 1,
                "assignee_id": 1,
                "team_id": 1,
                "type": "feature",
                "assigned_at": assigned,
                "due_date": assigned + dt.timedelta(days=4),
                "completed_at": assigned + dt.timedelta(days=5),
                "estimated_hours": 2.0,
                "actual_hours": 2.0,
                "assignment_mode": "manual",
                "project_id": 7,
                "status": "done",
                "priority": "medium",
            }
        )
    # Late weeks (near deadline): 12 hours per week (3 tasks of 4 hours each per week)
    for week_offset in range(3):
        for j in range(3):
            assigned = window_start + dt.timedelta(days=7 * (6 + week_offset) + j)
            rows.append(
                {
                    "id": 7 + week_offset * 3 + j,
                    "assignee_id": 1,
                    "team_id": 1,
                    "type": "feature",
                    "assigned_at": assigned,
                    "due_date": assigned + dt.timedelta(days=4),
                    "completed_at": assigned + dt.timedelta(days=5),
                    "estimated_hours": 4.0,
                    "actual_hours": 4.0,
                    "assignment_mode": "manual",
                    "project_id": 7,
                    "status": "done",
                    "priority": "medium",
                }
            )
    tasks = pd.DataFrame(rows)
    p = member_patterns(tasks, 1, AS_OF, [(window_start, deadline)])
    assert p["deadline_proximity_corr"] is not None
    assert p["deadline_proximity_corr"] > 0, f"Expected positive correlation, got {p['deadline_proximity_corr']}"


def test_deadline_proximity_corr_is_none_when_no_project_spans() -> None:
    tasks = _tasks()
    p = member_patterns(tasks, 1, AS_OF, [])
    assert p["deadline_proximity_corr"] is None


def test_ratio_cycle_and_lateness_use_full_history_not_window() -> None:
    window_start = week_start(AS_OF) - 13 * ONE_WEEK

    rows = []
    # Outside window (before): high ratio 2.0, high lateness 5 days, 15 tasks
    for i in range(15):
        assigned = window_start - dt.timedelta(days=7 * (15 - i))
        rows.append(
            {
                "id": i + 1,
                "assignee_id": 1,
                "team_id": 1,
                "type": "feature" if i % 2 else "bug",
                "assigned_at": assigned,
                "due_date": assigned + dt.timedelta(days=4),
                "completed_at": assigned + dt.timedelta(days=4 + 5),
                "estimated_hours": 5.0,
                "actual_hours": 10.0,
                "assignment_mode": "manual",
                "project_id": 7,
                "status": "done",
                "priority": "medium",
            }
        )
    # Inside window: low ratio 1.0, no lateness, 5 tasks
    for i in range(5):
        assigned = window_start + dt.timedelta(days=7 * i)
        rows.append(
            {
                "id": 16 + i,
                "assignee_id": 1,
                "team_id": 1,
                "type": "feature" if i % 2 else "bug",
                "assigned_at": assigned,
                "due_date": assigned + dt.timedelta(days=4),
                "completed_at": assigned + dt.timedelta(days=4),
                "estimated_hours": 5.0,
                "actual_hours": 5.0,
                "assignment_mode": "manual",
                "project_id": 7,
                "status": "done",
                "priority": "medium",
            }
        )
    tasks = pd.DataFrame(rows)
    p = member_patterns(tasks, 1, AS_OF, [])
    assert p["estimate_ratio_median"] == 2.0, f"Expected ratio 2.0 from full history, got {p['estimate_ratio_median']}"
    assert p["lateness_days_median"] == 5, f"Expected lateness 5 from full history, got {p['lateness_days_median']}"
    assert p["tasks_13w"] == 5, f"Expected 5 tasks in window, got {p['tasks_13w']}"
