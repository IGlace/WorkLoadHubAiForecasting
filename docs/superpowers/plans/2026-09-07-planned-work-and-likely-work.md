# Planned work and likely work: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allocate not-yet-assigned tasks to members as a third, deterministic demand component, keep the arrival models on work that never goes through a backlog, and give Copilot the facts to say what is likely to land on each member.

**Architecture:** A pure allocation module (`whf/planned.py`) turns planned tasks into per-member, per-week hours through share weights with shrinkage and the effort model's placement; the pipeline adds them to demand and the facts; the harness reconstructs the backlog at each origin and scores demand with and without the component. The narrative gains one tool-backed section validated by the existing number check. Nothing here lets the language model produce a number.

**Tech Stack:** Python 3.11, pandas, numpy, hypothesis, pytest, FastAPI, Typer, SQLite; Electron + React + TypeScript with vitest.

**Spec:** `docs/superpowers/specs/2026-09-07-planned-work-and-likely-work-design.md`

**When:** Not before the real WorkloadHub export has landed in the service database (gate A). Tasks marked **[reduced set]** are built even when gate B fails; tasks marked **[gate B]** only when the readiness report shows planned tasks or at least 10% of assigned tasks with a lag of two days or more; tasks marked **[always]** are built in every case. The code references below are as of commit `74ae2ac`; re-read each file before editing.

## Global Constraints

- The language model never produces a forecast number. Decision 3 of the spec is closed for good; no task adds an LLM candidate to the tournament or the harness.
- Demand is never capped by capacity. `demand_hours = open_task_hours + new_task_hours + planned_task_hours`.
- `BACKLOG_LAG_DAYS = 2`, `SHARE_WINDOW_WEEKS = 26`, `SHRINK_K = 3.0`, `PLANNED_WORK_ENABLED = True`, all in `service/src/whf/planned.py`.
- A task row with `assignee_id` null must also have `assigned_at` null, and the reverse; the loader rejects anything else with `ValueError`.
- Every user-facing string exists in English and French, French with correct accents and the typographic apostrophe (’).
- Test-driven development: failing test first, run it, then the code. Property tests (hypothesis) for the arithmetic invariants named in the spec.
- Gate before each commit: in `service/`: `uv run ruff check . && uv run ruff format --check . && uv run ty check && uv run pytest -q`; in `app/`: `npm run lint && npm run typecheck && npm test`. `ty` has a pre-existing diagnostic baseline; add none.
- Nothing downloads at run time; no new dependencies. No AI-assistant model names anywhere in the repository.
- Commit footer on every commit: the two footer lines the controller gives in the dispatch. Never `--no-verify`, never `WHF_SKIP_HOOKS`. Stage by path.
- Fakes for the Copilot SDK live in `service/tests/ai_fakes.py`; extend them rather than adding a second fake.

## File structure

| File | Responsibility |
|---|---|
| `service/src/whf/data/profile.py` (new) | Readiness numbers over the `tasks` table, pure function returning a dict. |
| `service/src/whf/db/schema.sql`, `service/src/whf/db/connection.py` | Nullable assignee columns; one-time table rebuild guarded by `PRAGMA user_version`. |
| `service/src/whf/data/loader.py` | Rejects half-null task rows. |
| `service/src/whf/data/generator.py` | `backlog_share`, `backlog_lag_days`, planned tasks at `as_of`, answer-key `planned_assignments`, `truncate_to` reconstruction. |
| `service/src/whf/features.py` | `fresh_hours` column, `SERIES_COLUMN`, features and targets computed on it. |
| `service/src/whf/models/naive.py`, `tsb.py`, `chronos2.py` | Read `SERIES_COLUMN` instead of `est_hours`. |
| `service/src/whf/planned.py` (new) | Candidate tasks, share weights, lag, placement; `PlannedAllocation`. |
| `service/src/whf/pipeline.py` | Third demand component, new forecast column, new facts. |
| `service/src/whf/eval/truth.py`, `harness.py`, `report.py` | Backlog reconstruction at an origin, `planned` flag, "Planned work" summary section. |
| `service/src/whf/ai/facts_tools.py`, `prompt.py`, `schema.py`, `skills/whf-likely-work/SKILL.md` | Likely-work facts, tools, contract, skill. |
| `service/src/whf/cli.py` | `whf data profile`, `--no-planned`, planned hours in `whf run` output. |
| `app/src/shared/types.ts`, `app/src/renderer/src/components/WeekTable.tsx`, `pages/TeamResult.tsx`, `pages/MemberDetail.tsx`, `i18n.ts` | Planned column, "Likely to land" list. |

---

### Task 1: Readiness report `whf data profile` [always]

**Files:**
- Create: `service/src/whf/data/profile.py`
- Modify: `service/src/whf/cli.py` (after `data_generate`)
- Test: `service/tests/test_data_profile.py`

**Interfaces:**
- Produces: `profile_tasks(conn: sqlite3.Connection, lag_days: int = 2) -> dict` with keys `tasks`, `assigned`, `planned`, `planned_with_project`, `share_lagged`, `lag_median_days`, `lag_p90_days`, `share_with_project`, `share_with_actual_hours`, `first_created`, `last_created`, `latest_date`. Task 2 makes null assignees possible; until then `planned` is always 0.

- [ ] **Step 1: Write the failing test**

```python
# service/tests/test_data_profile.py
import datetime as dt

from whf.data.profile import profile_tasks
from whf.db.connection import connect
from whf.db.repo import insert_rows


def _task(i, created, assigned, project=1, actual=None):
    return {
        "id": i, "title": f"t{i}", "project_id": project, "assignee_id": 11, "team_id": 1, "type": "feature",
        "priority": "medium", "status": "done" if actual else "todo", "created_at": created, "assigned_at": assigned,
        "due_date": None, "completed_at": assigned if actual else None, "estimated_hours": 8.0,
        "actual_hours": actual, "created_by": None, "assignment_mode": "manual",
    }


def test_profile_counts_lagged_tasks_and_dates() -> None:
    conn = connect(":memory:")
    insert_rows(conn, "departments", [{"id": 1, "name": "D", "skill_team_leader_id": None}])
    insert_rows(conn, "teams", [{"id": 1, "department_id": 1, "name": "T", "team_leader_id": None}])
    insert_rows(conn, "members", [{"id": 11, "name": "A", "team_id": 1, "department_id": 1, "role": "member",
                                   "counted_in_workload": 1, "active_from": None, "active_to": None}])
    insert_rows(conn, "projects", [{"id": 1, "name": "P", "department_id": 1, "start_date": "2026-01-05",
                                    "deadline": "2026-12-31", "type": "build", "status": "active", "created_by": None}])
    insert_rows(conn, "tasks", [
        _task(1, "2026-03-02", "2026-03-02", actual=6.0),   # fresh
        _task(2, "2026-03-02", "2026-03-03"),               # lag 1: fresh
        _task(3, "2026-03-02", "2026-03-09"),               # lag 7: backlog
        _task(4, "2026-03-01", "2026-03-20", project=None), # lag 19: backlog
    ])
    p = profile_tasks(conn, lag_days=2)
    assert p["tasks"] == 4 and p["assigned"] == 4 and p["planned"] == 0
    assert p["share_lagged"] == 0.5
    assert p["lag_median_days"] == 4.0 and p["lag_p90_days"] == 15.4
    assert p["share_with_project"] == 0.75 and p["share_with_actual_hours"] == 0.25
    assert p["first_created"] == "2026-03-01" and p["last_created"] == "2026-03-02"
    assert p["latest_date"] == "2026-03-20"


def test_profile_of_an_empty_database_is_all_zero() -> None:
    conn = connect(":memory:")
    p = profile_tasks(conn)
    assert p["tasks"] == 0 and p["share_lagged"] == 0.0 and p["lag_median_days"] is None
    assert p["first_created"] is None and p["latest_date"] is None
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `service/`: `uv run pytest tests/test_data_profile.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.data.profile'`.

- [ ] **Step 3: Write the module**

