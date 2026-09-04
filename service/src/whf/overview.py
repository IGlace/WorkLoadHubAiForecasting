"""Read-only summaries for the desktop app: is a forecast due, and one department at a glance."""

from __future__ import annotations

import datetime as dt
import sqlite3

from whf.db.repo import read_df

DUE_AFTER_DAYS = 14


def run_is_due(last_finished_at: str | None, now: dt.datetime, max_age_days: int = DUE_AFTER_DAYS) -> bool:
    """True when there is no successful run or the last one finished more than max_age_days ago."""
    if not last_finished_at:
        return True
    finished = dt.datetime.fromisoformat(str(last_finished_at))
    return now - finished > dt.timedelta(days=max_age_days)


def latest_ok_run_id(conn: sqlite3.Connection, team_id: int) -> int | None:
    """Return the run_id of the latest successful run; "ok" means status = 'done' in the runs table."""
    row = conn.execute(
        "SELECT id FROM runs WHERE team_id = ? AND status = 'done' ORDER BY id DESC LIMIT 1", (team_id,)
    ).fetchone()
    return None if row is None else int(row[0])


def team_due(conn: sqlite3.Connection, team_id: int, now: dt.datetime) -> dict:
    """Return due status and last successful run metadata; "ok" means status = 'done' in the runs table."""
    row = conn.execute(
        "SELECT id, finished_at FROM runs WHERE team_id = ? AND status = 'done' ORDER BY id DESC LIMIT 1", (team_id,)
    ).fetchone()
    last_id, finished = (None, None) if row is None else (int(row[0]), row[1])
    return {"team_id": team_id, "due": run_is_due(finished, now), "last_run_id": last_id, "last_finished_at": finished}


def _team_block(conn: sqlite3.Connection, team_id: int, team_name: str, now: dt.datetime) -> dict:
    due = team_due(conn, team_id, now)
    block = {
        "team_id": team_id,
        "team_name": team_name,
        "run_id": due["last_run_id"],
        "as_of": None,
        "finished_at": due["last_finished_at"],
        "due": due["due"],
        "weeks": [],
        "overloaded": [],
    }
    if due["last_run_id"] is None:
        return block
    run = conn.execute("SELECT as_of FROM runs WHERE id = ?", (due["last_run_id"],)).fetchone()
    block["as_of"] = run[0]
    fc = read_df(conn, "SELECT * FROM forecasts WHERE run_id = ?", (due["last_run_id"],))
    by_week = fc.groupby("week_start")[["demand_hours", "capacity_hours", "overload_hours"]].sum().sort_index()
    block["weeks"] = [
        {
            "week": str(w)[:10],
            "demand": round(float(r.demand_hours), 1),
            "capacity": round(float(r.capacity_hours), 1),
            "overload": round(float(r.overload_hours), 1),
        }
        for w, r in by_week.iterrows()
    ]
    names = read_df(conn, "SELECT id, name FROM members WHERE team_id = ?", (team_id,))
    name_of = dict(zip(names["id"], names["name"], strict=True))
    totals = fc.groupby("member_id")["overload_hours"].sum()
    block["overloaded"] = [
        {"member_id": int(m), "name": name_of.get(int(m), str(m)), "overload_hours": round(float(h), 1)}
        for m, h in totals.items()
        if h > 0
    ]
    return block


def department_overview(conn: sqlite3.Connection, department_id: int, now: dt.datetime) -> dict:
    teams = read_df(conn, "SELECT id, name FROM teams WHERE department_id = ? ORDER BY id", (department_id,))
    return {
        "department_id": department_id,
        "teams": [_team_block(conn, int(t.id), str(t.name), now) for t in teams.itertuples()],
    }
