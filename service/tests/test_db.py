import datetime as dt
import sqlite3

import pytest

from whf.db.connection import connect
from whf.db.repo import insert_rows, read_df, table_names, with_dates

EXPECTED_TABLES = {
    "departments",
    "teams",
    "members",
    "projects",
    "project_teams",
    "tasks",
    "capacity_defaults",
    "capacity_overrides",
    "holidays",
    "vacations",
    "profiles",
    "runs",
    "forecasts",
    "run_narratives",
    "run_facts",
}


def test_connect_creates_all_tables() -> None:
    conn = connect(":memory:")
    assert EXPECTED_TABLES <= set(table_names(conn))


def test_capacity_default_row_exists() -> None:
    conn = connect(":memory:")
    df = read_df(conn, "SELECT weekly_hours FROM capacity_defaults")
    assert df["weekly_hours"].tolist() == [40.0]


def test_insert_rows_converts_dates_and_bools() -> None:
    conn = connect(":memory:")
    insert_rows(conn, "departments", [{"id": 1, "name": "D", "skill_team_leader_id": None}])
    insert_rows(conn, "teams", [{"id": 1, "department_id": 1, "name": "T", "team_leader_id": None}])
    n = insert_rows(
        conn,
        "members",
        [
            {
                "id": 1,
                "name": "A",
                "team_id": 1,
                "department_id": 1,
                "role": "member",
                "counted_in_workload": True,
                "active_from": dt.date(2026, 1, 5),
                "active_to": None,
            }
        ],
    )
    assert n == 1
    df = with_dates(read_df(conn, "SELECT * FROM members"), ["active_from", "active_to"])
    assert df.loc[0, "counted_in_workload"] == 1
    assert df.loc[0, "active_from"] == dt.date(2026, 1, 5)
    assert df.loc[0, "active_to"] is None


def test_foreign_keys_are_enforced() -> None:
    conn = connect(":memory:")
    with pytest.raises(sqlite3.IntegrityError):
        insert_rows(conn, "teams", [{"id": 1, "department_id": 99, "name": "T", "team_leader_id": None}])


def test_connect_is_idempotent_on_disk(tmp_path) -> None:
    path = tmp_path / "x.db"
    connect(path).close()
    conn = connect(path)
    assert EXPECTED_TABLES <= set(table_names(conn))
