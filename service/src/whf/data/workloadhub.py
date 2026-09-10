"""Load a WorkloadHub export (real or seeded) into the whf schema, so the Python harness can score the same data as the Java module."""

from __future__ import annotations

import datetime as dt
import sqlite3
from collections import defaultdict
from typing import Any

from whf.db.repo import insert_rows

BACKLOG_LAG_DAYS = 2
COUNTED_ROLES = {"MEMBER", "TEAM_LEADER"}
UNASSIGNED = {"", "none", "null"}
FULL_DAY_HOURS = 8.0
DEFAULT_WEEKLY_HOURS = 40.0
REPLACED_TABLES = (
    "run_narratives",
    "run_facts",
    "forecasts",
    "runs",
    "vacations",
    "capacity_overrides",
    "holidays",
    "project_teams",
    "tasks",
    "projects",
    "members",
    "teams",
    "departments",
)


def _date(value: Any) -> dt.date | None:
    return None if value in (None, "") else dt.date.fromisoformat(str(value)[:10])


def _datetime(value: Any) -> dt.datetime | None:
    if value in (None, ""):
        return None
    text = str(value).replace(" ", "T")
    return (
        dt.datetime.fromisoformat(text[:26])
        if "T" in text
        else dt.datetime.combine(dt.date.fromisoformat(text[:10]), dt.time())
    )


class Ids:
    """UUID -> small integer per table, in sorted UUID order, so two imports of the same export agree."""

    def __init__(self, data: dict[str, list[dict[str, Any]]]) -> None:
        self._maps: dict[str, dict[str, int]] = {}
        for table in ("users", "teams", "projects", "tasks"):
            ids = sorted(str(r["id"]) for r in data.get(table, []))
            self._maps[table] = {u: i + 1 for i, u in enumerate(ids)}

    def of(self, table: str, uuid: Any) -> int | None:
        return None if uuid is None else self._maps[table].get(str(uuid))


class _Resolver:
    def __init__(self, users: list[dict[str, Any]], member_ids: set[str]) -> None:
        self.user_ids = {str(u["id"]).strip().lower() for u in users}
        self.by_email: dict[str, list[str]] = defaultdict(list)
        self.by_name: dict[str, list[str]] = defaultdict(list)
        self.member_by_email: dict[str, list[str]] = defaultdict(list)
        self.member_by_name: dict[str, list[str]] = defaultdict(list)
        for u in users:
            uid = str(u["id"])
            for key, index, member_index in (
                (u.get("email"), self.by_email, self.member_by_email),
                (u.get("full_name"), self.by_name, self.member_by_name),
            ):
                if key:
                    index[str(key).strip().lower()].append(uid)
                    if uid in member_ids:
                        member_index[str(key).strip().lower()].append(uid)

    def resolve(self, value: Any) -> str | None:
        if value is None or str(value).strip().lower() in UNASSIGNED:
            return None
        key = str(value).strip().lower()
        if key in self.user_ids:
            return key
        for index in (self.member_by_email, self.member_by_name, self.by_email, self.by_name):
            hits = index.get(key)
            if hits:
                return hits[0] if len(hits) == 1 else None
        return None


def _primary_team(team_ids: list[str], parent_of: dict[str, str | None]) -> str:
    ordered = sorted(team_ids)
    with_parent = [t for t in ordered if parent_of.get(t)]
    return with_parent[0] if with_parent else ordered[0]


