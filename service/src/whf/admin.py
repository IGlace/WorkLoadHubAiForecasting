"""Configuration writes shared by the CLI and the API."""

from __future__ import annotations

import datetime as dt
import sqlite3


def set_capacity_default(conn: sqlite3.Connection, weekly_hours: float) -> None:
    """Set the default weekly capacity for everyone."""
    try:
        conn.execute("UPDATE capacity_defaults SET weekly_hours = ? WHERE id = 1", (weekly_hours,))
        conn.commit()
    except Exception:
        conn.rollback()
        raise


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
    try:
        conn.execute("DELETE FROM capacity_overrides WHERE member_id = ? AND week_start IS ?", (member_id, week_iso))
        conn.execute(
            "INSERT INTO capacity_overrides (member_id, week_start, weekly_hours, reason) VALUES (?, ?, ?, ?)",
            (member_id, week_iso, weekly_hours, reason),
        )
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def add_vacation(
    conn: sqlite3.Connection,
    member_id: int,
    start_date: dt.date,
    end_date: dt.date,
    kind: str = "vacation",
) -> int:
    """Add a vacation record and return its id.

    Raises ValueError if end_date is before start_date.
    """
    if end_date < start_date:
        raise ValueError("end_date must not be before start_date")
    try:
        cur = conn.execute(
            "INSERT INTO vacations (member_id, start_date, end_date, type) VALUES (?, ?, ?, ?)",
            (member_id, start_date.isoformat(), end_date.isoformat(), kind),
        )
        conn.commit()
    except Exception:
        conn.rollback()
        raise
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
    try:
        cur = conn.execute(
            "INSERT INTO projects (name, department_id, start_date, deadline, type, status, created_by) VALUES (?, ?, ?, ?, ?, 'planned', ?)",
            (name, department_id, start_date.isoformat(), deadline.isoformat(), kind, created_by),
        )
        project_id = int(cur.lastrowid)
        conn.executemany(
            "INSERT INTO project_teams (project_id, team_id) VALUES (?, ?)", [(project_id, t) for t in team_ids]
        )
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    return project_id


def set_profile(conn: sqlite3.Connection, member_id: int | None) -> dict:
    """Store which member uses this installation (profiles row 1). None clears the profile."""
    role: str | None = None
    if member_id is not None:
        row = conn.execute("SELECT role FROM members WHERE id = ?", (member_id,)).fetchone()
        if row is None:
            raise ValueError(f"member {member_id} not found")
        role = str(row[0])
    conn.execute(
        "INSERT INTO profiles (id, member_id, role) VALUES (1, ?, ?) "
        "ON CONFLICT(id) DO UPDATE SET member_id = excluded.member_id, role = excluded.role",
        (member_id, role),
    )
    conn.commit()
    return {"member_id": member_id, "role": role}


PROJECT_STATUSES = ("planned", "active", "done")


def update_project(
    conn: sqlite3.Connection,
    project_id: int,
    *,
    name: str,
    start_date: dt.date,
    deadline: dt.date,
    team_ids: list[int],
    kind: str,
    status: str,
) -> None:
    """Replace every editable field of a project and its team links. Rolls back on any error."""
    if deadline <= start_date:
        raise ValueError("deadline must be after start_date")
    if not team_ids:
        raise ValueError("a project needs at least one team")
    if status not in PROJECT_STATUSES:
        raise ValueError(f"status must be one of {PROJECT_STATUSES}")
    if conn.execute("SELECT 1 FROM projects WHERE id = ?", (project_id,)).fetchone() is None:
        raise KeyError(f"project {project_id} not found")
    try:
        conn.execute(
            "UPDATE projects SET name = ?, start_date = ?, deadline = ?, type = ?, status = ? WHERE id = ?",
            (name, start_date.isoformat(), deadline.isoformat(), kind, status, project_id),
        )
        conn.execute("DELETE FROM project_teams WHERE project_id = ?", (project_id,))
        conn.executemany(
            "INSERT INTO project_teams (project_id, team_id) VALUES (?, ?)",
            [(project_id, int(t)) for t in sorted(set(team_ids))],
        )
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def delete_capacity_override(conn: sqlite3.Connection, override_id: int) -> bool:
    cur = conn.execute("DELETE FROM capacity_overrides WHERE id = ?", (override_id,))
    conn.commit()
    return cur.rowcount > 0


def delete_vacation(conn: sqlite3.Connection, vacation_id: int) -> bool:
    cur = conn.execute("DELETE FROM vacations WHERE id = ?", (vacation_id,))
    conn.commit()
    return cur.rowcount > 0
