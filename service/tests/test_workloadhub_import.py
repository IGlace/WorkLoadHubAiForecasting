"""The WorkloadHub export becomes the whf schema the Python harness reads."""

from __future__ import annotations

import json
from pathlib import Path

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
