"""The WorkloadHub export becomes the whf schema the Python harness reads."""

from __future__ import annotations

import json
from pathlib import Path

import pandas as pd
import pytest

from whf.data.workloadhub import BACKLOG_LAG_DAYS, import_workloadhub
from whf.db.connection import connect
from whf.db.repo import read_df

FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "server"
    / "forecast-core"
    / "src"
    / "test"
    / "resources"
    / "fixtures"
    / "mini-export.json"
)


@pytest.fixture
def export() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def test_members_teams_and_departments_follow_the_export(export: dict) -> None:
    conn = connect(":memory:")
    counts = import_workloadhub(conn, export)
    members = read_df(conn, "SELECT * FROM members ORDER BY id")
    assert counts["members"] == 1  # only Engineer Two has a team_members row in the fixture; Lead One does not
    assert set(members["role"]) <= {"member", "team_leader"}
    assert members["counted_in_workload"].eq(1).all()
    teams = read_df(conn, "SELECT * FROM teams")
    assert len(teams) >= 1
    assert set(members["team_id"]) <= set(teams["id"])
    departments = read_df(conn, "SELECT * FROM departments")
    assert set(teams["department_id"]) <= set(departments["id"])
    assert float(read_df(conn, "SELECT weekly_hours FROM capacity_defaults")["weekly_hours"][0]) == 40.0


def test_fresh_keeps_only_arrivals_assigned_within_the_lag(export: dict) -> None:
    conn = connect(":memory:")
    import_workloadhub(conn, export, arrivals="fresh")
    fresh = read_df(
        conn, "SELECT created_at, assigned_at, status, actual_hours, estimated_hours, assignment_mode FROM tasks"
    )
    assert len(fresh) > 0
    for created, assigned in zip(fresh["created_at"], fresh["assigned_at"], strict=True):
        assert (json_date(assigned) - json_date(created)).days < BACKLOG_LAG_DAYS
    assert set(fresh["status"]) <= {"todo", "in_progress", "done"}
    assert set(fresh["assignment_mode"].dropna()) <= {"manual", "self_picked", "project"}
    done = fresh[fresh["status"] == "done"]
    assert done["actual_hours"].notna().all()
    conn_all = connect(":memory:")
    import_workloadhub(conn_all, export, arrivals="all")
    assert len(read_df(conn_all, "SELECT id FROM tasks")) >= len(fresh)


def test_projects_holidays_and_vacations_are_present(export: dict) -> None:
    conn = connect(":memory:")
    import_workloadhub(conn, export)
    projects = read_df(conn, "SELECT * FROM projects")
    assert len(projects) >= 1
    assert (projects["deadline"] >= projects["start_date"]).all()
    assert len(read_df(conn, "SELECT * FROM project_teams")) >= len(projects)
    holidays = read_df(conn, "SELECT * FROM holidays")
    assert len(holidays) == sum(
        1
        for h in export["data"]["holidays"]
        if h["status"] == "CONFIRMED" and h["active"]
        for _ in _days(h["start_date"], h["end_date"])
    )
    vacations = read_df(conn, "SELECT * FROM vacations")
    assert (vacations["start_date"] == vacations["end_date"]).all()


def json_date(value: str):
    import datetime as dt

    return dt.date.fromisoformat(str(value)[:10])


def _days(start: str, end: str):
    import datetime as dt

    d, last = json_date(start), json_date(end)
    while d <= last:
        yield d
        d += dt.timedelta(days=1)