def import_workloadhub(conn: sqlite3.Connection, export: dict[str, Any], *, arrivals: str = "fresh") -> dict[str, int]:
    if arrivals not in ("fresh", "all"):
        raise ValueError(f"arrivals must be 'fresh' or 'all', not {arrivals!r}")
    data: dict[str, list[dict[str, Any]]] = export["data"]
    ids = Ids(data)
    teams = data.get("teams", [])
    users = data.get("users", [])
    parent_of = {str(t["id"]): (str(t["parent_team_id"]) if t.get("parent_team_id") else None) for t in teams}
    department_of_team: dict[str, int | None] = {}
    for t in teams:
        tid = str(t["id"])
        department_of_team[tid] = ids.of("teams", parent_of[tid] or tid)
    user_int = {str(u["id"]): ids.of("users", u["id"]) for u in users}
    department_rows = [
        {
            "id": ids.of("teams", t["id"]),
            "name": t["name"],
            "skill_team_leader_id": user_int.get(str(t.get("manager_id"))) if t.get("manager_id") else None,
        }
        for t in sorted(teams, key=lambda t: str(t["id"]))
        if not parent_of[str(t["id"])]
    ]
    if not department_rows:
        department_rows = [{"id": 1, "name": "Unassigned", "skill_team_leader_id": None}]
        department_of_team = {tid: 1 for tid in parent_of}

    # directory
    teams_of_user: dict[str, list[str]] = defaultdict(list)
    joined_of_user: dict[str, dt.date] = {}
    for tm in data.get("team_members", []):
        uid, tid = str(tm["user_id"]), str(tm["team_id"])
        if tid in parent_of:
            teams_of_user[uid].append(tid)
            joined = _date(tm.get("joined_at")) or dt.date.today()
            joined_of_user[uid] = min(joined_of_user.get(uid, joined), joined)
    members: dict[str, dict[str, Any]] = {}
    for u in users:
        uid = str(u["id"])
        if not u.get("active") or u.get("role") not in COUNTED_ROLES or not teams_of_user.get(uid):
            continue
        team = _primary_team(teams_of_user[uid], parent_of)
        members[uid] = {
            "id": ids.of("users", uid),
            "name": u.get("full_name") or u.get("email") or uid,
            "team_id": ids.of("teams", team),
            "department_id": department_of_team[team],
            "role": "team_leader" if u.get("role") == "TEAM_LEADER" else "member",
            "counted_in_workload": 1,
            "active_from": joined_of_user[uid],
            "active_to": _date(u.get("deactivated_at")),
        }
    team_rows = [
        {
            "id": ids.of("teams", t["id"]),
            "department_id": department_of_team[str(t["id"])],
            "name": t["name"],
            "team_leader_id": user_int.get(str(t.get("manager_id"))) if t.get("manager_id") else None,
        }
        for t in sorted(teams, key=lambda t: str(t["id"]))
    ]

    # lifecycle
    category_by_status_id = {str(s["id"]): s["category"] for s in data.get("task_statuses", [])}
    category_by_status_name = {s["name"]: s["category"] for s in data.get("task_statuses", [])}
    type_name = {str(t["id"]): t["name"] for t in data.get("task_types", [])}
    history: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for h in data.get("task_history", []):
        history[str(h["task_id"])].append(h)
    for rows in history.values():
        rows.sort(key=lambda h: _datetime(h["changed_at"]) or dt.datetime.min)
    logs: dict[tuple[str, str], float] = defaultdict(float)
    for log in data.get("time_logs", []):
        logs[(str(log["task_id"]), str(log["user_id"]))] += float(log.get("hours") or 0.0)
    resolver = _Resolver(users, set(members))

    task_rows: list[dict[str, Any]] = []
    project_first: dict[str, dt.date] = {}
    project_last_due: dict[str, dt.date] = {}
    project_teams: dict[str, set[int]] = defaultdict(set)
    created_dates = [d for t in data.get("tasks", []) if (d := _date(t.get("created_date"))) is not None]
    latest_created = max(created_dates, default=dt.date.today())
    for t in sorted(data.get("tasks", []), key=lambda t: str(t["id"])):
        tid = str(t["id"])
        assignee = str(t["assignee_id"]) if t.get("assignee_id") else None
        if t.get("archived") or assignee not in members:
            continue
        created = _datetime(t["created_date"])
        assert created is not None
        assigned = created
        for h in reversed(history.get(tid, [])):
            if h.get("field_name") == "assignee" and resolver.resolve(h.get("new_value")) == assignee.strip().lower():
                assigned = _datetime(h["changed_at"])
                break
        assert assigned is not None
        lag = (assigned.date() - created.date()).days
        if arrivals == "fresh" and lag >= BACKLOG_LAG_DAYS:
            continue
        category = category_by_status_id.get(str(t.get("task_status_id")), "TO_DO")
        completed = None
        if category == "DONE":
            completed = _datetime(t.get("finished_date"))
            if completed is None:
                for h in history.get(tid, []):
                    if h.get("field_name") == "status" and category_by_status_name.get(h.get("new_value")) == "DONE":
                        completed = _datetime(h["changed_at"])
                        break
        estimate = float(t.get("original_estimate_hrs") or 0.0)
        has_logs = (tid, assignee) in logs
        actual = logs[(tid, assignee)] if has_logs else None
        if not has_logs and completed is not None:
            actual = max(0.0, estimate - float(t.get("remaining_estimate_hrs") or 0.0))
        reporter = str(t["reporter_id"]) if t.get("reporter_id") else None
        mode = "self_picked" if reporter == assignee else ("project" if t.get("parent_task_id") else "manual")
        project = str(t["project_id"]) if t.get("project_id") else None
        task_rows.append(
            {
                "id": ids.of("tasks", tid),
                "title": t.get("title") or t.get("key") or tid,
                "project_id": ids.of("projects", project) if project else None,
                "assignee_id": members[assignee]["id"],
                "team_id": members[assignee]["team_id"],
                "type": type_name.get(str(t.get("task_type_id")), "Task"),
                "priority": t.get("priority") or "MEDIUM",
                "status": {"DONE": "done", "IN_PROGRESS": "in_progress"}.get(category, "todo"),
                "created_at": created.date(),
                "assigned_at": assigned.date(),
                "due_date": _date(t.get("due_date")),
                "completed_at": completed.date() if completed else None,
                "estimated_hours": estimate,
                "actual_hours": None if actual is None else float(actual),
                "created_by": members[reporter]["id"] if reporter in members else None,
                "assignment_mode": mode,
            }
        )
        if project:
            project_first[project] = min(project_first.get(project, created.date()), created.date())
            project_teams[project].add(members[assignee]["team_id"])
            if t.get("due_date"):
                due = _date(t["due_date"])
                assert due is not None
                project_last_due[project] = max(project_last_due.get(project, due), due)

    project_rows: list[dict[str, Any]] = []
    project_team_rows: list[dict[str, Any]] = []
    for p in sorted(data.get("projects", []), key=lambda p: str(p["id"])):
        pid = str(p["id"])
        owner = str(p["team_id"]) if p.get("team_id") and str(p["team_id"]) in parent_of else None
        if pid not in project_first and owner is None:
            continue
        start = project_first.get(pid, latest_created)
        project_rows.append(
            {
                "id": ids.of("projects", pid),
                "name": p.get("name") or p.get("key") or pid,
                "department_id": department_of_team[owner] if owner else department_rows[0]["id"],
                "start_date": start,
                "deadline": max(project_last_due.get(pid, start + dt.timedelta(days=90)), start),
                "type": "delivery",
                "status": str(p.get("status") or "active").lower(),
                "created_by": None,
            }
        )
        linked = set(project_teams.get(pid, set()))
        if owner:
            owner_team_id = ids.of("teams", owner)
            assert owner_team_id is not None
            linked.add(owner_team_id)
        for team_id in sorted(linked):
            project_team_rows.append({"project_id": ids.of("projects", pid), "team_id": team_id})

    holiday_rows: list[dict[str, Any]] = []
    for h in data.get("holidays", []):
        if h.get("status") != "CONFIRMED" or not h.get("active"):
            continue
        day, last = _date(h["start_date"]), _date(h["end_date"])
        assert day is not None
        assert last is not None
        while day <= last:
            holiday_rows.append({"date": day, "name": h.get("title") or "holiday", "country": "MA"})
            day += dt.timedelta(days=1)
    deduped_holiday_rows: list[dict[str, Any]] = []
    seen_holidays: set[dt.date] = set()
    for row in holiday_rows:
        if row["date"] not in seen_holidays:
            seen_holidays.add(row["date"])
            deduped_holiday_rows.append(row)
    holiday_rows = deduped_holiday_rows
    vacation_rows = [
        {
            "member_id": members[str(a["user_id"])]["id"],
            "start_date": _date(a["date"]),
            "end_date": _date(a["date"]),
            "type": "absence",
        }
        for a in data.get("absences", [])
        if str(a.get("user_id")) in members and float(a.get("hours") or 0.0) >= FULL_DAY_HOURS
    ]

    conn.execute("PRAGMA foreign_keys = OFF")
    counts: dict[str, int] = {}
    for table, rows in (
        ("departments", department_rows),
        ("teams", team_rows),
        ("members", sorted(members.values(), key=lambda m: m["id"])),
        ("projects", project_rows),
        ("project_teams", project_team_rows),
        ("tasks", task_rows),
        ("holidays", holiday_rows),
        ("vacations", vacation_rows),
    ):
        counts[table] = insert_rows(conn, table, rows, commit=False)
    conn.execute("UPDATE capacity_defaults SET weekly_hours = ? WHERE id = 1", (DEFAULT_WEEKLY_HOURS,))
    conn.commit()
    conn.execute("PRAGMA foreign_keys = ON")
    return counts


def clear_for_replace(conn: sqlite3.Connection) -> None:
    conn.execute("PRAGMA foreign_keys = OFF")
    for table in REPLACED_TABLES:
        conn.execute(f"DELETE FROM {table}")
    conn.commit()
    conn.execute("PRAGMA foreign_keys = ON")
