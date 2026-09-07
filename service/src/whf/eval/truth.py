"""What the forecast is measured against, and how to look at the database as it was on a past date."""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import sqlite3
from pathlib import Path

import pandas as pd

from whf.db.connection import connect
from whf.db.repo import insert_rows, read_df, table_names, with_dates
from whf.models.effort import place_hours

TRUTH_COLUMNS = ["member_id", "week_start", "hours"]
COPIED_TABLES = (
    "departments",
    "teams",
    "members",
    "projects",
    "project_teams",
    "holidays",
    "vacations",
    "capacity_overrides",
    "profiles",
)


def truth_from_answer_key(path: Path) -> pd.DataFrame:
    """The generator's true hours per member and week (`effort_by_member_week`)."""
    rows = json.loads(Path(path).read_text(encoding="utf-8"))["effort_by_member_week"]
    out = pd.DataFrame(rows, columns=TRUTH_COLUMNS)
    out["member_id"] = out["member_id"].astype(int)
    out["week_start"] = [dt.date.fromisoformat(str(w)[:10]) for w in out["week_start"]]
    out["hours"] = out["hours"].astype(float)
    return out


def realised_hours(conn: sqlite3.Connection) -> pd.DataFrame:
    """Real-data truth: each completed task's actual hours spread evenly over the working days between
    assignment and completion (holidays from the holidays table), summed per member and week.

    This is the only place that definition lives; replace the body if the export carries logged hours.
    """
    tasks = with_dates(
        read_df(conn, "SELECT assignee_id, assigned_at, completed_at, actual_hours FROM tasks"),
        ["assigned_at", "completed_at"],
    )
    holidays = {d for d in with_dates(read_df(conn, "SELECT date FROM holidays"), ["date"])["date"] if d}
    done = tasks.dropna(subset=["completed_at", "actual_hours"])
    acc: dict[tuple[int, dt.date], float] = {}
    for t in done.itertuples(index=False):
        for ws, h in place_hours(float(t.actual_hours), t.assigned_at, t.completed_at, holidays).items():
            acc[(int(t.assignee_id), ws)] = acc.get((int(t.assignee_id), ws), 0.0) + h
    rows = [{"member_id": m, "week_start": w, "hours": round(h, 6)} for (m, w), h in sorted(acc.items())]
    return pd.DataFrame(rows, columns=TRUTH_COLUMNS)


def truncated_copy(conn: sqlite3.Connection, as_of: dt.date) -> sqlite3.Connection:
    """An in-memory database as it looked on `as_of`: no tasks assigned later, later completions reopened,
    no runs. Organisation, projects, holidays, vacations and capacity settings are copied unchanged."""
    copy = connect(":memory:")
    copy.execute("PRAGMA foreign_keys = OFF")
    cutoff = as_of.isoformat()
    for table in COPIED_TABLES:
        if table not in table_names(conn):
            continue
        rows = read_df(conn, f"SELECT * FROM {table}").to_dict(orient="records")
        if rows:
            insert_rows(copy, table, rows, commit=False)
    copy.execute("DELETE FROM capacity_defaults")
    insert_rows(
        copy,
        "capacity_defaults",
        read_df(conn, "SELECT * FROM capacity_defaults").to_dict(orient="records"),
        commit=False,
    )
    tasks = read_df(conn, "SELECT * FROM tasks WHERE substr(assigned_at, 1, 10) <= ?", (cutoff,))
    later = tasks["completed_at"].notna() & (tasks["completed_at"].astype(str).str[:10] > cutoff)
    tasks.loc[later, ["completed_at", "actual_hours"]] = None
    tasks.loc[later, "status"] = "in_progress"
    if len(tasks):
        insert_rows(copy, "tasks", tasks.to_dict(orient="records"), commit=False)
    copy.commit()
    copy.execute("PRAGMA foreign_keys = ON")
    return copy


def data_fingerprint(conn: sqlite3.Connection, db_file: Path | None) -> dict:
    counts = {t: int(read_df(conn, f"SELECT COUNT(*) AS n FROM {t}")["n"][0]) for t in ("members", "teams", "tasks")}
    span = read_df(conn, "SELECT MIN(assigned_at) AS first, MAX(assigned_at) AS last FROM tasks")
    digest = hashlib.sha256(Path(db_file).read_bytes()).hexdigest() if db_file and Path(db_file).exists() else None
    return {
        **counts,
        "first_assigned": None if span["first"][0] is None else str(span["first"][0])[:10],
        "last_assigned": None if span["last"][0] is None else str(span["last"][0])[:10],
        "sha256": digest,
    }