def _built_export() -> dict:
    """A minimal export exercising the actual-hours fallback rules and a parent-parent team cycle."""
    return {
        "database": "avl_workloadhub",
        "schema": "task_service",
        "exported_at": "2026-01-10T08:00:00",
        "excluded_tables": [],
        "data": {
            "users": [
                {
                    "id": "u1",
                    "role": "MEMBER",
                    "email": "alice@example.test",
                    "active": True,
                    "full_name": "Alice A",
                    "manager_id": None,
                    "deactivated_at": None,
                }
            ],
            "teams": [
                {"id": "t1", "name": "Team One", "active": True, "manager_id": None, "parent_team_id": "t2"},
                {"id": "t2", "name": "Team Two", "active": True, "manager_id": None, "parent_team_id": "t1"},
            ],
            "team_members": [{"id": "tm1", "team_id": "t1", "user_id": "u1", "joined_at": "2026-01-01T08:00:00"}],
            "task_statuses": [
                {"id": "s_done", "name": "Done", "active": True, "category": "DONE"},
                {"id": "s_todo", "name": "To Do", "active": True, "category": "TO_DO"},
            ],
            "task_types": [{"id": "ty1", "name": "Task", "active": True}],
            "projects": [],
            "tasks": [
                {
                    "id": "taskA",
                    "title": "Logged task",
                    "archived": False,
                    "assignee_id": "u1",
                    "reporter_id": "u1",
                    "created_date": "2026-01-01T09:00:00",
                    "task_status_id": "s_done",
                    "finished_date": "2026-01-02",
                    "original_estimate_hrs": 10,
                    "remaining_estimate_hrs": 2,
                },
                {
                    "id": "taskB",
                    "title": "Unlogged but finished task",
                    "archived": False,
                    "assignee_id": "u1",
                    "reporter_id": "u1",
                    "created_date": "2026-01-01T09:00:00",
                    "task_status_id": "s_done",
                    "finished_date": "2026-01-03",
                    "original_estimate_hrs": 8,
                    "remaining_estimate_hrs": 3,
                },
                {
                    "id": "taskC",
                    "title": "Unresolved completion",
                    "archived": False,
                    "assignee_id": "u1",
                    "reporter_id": "u1",
                    "created_date": "2026-01-01T09:00:00",
                    "task_status_id": "s_done",
                    "finished_date": None,
                    "original_estimate_hrs": 4,
                    "remaining_estimate_hrs": 1,
                },
                {
                    "id": "taskD",
                    "title": "Zero-hour logged task",
                    "archived": False,
                    "assignee_id": "u1",
                    "reporter_id": "u1",
                    "created_date": "2026-01-01T09:00:00",
                    "task_status_id": "s_done",
                    "finished_date": None,
                    "original_estimate_hrs": 5,
                    "remaining_estimate_hrs": 2,
                },
            ],
            "task_history": [],
            "time_logs": [
                {"id": "log1", "task_id": "taskA", "user_id": "u1", "hours": 6.5},
                {"id": "log2", "task_id": "taskD", "user_id": "u1", "hours": 0},
            ],
            "absences": [
                {"id": "abs1", "user_id": "u1", "date": "2026-01-05", "hours": 8},
                {"id": "abs2", "user_id": "u1", "date": "2026-01-06", "hours": 4},
            ],
            "holidays": [],
        },
    }


def test_actual_hours_rules_and_department_fallback_for_a_parent_cycle() -> None:
    conn = connect(":memory:")
    import_workloadhub(conn, _built_export())
    tasks = read_df(conn, "SELECT id, title, completed_at, actual_hours FROM tasks ORDER BY id")
    by_title = {row.title: row for row in tasks.itertuples()}

    logged = by_title["Logged task"]
    assert logged.completed_at is not None
    assert float(logged.actual_hours) == 6.5  # sum of the assignee's logs, not the estimate/remaining fallback

    unlogged_finished = by_title["Unlogged but finished task"]
    assert unlogged_finished.completed_at is not None
    assert float(unlogged_finished.actual_hours) == 5.0  # 8 - 3, only because completed resolved and no logs

    unresolved = by_title["Unresolved completion"]
    assert pd.isna(unresolved.completed_at)
    assert pd.isna(unresolved.actual_hours)  # no resolved completion and no logs: stays null, no fallback applied

    zero_logged = by_title["Zero-hour logged task"]
    assert float(zero_logged.actual_hours) == 0.0  # a logged total of exactly 0 is kept, not replaced by a fallback

    vacations = read_df(conn, "SELECT start_date, end_date FROM vacations")
    assert len(vacations) == 1  # only the 8h absence becomes a vacation row; the 4h one does not
    assert vacations["start_date"][0] == vacations["end_date"][0] == "2026-01-05"

    members = read_df(conn, "SELECT department_id FROM members")
    teams = read_df(conn, "SELECT department_id FROM teams")
    departments = read_df(conn, "SELECT id FROM departments")
    assert len(departments) == 1  # both teams are each other's parent, so neither is a root: synthetic fallback
    dept_id = int(departments["id"][0])
    assert set(members["department_id"]) == {dept_id}
    assert set(teams["department_id"]) == {dept_id}


def test_cli_imports_and_refuses_to_overwrite(tmp_path: Path) -> None:
    from typer.testing import CliRunner

    from whf.cli import app

    db = tmp_path / "w.db"
    result = CliRunner().invoke(app, ["import-workloadhub", str(FIXTURE), "--db", str(db)])
    assert result.exit_code == 0, result.output
    assert "Imported 1 members" in result.output
    again = CliRunner().invoke(app, ["import-workloadhub", str(FIXTURE), "--db", str(db)])
    assert again.exit_code == 2
    replaced = CliRunner().invoke(
        app, ["import-workloadhub", str(FIXTURE), "--db", str(db), "--replace", "--arrivals", "all"]
    )
    assert replaced.exit_code == 0, replaced.output
