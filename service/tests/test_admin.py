import datetime as dt
import sqlite3

import pytest

from whf.admin import add_project, add_vacation, set_capacity_default, set_capacity_override
from whf.db.repo import read_df


def test_permanent_override_is_replaced_not_duplicated(db) -> None:
    """Permanent overrides (week_start=None) should replace, not duplicate."""
    set_capacity_override(db, member_id=1, weekly_hours=40)
    set_capacity_override(db, member_id=1, weekly_hours=36)
    df = read_df(db, "SELECT * FROM capacity_overrides WHERE member_id = 1 AND week_start IS NULL")
    assert len(df) == 1
    assert float(df["weekly_hours"][0]) == 36.0


def test_weekly_override_is_replaced(db) -> None:
    """Weekly overrides for a specific week should replace, not duplicate."""
    week = dt.date(2026, 9, 14)
    set_capacity_override(db, member_id=2, weekly_hours=40, week_start=week)
    set_capacity_override(db, member_id=2, weekly_hours=32, week_start=week, reason="sick")
    df = read_df(db, "SELECT * FROM capacity_overrides WHERE member_id = 2 AND week_start = ?", (week.isoformat(),))
    assert len(df) == 1
    assert float(df["weekly_hours"][0]) == 32.0
    assert df["reason"][0] == "sick"


def test_add_project_links_teams_and_rejects_bad_deadline(db) -> None:
    """Projects should link teams and reject deadlines not after start."""
    with pytest.raises(ValueError, match="deadline must be after start_date"):
        add_project(
            db,
            "BadProject",
            1,
            dt.date(2026, 10, 1),
            dt.date(2026, 10, 1),
            [1, 2],
        )
    project_id = add_project(
        db,
        "GoodProject",
        1,
        dt.date(2026, 10, 1),
        dt.date(2026, 11, 1),
        [1, 2],
    )
    df = read_df(db, "SELECT * FROM project_teams WHERE project_id = ?", (project_id,))
    assert len(df) == 2
    assert list(df["team_id"]) == [1, 2]


def test_add_vacation_returns_id(db) -> None:
    """add_vacation should return the inserted row id."""
    vacation_id = add_vacation(db, member_id=1, start_date=dt.date(2026, 9, 21), end_date=dt.date(2026, 9, 23))
    assert vacation_id > 0
    df = read_df(db, "SELECT * FROM vacations WHERE id = ?", (vacation_id,))
    assert len(df) == 1
    assert int(df["member_id"][0]) == 1


def test_add_vacation_rejects_end_before_start(db) -> None:
    """add_vacation should reject an inverted date range."""
    with pytest.raises(ValueError, match="end_date must not be before start_date"):
        add_vacation(db, member_id=1, start_date=dt.date(2026, 9, 23), end_date=dt.date(2026, 9, 21))
    assert len(read_df(db, "SELECT * FROM vacations WHERE member_id = 1")) == 0


def test_failed_project_insert_leaves_no_orphan(db) -> None:
    """A project insert that fails on a bad team_id must not leave a partial row behind."""
    with pytest.raises(sqlite3.IntegrityError):
        add_project(
            db,
            "OrphanProject",
            1,
            dt.date(2026, 10, 1),
            dt.date(2026, 11, 1),
            [999],
        )
    set_capacity_default(db, 39.0)  # a subsequent, unrelated write must succeed cleanly
    assert len(read_df(db, "SELECT * FROM projects WHERE name = 'OrphanProject'")) == 0
    assert float(read_df(db, "SELECT weekly_hours FROM capacity_defaults")["weekly_hours"][0]) == 39.0
