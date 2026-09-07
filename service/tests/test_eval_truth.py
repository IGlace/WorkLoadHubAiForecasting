import datetime as dt
import json

from whf.data.generator import GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect
from whf.db.repo import read_df
from whf.eval.truth import data_fingerprint, realised_hours, truncated_copy, truth_from_answer_key


def test_truth_from_answer_key_reads_member_week_hours(tmp_path) -> None:
    key = tmp_path / "answer_key.json"
    key.write_text(json.dumps({"effort_by_member_week": [{"member_id": 3, "week_start": "2026-08-03", "hours": 12.5}]}))
    truth = truth_from_answer_key(key)
    assert truth.to_dict(orient="records") == [{"member_id": 3, "week_start": dt.date(2026, 8, 3), "hours": 12.5}]


def test_realised_hours_spreads_actual_hours_over_working_days_of_the_task_window() -> None:
    conn = connect(":memory:")
    conn.execute("INSERT INTO departments (id, name) VALUES (1, 'D')")
    conn.execute("INSERT INTO teams (id, name, department_id) VALUES (1, 'T', 1)")
    conn.execute(
        "INSERT INTO members (id, name, team_id, department_id, role, counted_in_workload)"
        " VALUES (7, 'M', 1, 1, 'member', 1)"
    )
    # Wed 2026-08-05 to Tue 2026-08-11: 5 working days, 3 in the first week, 2 in the second
    conn.execute(
        "INSERT INTO tasks (id, title, assignee_id, team_id, type, priority, status, created_at, assigned_at,"
        " completed_at, estimated_hours, actual_hours) VALUES (1, 't', 7, 1, 'dev', 'p2', 'done', '2026-08-05',"
        " '2026-08-05', '2026-08-11', 8.0, 10.0)"
    )
    conn.commit()
    out = realised_hours(conn).sort_values("week_start").to_dict(orient="records")
    assert out == [
        {"member_id": 7, "week_start": dt.date(2026, 8, 3), "hours": 6.0},
        {"member_id": 7, "week_start": dt.date(2026, 8, 10), "hours": 4.0},
    ]


def test_truncated_copy_hides_the_future(db, generated) -> None:
    as_of = generated.config.as_of - dt.timedelta(days=60)
    copy = truncated_copy(db, as_of)
    tasks = read_df(copy, "SELECT assigned_at, completed_at, actual_hours, status FROM tasks")
    assert (tasks["assigned_at"].str[:10] <= as_of.isoformat()).all()
    later = tasks["completed_at"].dropna().str[:10]
    assert (later <= as_of.isoformat()).all()
    reopened = tasks[tasks["completed_at"].isna()]
    assert reopened["actual_hours"].isna().all() and (reopened["status"] != "done").all()
    assert read_df(copy, "SELECT COUNT(*) AS n FROM runs")["n"][0] == 0
    assert read_df(copy, "SELECT COUNT(*) AS n FROM members")["n"][0] == len(generated.members)
    assert read_df(copy, "SELECT weekly_hours FROM capacity_defaults")["weekly_hours"][0] == 44.0


def test_data_fingerprint_names_counts_range_and_hash(tmp_path) -> None:
    db_file = tmp_path / "f.db"
    conn = connect(db_file)
    load_generated(conn, generate(GeneratorConfig(seed=1, months=3)))
    fp = data_fingerprint(conn, db_file)
    assert fp["members"] > 0 and fp["tasks"] > 0 and fp["teams"] > 0
    assert fp["first_assigned"] <= fp["last_assigned"]
    assert len(fp["sha256"]) == 64
    assert data_fingerprint(conn, None)["sha256"] is None