```python
# service/src/whf/data/profile.py
"""Readiness numbers over the task history: what the planned-work design can and cannot use."""

from __future__ import annotations

import sqlite3

import numpy as np
import pandas as pd

from whf.db.repo import read_df, with_dates


def profile_tasks(conn: sqlite3.Connection, lag_days: int = 2) -> dict:
    """Counts and shares over `tasks`; `lag_days` is the backlog threshold (`BACKLOG_LAG_DAYS`)."""
    tasks = with_dates(read_df(conn, "SELECT * FROM tasks"), ["created_at", "assigned_at", "completed_at"])
    assigned = tasks[tasks["assignee_id"].notna() & tasks["assigned_at"].notna()]
    planned = tasks[tasks["assignee_id"].isna()]
    lags = pd.Series(
        [(a - c).days for a, c in zip(assigned["assigned_at"], assigned["created_at"], strict=True)], dtype=float
    )
    dates = [d for d in list(tasks["created_at"]) + list(assigned["assigned_at"]) + list(tasks["completed_at"]) if d]
    n = len(tasks)
    return {
        "tasks": n,
        "assigned": int(len(assigned)),
        "planned": int(len(planned)),
        "planned_with_project": int(planned["project_id"].notna().sum()),
        "share_lagged": float((lags >= lag_days).mean()) if len(lags) else 0.0,
        "lag_median_days": float(np.median(lags)) if len(lags) else None,
        "lag_p90_days": float(np.percentile(lags, 90)) if len(lags) else None,
        "share_with_project": float(tasks["project_id"].notna().mean()) if n else 0.0,
        "share_with_actual_hours": float(tasks["actual_hours"].notna().mean()) if n else 0.0,
        "first_created": min(tasks["created_at"]).isoformat() if n else None,
        "last_created": max(tasks["created_at"]).isoformat() if n else None,
        "latest_date": max(dates).isoformat() if dates else None,
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `uv run pytest tests/test_data_profile.py -v`
Expected: PASS (2 tests). If `lag_p90_days` differs in the last digit, check `np.percentile` uses linear interpolation (the default); the expected value for lags `[0, 1, 7, 19]` is `15.4`.

- [ ] **Step 5: Add the CLI command and its test**

In `service/src/whf/cli.py`, after `data_generate`:

```python
@data_app.command("profile")
def data_profile(
    db: DbOption = None,
    out: Annotated[Path | None, typer.Option("--out", help="Write the JSON here; default <data dir>/profile.json")] = None,
) -> None:
    """Print what the task history can support (backlog share, lags, planned tasks) and write it as JSON."""
    from whf.data.profile import profile_tasks
    from whf.planned import BACKLOG_LAG_DAYS

    profile = profile_tasks(_conn(db), lag_days=BACKLOG_LAG_DAYS)
    target = out or (data_dir() / "profile.json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(profile, indent=1), encoding="utf-8")
    for key, value in profile.items():
        typer.echo(f"{key}: {value}")
    typer.echo(f"written to {target}")
```

`whf.planned` does not exist until Task 5; until then import `BACKLOG_LAG_DAYS` from a one-line module `service/src/whf/planned.py` containing only `BACKLOG_LAG_DAYS = 2` with a module docstring; Task 5 grows it. Test in `service/tests/test_cli.py`, following the file's `CliRunner` pattern:

```python
def test_data_profile_prints_and_writes_json(tmp_path, db_file) -> None:
    result = runner.invoke(app, ["data", "profile", "--db", str(db_file), "--out", str(tmp_path / "p.json")])
    assert result.exit_code == 0, result.output
    assert "share_lagged:" in result.output
    assert json.loads((tmp_path / "p.json").read_text(encoding="utf-8"))["tasks"] > 0
```

Use whatever fixture `test_cli.py` already uses for a database file (read the file; it has one for `whf run`).

- [ ] **Step 6: Run the gate and commit**

```bash
uv run ruff check . && uv run ruff format --check . && uv run ty check && uv run pytest -q
git add src/whf/data/profile.py src/whf/planned.py src/whf/cli.py tests/test_data_profile.py tests/test_cli.py
git commit -m "feat(data): whf data profile reports what the task history can support"
```

---

### Task 2: Nullable assignee, migration, loader guard [reduced set]

**Files:**
- Modify: `service/src/whf/db/schema.sql` (tasks table), `service/src/whf/db/connection.py`, `service/src/whf/data/loader.py`
- Test: `service/tests/test_db.py`

**Interfaces:**
- Produces: `tasks.assignee_id` and `tasks.assigned_at` nullable; `PRAGMA user_version = 2` after `connect()`; `load_generated` raises `ValueError("task {id}: assignee_id and assigned_at must be both set or both null")`.
- Consumers: every reader of tasks that means assigned work filters `assignee_id IS NOT NULL` (Task 4 does this for the arrivals; `patterns.pattern_table` already filters by `assignee_id == member_id`, which excludes nulls; `_build_facts` open tasks filter by `assignee_id == mid`, which excludes nulls; `realised_hours` drops rows without `completed_at`, and a planned task has none).

- [ ] **Step 1: Write the failing tests**

```python
# service/tests/test_db.py (append)
import sqlite3

from whf.db.connection import connect, _schema_sql


def _legacy_database(path) -> None:
    """A version-1 file: the tasks table with NOT NULL assignee columns, one row."""
    raw = sqlite3.connect(path)
    raw.executescript(_schema_sql().replace(
        "assignee_id INTEGER REFERENCES members(id)", "assignee_id INTEGER NOT NULL REFERENCES members(id)"
    ).replace("assigned_at TEXT,", "assigned_at TEXT NOT NULL,"))
    raw.execute("INSERT INTO departments (id, name) VALUES (1, 'D')")
    raw.execute("INSERT INTO teams (id, department_id, name) VALUES (1, 1, 'T')")
    raw.execute("INSERT INTO members (id, name, team_id, department_id, role) VALUES (11, 'A', 1, 1, 'member')")
    raw.execute(
        "INSERT INTO tasks (id, title, assignee_id, team_id, type, priority, status, created_at, assigned_at, estimated_hours)"
        " VALUES (1, 't', 11, 1, 'feature', 'medium', 'todo', '2026-03-02', '2026-03-02', 8.0)"
    )
    raw.execute("PRAGMA user_version = 0")
    raw.commit()
    raw.close()


def test_connect_migrates_a_version_one_database_and_keeps_its_rows(tmp_path) -> None:
    path = tmp_path / "v1.sqlite"
    _legacy_database(path)
    conn = connect(path)
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 2
    assert conn.execute("SELECT COUNT(*) FROM tasks").fetchone()[0] == 1
    conn.execute(
        "INSERT INTO tasks (id, title, team_id, type, priority, status, created_at, estimated_hours)"
        " VALUES (2, 'planned', 1, 'feature', 'low', 'todo', '2026-03-03', 4.0)"
    )
    assert conn.execute("SELECT assignee_id, assigned_at FROM tasks WHERE id = 2").fetchone() == (None, None)
    names = {r[1] for r in conn.execute("PRAGMA index_list('tasks')")}
    assert {"idx_tasks_assignee", "idx_tasks_team"} <= names
    conn.close()
    again = connect(path)  # idempotent
    assert again.execute("PRAGMA user_version").fetchone()[0] == 2
    assert again.execute("SELECT COUNT(*) FROM tasks").fetchone()[0] == 2


def test_a_fresh_database_starts_at_version_two() -> None:
    conn = connect(":memory:")
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 2


def test_loader_rejects_a_task_with_only_one_of_the_assignee_columns(generated) -> None:
    import copy
    import pytest
    from whf.data.loader import load_generated

    data = copy.deepcopy(generated)
    data.tasks[0]["assigned_at"] = None
    with pytest.raises(ValueError, match="both set or both null"):
        load_generated(connect(":memory:"), data)
```

Check the exact column list of `departments`, `teams` and `members` in `schema.sql` before running; the inserts above name only the required columns.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run pytest tests/test_db.py -v -k "migrates or version_two or rejects"`
Expected: the first two fail on `user_version == 0`, the third fails because no error is raised.

- [ ] **Step 3: Change the schema and add the migration**

In `schema.sql`, the tasks table becomes:

```sql
CREATE TABLE IF NOT EXISTS tasks (
    id INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    project_id INTEGER REFERENCES projects(id),
    assignee_id INTEGER REFERENCES members(id),
    team_id INTEGER NOT NULL REFERENCES teams(id),
    type TEXT NOT NULL,
    priority TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    assigned_at TEXT,
    due_date TEXT,
    completed_at TEXT,
    estimated_hours REAL NOT NULL,
    actual_hours REAL,
    created_by INTEGER,
    assignment_mode TEXT CHECK (assignment_mode IN ('manual', 'self_picked', 'project')),
    CHECK ((assignee_id IS NULL) = (assigned_at IS NULL))
);
```

In `connection.py`:

```python
SCHEMA_VERSION = 2


def _migrate(conn: sqlite3.Connection) -> None:
    """Bring an older file up to `SCHEMA_VERSION`. Version 0/1: tasks.assignee_id and assigned_at NOT NULL."""
    version = int(conn.execute("PRAGMA user_version").fetchone()[0])
    if version >= SCHEMA_VERSION:
        return
    columns = {r[1]: r for r in conn.execute("PRAGMA table_info('tasks')")}
    if columns and columns["assignee_id"][3] == 1:  # notnull flag set: rebuild the table
        conn.execute("PRAGMA foreign_keys = OFF")
        try:
            conn.execute("BEGIN")
            conn.execute("ALTER TABLE tasks RENAME TO tasks_old")
            conn.execute(_tasks_ddl())  # one statement; executescript would commit the open transaction
            conn.execute("INSERT INTO tasks SELECT * FROM tasks_old")
            conn.execute("DROP TABLE tasks_old")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_tasks_assignee ON tasks(assignee_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_tasks_team ON tasks(team_id)")
            conn.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.execute("PRAGMA foreign_keys = ON")
    else:
        conn.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
        conn.commit()


def _tasks_ddl() -> str:
    sql = _schema_sql()
    start = sql.index("CREATE TABLE IF NOT EXISTS tasks")
    end = sql.index(";", start) + 1
    return sql[start:end]
```

and in `connect()`, after `conn.executescript(_schema_sql())`: call `_migrate(conn)` before `conn.commit()`. `ALTER TABLE ... RENAME` on a table with an index renames the index with the table in SQLite, which is why the two indexes are recreated after the drop. `INSERT INTO tasks SELECT * FROM tasks_old` works because the column order is unchanged.

- [ ] **Step 4: Add the loader guard**

In `loader.py`, before `insert_rows(conn, "tasks", data.tasks)`:

```python
    for t in data.tasks:
        if (t.get("assignee_id") is None) != (t.get("assigned_at") is None):
            raise ValueError(f"task {t['id']}: assignee_id and assigned_at must be both set or both null")
```

- [ ] **Step 5: Run the tests, then the whole suite**

Run: `uv run pytest tests/test_db.py -v` then `uv run pytest -q`.
Expected: all green. If a test elsewhere inserts a task with a null `assigned_at` and a set assignee, it now violates the CHECK; fix the fixture, not the constraint.

- [ ] **Step 6: Commit**

```bash
git add src/whf/db/schema.sql src/whf/db/connection.py src/whf/data/loader.py tests/test_db.py
git commit -m "feat(db): tasks may exist before they are assigned; migrate older files in place"
```

---

### Task 3: Generator backlog stage and planned tasks [reduced set]

**Files:**
- Modify: `service/src/whf/data/generator.py` (`GeneratorConfig`, `_new_task`, `simulate_tasks`, `generate`, `truncate_to`)
- Test: `service/tests/test_generator_simulation.py`

**Interfaces:**
- Produces: `GeneratorConfig(backlog_share: float = 0.0, backlog_lag_days: tuple[int, int] = (3, 21))`. The default stays 0 so every existing test and the installer's sample data are byte-for-byte unchanged; the harness smoke test in Task 7 and the fixtures of Tasks 5 to 9 pass `backlog_share=0.35`. `answer_key["planned_assignments"]`: list of `{"task_id", "member_id", "assigned_at"}` for the planned rows at `as_of`. `truncate_to(data, as_of)` returns tasks created after `as_of` removed and tasks assigned after it turned into planned rows.

- [ ] **Step 1: Write the failing tests**

```python
# service/tests/test_generator_simulation.py (append)
import datetime as dt

from whf.data.generator import GeneratorConfig, generate, truncate_to


def test_backlog_share_zero_reproduces_the_default_data_exactly() -> None:
    a = generate(GeneratorConfig(seed=7, months=3))
    b = generate(GeneratorConfig(seed=7, months=3, backlog_share=0.0))
    assert a.tasks == b.tasks and a.answer_key["effort_by_member_week"] == b.answer_key["effort_by_member_week"]


def test_backlog_share_creates_lagged_history_and_planned_rows_at_as_of() -> None:
    data = generate(GeneratorConfig(seed=7, months=3, backlog_share=0.5))
    assigned = [t for t in data.tasks if t["assignee_id"] is not None]
    lagged = [t for t in assigned if (t["assigned_at"] - t["created_at"]).days >= 2]
    assert 0.2 < len(lagged) / len(assigned) < 0.8
    assert all(3 <= (t["assigned_at"] - t["created_at"]).days <= 21 for t in lagged)
    assert all(t["assignment_mode"] == "project" for t in lagged)
    planned = [t for t in data.tasks if t["assignee_id"] is None]
    assert planned, "no planned rows at as_of"
    assert all(t["assigned_at"] is None and t["status"] == "todo" and t["created_at"] <= data.config.as_of for t in planned)
    key = {k["task_id"]: k for k in data.answer_key["planned_assignments"]}
    assert set(key) == {t["id"] for t in planned}
    assert all(dt.date.fromisoformat(k["assigned_at"]) > data.config.as_of for k in key.values())


def test_truncate_to_turns_later_assignments_into_planned_rows() -> None:
    data = generate(GeneratorConfig(seed=7, months=3, backlog_share=0.5))
    cut = data.config.as_of - dt.timedelta(days=30)
    older = truncate_to(data, cut)
    by_id = {t["id"]: t for t in older.tasks}
    for t in data.tasks:
        if t["created_at"] > cut:
            assert t["id"] not in by_id
        elif t["assigned_at"] is None or t["assigned_at"] > cut:
            assert by_id[t["id"]]["assignee_id"] is None and by_id[t["id"]]["assigned_at"] is None
            assert by_id[t["id"]]["status"] == "todo"
        else:
            assert by_id[t["id"]]["assignee_id"] == t["assignee_id"]
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run pytest tests/test_generator_simulation.py -v -k backlog`
Expected: `TypeError: unexpected keyword argument 'backlog_share'`.

- [ ] **Step 3: Implement**

In `GeneratorConfig` add:

```python
    backlog_share: float = 0.0  # share of project-driven tasks that sit in a backlog before assignment
    backlog_lag_days: tuple[int, int] = (3, 21)  # uniform range of that wait, in days
```

In `_new_task`, add a parameter `created_at: dt.date` and use it for `"created_at": created_at` (the call site decides). In `simulate_tasks`, inside the per-task loop, replace the `_new_task(...)` call with:

```python
                created_at = day
                if config.backlog_share > 0 and mode == "project" and rng.random() < config.backlog_share:
                    lag = int(rng.integers(config.backlog_lag_days[0], config.backlog_lag_days[1] + 1))
                    created_at = day - dt.timedelta(days=lag)
                task, total = _new_task(rng, task_id, day, created_at, member, profile, mode, team_projects, intensity,
                                        leader_by_team[member["team_id"]])
```

The `rng.random()` draw happens only when `backlog_share > 0`, which is what keeps `backlog_share=0` identical to today.

Planned rows at `as_of`: change the loop bound from `while day <= config.as_of` to `while day <= config.as_of + dt.timedelta(days=config.backlog_lag_days[1] if config.backlog_share > 0 else 0)`, and at the top of the per-member body add:

```python
            future = day > config.as_of
```

For a future day, still draw the arrivals exactly as above but: skip the effort block (`budget` loop) entirely, and for each task drawn, if `created_at <= config.as_of` append it to a new list `planned` with `assignee_id=None`, `assigned_at=None`, `status="todo"` and record `{"task_id": task["id"], "member_id": member["id"], "assigned_at": day.isoformat()}` in `planned_assignments`; if `created_at > config.as_of` drop it (`task_id` still advanced, so ids stay unique). Return `planned_assignments` as a third element of the tuple and extend `generate()` to append `planned` tasks to `tasks` and to put `"planned_assignments": planned_assignments` in `answer_key`. The effort log therefore ends at `as_of`, as today.

`truncate_to` becomes:

```python
    tasks: list[dict] = []
    for t in data.tasks:
        if t["created_at"] > as_of:
            continue
        t2 = dict(t)
        if t2["assigned_at"] is None or t2["assigned_at"] > as_of:
            t2.update(assignee_id=None, assigned_at=None, status="todo", completed_at=None, actual_hours=None)
        elif t2["completed_at"] is not None and t2["completed_at"] > as_of:
            t2.update(completed_at=None, actual_hours=None, status="in_progress")
        tasks.append(t2)
```

- [ ] **Step 4: Run the tests and the suite**

Run: `uv run pytest tests/test_generator_simulation.py -v` then `uv run pytest -q`.
Expected: green; the equivalence test proves the default path is untouched.

- [ ] **Step 5: Commit**

```bash
git add src/whf/data/generator.py tests/test_generator_simulation.py
git commit -m "feat(generator): optional backlog stage with lagged assignments and planned tasks at as_of"
```

---

### Task 4: The arrival split, `fresh_hours` [reduced set]

**Files:**
- Modify: `service/src/whf/features.py`, `service/src/whf/models/naive.py`, `service/src/whf/models/tsb.py`, `service/src/whf/models/chronos2.py`, `service/src/whf/planned.py`
- Test: `service/tests/test_features.py`, `service/tests/test_models_baselines.py`

**Interfaces:**
- Produces: `features.SERIES_COLUMN = "fresh_hours"`; `weekly_arrivals(...)` returns `n_tasks`, `est_hours`, `fresh_hours`; `build_feature_matrix` computes lags, rolling statistics, `weeks_since_last_arrival` and `target_h*` from `SERIES_COLUMN`; `planned.is_backlog_arrival(created_at, assigned_at) -> bool`.
- Consumed by: the models (they read `SERIES_COLUMN`), the backtest (unchanged, it reads `target_h*`), the harness caption (Task 7).

- [ ] **Step 1: Write the failing tests**

```python
# service/tests/test_features.py (append)
import datetime as dt

import pandas as pd
from hypothesis import given, strategies as st

from whf.features import SERIES_COLUMN, build_feature_matrix, weekly_arrivals
from whf.planned import is_backlog_arrival


def test_fresh_hours_exclude_backlog_arrivals() -> None:
    monday = dt.date(2026, 3, 2)
    tasks = pd.DataFrame([
        {"id": 1, "assignee_id": 11, "created_at": monday, "assigned_at": monday, "estimated_hours": 8.0, "assignment_mode": "manual"},
        {"id": 2, "assignee_id": 11, "created_at": monday, "assigned_at": monday + dt.timedelta(days=1), "estimated_hours": 4.0, "assignment_mode": "manual"},
        {"id": 3, "assignee_id": 11, "created_at": monday - dt.timedelta(days=10), "assigned_at": monday + dt.timedelta(days=2), "estimated_hours": 6.0, "assignment_mode": "project"},
    ])
    out = weekly_arrivals(tasks, [11], [monday])
    assert float(out["est_hours"][0]) == 18.0
    assert float(out["fresh_hours"][0]) == 12.0
    assert int(out["n_tasks"][0]) == 3


@given(st.lists(st.tuples(st.integers(0, 60), st.floats(0.5, 40)), min_size=0, max_size=30))
def test_fresh_plus_backlog_equals_all_arrivals(lag_and_hours) -> None:
    monday = dt.date(2026, 3, 2)
    rows = [
        {"id": i, "assignee_id": 11, "created_at": monday - dt.timedelta(days=lag), "assigned_at": monday,
         "estimated_hours": h, "assignment_mode": "manual"}
        for i, (lag, h) in enumerate(lag_and_hours)
    ]
    tasks = pd.DataFrame(rows, columns=["id", "assignee_id", "created_at", "assigned_at", "estimated_hours", "assignment_mode"])
    out = weekly_arrivals(tasks, [11], [monday])
    backlog = sum(h for lag, h in lag_and_hours if is_backlog_arrival(monday - dt.timedelta(days=lag), monday))
    assert abs(float(out["fresh_hours"][0]) + backlog - float(out["est_hours"][0])) < 1e-6


def test_feature_matrix_targets_are_fresh_hours(generated) -> None:
    from whf.pipeline import _load_frames, arrival_feature_matrix
    from whf.db.connection import connect
    from whf.data.loader import load_generated

    conn = connect(":memory:")
    load_generated(conn, generated)
    arrivals, feat, weeks = arrival_feature_matrix(_load_frames(conn), weeks_end := dt.date(2026, 8, 24))
    row = feat[(feat["member_id"].astype(int) == 11) & (feat["week_start"] == weeks_end - dt.timedelta(days=7))].iloc[0]
    nxt = arrivals[(arrivals["member_id"] == 11) & (arrivals["week_start"] == weeks_end)].iloc[0]
    assert float(row["target_h1"]) == float(nxt[SERIES_COLUMN])
```

(`weeks_end` must be a Monday inside the generated history; adjust the date if the generator's `as_of` changes. `weekly_arrivals` must accept tasks without `created_at` in older callers: treat a missing column as "all fresh".)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run pytest tests/test_features.py -v -k "fresh or targets_are"`
Expected: `ImportError: cannot import name 'SERIES_COLUMN'`.

- [ ] **Step 3: Implement**

In `planned.py` (still tiny after Task 1):

```python
"""Planned work: tasks that exist before they are assigned, and the arrival split they imply."""

from __future__ import annotations

import datetime as dt

BACKLOG_LAG_DAYS = 2  # a task assigned this many days or more after its creation came through a backlog


def is_backlog_arrival(created_at: dt.date | None, assigned_at: dt.date | None) -> bool:
    if created_at is None or assigned_at is None:
        return False
    return (assigned_at - created_at).days >= BACKLOG_LAG_DAYS
```

In `features.py`: add `SERIES_COLUMN = "fresh_hours"` next to `HORIZONS`; in `weekly_arrivals`, select `created_at` when present, compute a boolean `backlog` per task with `is_backlog_arrival` (False when the column is missing), aggregate `fresh_hours=("fresh", "sum")` where `fresh = estimated_hours * (~backlog)`, fill with 0.0 like `est_hours`. Filter `assignee_id.notna()` before grouping. In `build_feature_matrix`, replace `hours = feat.groupby("member_id")["est_hours"]` with `hours = feat.groupby("member_id")[SERIES_COLUMN]` and `nonzero = feat["est_hours"] > 0` with `nonzero = feat[SERIES_COLUMN] > 0`. In `naive.py` and `tsb.py`, replace `train["est_hours"]` and `["est_hours"]` with `[SERIES_COLUMN]` (import from `whf.features`). In `chronos2.py`, every `"est_hours"` at lines 160, 206, 240, 264 and 290 becomes `SERIES_COLUMN`; read the file, there may be more. `SeasonalNaive.predict`'s fallback `roll_mean_4` is already computed from the series column.

- [ ] **Step 4: Run the tests and the suite**

Run: `uv run pytest tests/test_features.py tests/test_models_baselines.py tests/test_models_gbm.py tests/test_backtest.py tests/test_pipeline.py -q` then `uv run pytest -q`.
Expected: green. The default generated data has no lags, so `fresh_hours == est_hours` and every numeric assertion in the suite holds unchanged; if one fails, the split leaked into a place that still reads `est_hours`, which is the bug to fix.

- [ ] **Step 5: Commit**

```bash
git add src/whf/features.py src/whf/planned.py src/whf/models/naive.py src/whf/models/tsb.py src/whf/models/chronos2.py tests/test_features.py
git commit -m "feat(features): arrival models learn from fresh arrivals only; backlog arrivals are split out"
```

---

### Task 5: Allocation module `whf/planned.py` [gate B]

**Files:**
- Modify: `service/src/whf/planned.py`
- Test: `service/tests/test_planned.py`

**Interfaces:**
- Consumes: `EffortModel.estimate_ratio(member_id, task_type, team_id) -> float`, `EffortModel.member_cycle_days(member_id, team_id) -> float`, `place_hours(hours, start, end, off) -> dict[week_start, hours]` from `whf.models.effort`; `working_days(start, end, off)` from `whf.calendar`.
- Produces:

```python
SHARE_WINDOW_WEEKS = 26
SHRINK_K = 3.0
PLANNED_WORK_ENABLED = True

@dataclass
class PlannedAllocation:
    hours: pd.DataFrame          # columns member_id, week_start, hours (only weeks f1 and f2)
    items: list[dict]            # one per (task, member) with a positive share, see below
    after_window: dict[int, float]   # member_id -> hours placed after f2
    backlog: list[dict]          # one per project: project_id, tasks, estimated_hours, hours_in_window, hours_after_window

def candidate_tasks(tasks: pd.DataFrame, team_id: int) -> pd.DataFrame
def assignment_lag_days(tasks: pd.DataFrame, project_id: int | None, team_id: int) -> float
def share_weights(tasks: pd.DataFrame, member_ids: list[int], project_id: int | None, task_type: str, team_id: int, as_of: dt.date) -> dict[int, float]
def allocate(tasks, team_members, team_id, as_of, weeks, effort, off_by_member, *, enabled=PLANNED_WORK_ENABLED) -> PlannedAllocation
```

`items` entries: `{"task_id", "title", "project_id", "type", "estimated_hours", "member_id", "share", "expected_assignment", "expected_week"}` where `expected_week` is the ISO Monday or the string `"after_window"`.

- [ ] **Step 1: Write the failing tests**

```python
# service/tests/test_planned.py
import datetime as dt

import pandas as pd
import pytest
from hypothesis import given, settings, strategies as st

from whf.models.effort import EffortModel
from whf.planned import PlannedAllocation, allocate, assignment_lag_days, candidate_tasks, share_weights

MON = dt.date(2026, 9, 7)
F1, F2 = dt.date(2026, 9, 14), dt.date(2026, 9, 21)
TEAM = pd.DataFrame([
    {"id": 11, "team_id": 1, "active_to": None}, {"id": 12, "team_id": 1, "active_to": None}, {"id": 13, "team_id": 1, "active_to": None},
])


def _assigned(i, member, project, ttype, days_ago, lag=0):
    assigned = MON - dt.timedelta(days=days_ago)
    return {"id": i, "title": f"t{i}", "project_id": project, "assignee_id": member, "team_id": 1, "type": ttype,
            "priority": "medium", "status": "done", "created_at": assigned - dt.timedelta(days=lag), "assigned_at": assigned,
            "due_date": None, "completed_at": assigned + dt.timedelta(days=5), "estimated_hours": 8.0, "actual_hours": 8.0,
            "created_by": None, "assignment_mode": "project"}


def _planned(i, project, ttype, created_days_ago, hours=10.0):
    return {"id": i, "title": f"p{i}", "project_id": project, "assignee_id": None, "team_id": 1, "type": ttype,
            "priority": "medium", "status": "todo", "created_at": MON - dt.timedelta(days=created_days_ago), "assigned_at": None,
            "due_date": None, "completed_at": None, "estimated_hours": hours, "actual_hours": None, "created_by": None,
            "assignment_mode": "project"}


def _history():
    rows = []
    i = 0
    for _ in range(6):  # member 11 did six backend tasks on project 1, member 12 two, member 13 none
        i += 1; rows.append(_assigned(i, 11, 1, "feature", 20, lag=7))
    for _ in range(2):
        i += 1; rows.append(_assigned(i, 12, 1, "feature", 15, lag=3))
    for _ in range(4):  # member 13 only does support, on no project
        i += 1; rows.append(_assigned(i, 13, None, "support", 10))
    return pd.DataFrame(rows)


def test_candidates_are_the_teams_planned_tasks_not_done() -> None:
    tasks = pd.concat([_history(), pd.DataFrame([_planned(90, 1, "feature", 4), {**_planned(91, 1, "feature", 4), "team_id": 2},
                                                  {**_planned(92, 1, "feature", 4), "status": "done"}])], ignore_index=True)
    assert list(candidate_tasks(tasks, 1)["id"]) == [90]


def test_share_weights_follow_project_and_type_history_with_shrinkage() -> None:
    w = share_weights(_history(), [11, 12, 13], project_id=1, task_type="feature", team_id=1, as_of=MON)
    assert abs(sum(w.values()) - 1.0) < 1e-9
    assert w[11] > w[12] > w[13] > 0.0  # shrinkage keeps the unseen member above zero
    # level 1 for member 11: (6 + 3 * prior) / (8 + 3) with prior = project-level share 6/8 shrunk towards team-level
    assert 0.6 < w[11] < 0.75


def test_share_weights_fall_back_to_equal_split_without_history() -> None:
    empty = _history().iloc[0:0]
    w = share_weights(empty, [11, 12, 13], project_id=5, task_type="bug", team_id=1, as_of=MON)
    assert w == pytest.approx({11: 1 / 3, 12: 1 / 3, 13: 1 / 3})


def test_assignment_lag_prefers_the_project_then_the_team_then_the_threshold() -> None:
    hist = _history()
    assert assignment_lag_days(hist, project_id=1, team_id=1) == 7.0  # median of 6x7 and 2x3
    assert assignment_lag_days(hist, project_id=None, team_id=1) == 5.0  # team median over 12 tasks: [0]*4 + [3]*2 + [7]*6 -> (3 + 7) / 2
    assert assignment_lag_days(hist.iloc[0:0], project_id=1, team_id=1) == 2.0


def test_allocate_places_hours_in_the_window_and_reports_the_rest() -> None:
    tasks = pd.concat([_history(), pd.DataFrame([_planned(90, 1, "feature", 4, hours=10.0)])], ignore_index=True)
    effort = EffortModel().fit(tasks.dropna(subset=["actual_hours", "completed_at"]))
    out = allocate(tasks, TEAM, 1, MON, (F1, F2), effort, off_by_member={})
    assert isinstance(out, PlannedAllocation)
    total = float(out.hours["hours"].sum()) + sum(out.after_window.values())
    ratios = {m: effort.estimate_ratio(m, "feature", 1) for m in (11, 12, 13)}
    weights = share_weights(tasks, [11, 12, 13], 1, "feature", 1, MON)
    assert total == pytest.approx(sum(10.0 * weights[m] * ratios[m] for m in weights), abs=1e-6)
    assert set(out.hours["week_start"]) <= {F1, F2}
    assert {i["member_id"] for i in out.items} == {11, 12, 13}
    assert out.backlog == [{"project_id": 1, "tasks": 1, "estimated_hours": 10.0,
                            "hours_in_window": pytest.approx(float(out.hours["hours"].sum())),
                            "hours_after_window": pytest.approx(sum(out.after_window.values()))}]


def test_allocate_is_empty_when_disabled_or_without_planned_tasks() -> None:
    effort = EffortModel().fit(_history())
    for tasks, enabled in ((_history(), True), (pd.concat([_history(), pd.DataFrame([_planned(90, 1, "feature", 4)])]), False)):
        out = allocate(tasks, TEAM, 1, MON, (F1, F2), effort, off_by_member={}, enabled=enabled)
        assert out.hours.empty and out.items == [] and out.after_window == {} and out.backlog == []


def test_a_member_gone_for_the_whole_window_gets_no_share() -> None:
    team = TEAM.copy(); team.loc[team["id"] == 13, "active_to"] = F1 - dt.timedelta(days=1)
    tasks = pd.concat([_history(), pd.DataFrame([_planned(90, None, "support", 4)])], ignore_index=True)
    effort = EffortModel().fit(_history())
    out = allocate(tasks, team, 1, MON, (F1, F2), effort, off_by_member={})
    assert 13 not in {i["member_id"] for i in out.items}
    assert abs(sum(i["share"] for i in out.items) - 1.0) < 1e-6


@settings(max_examples=60, deadline=None)
@given(
    counts=st.lists(st.tuples(st.sampled_from([11, 12, 13]), st.sampled_from(["feature", "bug"])), max_size=40),
    est=st.floats(0.5, 40.0),
    lag=st.integers(0, 30),
)
def test_weights_sum_to_one_and_hours_are_conserved(counts, est, lag) -> None:
    rows = [_assigned(i + 1, m, 1, t, 10 + i % 7) for i, (m, t) in enumerate(counts)]
    tasks = pd.DataFrame(rows + [{**_planned(999, 1, "feature", lag, hours=est)}], columns=list(_planned(0, 1, "feature", 0)))
    w = share_weights(tasks, [11, 12, 13], 1, "feature", 1, MON)
    assert all(v >= 0 for v in w.values()) and abs(sum(w.values()) - 1.0) < 1e-9
    effort = EffortModel().fit(tasks.dropna(subset=["actual_hours", "completed_at"]))
    out = allocate(tasks, TEAM, 1, MON, (F1, F2), effort, off_by_member={11: {F1}, 12: set(), 13: set()})
    placed = float(out.hours["hours"].sum()) + sum(out.after_window.values())
    expected = sum(est * w[m] * effort.estimate_ratio(m, "feature", 1) for m in w)
    assert placed == pytest.approx(expected, rel=1e-9, abs=1e-9)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run pytest tests/test_planned.py -v`
Expected: `ImportError` on `PlannedAllocation`.

- [ ] **Step 3: Implement**

```python
# service/src/whf/planned.py (whole file; keeps BACKLOG_LAG_DAYS and is_backlog_arrival from Task 4)
"""Planned work: tasks that exist before they are assigned, who is likely to get them, and when."""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from whf.calendar import ONE_WEEK, week_start, working_days
from whf.models.effort import EffortModel, place_hours

BACKLOG_LAG_DAYS = 2
SHARE_WINDOW_WEEKS = 26
SHRINK_K = 3.0
PLANNED_WORK_ENABLED = True
MIN_LAG_ROWS = 5


def is_backlog_arrival(created_at: dt.date | None, assigned_at: dt.date | None) -> bool:
    if created_at is None or assigned_at is None:
        return False
    return (assigned_at - created_at).days >= BACKLOG_LAG_DAYS


@dataclass
class PlannedAllocation:
    hours: pd.DataFrame
    items: list[dict] = field(default_factory=list)
    after_window: dict[int, float] = field(default_factory=dict)
    backlog: list[dict] = field(default_factory=list)


def _empty() -> PlannedAllocation:
    return PlannedAllocation(hours=pd.DataFrame(columns=["member_id", "week_start", "hours"]))


def candidate_tasks(tasks: pd.DataFrame, team_id: int) -> pd.DataFrame:
    if tasks.empty:
        return tasks
    mask = tasks["assignee_id"].isna() & (tasks["team_id"] == team_id) & (tasks["status"] != "done")
    return tasks[mask].sort_values("id").reset_index(drop=True)


def _recent_assigned(tasks: pd.DataFrame, as_of: dt.date) -> pd.DataFrame:
    if tasks.empty:
        return tasks
    since = week_start(as_of) - SHARE_WINDOW_WEEKS * ONE_WEEK
    return tasks[tasks["assignee_id"].notna() & (tasks["assigned_at"] >= since)]


def _shares(rows: pd.DataFrame, member_ids: list[int]) -> tuple[dict[int, int], int]:
    counts = rows["assignee_id"].astype(int).value_counts() if len(rows) else pd.Series(dtype=int)
    return {m: int(counts.get(m, 0)) for m in member_ids}, int(sum(counts.get(m, 0) for m in member_ids))


def _shrink(counts: dict[int, int], total: int, prior: dict[int, float]) -> dict[int, float]:
    return {m: (counts[m] + SHRINK_K * prior[m]) / (total + SHRINK_K) for m in prior}


def share_weights(
    tasks: pd.DataFrame, member_ids: list[int], project_id: int | None, task_type: str, team_id: int, as_of: dt.date
) -> dict[int, float]:
    """Level 1 (project, type) shrunk towards level 2 (project), towards level 3 (team, type), towards equal."""
    if not member_ids:
        return {}
    recent = _recent_assigned(tasks, as_of)
    recent = recent[recent["assignee_id"].isin(member_ids)] if len(recent) else recent
    equal = {m: 1.0 / len(member_ids) for m in member_ids}
    team_type = recent[(recent["team_id"] == team_id) & (recent["type"] == task_type)] if len(recent) else recent
    level3 = _shrink(*_shares(team_type, member_ids), equal)
    if project_id is None or pd.isna(project_id):
        return _normalise(level3)
    project = recent[recent["project_id"] == project_id] if len(recent) else recent
    level2 = _shrink(*_shares(project, member_ids), level3)
    level1 = _shrink(*_shares(project[project["type"] == task_type] if len(project) else project, member_ids), level2)
    return _normalise(level1)


def _normalise(weights: dict[int, float]) -> dict[int, float]:
    total = sum(weights.values())
    return {m: (w / total if total > 0 else 1.0 / len(weights)) for m, w in weights.items()}


def assignment_lag_days(tasks: pd.DataFrame, project_id: int | None, team_id: int) -> float:
    assigned = tasks[tasks["assignee_id"].notna()] if len(tasks) else tasks
    if len(assigned):
        lags = pd.Series([(a - c).days for a, c in zip(assigned["assigned_at"], assigned["created_at"], strict=True)],
                         index=assigned.index, dtype=float)
        if project_id is not None and not pd.isna(project_id):
            mine = lags[assigned["project_id"] == project_id]
            if len(mine) >= MIN_LAG_ROWS:
                return float(np.median(mine))
        team = lags[assigned["team_id"] == team_id]
        if len(team) >= MIN_LAG_ROWS:
            return float(np.median(team))
        if len(lags) >= MIN_LAG_ROWS:
            return float(np.median(lags))
    return float(BACKLOG_LAG_DAYS)


def _present(member: pd.Series, f1: dt.date, f2: dt.date, off: set[dt.date]) -> bool:
    active_to = member.get("active_to")
    if active_to is not None and not pd.isna(active_to) and active_to < f1:
        return False
    return bool(working_days(f1, f2 + dt.timedelta(days=6), off))


def allocate(
    tasks: pd.DataFrame,
    team_members: pd.DataFrame,
    team_id: int,
    as_of: dt.date,
    weeks: tuple[dt.date, dt.date],
    effort: EffortModel,
    off_by_member: dict[int, set[dt.date]],
    *,
    enabled: bool = PLANNED_WORK_ENABLED,
) -> PlannedAllocation:
    f1, f2 = weeks
    if not enabled:
        return _empty()
    candidates = candidate_tasks(tasks, team_id)
    if candidates.empty:
        return _empty()
    members = team_members[[_present(m, f1, f2, off_by_member.get(int(m["id"]), set())) for _, m in team_members.iterrows()]]
    member_ids = [int(m) for m in members["id"]]
    if not member_ids:
        return _empty()
    rows: list[dict] = []
    items: list[dict] = []
    after: dict[int, float] = {}
    per_project: dict[int | None, dict] = {}
    for t in candidates.itertuples(index=False):
        project = None if pd.isna(t.project_id) else int(t.project_id)
        weights = share_weights(tasks, member_ids, project, str(t.type), team_id, as_of)
        lag = assignment_lag_days(tasks, project, team_id)
        expected = max(f1, t.created_at + dt.timedelta(days=int(round(lag))))
        summary = per_project.setdefault(project, {"project_id": project, "tasks": 0, "estimated_hours": 0.0,
                                                    "hours_in_window": 0.0, "hours_after_window": 0.0})
        summary["tasks"] += 1
        summary["estimated_hours"] += float(t.estimated_hours)
        for m, w in weights.items():
            if w <= 0:
                continue
            hours = float(t.estimated_hours) * w * effort.estimate_ratio(m, str(t.type), team_id)
            span = int(round(effort.member_cycle_days(m, team_id)))
            end = expected + dt.timedelta(days=max(span - 1, 0))
            placed = place_hours(hours, expected, end, off_by_member.get(m, set()))
            in_window = 0.0
            for ws, h in placed.items():
                if ws <= f2:
                    rows.append({"member_id": m, "week_start": ws, "hours": h})
                    in_window += h
                else:
                    after[m] = after.get(m, 0.0) + h
            summary["hours_in_window"] += in_window
            summary["hours_after_window"] += hours - in_window
            first_week = min((ws for ws in placed if ws <= f2), default=None)
            items.append({
                "task_id": int(t.id), "title": t.title, "project_id": project, "type": str(t.type),
                "estimated_hours": float(t.estimated_hours), "member_id": m, "share": round(w, 2),
                "expected_assignment": expected, "expected_week": first_week if first_week is not None else "after_window",
            })
    hours = pd.DataFrame(rows, columns=["member_id", "week_start", "hours"])
    if len(hours):
        hours = hours.groupby(["member_id", "week_start"], as_index=False)["hours"].sum()
    return PlannedAllocation(hours=hours, items=items, after_window=after, backlog=list(per_project.values()))
```

`place_hours` with a start week later than `f2` returns only later weeks, which land in `after_window`; a start before `f1` cannot happen because `expected` is clamped. The `status` column of the history frame in tests is `"done"`, which `candidate_tasks` ignores because those rows have an assignee.

- [ ] **Step 4: Run the tests**

Run: `uv run pytest tests/test_planned.py -v`
Expected: PASS (8 tests including the property). If the numeric bound in `test_share_weights_follow_project_and_type_history_with_shrinkage` fails, print the three levels and check the shrinkage direction before touching the bound: with the fixture, level 3 for member 11 is `(6 + 3/3) / (8 + 3) = 0.636`, level 2 `(6 + 3 * 0.636) / (8 + 3) = 0.719`, level 1 the same counts again, `0.741`, normalised with the other two members.

- [ ] **Step 5: Gate and commit**

```bash
uv run ruff check . && uv run ruff format --check . && uv run ty check && uv run pytest -q
git add src/whf/planned.py tests/test_planned.py
git commit -m "feat(planned): allocate planned tasks to members by shrunken shares and place them by lag and cycle"
```

---

### Task 6: Planned hours in the pipeline, the facts, the CLI and the app table [gate B]

**Files:**
- Modify: `service/src/whf/pipeline.py` (`run_forecast` after `new_placed`, the `rows` loop, `_build_facts`), `service/src/whf/db/schema.sql` (forecasts table), `service/src/whf/db/connection.py` (migration step for the new column), `service/src/whf/cli.py` (`run` output), `app/src/shared/types.ts`, `app/src/renderer/src/components/WeekTable.tsx`, `app/src/renderer/src/i18n.ts`
- Test: `service/tests/test_pipeline.py`, `app/src/renderer/src/__tests__/WeekTable.test.tsx` (create if absent), existing `TeamResult.test.tsx` fixtures

**Interfaces:**
- Produces: forecast column `planned_task_hours` (float, 0.0 when inactive), `demand_hours` = sum of the three; facts: per member per week `planned_hours`; per member `planned: list[items]` (from `PlannedAllocation.items` filtered to that member, with `expected_assignment` as ISO date); per team `planned_backlog: list` (`PlannedAllocation.backlog` with `project_name` added); `model.planned_basis = "share weights, 26-week window, shrink k=3"` when active else `null`; `model.limitations` extended with `"; planned tasks created and assigned inside the window are not modelled"` when active. `ForecastRow.planned_task_hours: number` in the app.

- [ ] **Step 1: Write the failing service tests**

```python
# service/tests/test_pipeline.py (append)
import datetime as dt

from whf.data.generator import GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect
from whf.pipeline import run_forecast


def test_planned_hours_are_a_third_demand_component() -> None:
    data = generate(GeneratorConfig(seed=7, months=6, backlog_share=0.5))
    conn = connect(":memory:")
    load_generated(conn, data)
    team = next(t["team_id"] for t in data.tasks if t["assignee_id"] is None)
    result = run_forecast(conn, team_id=team, as_of=data.config.as_of, persist=False)
    f = result.forecasts
    assert "planned_task_hours" in f.columns and float(f["planned_task_hours"].sum()) > 0
    assert (f["demand_hours"] - (f["open_task_hours"] + f["new_task_hours"] + f["planned_task_hours"])).abs().max() < 0.011
    assert (f["demand_low"] >= f["open_task_hours"] + f["planned_task_hours"] - 0.011).all()
    member = next(m for m in result.facts["members"] if m["planned"])
    assert {"task_id", "title", "project_id", "type", "estimated_hours", "share", "expected_assignment", "expected_week"} <= set(member["planned"][0])
    assert member["forecast"][0]["planned_hours"] >= 0
    assert result.facts["team"]["planned_backlog"] and "project_name" in result.facts["team"]["planned_backlog"][0]
    assert result.facts["model"]["planned_basis"] == "share weights, 26-week window, shrink k=3"
    assert "inside the window" in result.facts["model"]["limitations"]


def test_without_planned_tasks_the_forecast_is_unchanged(db) -> None:
    result = run_forecast(db, team_id=1, as_of=dt.date(2026, 9, 3), persist=False)
    f = result.forecasts
    assert float(f["planned_task_hours"].abs().sum()) == 0.0
    assert result.facts["model"]["planned_basis"] is None
    assert all(m["planned"] == [] for m in result.facts["members"])
    assert result.facts["team"]["planned_backlog"] == []
```

- [ ] **Step 2: Run them to verify they fail**

Run: `uv run pytest tests/test_pipeline.py -v -k planned`
Expected: `KeyError: 'planned_task_hours'` and `KeyError: 'planned'`.

- [ ] **Step 3: Implement the pipeline change**

In `run_forecast`, after `new_placed = place_new_arrivals(...)`:

```python
    planned = allocate(tasks, team_members, team_id, as_of, (f1, f2), effort, off_by_member)
```

(import `allocate` from `whf.planned`). In the `rows` loop, beside `new_hours`:

```python
            planned_hours = (
                float(planned.hours[(planned.hours.member_id == m) & (planned.hours.week_start == week)]["hours"].sum())
                if len(planned.hours)
                else 0.0
            )
            open_hours, new_hours, planned_hours = round(open_hours, 2), round(new_hours, 2), round(planned_hours, 2)
            demand = round(open_hours + new_hours + planned_hours, 2)
            low, high = offsets[(m, h)]
            demand_low = min(demand, open_hours + planned_hours + max(0.0, new_hours + low * ratio))
            demand_high = max(demand, open_hours + planned_hours + new_hours + high * ratio)
```

and add `"planned_task_hours": planned_hours` to the row dict. Pass `planned` into `_build_facts` (new parameter after `unavailable`), and there: in each member's `forecast` entries add `"planned_hours": r.planned_task_hours`; add `"planned": [{**i, "expected_assignment": i["expected_assignment"]} for i in planned.items if i["member_id"] == mid]` (the `jsonable` call on persist turns dates into ISO strings; `expected_week` is a date or the string `"after_window"`); on the team dict add `"planned_backlog": [{**b, "project_name": project_names.get(b["project_id"])} for b in planned.backlog]` with `project_names = dict(zip(frames["projects"]["id"].astype(int), frames["projects"]["name"], strict=True))`; on the model dict add `"planned_basis": "share weights, 26-week window, shrink k=3" if planned.items else None` and extend `limitations` with `"; planned tasks created and assigned inside the window are not modelled"` when `planned.items`.

Schema: add `planned_task_hours REAL NOT NULL DEFAULT 0` to the `forecasts` table in `schema.sql`, and in `connection._migrate` add, for files whose `forecasts` table lacks the column (`PRAGMA table_info('forecasts')`), `ALTER TABLE forecasts ADD COLUMN planned_task_hours REAL NOT NULL DEFAULT 0`. Bump `SCHEMA_VERSION` to 3 and make the version-2 step of Task 2 run only when the notnull flag is set, as it already does, so both steps are independent and idempotent. Extend `test_connect_migrates_a_version_one_database_and_keeps_its_rows` to assert the column exists afterwards.

CLI: in `whf run`'s per-row echo add `planned {row.planned_task_hours:5.1f}h` between demand and capacity.

- [ ] **Step 4: Run the service tests and the suite**

Run: `uv run pytest tests/test_pipeline.py tests/test_db.py tests/test_cli.py -q` then `uv run pytest -q`.
Expected: green. `test_api.py` compares forecast row keys in one place; add the new key there.

- [ ] **Step 5: App: type, table column, i18n, tests**

`types.ts`: add `planned_task_hours: number` to `ForecastRow`. `WeekTable.tsx`: when `rows.some((r) => weeks.some((w) => (r.cells[w]?.planned_task_hours ?? 0) > 0))`, render an extra header cell `t('team.planned')` and, per week, a cell with `hours(c.planned_task_hours)`, before the capacity cell (`colSpan` becomes 4 in that case). i18n: `'team.planned': 'Planned'` and `'team.planned': 'Planifié'`. Fixtures: add `planned_task_hours: 0` to every `ForecastRow` literal in `app/src/renderer/src/test/fixtures.ts` and the tests that build rows inline (grep `new_task_hours`).

Test (`WeekTable.test.tsx`, create beside the other component tests):

```tsx
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { WeekTable } from '../components/WeekTable'

const row = (planned: number) => ({
  run_id: 1, member_id: 11, week_start: '2026-09-14', demand_hours: 30, demand_low: 25, demand_high: 35,
  capacity_hours: 44, overload_hours: 0, open_task_hours: 20, new_task_hours: 10 - planned, planned_task_hours: planned,
})

it('shows the planned column only when some row has planned hours', () => {
  const { rerender } = render(<MemoryRouter><WeekTable weeks={['2026-09-14']} rows={[{ member_id: 11, name: 'Ahmed', cells: { '2026-09-14': row(0) } }]} /></MemoryRouter>)
  expect(screen.queryByText('Planned')).not.toBeInTheDocument()
  rerender(<MemoryRouter><WeekTable weeks={['2026-09-14']} rows={[{ member_id: 11, name: 'Ahmed', cells: { '2026-09-14': row(4) } }]} /></MemoryRouter>)
  expect(screen.getByText('Planned')).toBeInTheDocument()
  expect(screen.getByText('4.0 h')).toBeInTheDocument()
})
```

(`hours()` formats as in `format.ts`; check its output for `4` and adjust the literal.)

- [ ] **Step 6: Gate both halves and commit**

```bash
# service/
uv run ruff check . && uv run ruff format --check . && uv run ty check && uv run pytest -q
# app/
npm run lint && npm run typecheck && npm test
git add service/src/whf/pipeline.py service/src/whf/db/schema.sql service/src/whf/db/connection.py service/src/whf/cli.py service/tests/test_pipeline.py service/tests/test_db.py service/tests/test_api.py app/src/shared/types.ts app/src/renderer/src/components/WeekTable.tsx app/src/renderer/src/i18n.ts app/src/renderer/src/test/fixtures.ts app/src/renderer/src/__tests__/WeekTable.test.tsx
git commit -m "feat: planned hours join open and new hours in demand, the facts and the week table"
```

---

### Task 7: Harness: backlog reconstruction, `--no-planned`, "Planned work" section [reconstruction: reduced set; the rest: gate B]

**Files:**
- Modify: `service/src/whf/eval/truth.py` (`truncated_copy`), `service/src/whf/eval/harness.py` (`EvalConfig`, `demand_level`, `evaluate`), `service/src/whf/eval/report.py`, `service/src/whf/pipeline.py` (`run_forecast` gains `planned: bool = True` keyword), `service/src/whf/cli.py` (`eval`)
- Test: `service/tests/test_eval_truth.py`, `service/tests/test_eval_harness.py`, `service/tests/test_eval_report.py`

**Interfaces:**
- `truncated_copy(conn, as_of)`: tasks created after `as_of` absent; tasks created on or before and assigned after: `assignee_id`, `assigned_at`, `completed_at`, `actual_hours` null and status `todo`.
- `EvalConfig.planned: bool = True`; `run_forecast(..., planned=True)` passes `enabled=planned` to `allocate`; `demand_level(..., planned: bool)`; `EvalResult.demand` gains column `planned_hours` and `EvalResult.demand_without_planned: pd.DataFrame | None` (the counterfactual replay, run only when `config.planned` is true and the data has any planned task or backlog arrival); `EvalResult.backlog_profile: dict` (`share_lagged`, `lag_median_days` from `profile_tasks`).
- Summary: section "## Planned work" with the profile numbers and a two-row table `with planned` / `without planned` over `demand_mae`, `coverage80`, `overload_precision`, `overload_recall`; Level A caption states the series is `fresh_hours`.

- [ ] **Step 1: Write the failing tests**

```python
# service/tests/test_eval_truth.py (append)
import datetime as dt

from whf.data.generator import GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect
from whf.db.repo import read_df
from whf.eval.truth import truncated_copy


def test_truncated_copy_reconstructs_the_backlog_at_the_origin() -> None:
    data = generate(GeneratorConfig(seed=7, months=4, backlog_share=0.5))
    conn = connect(":memory:")
    load_generated(conn, data)
    cut = data.config.as_of - dt.timedelta(days=28)
    replay = truncated_copy(conn, cut)
    rows = read_df(replay, "SELECT id, assignee_id, assigned_at, status, created_at FROM tasks")
    assert (rows["created_at"].astype(str).str[:10] <= cut.isoformat()).all()
    reconstructed = rows[rows["assignee_id"].isna()]
    assert len(reconstructed) > 0 and (reconstructed["status"] == "todo").all()
    original = {t["id"]: t for t in data.tasks}
    for tid in reconstructed["id"]:
        t = original[int(tid)]
        assert t["assigned_at"] is None or t["assigned_at"] > cut
```

```python
# service/tests/test_eval_harness.py (append)
from whf.eval.harness import EvalConfig, evaluate
from whf.models import MODEL_FACTORIES


def test_evaluate_reports_demand_with_and_without_planned_work(tmp_path) -> None:
    from whf.data.generator import GeneratorConfig, generate
    from whf.data.loader import load_generated
    from whf.db.connection import connect

    data = generate(GeneratorConfig(seed=7, months=6, backlog_share=0.5))
    conn = connect(":memory:")
    load_generated(conn, data)
    factories = {"seasonal_naive": MODEL_FACTORIES["seasonal_naive"]}
    result = evaluate(conn, EvalConfig(as_of=data.config.as_of, origins=2, teams=(1,)), factories=factories)
    assert "planned_hours" in result.demand.columns
    assert result.demand_without_planned is not None
    assert float(result.demand["planned_hours"].sum()) > 0
    assert float(result.demand_without_planned["planned_hours"].sum()) == 0.0
    assert result.backlog_profile["share_lagged"] > 0


def test_evaluate_skips_the_counterfactual_when_nothing_is_planned(db) -> None:
    import datetime as dt
    factories = {"seasonal_naive": MODEL_FACTORIES["seasonal_naive"]}
    result = evaluate(db, EvalConfig(as_of=dt.date(2026, 9, 3), origins=1, teams=(1,)), factories=factories)
    assert result.demand_without_planned is None
```

```python
# service/tests/test_eval_report.py (append)
def test_summary_has_a_planned_work_section(tmp_path, eval_result_with_planned) -> None:
    ...
```

Write this last test against whatever fixture pattern `test_eval_report.py` already uses to build an `EvalResult` (read the file); assert `"## Planned work" in summary`, that both `with planned` and `without planned` rows appear, and that the Level A caption contains `fresh_hours`.

- [ ] **Step 2: Run them to verify they fail**

Run: `uv run pytest tests/test_eval_truth.py tests/test_eval_harness.py tests/test_eval_report.py -v -k planned`
Expected: the truth test fails on the reconstruction (later-assigned tasks are dropped today), the harness tests on the missing attribute.

- [ ] **Step 3: Implement**

`truncated_copy`: replace the tasks query with `SELECT * FROM tasks WHERE substr(created_at, 1, 10) <= ?`, then:

```python
    later_assigned = tasks["assigned_at"].isna() | (tasks["assigned_at"].astype(str).str[:10] > cutoff)
    tasks.loc[later_assigned, ["assignee_id", "assigned_at", "completed_at", "actual_hours"]] = None
    tasks.loc[later_assigned, "status"] = "todo"
    later_done = tasks["completed_at"].notna() & (tasks["completed_at"].astype(str).str[:10] > cutoff)
    tasks.loc[later_done, ["completed_at", "actual_hours"]] = None
    tasks.loc[later_done, "status"] = "in_progress"
```

`assignee_id` must stay an integer-or-null column when inserted; convert with `tasks["assignee_id"] = tasks["assignee_id"].astype("Int64")` before `to_dict`, and make sure `insert_rows` writes `pd.NA` as null (extend `_to_sql` in `repo.py` if it does not).

`run_forecast`: add keyword `planned: bool = True` and pass `enabled=planned` to `allocate`. `demand_level`: add parameter `planned: bool` forwarded to `run_forecast`, and add `"planned_hours": float(r.planned_task_hours)` to each row; `DEMAND_COLUMNS` gains `planned_hours`. `evaluate`: compute `backlog_profile = profile_tasks(conn, BACKLOG_LAG_DAYS)`; run `demand_level(..., planned=config.planned)`; if `config.planned` and (`backlog_profile["planned"] > 0` or `backlog_profile["share_lagged"] > 0`), run it again with `planned=False` into `demand_without_planned`, else `None`. `EvalConfig.planned: bool = True`. CLI `eval`: `--no-planned` sets `planned=False` (Typer boolean option `planned: bool = True` with `"--planned/--no-planned"`).

`report.py`: `summary_tables` already computes level B from `result.demand`; factor its per-model aggregation into `_level_b(demand)` and build the planned-work table as `pd.DataFrame([{"variant": "with planned", **_level_b(result.demand).mean(numeric_only=True)}, {"variant": "without planned", **_level_b(result.demand_without_planned).mean(numeric_only=True)}])` when the counterfactual exists. In `write_outputs` insert after Level B:

```python
        "## Planned work",
        "",
        f"Backlog arrivals in the data: {result.backlog_profile.get('share_lagged', 0.0):.1%}; median assignment lag "
        f"{result.backlog_profile.get('lag_median_days')} days; planned tasks at the latest date: {result.backlog_profile.get('planned', 0)}.",
        "",
        _markdown(planned_table) if planned_table is not None else "No planned work in this data; the demand replay ran once.",
        "",
```

and change `LEVEL_A_CAPTION` to state that the arrival series is `fresh_hours` (tasks assigned within one day of creation). Record the decision rule of the spec in `REPLAY_ASSUMPTIONS`: "planned work stays on if demand MAE improves and coverage drops by at most five points".

- [ ] **Step 4: Run the tests and the suite, then the smoke A/B**

Run: `uv run pytest tests/test_eval_truth.py tests/test_eval_harness.py tests/test_eval_report.py -q`, then `uv run pytest -q`, then, as a smoke test on generated data:

```bash
uv run whf data generate --db /tmp/planned.sqlite --seed 7 --months 12 --answer-key /tmp/planned-key.json  # add a --backlog-share option to `data generate` (default 0) in this task and pass 0.35 here
uv run whf eval --db /tmp/planned.sqlite --origins 3 --models seasonal_naive,gbm --answer-key /tmp/planned-key.json --out /tmp/planned-eval
```

Expected: `summary.md` has the "Planned work" section with both rows. Commit the summary under `docs/eval/2026-XX-XX-generated-planned/` only if it is the first run with planned work; the decision run is the one on real data.

- [ ] **Step 5: Commit**

```bash
git add src/whf/eval/truth.py src/whf/eval/harness.py src/whf/eval/report.py src/whf/pipeline.py src/whf/cli.py src/whf/db/repo.py tests/test_eval_truth.py tests/test_eval_harness.py tests/test_eval_report.py
git commit -m "feat(eval): rebuild the backlog at each origin and score demand with and without planned work"
```

---

### Task 8: Likely-work facts, tools, contract and product skill [always]

**Files:**
- Modify: `service/src/whf/pipeline.py` (`_build_facts`), `service/src/whf/ai/facts_tools.py`, `service/src/whf/ai/prompt.py`, `service/src/whf/ai/schema.py`, `service/src/whf/ai/skills/whf-forecast-interpretation/SKILL.md`, `service/src/whf/ai/skills/whf-domain/SKILL.md`
- Create: `service/src/whf/ai/skills/whf-likely-work/SKILL.md`, `service/src/whf/likely.py`
- Test: `service/tests/test_likely.py`, `service/tests/test_ai_tools.py`, `service/tests/test_ai_schema.py`, `service/tests/test_ai_verify.py`, `service/tests/test_ai_prompt_skills.py`

**Interfaces:**
- `likely.member_likely_work(tasks, projects, project_teams, member_id, team_id, as_of, weeks, planned_items) -> dict` with keys `planned` (the member's items from Task 6, `[]` when Task 6 is not built: pass `[]`), `project_roles`, `similar_projects`, `recent_mix`.
- Facts: `members[i]["likely_work"]` and `team["planned_backlog"]` (already from Task 6; when Task 6 is absent, `_build_facts` sets `"planned_backlog": []`).
- Tools: `get_member_likely_work(member_id)` returning `{"member_id", "name", "likely_work"}`; `get_planned_backlog()` returning `{"weeks", "planned_backlog", "planned_basis"}`.
- Schema: `LikelyWork(statement: str[1..300], evidence: str[1..300], confidence: Literal["low","medium","high"])`; `MemberNarrative.likely_work: list[LikelyWork] = []` with `max_length=4`.
- Prompt: procedure step 2 adds `get_member_likely_work`; step 3 becomes `get_project_timelines, get_planned_backlog`. `PRODUCT_SKILLS` gains `"whf-likely-work"`.

- [ ] **Step 1: Write the failing tests**

```python
# service/tests/test_likely.py
import datetime as dt

import pandas as pd

from whf.likely import member_likely_work

MON = dt.date(2026, 9, 7)
WEEKS = (dt.date(2026, 9, 14), dt.date(2026, 9, 21))
PROJECTS = pd.DataFrame([
    {"id": 1, "name": "Nova", "type": "build", "start_date": dt.date(2026, 3, 2), "deadline": dt.date(2026, 9, 25), "status": "active", "department_id": 1},
    {"id": 2, "name": "Atlas", "type": "build", "start_date": dt.date(2026, 9, 14), "deadline": dt.date(2027, 1, 29), "status": "planned", "department_id": 1},
    {"id": 3, "name": "Old", "type": "build", "start_date": dt.date(2025, 9, 1), "deadline": dt.date(2026, 1, 30), "status": "closed", "department_id": 1},
])
LINKS = pd.DataFrame([{"project_id": p, "team_id": 1} for p in (1, 2, 3)])


def _task(i, member, project, ttype, days_ago):
    d = MON - dt.timedelta(days=days_ago)
    return {"id": i, "assignee_id": member, "team_id": 1, "project_id": project, "type": ttype, "assigned_at": d,
            "created_at": d, "estimated_hours": 8.0, "status": "done", "completed_at": d, "actual_hours": 8.0}


def _tasks():
    rows = [_task(i, 11, 1, "feature", 20) for i in range(1, 7)] + [_task(i, 12, 1, "feature", 20) for i in range(7, 9)]
    rows += [_task(i, 11, 3, "feature", 300) for i in range(9, 12)] + [_task(12, 12, 3, "bug", 300)]
    rows += [_task(13, 11, None, "support", 5)]
    return pd.DataFrame(rows)


def test_project_roles_cover_projects_in_the_window_with_shares_and_types() -> None:
    out = member_likely_work(_tasks(), PROJECTS, LINKS, 11, 1, MON, WEEKS, planned_items=[])
    roles = {r["project_id"]: r for r in out["project_roles"]}
    assert set(roles) == {1, 2}
    assert roles[1]["phase"] == "ending" and roles[1]["share"] == 0.75 and roles[1]["tasks"] == 6
    assert roles[1]["dominant_types"] == [{"type": "feature", "tasks": 6}]
    assert roles[2]["phase"] == "starting" and roles[2]["share"] == 0.0 and roles[2]["tasks"] == 0


def test_similar_projects_use_the_whole_history_of_the_same_type() -> None:
    out = member_likely_work(_tasks(), PROJECTS, LINKS, 11, 1, MON, WEEKS, planned_items=[])
    sim = {s["project_id"]: s for s in out["similar_projects"]}
    assert set(sim) == {2}
    assert sim[2]["same_type_projects"] == 2 and sim[2]["share"] == 0.75  # 9 of 12 tasks on Nova and Old
    assert sim[2]["dominant_types"][0]["type"] == "feature"


def test_recent_mix_and_planned_passthrough() -> None:
    items = [{"task_id": 90, "member_id": 11, "share": 0.6}, {"task_id": 90, "member_id": 12, "share": 0.4}]
    out = member_likely_work(_tasks(), PROJECTS, LINKS, 11, 1, MON, WEEKS, planned_items=items)
    assert out["planned"] == [items[0]]
    assert out["recent_mix"] == [{"type": "feature", "tasks": 6}, {"type": "support", "tasks": 1}]
```

Add to `test_ai_tools.py`: the toolbox exposes `get_member_likely_work` and `get_planned_backlog`, the first returns the member's `likely_work`, the second the team's `planned_backlog` with the weeks. Add to `test_ai_schema.py`: a narrative with five `likely_work` items is rejected, with an unknown confidence is rejected, without the field is accepted. Add to `test_ai_verify.py`: a `likely_work.statement` containing `"about 12 hours"` when no fact carries 12 is flagged in `report.fields`. Add to `test_ai_prompt_skills.py`: the prompt names both tools and `skill_directories()` includes `whf-likely-work`. Read each file's existing tests first and follow their shape.

- [ ] **Step 2: Run them to verify they fail**

Run: `uv run pytest tests/test_likely.py tests/test_ai_tools.py tests/test_ai_schema.py tests/test_ai_verify.py tests/test_ai_prompt_skills.py -q`
Expected: `ModuleNotFoundError: whf.likely` and assertion failures on the missing tools and field.

- [ ] **Step 3: Implement `whf/likely.py`**

```python
"""What is likely to land on a member: facts only, for Copilot to phrase."""

from __future__ import annotations

import datetime as dt

import pandas as pd

from whf.calendar import ONE_WEEK, week_start

ROLE_WINDOW_WEEKS = 26
MIX_WINDOW_WEEKS = 13
MAX_TYPES = 3


def _dominant_types(rows: pd.DataFrame) -> list[dict]:
    counts = rows["type"].value_counts()
    return [{"type": str(t), "tasks": int(n)} for t, n in counts.head(MAX_TYPES).items()]


def _phase(p, f1: dt.date, f2: dt.date) -> str:
    end = f2 + dt.timedelta(days=6)
    if f1 <= p.start_date <= end:
        return "starting"
    if f1 <= p.deadline <= end:
        return "ending"
    return "active"


def member_likely_work(
    tasks: pd.DataFrame, projects: pd.DataFrame, project_teams: pd.DataFrame, member_id: int, team_id: int,
    as_of: dt.date, weeks: tuple[dt.date, dt.date], planned_items: list[dict],
) -> dict:
    f1, f2 = weeks
    end = f2 + dt.timedelta(days=6)
    assigned = tasks[tasks["assignee_id"].notna()] if len(tasks) else tasks
    since = week_start(as_of) - ROLE_WINDOW_WEEKS * ONE_WEEK
    recent = assigned[(assigned["assigned_at"] >= since) & (assigned["team_id"] == team_id)] if len(assigned) else assigned
    linked = set(project_teams[project_teams["team_id"] == team_id]["project_id"].astype(int))
    in_window = projects[projects["id"].isin(linked) & (projects["start_date"] <= end) & (projects["deadline"] >= f1)]
    roles = []
    for p in in_window.sort_values("start_date").itertuples():
        on_project = recent[recent["project_id"] == p.id] if len(recent) else recent
        mine = on_project[on_project["assignee_id"] == member_id] if len(on_project) else on_project
        roles.append({
            "project_id": int(p.id), "project_name": p.name, "phase": _phase(p, f1, f2),
            "tasks": int(len(mine)), "share": round(len(mine) / len(on_project), 2) if len(on_project) else 0.0,
            "dominant_types": _dominant_types(mine) if len(mine) else [],
        })
    similar = []
    for p in in_window[(in_window["start_date"] >= f1) & (in_window["start_date"] <= end)].itertuples():
        same_type = projects[(projects["type"] == p.type) & (projects["id"] != p.id)]
        on_type = assigned[assigned["project_id"].isin(same_type["id"]) & (assigned["team_id"] == team_id)] if len(assigned) else assigned
        mine = on_type[on_type["assignee_id"] == member_id] if len(on_type) else on_type
        similar.append({
            "project_id": int(p.id), "project_name": p.name, "project_type": p.type,
            "same_type_projects": int(len(same_type)), "tasks": int(len(mine)),
            "share": round(len(mine) / len(on_type), 2) if len(on_type) else 0.0,
            "dominant_types": _dominant_types(mine) if len(mine) else [],
        })
    mix_since = week_start(as_of) - MIX_WINDOW_WEEKS * ONE_WEEK
    mine_recent = assigned[(assigned["assignee_id"] == member_id) & (assigned["assigned_at"] >= mix_since)] if len(assigned) else assigned
    return {
        "planned": [i for i in planned_items if int(i["member_id"]) == member_id],
        "project_roles": roles,
        "similar_projects": similar,
        "recent_mix": [{"type": str(t), "tasks": int(n)} for t, n in mine_recent["type"].value_counts().items()] if len(mine_recent) else [],
    }
```

`_build_facts` calls it per member with `planned.items` (or `[]` when Task 6 is absent) and stores the result under `"likely_work"`. `facts_tools.py`: add `member_likely_work(member_id)` and `planned_backlog()` methods and register them with `member_tool("get_member_likely_work", "Planned tasks allocated to one member, their role on projects active or starting in the window, their role on past projects of the same type, and their recent task mix. No forecast numbers; use it to say what is likely to land.", box.member_likely_work)` and `plain_tool("get_planned_backlog", "Tasks that exist but are not assigned yet, per project, with the hours the allocation placed inside and after the window.", box.planned_backlog)`. Update `how_to_proceed` in `run_overview`. `schema.py`: add `LikelyWork` and the field. `prompt.py`: update the procedure sentence and `PRODUCT_SKILLS`.

- [ ] **Step 4: Write the product skill**

```markdown
---
name: whf-likely-work
description: How to say what work is likely to land on a member in the next two weeks, from planned tasks, project phases and the member's past role, without inventing any number.
---

# Likely work

- The `likely_work` facts are: `planned` (tasks that exist but are not assigned, with the share the allocation gave this member and the expected week), `project_roles` (this member's share and dominant task types on each project active, starting or ending in the window), `similar_projects` (their share and types on past projects of the same type as a project that starts in the window), `recent_mix` (their task types over the last 13 weeks).
- Write at most four items per member, each a `statement`, its `evidence` (name the fact: the planned task title, the project and share, the similar-project share) and a `confidence`.
- `high` only when a planned task is allocated to this member with a share of 0.5 or more; `medium` when a starting project matches a role they held on projects of the same type (share 0.3 or more), or a planned task is allocated with a smaller share; `low` for anything inferred from the recent mix alone.
- Say "likely" or "probably". Never "will".
- When `planned` is empty and no project starts, write one `low` item saying that no planned work is recorded and the expectation rests on the member's history, and stop.
- Never write an hours figure, a task count or a share that is not in these facts, and never add them up. The demand numbers live in `get_member_forecast`; do not repeat them here.
- The allocation is an expectation used by the forecast, not a decision: do not tell the leader to assign the task to this member.
```

Add one bullet to `whf-forecast-interpretation`: "`planned_hours` are backlog tasks the allocation expects to reach this member inside the window; they are known work with an uncertain owner, so they sit in demand, low and high alike." Add the section 3 vocabulary (planned task, assignment lag, fresh and backlog arrival, planned hours) to `whf-domain`.

- [ ] **Step 5: Run the tests and the suite**

Run: `uv run pytest tests/test_likely.py tests/test_ai_tools.py tests/test_ai_schema.py tests/test_ai_verify.py tests/test_ai_prompt_skills.py tests/test_ai_session.py -q` then `uv run pytest -q`.
Expected: green. The fake session's tool loop calls the first two tools; the new tools need no fake change.

- [ ] **Step 6: Commit**

```bash
git add src/whf/likely.py src/whf/pipeline.py src/whf/ai/facts_tools.py src/whf/ai/prompt.py src/whf/ai/schema.py src/whf/ai/skills/whf-likely-work/SKILL.md src/whf/ai/skills/whf-forecast-interpretation/SKILL.md src/whf/ai/skills/whf-domain/SKILL.md tests/test_likely.py tests/test_ai_tools.py tests/test_ai_schema.py tests/test_ai_verify.py tests/test_ai_prompt_skills.py
git commit -m "feat(ai): facts and a narrative section for what is likely to land on each member"
```

---

### Task 9: "Likely to land" in the app [always]

**Files:**
- Modify: `app/src/shared/types.ts`, `app/src/renderer/src/pages/TeamResult.tsx`, `app/src/renderer/src/pages/MemberDetail.tsx`, `app/src/renderer/src/i18n.ts`
- Test: `app/src/renderer/src/__tests__/TeamResult.test.tsx`, `MemberDetail.test.tsx`

**Interfaces:**
- `LikelyWork { statement: string; evidence: string; confidence: RiskLevel }`; `MemberNarrative.likely_work?: LikelyWork[]` (optional: older narratives lack it).
- i18n: `'team.likely': 'Likely to land'` / `'team.likely': 'Travail probable à venir'`; the confidence badge reuses `RiskBadge` with the existing low/medium/high labels.

- [ ] **Step 1: Write the failing tests**

In `TeamResult.test.tsx`, following the file's narrative fixture pattern: a stored narrative whose first member has `likely_work: [{ statement: 'Probably the Atlas API tasks.', evidence: 'Atlas starts 2026-09-14; share 0.75 on Nova', confidence: 'medium' }]` renders the heading "Likely to land", the statement and the evidence; a narrative without the field renders no such heading. In `MemberDetail.test.tsx`: the same statement appears on the member page. Then the French variant of the heading with `setLanguage('fr')`.

- [ ] **Step 2: Run to verify they fail**

Run from `app/`: `npx vitest run src/renderer/src/__tests__/TeamResult.test.tsx src/renderer/src/__tests__/MemberDetail.test.tsx`
Expected: "Likely to land" not found.

- [ ] **Step 3: Implement**

In `TeamResult.tsx`, after the warnings block inside `{narrative && (...)}`:

```tsx
            {narrative.members.some((m) => (m.likely_work ?? []).length > 0) && (
              <>
                <h3>{t('team.likely')}</h3>
                <ul>
                  {narrative.members.flatMap((m) => (m.likely_work ?? []).map((l, i) => (
                    <li key={`${m.member_id}-${i}`}><strong>{m.name}</strong>: {l.statement} <RiskBadge level={l.confidence} /> <span className="muted">({l.evidence})</span></li>
                  )))}
                </ul>
              </>
            )}
```

In `MemberDetail.tsx`, after the patterns list: the same list for `story?.likely_work ?? []` under an `<h2>{t('team.likely')}</h2>`, rendered only when non-empty. Import `RiskBadge` where missing.

- [ ] **Step 4: Run the app gate and commit**

```bash
npm run lint && npm run typecheck && npm test
git add app/src/shared/types.ts app/src/renderer/src/pages/TeamResult.tsx app/src/renderer/src/pages/MemberDetail.tsx app/src/renderer/src/i18n.ts app/src/renderer/src/__tests__/TeamResult.test.tsx app/src/renderer/src/__tests__/MemberDetail.test.tsx
git commit -m "feat(app): show what is likely to land on each member"
```

---

### Task 10: Documentation and the decision record [always]

**Files:**
- Modify: `CLAUDE.md` (read-first list and the `whf data profile` / `--no-planned` commands), `docs/backlog.md`, `.claude/skills/README.md` is untouched (product skills are indexed in `CLAUDE.md`), `docs/requirements/requirements-v1.md` (one line under the forecasting requirement naming the planned component)

- [ ] **Step 1: Update `CLAUDE.md`**

Add the spec to "Read these first"; under Toolchain commands add `uv run whf data profile` and `uv run whf eval --no-planned`; under Hard rules add: "The language model never produces a forecast number, and never will: decision 3 of the 2026-09-07 planned-work design is closed."

- [ ] **Step 2: Update `docs/backlog.md`**

One dated entry describing what landed, which gate applied, and a placeholder-free line for the harness decision: either the measured numbers and "planned work stays on" or "planned work left inactive because ..." with the numbers. Write the actual numbers from the real-data run; if the run has not happened when this task executes, the entry says "decision run pending, see `docs/eval/`" and the controller schedules the run.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md docs/backlog.md docs/requirements/requirements-v1.md
git commit -m "docs: planned work and likely work landed; decision 3 recorded as closed"
```

---

## Self-review

- **Spec coverage.** 4.1 schema and migration: Task 2. 4.2 generator: Task 3. 4.3 readiness report: Task 1. 5.1 candidates, 5.2 weights, 5.3 timing: Task 5. 5.4 split: Task 4. 5.5 demand, bands, facts: Task 6. 5.6 evaluation: Task 7. 6.1 facts, 6.2 contract, 6.3 tools, prompt, skill: Task 8. 6.4 app: Tasks 6 and 9. 8 gates: the task labels. 9 testing: each task's tests, the property tests in Tasks 4 and 5, the harness smoke in Task 7.
- **Placeholders.** Task 7's report test body is a reference to the file's fixture pattern rather than code, and Task 9's tests are described rather than written: both are deliberate, because the fixture helpers in those files will have moved by the time this plan runs; the implementer reads the file first. Everything else carries its code.
- **Type consistency.** `PlannedAllocation.hours` columns `member_id, week_start, hours` are read in Task 6 as `planned.hours.member_id` and `.week_start`; `items` keys in Task 5 match the facts assertion in Task 6 and the passthrough in Task 8; `SERIES_COLUMN` is defined in Task 4 and read by the models; `profile_tasks` keys in Task 1 are the ones Task 7 reads (`share_lagged`, `lag_median_days`, `planned`).
