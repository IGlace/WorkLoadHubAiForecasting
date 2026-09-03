import datetime as dt

import pandas as pd

from whf.calendar import days_in_ranges
from whf.data.generator import GeneratedData, GeneratorConfig, generate, truncate_to
from whf.db.repo import read_df


def test_generate_is_reproducible() -> None:
    a = generate(GeneratorConfig(seed=11, months=3))
    b = generate(GeneratorConfig(seed=11, months=3))
    assert a.tasks == b.tasks and a.answer_key["profiles"] == b.answer_key["profiles"]


def test_tasks_have_all_fields_and_consistent_dates(generated: GeneratedData) -> None:
    required = {
        "id",
        "title",
        "project_id",
        "assignee_id",
        "team_id",
        "type",
        "priority",
        "status",
        "created_at",
        "assigned_at",
        "due_date",
        "completed_at",
        "estimated_hours",
        "actual_hours",
        "created_by",
        "assignment_mode",
    }
    ids = set()
    for t in generated.tasks:
        assert required <= set(t)
        assert t["id"] not in ids
        ids.add(t["id"])
        assert t["assigned_at"] >= t["created_at"]
        assert t["due_date"] > t["assigned_at"]
        assert t["estimated_hours"] > 0
        if t["status"] == "done":
            assert t["completed_at"] is not None and t["completed_at"] >= t["assigned_at"]
            assert t["actual_hours"] is not None and t["actual_hours"] > 0
        else:
            assert t["completed_at"] is None and t["actual_hours"] is None


def test_skill_team_leaders_hold_no_tasks_and_team_leaders_do(generated: GeneratedData) -> None:
    by_id = {m["id"]: m for m in generated.members}
    assignees = {t["assignee_id"] for t in generated.tasks}
    for m in generated.members:
        if m["role"] == "skill_team_leader":
            assert m["id"] not in assignees
        if m["role"] == "team_leader":
            assert m["id"] in assignees
    assert all(by_id[t["assignee_id"]]["team_id"] == t["team_id"] for t in generated.tasks)


def test_calibration_of_weekly_effort(generated: GeneratedData) -> None:
    eff = pd.DataFrame(generated.answer_key["effort_by_member_week"])
    mean_hours = eff["hours"].mean()
    assert 18.0 <= mean_hours <= 34.0
    assert eff["hours"].max() <= 40.0 + 0.05  # per-entry rounding
    open_tasks = [t for t in generated.tasks if t["status"] != "done"]
    assert 20 <= len(open_tasks) <= 600


def test_history_spans_roughly_a_year(generated: GeneratedData) -> None:
    first = min(t["assigned_at"] for t in generated.tasks)
    last = max(t["assigned_at"] for t in generated.tasks)
    assert (last - first).days >= 340
    assert last <= generated.config.as_of


def test_holidays_cover_history_and_horizon(generated: GeneratedData) -> None:
    dates = {h["date"] for h in generated.holidays}
    assert dt.date(2026, 1, 1) in dates
    assert all(h["country"] == "MA" for h in generated.holidays)


def test_answer_key_has_profiles_curves_and_effort(generated: GeneratedData) -> None:
    key = generated.answer_key
    assert set(key) >= {"profiles", "curves", "effort_by_member_week"}
    counted = {m["id"] for m in generated.members if m["counted_in_workload"]}
    assert set(int(k) for k in key["profiles"]) == counted


def test_truncate_reopens_tasks_completed_after_cutoff(generated: GeneratedData) -> None:
    cutoff = generated.config.as_of - dt.timedelta(days=21)
    cut = truncate_to(generated, cutoff)
    assert all(t["assigned_at"] <= cutoff for t in cut.tasks)
    assert all(t["completed_at"] is None or t["completed_at"] <= cutoff for t in cut.tasks)
    assert cut.config.as_of == cutoff
    assert cut.answer_key is generated.answer_key


def test_effort_log_matches_actual_hours_of_done_tasks(generated: GeneratedData) -> None:
    effort_log = generated.answer_key["effort_log"]
    hours_by_task: dict[int, float] = {}
    for row in effort_log:
        hours_by_task[row["task_id"]] = hours_by_task.get(row["task_id"], 0.0) + row["hours"]
    done = [t for t in generated.tasks if t["status"] == "done"]
    assert done  # sanity: there are done tasks to check
    for t in done:
        logged = hours_by_task.get(t["id"], 0.0)
        assert abs(logged - t["actual_hours"]) < 0.1, (t["id"], logged, t["actual_hours"])


def test_no_effort_on_weekends_holidays_or_vacations(generated: GeneratedData) -> None:
    holidays = {h["date"] for h in generated.holidays}
    vacation_days: dict[int, set[dt.date]] = {}
    for v in generated.vacations:
        vacation_days.setdefault(v["member_id"], set()).update(days_in_ranges([(v["start_date"], v["end_date"])]))
    for row in generated.answer_key["effort_log"]:
        day = dt.date.fromisoformat(row["date"])
        assert day.weekday() < 5, row
        assert day not in holidays, row
        assert day not in vacation_days.get(row["member_id"], set()), row


def test_no_effort_after_completion(generated: GeneratedData) -> None:
    completed_at = {t["id"]: t["completed_at"] for t in generated.tasks if t["completed_at"] is not None}
    for row in generated.answer_key["effort_log"]:
        deadline = completed_at.get(row["task_id"])
        if deadline is None:
            continue
        day = dt.date.fromisoformat(row["date"])
        assert day <= deadline, row


def test_loader_writes_every_table(db) -> None:
    counts = {
        name: int(read_df(db, f"SELECT COUNT(*) AS n FROM {name}")["n"][0])
        for name in ["departments", "teams", "members", "projects", "project_teams", "tasks", "vacations", "holidays"]
    }
    assert counts["departments"] == 3
    assert counts["tasks"] > 1000
    assert counts["holidays"] > 10
    assert counts["vacations"] > 0
