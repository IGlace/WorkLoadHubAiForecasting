"""Configuration writes shared by the CLI and the API."""

from __future__ import annotations

import datetime as dt
import sqlite3


def set_capacity_default(conn: sqlite3.Connection, weekly_hours: float) -> None:
    """Set the default weekly capacity for everyone."""
    conn.execute("UPDATE capacity_defaults SET weekly_hours = ? WHERE id = 1", (weekly_hours,))
    conn.commit()


def set_capacity_override(
    conn: sqlite3.Connection,
    member_id: int,
    weekly_hours: float,
    week_start: dt.date | None = None,
    reason: str | None = None,
) -> None:
    """Set or update a member's capacity override.

    For permanent overrides, omit week_start. Replaces any existing row for that member/week combination.
    """
    week_iso = week_start.isoformat() if week_start else None
    conn.execute("DELETE FROM capacity_overrides WHERE member_id = ? AND week_start IS ?", (member_id, week_iso))
    conn.execute(
        "INSERT INTO capacity_overrides (member_id, week_start, weekly_hours, reason) VALUES (?, ?, ?, ?)",
        (member_id, week_iso, weekly_hours, reason),
    )
    conn.commit()


def add_vacation(
    conn: sqlite3.Connection,
    member_id: int,
    start_date: dt.date,
    end_date: dt.date,
    kind: str = "vacation",
) -> int:
    """Add a vacation record and return its id."""
    cur = conn.execute(
        "INSERT INTO vacations (member_id, start_date, end_date, type) VALUES (?, ?, ?, ?)",
        (member_id, start_date.isoformat(), end_date.isoformat(), kind),
    )
    conn.commit()
    return int(cur.lastrowid)


def add_project(
    conn: sqlite3.Connection,
    name: str,
    department_id: int,
    start_date: dt.date,
    deadline: dt.date,
    team_ids: list[int],
    kind: str = "delivery",
    created_by: int | None = None,
) -> int:
    """Create a project and link its teams. Returns the project id.

    Raises ValueError if deadline is not after start_date.
    """
    if deadline <= start_date:
        raise ValueError("deadline must be after start_date")
    cur = conn.execute(
        "INSERT INTO projects (name, department_id, start_date, deadline, type, status, created_by) VALUES (?, ?, ?, ?, ?, 'planned', ?)",
        (name, department_id, start_date.isoformat(), deadline.isoformat(), kind, created_by),
    )
    project_id = int(cur.lastrowid)
    conn.executemany(
        "INSERT INTO project_teams (project_id, team_id) VALUES (?, ?)", [(project_id, t) for t in team_ids]
    )
    conn.commit()
    return project_id
