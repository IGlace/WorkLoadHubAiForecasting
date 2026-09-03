"""Write generated data into the database and the answer key to disk."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from whf.data.generator import GeneratedData
from whf.db.repo import insert_rows


def load_generated(conn: sqlite3.Connection, data: GeneratedData) -> None:
    for table in [
        "forecasts",
        "run_facts",
        "run_narratives",
        "runs",
        "tasks",
        "project_teams",
        "projects",
        "vacations",
        "capacity_overrides",
        "holidays",
        "members",
        "teams",
        "departments",
    ]:
        conn.execute(f"DELETE FROM {table}")
    conn.commit()
    # departments reference leaders and teams reference departments: insert leaders after departments
    insert_rows(conn, "departments", [{**d, "skill_team_leader_id": None} for d in data.departments])
    insert_rows(conn, "teams", [{**t, "team_leader_id": None} for t in data.teams])
    insert_rows(conn, "members", data.members)
    for d in data.departments:
        conn.execute(
            "UPDATE departments SET skill_team_leader_id = ? WHERE id = ?", (d["skill_team_leader_id"], d["id"])
        )
    for t in data.teams:
        conn.execute("UPDATE teams SET team_leader_id = ? WHERE id = ?", (t["team_leader_id"], t["id"]))
    insert_rows(conn, "projects", data.projects)
    insert_rows(conn, "project_teams", data.project_teams)
    insert_rows(conn, "tasks", data.tasks)
    insert_rows(conn, "vacations", data.vacations)
    insert_rows(conn, "holidays", data.holidays)
    conn.commit()


def write_answer_key(path: Path, data: GeneratedData) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data.answer_key, indent=1), encoding="utf-8")
