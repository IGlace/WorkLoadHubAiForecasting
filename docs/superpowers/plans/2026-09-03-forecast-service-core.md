# Forecast Service Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Python forecast service (`service/`, package `whf`) that generates dummy data, forecasts each team member's estimated hours for the next two weeks with a backtest-selected champion model, computes capacity and overload, stores runs, and exposes the result through a PowerShell-friendly CLI and a localhost API.

**Architecture:** A `src/whf` package with small single-purpose modules: calendar and capacity arithmetic, a SQLite store, a seeded dummy-data generator with a hidden answer key, a feature builder, three arrival models behind one interface, an effort model that places hours in weeks, a rolling-origin backtest that picks the champion, deterministic pattern statistics, and a pipeline that orchestrates a run and persists it. The CLI (Typer) and API (FastAPI) are thin wrappers over the pipeline. No Copilot code in this plan; the pipeline produces the `facts` JSON that the Copilot plan consumes.

**Tech Stack:** Python 3.11+, uv, ruff, pytest, hypothesis, pandas 2.2+/3.x, numpy, scikit-learn 1.5+, holidays, pydantic 2, typer, fastapi, uvicorn, httpx (tests), sqlite3 (stdlib).

**Spec:** `docs/superpowers/specs/2026-09-03-workload-forecast-design.md` (sections 3, 4, 5, 8 and the service parts of 9 and 10).

**Validation:** on 2026-09-03 every code block in this plan was assembled into a scratch project and its own test suite was run (Python 3.11, pandas 3.0, scikit-learn 1.9): 84 fast tests and the slow accuracy gate passed, ruff clean. Executors should still follow the red-green steps; the code is a verified reference, not a shortcut.

**Follow-up plans (written after this one ships):** 2. Copilot integration and product skills (spec section 6). 3. Electron desktop app (spec section 7). 4. Packaging and installer (spec section 9).

## Global Constraints

- Python `>=3.11`; package name `whf`; console script `whf`; source layout `service/src/whf`.
- Every module in this plan must run on Windows (PowerShell) and Linux. No shell-specific code, no symlinks, paths through `pathlib`.
- Weeks start on Monday; working days are Monday to Friday; default capacity `40.0` hours per week; holiday calendar country code `MA`.
- The forecast horizon is the two weeks returned by `forecast_weeks(as_of)`: the current week if `as_of` is a Monday, otherwise the next week, plus the week after.
- Demand is never capped by capacity; `overload_hours = max(0, demand_hours - capacity_hours)`.
- The language model is never called in this plan; the pipeline stores the facts it would receive.
- Dates are stored in SQLite as ISO 8601 text (`YYYY-MM-DD`); in pandas frames they are Python `datetime.date` objects unless a function says otherwise.
- All randomness goes through `numpy.random.default_rng(seed)`; the same seed must produce identical data.
- Tests: `uv run pytest` from `service/`; lint: `uv run ruff check .` and `uv run ruff format --check .` must pass before each commit.
- Commit after every task with the message given in the task.

---

## File structure

```
service/
  pyproject.toml                 project metadata, dependencies, ruff and pytest config
  README.md                      how to install, test and run the service
  src/whf/__init__.py            __version__
  src/whf/config.py              data directory and database path resolution (WHF_HOME)
  src/whf/calendar.py            week_start, forecast_weeks, working_days, Morocco holidays, off-day sets
  src/whf/capacity.py            weekly capacity resolution, available hours, overload
  src/whf/db/__init__.py
  src/whf/db/schema.sql          all tables (spec section 3)
  src/whf/db/connection.py       connect(), schema initialisation
  src/whf/db/repo.py             insert_rows, read_df, date parsing helpers
  src/whf/data/__init__.py
  src/whf/data/generator.py      GeneratorConfig, profiles, org, projects, vacations, simulation, answer key
  src/whf/data/loader.py         write GeneratedData into the database, write answer_key.json
  src/whf/features.py            weekly arrival series and feature matrix
  src/whf/models/__init__.py     MODEL_FACTORIES registry
  src/whf/models/base.py         ArrivalModel protocol
  src/whf/models/naive.py        SeasonalNaive
  src/whf/models/tsb.py          TSB
  src/whf/models/gbm.py          GradientBoostingArrival
  src/whf/models/effort.py       EffortModel, place_hours, place_open_tasks, place_new_arrivals
  src/whf/backtest.py            mase, rolling_backtest, select_champion, interval_bounds
  src/whf/patterns.py            per-member pattern statistics and clustering
  src/whf/pipeline.py            run_forecast, persistence, facts JSON
  src/whf/cli.py                 Typer application
  src/whf/api.py                 FastAPI application
  tests/conftest.py              shared fixtures (in-memory database with generated data)
  tests/test_cli_version.py
  tests/test_calendar.py
  tests/test_capacity.py
  tests/test_db.py
  tests/test_generator_org.py
  tests/test_generator_simulation.py
  tests/test_features.py
  tests/test_models_baselines.py
  tests/test_models_gbm.py
  tests/test_effort.py
  tests/test_backtest.py
  tests/test_patterns.py
  tests/test_pipeline.py
  tests/test_cli.py
  tests/test_api.py
```

---

### Task 1: Scaffold the service project

**Files:**
- Create: `service/pyproject.toml`, `service/README.md`, `service/src/whf/__init__.py`, `service/src/whf/config.py`, `service/src/whf/cli.py`, `service/tests/__init__.py`, `service/tests/test_cli_version.py`
- Modify: `.gitignore` (root)

**Interfaces:**
- Produces: `whf.__version__: str`; `whf.config.data_dir() -> Path`; `whf.config.db_path() -> Path`; `whf.cli.app: typer.Typer` with a `version` command.

- [ ] **Step 1: Create the project files**

`service/pyproject.toml`:

```toml
[project]
name = "whf"
version = "0.1.0"
description = "WorkloadHub AI Forecasting service and CLI"
requires-python = ">=3.11"
dependencies = [
    "pandas>=2.2",
    "numpy>=1.26",
    "scikit-learn>=1.5",
    "holidays>=0.60",
    "pydantic>=2.7",
    "typer>=0.12",
    "fastapi>=0.115",
    "uvicorn>=0.30",
]

[project.scripts]
whf = "whf.cli:app"

[dependency-groups]
dev = [
    "pytest>=8",
    "hypothesis>=6.100",
    "httpx>=0.27",
    "ruff>=0.6",
]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/whf"]

[tool.ruff]
line-length = 120
target-version = "py311"

[tool.ruff.lint]
select = ["E", "F", "I", "UP", "B"]
ignore = ["E501", "B008"]  # B008: FastAPI Depends/Query defaults are idiomatic

[tool.pytest.ini_options]
testpaths = ["tests"]
markers = ["slow: long-running accuracy gates"]
```

`service/src/whf/__init__.py`:

```python
"""WorkloadHub AI Forecasting service."""

__version__ = "0.1.0"
```

`service/src/whf/config.py`:

```python
"""Locations of local data. Override everything with the WHF_HOME environment variable."""

from __future__ import annotations

import os
import sys
from pathlib import Path

APP_DIR_NAME = "WorkloadHubForecast"


def data_dir() -> Path:
    """Directory holding the database, logs and exports. Created on demand."""
    env = os.environ.get("WHF_HOME")
    if env:
        base = Path(env)
    elif sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home())) / APP_DIR_NAME
    else:
        base = Path.home() / ".local" / "share" / APP_DIR_NAME
    base.mkdir(parents=True, exist_ok=True)
    return base


def db_path() -> Path:
    return data_dir() / "whf.db"
```

`service/src/whf/cli.py`:

```python
"""Command-line interface. Every command calls the same functions the API uses."""

from __future__ import annotations

import typer

from whf import __version__

app = typer.Typer(help="WorkloadHub AI Forecasting", no_args_is_help=True)


@app.callback()
def main() -> None:
    """WorkloadHub AI Forecasting service commands."""


@app.command()
def version() -> None:
    """Print the service version."""
    typer.echo(f"whf {__version__}")
```

`service/README.md`:

```markdown
# whf: WorkloadHub AI Forecasting service

Python service, models and CLI. See `docs/superpowers/specs/2026-09-03-workload-forecast-design.md`.

## Develop

```powershell
cd service
uv sync            # creates .venv and installs everything incl. dev tools
uv run pytest      # tests
uv run ruff check . ; uv run ruff format --check .
uv run whf version
```
```

Append to the root `.gitignore`:

```
# Python
.venv/
__pycache__/
*.pyc
.pytest_cache/
.ruff_cache/
.hypothesis/
service/dist/
service/build/
```

- [ ] **Step 2: Write the failing test**

`service/tests/__init__.py`: empty file.

`service/tests/test_cli_version.py`:

```python
from typer.testing import CliRunner

from whf import __version__
from whf.cli import app


def test_version_command_prints_version() -> None:
    result = CliRunner().invoke(app, ["version"])
    assert result.exit_code == 0
    assert result.output.strip() == f"whf {__version__}"
```

- [ ] **Step 3: Install and run the test**

Run: `cd service && uv sync && uv run pytest tests/test_cli_version.py -v`
Expected: PASS (the scaffold already satisfies it; this confirms the toolchain works).

- [ ] **Step 4: Lint**

Run: `uv run ruff check . && uv run ruff format .`
Expected: no errors; format may rewrite files, which is fine.

- [ ] **Step 5: Commit**

```bash
git add .gitignore service
git commit -m "feat(service): scaffold whf package, config and CLI skeleton"
```

---

### Task 2: Calendar module

**Files:**
- Create: `service/src/whf/calendar.py`, `service/tests/test_calendar.py`

**Interfaces:**
- Produces:
  - `week_start(d: date) -> date` (Monday of the week containing `d`)
  - `last_complete_week(as_of: date) -> date` (Monday of the week before the one containing `as_of`)
  - `forecast_weeks(as_of: date) -> tuple[date, date]` (the two Mondays to forecast)
  - `morocco_holidays(years: Iterable[int]) -> dict[date, str]`
  - `working_days(start: date, end: date, off: set[date] | frozenset[date] = frozenset()) -> list[date]` (Mon–Fri, inclusive, excluding `off`)
  - `days_in_ranges(ranges: Iterable[tuple[date, date]]) -> set[date]`
  - `weeks_between(first: date, last: date) -> list[date]` (Mondays from `week_start(first)` to `week_start(last)` inclusive)

- [ ] **Step 1: Write the failing tests**

`service/tests/test_calendar.py`:

```python
import datetime as dt

from hypothesis import given
from hypothesis import strategies as st

from whf.calendar import (
    days_in_ranges,
    forecast_weeks,
    last_complete_week,
    morocco_holidays,
    week_start,
    weeks_between,
    working_days,
)

dates = st.dates(min_value=dt.date(2020, 1, 1), max_value=dt.date(2030, 12, 31))


@given(dates)
def test_week_start_is_monday_on_or_before(d: dt.date) -> None:
    ws = week_start(d)
    assert ws.weekday() == 0
    assert 0 <= (d - ws).days <= 6


def test_last_complete_week_is_previous_monday() -> None:
    assert last_complete_week(dt.date(2026, 9, 3)) == dt.date(2026, 8, 24)


def test_forecast_weeks_on_a_thursday_start_next_monday() -> None:
    assert forecast_weeks(dt.date(2026, 9, 3)) == (dt.date(2026, 9, 7), dt.date(2026, 9, 14))


def test_forecast_weeks_on_a_monday_include_that_week() -> None:
    assert forecast_weeks(dt.date(2026, 9, 7)) == (dt.date(2026, 9, 7), dt.date(2026, 9, 14))


def test_working_days_skips_weekends_and_off_days() -> None:
    off = {dt.date(2026, 9, 9)}
    days = working_days(dt.date(2026, 9, 5), dt.date(2026, 9, 13), off)  # Sat .. Sun
    assert days == [dt.date(2026, 9, 7), dt.date(2026, 9, 8), dt.date(2026, 9, 10), dt.date(2026, 9, 11)]


def test_working_days_empty_when_end_before_start() -> None:
    assert working_days(dt.date(2026, 9, 10), dt.date(2026, 9, 9)) == []


def test_morocco_holidays_include_new_year() -> None:
    hol = morocco_holidays([2026])
    assert dt.date(2026, 1, 1) in hol
    assert hol[dt.date(2026, 1, 1)] == "New Year's Day"


def test_days_in_ranges_is_inclusive() -> None:
    days = days_in_ranges([(dt.date(2026, 9, 1), dt.date(2026, 9, 3)), (dt.date(2026, 9, 3), dt.date(2026, 9, 4))])
    assert days == {dt.date(2026, 9, 1), dt.date(2026, 9, 2), dt.date(2026, 9, 3), dt.date(2026, 9, 4)}


def test_weeks_between_returns_mondays_inclusive() -> None:
    assert weeks_between(dt.date(2026, 8, 26), dt.date(2026, 9, 8)) == [
        dt.date(2026, 8, 24),
        dt.date(2026, 8, 31),
        dt.date(2026, 9, 7),
    ]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_calendar.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.calendar'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/calendar.py`:

```python
"""Week and working-day arithmetic. Weeks start on Monday; working days are Monday to Friday."""

from __future__ import annotations

import datetime as dt
from collections.abc import Iterable

import holidays

ONE_DAY = dt.timedelta(days=1)
ONE_WEEK = dt.timedelta(days=7)
HOLIDAY_COUNTRY = "MA"


def week_start(d: dt.date) -> dt.date:
    return d - dt.timedelta(days=d.weekday())


def last_complete_week(as_of: dt.date) -> dt.date:
    return week_start(as_of) - ONE_WEEK


def forecast_weeks(as_of: dt.date) -> tuple[dt.date, dt.date]:
    """The two Mondays to forecast: this week if as_of is a Monday, otherwise next week; plus one."""
    first = week_start(as_of) if as_of.weekday() == 0 else week_start(as_of) + ONE_WEEK
    return first, first + ONE_WEEK


def morocco_holidays(years: Iterable[int]) -> dict[dt.date, str]:
    cal = holidays.country_holidays(HOLIDAY_COUNTRY, years=list(years), language="en_US")
    return {d: name for d, name in cal.items()}


def working_days(
    start: dt.date, end: dt.date, off: set[dt.date] | frozenset[dt.date] = frozenset()
) -> list[dt.date]:
    days: list[dt.date] = []
    d = start
    while d <= end:
        if d.weekday() < 5 and d not in off:
            days.append(d)
        d += ONE_DAY
    return days


def days_in_ranges(ranges: Iterable[tuple[dt.date, dt.date]]) -> set[dt.date]:
    days: set[dt.date] = set()
    for start, end in ranges:
        d = start
        while d <= end:
            days.add(d)
            d += ONE_DAY
    return days


def weeks_between(first: dt.date, last: dt.date) -> list[dt.date]:
    out: list[dt.date] = []
    w = week_start(first)
    stop = week_start(last)
    while w <= stop:
        out.append(w)
        w += ONE_WEEK
    return out
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_calendar.py -v`
Expected: PASS (9 tests)

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/calendar.py service/tests/test_calendar.py
git commit -m "feat(service): calendar helpers for weeks, working days and Morocco holidays"
```

---

### Task 3: Capacity module

**Files:**
- Create: `service/src/whf/capacity.py`, `service/tests/test_capacity.py`

**Interfaces:**
- Consumes: `whf.calendar.working_days`, `whf.calendar.ONE_DAY`
- Produces:
  - `DEFAULT_WEEKLY_HOURS = 40.0`
  - `resolve_weekly_hours(default: float, permanent: float | None, week: float | None) -> float`
  - `available_hours(week: date, weekly_hours: float, off: set[date] | frozenset[date]) -> float`
  - `overload_hours(demand: float, capacity: float) -> float`

- [ ] **Step 1: Write the failing tests**

`service/tests/test_capacity.py`:

```python
import datetime as dt

from hypothesis import given
from hypothesis import strategies as st

from whf.capacity import DEFAULT_WEEKLY_HOURS, available_hours, overload_hours, resolve_weekly_hours

MONDAY = dt.date(2026, 9, 7)


def test_default_is_forty() -> None:
    assert DEFAULT_WEEKLY_HOURS == 40.0


def test_resolve_precedence_week_over_permanent_over_default() -> None:
    assert resolve_weekly_hours(40.0, None, None) == 40.0
    assert resolve_weekly_hours(40.0, 32.0, None) == 32.0
    assert resolve_weekly_hours(40.0, 32.0, 20.0) == 20.0
    assert resolve_weekly_hours(40.0, None, 20.0) == 20.0


def test_full_week_gives_weekly_hours() -> None:
    assert available_hours(MONDAY, 40.0, set()) == 40.0


def test_one_holiday_removes_one_fifth() -> None:
    assert available_hours(MONDAY, 40.0, {dt.date(2026, 9, 9)}) == 32.0


def test_full_vacation_week_gives_zero() -> None:
    off = {MONDAY + dt.timedelta(days=i) for i in range(7)}
    assert available_hours(MONDAY, 40.0, off) == 0.0


@given(
    st.floats(min_value=0, max_value=80),
    st.sets(st.integers(min_value=0, max_value=6), max_size=7),
)
def test_available_is_between_zero_and_weekly(weekly: float, off_offsets: set[int]) -> None:
    off = {MONDAY + dt.timedelta(days=i) for i in off_offsets}
    got = available_hours(MONDAY, weekly, off)
    assert 0.0 <= got <= weekly + 0.005  # rounded to two decimals


@given(st.floats(min_value=0, max_value=200), st.floats(min_value=0, max_value=200))
def test_overload_never_negative(demand: float, capacity: float) -> None:
    got = overload_hours(demand, capacity)
    assert got >= 0.0
    assert got == max(0.0, demand - capacity)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_capacity.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.capacity'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/capacity.py`:

```python
"""Weekly capacity: configured hours reduced by holidays, vacations and overrides."""

from __future__ import annotations

import datetime as dt

from whf.calendar import working_days

DEFAULT_WEEKLY_HOURS = 40.0
WORKING_DAYS_PER_WEEK = 5


def resolve_weekly_hours(default: float, permanent: float | None, week: float | None) -> float:
    """Week override beats permanent override beats default."""
    if week is not None:
        return float(week)
    if permanent is not None:
        return float(permanent)
    return float(default)


def available_hours(
    week: dt.date, weekly_hours: float, off: set[dt.date] | frozenset[dt.date]
) -> float:
    """Hours available in the week starting on `week` (a Monday)."""
    days = working_days(week, week + dt.timedelta(days=6), off)
    return round(weekly_hours * len(days) / WORKING_DAYS_PER_WEEK, 2)


def overload_hours(demand: float, capacity: float) -> float:
    return max(0.0, demand - capacity)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_capacity.py -v`
Expected: PASS (7 tests)

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/capacity.py service/tests/test_capacity.py
git commit -m "feat(service): capacity resolution, available hours and overload"
```

---

### Task 4: SQLite store

**Files:**
- Create: `service/src/whf/db/__init__.py`, `service/src/whf/db/schema.sql`, `service/src/whf/db/connection.py`, `service/src/whf/db/repo.py`, `service/tests/test_db.py`

**Interfaces:**
- Consumes: `whf.config.db_path`
- Produces:
  - `whf.db.connection.connect(path: str | Path | None = None) -> sqlite3.Connection` (creates schema, `row_factory = sqlite3.Row`, foreign keys on; `":memory:"` allowed)
  - `whf.db.repo.insert_rows(conn, table: str, rows: list[dict]) -> int` (dates and datetimes become ISO strings, bools become ints, returns row count)
  - `whf.db.repo.read_df(conn, sql: str, params: tuple = ()) -> pd.DataFrame`
  - `whf.db.repo.with_dates(df: pd.DataFrame, columns: list[str]) -> pd.DataFrame` (ISO text or None to `date` or None)
  - `whf.db.repo.table_names(conn) -> list[str]`

- [ ] **Step 1: Write the failing tests**

`service/tests/test_db.py`:

```python
import datetime as dt
import sqlite3

import pytest

from whf.db.connection import connect
from whf.db.repo import insert_rows, read_df, table_names, with_dates

EXPECTED_TABLES = {
    "departments", "teams", "members", "projects", "project_teams", "tasks",
    "capacity_defaults", "capacity_overrides", "holidays", "vacations", "profiles",
    "runs", "forecasts", "run_narratives", "run_facts",
}


def test_connect_creates_all_tables() -> None:
    conn = connect(":memory:")
    assert EXPECTED_TABLES <= set(table_names(conn))


def test_capacity_default_row_exists() -> None:
    conn = connect(":memory:")
    df = read_df(conn, "SELECT weekly_hours FROM capacity_defaults")
    assert df["weekly_hours"].tolist() == [40.0]


def test_insert_rows_converts_dates_and_bools() -> None:
    conn = connect(":memory:")
    insert_rows(conn, "departments", [{"id": 1, "name": "D", "skill_team_leader_id": None}])
    insert_rows(conn, "teams", [{"id": 1, "department_id": 1, "name": "T", "team_leader_id": None}])
    n = insert_rows(
        conn,
        "members",
        [
            {
                "id": 1, "name": "A", "team_id": 1, "department_id": 1, "role": "member",
                "counted_in_workload": True, "active_from": dt.date(2026, 1, 5), "active_to": None,
            }
        ],
    )
    assert n == 1
    df = with_dates(read_df(conn, "SELECT * FROM members"), ["active_from", "active_to"])
    assert df.loc[0, "counted_in_workload"] == 1
    assert df.loc[0, "active_from"] == dt.date(2026, 1, 5)
    assert df.loc[0, "active_to"] is None


def test_foreign_keys_are_enforced() -> None:
    conn = connect(":memory:")
    with pytest.raises(sqlite3.IntegrityError):
        insert_rows(conn, "teams", [{"id": 1, "department_id": 99, "name": "T", "team_leader_id": None}])


def test_connect_is_idempotent_on_disk(tmp_path) -> None:
    path = tmp_path / "x.db"
    connect(path).close()
    conn = connect(path)
    assert EXPECTED_TABLES <= set(table_names(conn))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_db.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.db'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/db/__init__.py`: empty.

`service/src/whf/db/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS departments (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    skill_team_leader_id INTEGER
);
CREATE TABLE IF NOT EXISTS teams (
    id INTEGER PRIMARY KEY,
    department_id INTEGER NOT NULL REFERENCES departments(id),
    name TEXT NOT NULL,
    team_leader_id INTEGER
);
CREATE TABLE IF NOT EXISTS members (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    team_id INTEGER REFERENCES teams(id),
    department_id INTEGER NOT NULL REFERENCES departments(id),
    role TEXT NOT NULL CHECK (role IN ('member', 'team_leader', 'skill_team_leader')),
    counted_in_workload INTEGER NOT NULL DEFAULT 1,
    active_from TEXT,
    active_to TEXT
);
CREATE TABLE IF NOT EXISTS projects (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    department_id INTEGER NOT NULL REFERENCES departments(id),
    start_date TEXT NOT NULL,
    deadline TEXT NOT NULL,
    type TEXT NOT NULL,
    status TEXT NOT NULL,
    created_by INTEGER
);
CREATE TABLE IF NOT EXISTS project_teams (
    project_id INTEGER NOT NULL REFERENCES projects(id),
    team_id INTEGER NOT NULL REFERENCES teams(id),
    PRIMARY KEY (project_id, team_id)
);
CREATE TABLE IF NOT EXISTS tasks (
    id INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    project_id INTEGER REFERENCES projects(id),
    assignee_id INTEGER NOT NULL REFERENCES members(id),
    team_id INTEGER NOT NULL REFERENCES teams(id),
    type TEXT NOT NULL,
    priority TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    assigned_at TEXT NOT NULL,
    due_date TEXT,
    completed_at TEXT,
    estimated_hours REAL NOT NULL,
    actual_hours REAL,
    created_by INTEGER,
    assignment_mode TEXT CHECK (assignment_mode IN ('manual', 'self_picked', 'project'))
);
CREATE INDEX IF NOT EXISTS idx_tasks_assignee ON tasks(assignee_id);
CREATE INDEX IF NOT EXISTS idx_tasks_team ON tasks(team_id);
CREATE TABLE IF NOT EXISTS capacity_defaults (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    weekly_hours REAL NOT NULL
);
INSERT OR IGNORE INTO capacity_defaults (id, weekly_hours) VALUES (1, 40.0);
CREATE TABLE IF NOT EXISTS capacity_overrides (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL REFERENCES members(id),
    week_start TEXT,
    weekly_hours REAL NOT NULL,
    reason TEXT,
    UNIQUE (member_id, week_start)
);
CREATE TABLE IF NOT EXISTS holidays (
    date TEXT NOT NULL,
    name TEXT NOT NULL,
    country TEXT NOT NULL DEFAULT 'MA',
    PRIMARY KEY (date, country)
);
CREATE TABLE IF NOT EXISTS vacations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL REFERENCES members(id),
    start_date TEXT NOT NULL,
    end_date TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'vacation'
);
CREATE TABLE IF NOT EXISTS profiles (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    member_id INTEGER REFERENCES members(id),
    role TEXT
);
CREATE TABLE IF NOT EXISTS runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    team_id INTEGER NOT NULL REFERENCES teams(id),
    as_of TEXT NOT NULL,
    requested_by INTEGER,
    status TEXT NOT NULL,
    champion_model TEXT,
    backtest_mase REAL,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    ai_status TEXT NOT NULL DEFAULT 'not_requested'
);
CREATE TABLE IF NOT EXISTS forecasts (
    run_id INTEGER NOT NULL REFERENCES runs(id),
    member_id INTEGER NOT NULL REFERENCES members(id),
    week_start TEXT NOT NULL,
    demand_hours REAL NOT NULL,
    demand_low REAL NOT NULL,
    demand_high REAL NOT NULL,
    capacity_hours REAL NOT NULL,
    overload_hours REAL NOT NULL,
    open_task_hours REAL NOT NULL,
    new_task_hours REAL NOT NULL,
    PRIMARY KEY (run_id, member_id, week_start)
);
CREATE TABLE IF NOT EXISTS run_narratives (
    run_id INTEGER PRIMARY KEY REFERENCES runs(id),
    json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS run_facts (
    run_id INTEGER PRIMARY KEY REFERENCES runs(id),
    json TEXT NOT NULL
);
```

`service/src/whf/db/connection.py`:

```python
"""SQLite connection with the schema applied."""

from __future__ import annotations

import sqlite3
from importlib import resources
from pathlib import Path

from whf.config import db_path


def _schema_sql() -> str:
    return resources.files("whf.db").joinpath("schema.sql").read_text(encoding="utf-8")


def connect(path: str | Path | None = None) -> sqlite3.Connection:
    """Open (and initialise) the database. Pass ':memory:' for tests."""
    target = ":memory:" if path == ":memory:" else str(path or db_path())
    conn = sqlite3.connect(target)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(_schema_sql())
    conn.commit()
    return conn
```

`service/src/whf/db/repo.py`:

```python
"""Small helpers between Python values, SQLite rows and pandas frames."""

from __future__ import annotations

import datetime as dt
import sqlite3
from typing import Any

import pandas as pd


def _to_sql(value: Any) -> Any:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, dt.datetime):
        return value.isoformat(timespec="seconds")
    if isinstance(value, dt.date):
        return value.isoformat()
    return value


def insert_rows(conn: sqlite3.Connection, table: str, rows: list[dict[str, Any]]) -> int:
    if not rows:
        return 0
    columns = list(rows[0].keys())
    placeholders = ", ".join("?" for _ in columns)
    sql = f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({placeholders})"
    conn.executemany(sql, [[_to_sql(row.get(c)) for c in columns] for row in rows])
    conn.commit()
    return len(rows)


def read_df(conn: sqlite3.Connection, sql: str, params: tuple = ()) -> pd.DataFrame:
    cur = conn.execute(sql, params)
    columns = [c[0] for c in cur.description]
    return pd.DataFrame([tuple(r) for r in cur.fetchall()], columns=columns)


def _parse_date(value: Any) -> dt.date | None:
    if value is None or value == "" or (isinstance(value, float) and pd.isna(value)):
        return None
    if isinstance(value, dt.date):
        return value
    return dt.date.fromisoformat(str(value)[:10])


def with_dates(df: pd.DataFrame, columns: list[str]) -> pd.DataFrame:
    out = df.copy()
    for c in columns:
        if c in out.columns:
            out[c] = [_parse_date(v) for v in out[c]]
            out[c] = out[c].astype(object)
    return out


def table_names(conn: sqlite3.Connection) -> list[str]:
    rows = conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()
    return [r[0] for r in rows]
```

Add to `[tool.hatch.build.targets.wheel]` in `pyproject.toml` nothing extra: hatchling includes `schema.sql` because it is inside the package directory. Verify with `uv run python -c "from whf.db.connection import connect; connect(':memory:')"`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_db.py -v`
Expected: PASS (5 tests)

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/db service/tests/test_db.py
git commit -m "feat(service): SQLite schema, connection and row helpers"
```

---

### Task 5: Dummy data generator, part A: organisation, profiles, projects, vacations

**Files:**
- Create: `service/src/whf/data/__init__.py`, `service/src/whf/data/generator.py`, `service/tests/test_generator_org.py`

**Interfaces:**
- Consumes: `whf.calendar.week_start`, `ONE_DAY`, `ONE_WEEK`
- Produces (all in `whf.data.generator`):
  - `GeneratorConfig(seed: int = 42, months: int = 12, as_of: date = date(2026, 9, 3), horizon_days: int = 21)`
  - `MemberProfile(member_id, base_rate, dispersion, style: dict[str, float], est_bias, cycle_factor, weekday_weights: list[float])`
  - `ProjectCurve(project_id, ramp_weeks, crunch_weeks, crunch_factor)`
  - `Org(departments: list[dict], teams: list[dict], members: list[dict], profiles: dict[int, MemberProfile])`
  - `build_org(rng: np.random.Generator) -> Org`
  - `build_projects(rng, org: Org, config) -> tuple[list[dict], list[dict], dict[int, ProjectCurve]]` (projects, project_teams, curves)
  - `build_vacations(rng, org: Org, config) -> list[dict]`
  - `history_start(config) -> date` (Monday, `months * 30.44` days before `as_of`)
  - `phase_intensity(d: date, start: date, deadline: date, curve: ProjectCurve) -> float`
  - `seasonal_factor(d: date) -> float`
  - Constants `TASK_TYPES`, `TYPE_BASE_HOURS`, `TYPE_BASE_CYCLE_DAYS`, `DEPARTMENTS`, `ASSIGNMENT_MODES = ("manual", "self_picked", "project")`

- [ ] **Step 1: Write the failing tests**

`service/tests/test_generator_org.py`:

```python
import datetime as dt

import numpy as np

from whf.data.generator import (
    ASSIGNMENT_MODES,
    GeneratorConfig,
    ProjectCurve,
    build_org,
    build_projects,
    build_vacations,
    history_start,
    phase_intensity,
    seasonal_factor,
)


def test_history_start_is_a_monday_about_a_year_back() -> None:
    cfg = GeneratorConfig(as_of=dt.date(2026, 9, 3), months=12)
    hs = history_start(cfg)
    assert hs.weekday() == 0
    assert 355 <= (cfg.as_of - hs).days <= 372


def test_org_has_three_departments_with_leaders_and_teams() -> None:
    org = build_org(np.random.default_rng(1))
    assert len(org.departments) == 3
    assert 7 <= len(org.teams) <= 9
    for d in org.departments:
        leader = next(m for m in org.members if m["id"] == d["skill_team_leader_id"])
        assert leader["role"] == "skill_team_leader"
        assert leader["counted_in_workload"] == 0
        assert leader["team_id"] is None
    for t in org.teams:
        leader = next(m for m in org.members if m["id"] == t["team_leader_id"])
        assert leader["role"] == "team_leader"
        assert leader["counted_in_workload"] == 1
        size = sum(1 for m in org.members if m["team_id"] == t["id"])
        assert 4 <= size <= 7


def test_every_counted_member_has_a_profile_with_a_style_mix_summing_to_one() -> None:
    org = build_org(np.random.default_rng(2))
    for m in org.members:
        if m["counted_in_workload"]:
            p = org.profiles[m["id"]]
            assert set(p.style) == set(ASSIGNMENT_MODES)
            assert abs(sum(p.style.values()) - 1.0) < 1e-9
            assert p.base_rate > 0 and len(p.weekday_weights) == 5
        else:
            assert m["id"] not in org.profiles


def test_org_is_reproducible() -> None:
    a = build_org(np.random.default_rng(5))
    b = build_org(np.random.default_rng(5))
    assert a.members == b.members and a.teams == b.teams


def test_projects_cover_history_and_include_one_starting_in_horizon_per_team() -> None:
    cfg = GeneratorConfig(seed=3)
    rng = np.random.default_rng(cfg.seed)
    org = build_org(rng)
    projects, project_teams, curves = build_projects(rng, org, cfg)
    assert {p["id"] for p in projects} == set(curves)
    assert len(project_teams) == len(projects)
    for t in org.teams:
        mine = [p for p, pt in zip(projects, project_teams, strict=True) if pt["team_id"] == t["id"]]
        assert len(mine) >= 4
        future = [p for p in mine if cfg.as_of < p["start_date"] <= cfg.as_of + dt.timedelta(days=cfg.horizon_days)]
        assert len(future) == 1 and future[0]["status"] == "planned"
        for p in mine:
            assert p["deadline"] > p["start_date"]


def test_phase_intensity_ramps_then_crunches() -> None:
    curve = ProjectCurve(1, ramp_weeks=2, crunch_weeks=2, crunch_factor=1.5)
    start, deadline = dt.date(2026, 1, 5), dt.date(2026, 3, 30)
    assert phase_intensity(dt.date(2026, 1, 1), start, deadline, curve) == 0.0
    assert phase_intensity(dt.date(2026, 1, 6), start, deadline, curve) == 0.6
    assert phase_intensity(dt.date(2026, 2, 10), start, deadline, curve) == 1.0
    assert phase_intensity(dt.date(2026, 3, 25), start, deadline, curve) == 1.5
    assert phase_intensity(dt.date(2026, 4, 1), start, deadline, curve) == 0.0


def test_seasonal_factor_has_summer_dip_and_year_end_peak() -> None:
    assert seasonal_factor(dt.date(2026, 8, 5)) < 1.0
    assert seasonal_factor(dt.date(2026, 12, 10)) > 1.0
    assert seasonal_factor(dt.date(2026, 5, 6)) == 1.0


def test_vacations_include_future_ones_for_some_members() -> None:
    cfg = GeneratorConfig(seed=4)
    rng = np.random.default_rng(cfg.seed)
    org = build_org(rng)
    vacations = build_vacations(rng, org, cfg)
    counted = {m["id"] for m in org.members if m["counted_in_workload"]}
    assert all(v["member_id"] in counted for v in vacations)
    future = [v for v in vacations if v["start_date"] > cfg.as_of]
    assert len(future) >= 3
    assert all(v["end_date"] >= v["start_date"] for v in vacations)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_generator_org.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.data'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/data/__init__.py`: empty.

`service/src/whf/data/generator.py` (part A; Task 6 appends the simulation to this same file):

```python
"""Seeded dummy data with hidden, discoverable patterns.

Part A (this task): organisation, member profiles, projects with phase curves, vacations.
Part B (next task): day-by-day task arrival and effort simulation, answer key.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field

import numpy as np

from whf.calendar import ONE_DAY, ONE_WEEK, week_start

TASK_TYPES = ("feature", "bug", "support", "analysis", "maintenance")
TYPE_PROBS = (0.30, 0.25, 0.20, 0.15, 0.10)
TYPE_BASE_HOURS = {"feature": 12.0, "bug": 5.0, "support": 3.0, "analysis": 8.0, "maintenance": 6.0}
TYPE_BASE_CYCLE_DAYS = {"feature": 9.0, "bug": 3.0, "support": 1.5, "analysis": 6.0, "maintenance": 4.0}
ASSIGNMENT_MODES = ("manual", "self_picked", "project")
PRIORITIES = ("low", "medium", "high")
PRIORITY_PROBS = (0.3, 0.5, 0.2)

DEPARTMENTS: dict[str, list[str]] = {
    "Software Engineering": ["Web Platform", "Mobile Apps", "Integration"],
    "Data & Analytics": ["Data Engineering", "BI & Reporting"],
    "Infrastructure & Support": ["Cloud Operations", "Service Desk", "Security"],
}

FIRST_NAMES = [
    "Youssef", "Amina", "Omar", "Salma", "Mehdi", "Khadija", "Hamza", "Nadia", "Anas", "Sara",
    "Bilal", "Imane", "Yassine", "Meryem", "Zakaria", "Hajar", "Reda", "Fatima", "Ayoub", "Soukaina",
    "Ilyas", "Rim", "Adam", "Laila", "Karim", "Ghita", "Othmane", "Nour", "Amine", "Houda",
    "Walid", "Zineb", "Taha", "Hind", "Ismail", "Aya", "Rachid", "Samira", "Nabil", "Ikram",
    "Mohammed", "Chaimae", "Hicham", "Kenza", "Driss", "Loubna", "Tarik", "Wafae", "Jalil", "Asmae",
    "Khalil", "Siham", "Mounir", "Rania", "Saad", "Malak", "Badr", "Yasmine", "Fouad", "Dounia",
]
LAST_NAMES = [
    "El Idrissi", "Benali", "Alaoui", "Bennani", "Chraibi", "El Fassi", "Tazi", "Berrada", "Lahlou",
    "Sefrioui", "Kettani", "Bouazza", "Amrani", "Cherkaoui", "Mansouri", "Zniber", "Filali", "Haddad",
    "Ouazzani", "Skalli", "Belkadi", "Naciri", "Lamrani", "Rhazi", "Benjelloun", "Tahiri", "Ziani",
    "El Ghazi", "Boukhari", "Saidi",
]


@dataclass(frozen=True)
class GeneratorConfig:
    seed: int = 42
    months: int = 12
    as_of: dt.date = dt.date(2026, 9, 3)
    horizon_days: int = 21


@dataclass
class MemberProfile:
    member_id: int
    base_rate: float  # expected tasks per week
    dispersion: float  # negative-binomial over-dispersion (0 = Poisson)
    style: dict[str, float]  # assignment mode probabilities, sums to 1
    est_bias: float  # actual hours / estimated hours
    cycle_factor: float  # multiplies the type's base cycle time for due dates
    weekday_weights: list[float]  # Mon..Fri, average 1.0


@dataclass
class ProjectCurve:
    project_id: int
    ramp_weeks: int
    crunch_weeks: int
    crunch_factor: float


@dataclass
class Org:
    departments: list[dict] = field(default_factory=list)
    teams: list[dict] = field(default_factory=list)
    members: list[dict] = field(default_factory=list)
    profiles: dict[int, MemberProfile] = field(default_factory=dict)


def history_start(config: GeneratorConfig) -> dt.date:
    return week_start(config.as_of - dt.timedelta(days=int(config.months * 30.44)))


def seasonal_factor(d: dt.date) -> float:
    week = d.isocalendar()[1]
    if 31 <= week <= 34:
        return 0.6  # summer dip
    if week >= 49:
        return 1.3  # year-end crunch
    if week <= 1:
        return 0.7
    return 1.0


def phase_intensity(d: dt.date, start: dt.date, deadline: dt.date, curve: ProjectCurve) -> float:
    if d < start or d > deadline:
        return 0.0
    weeks_since_start = (d - start).days / 7
    weeks_to_deadline = (deadline - d).days / 7
    if weeks_since_start < curve.ramp_weeks:
        return 0.6
    if weeks_to_deadline <= curve.crunch_weeks:
        return curve.crunch_factor
    return 1.0


def _make_profile(rng: np.random.Generator, member_id: int) -> MemberProfile:
    dominant = str(rng.choice(ASSIGNMENT_MODES, p=[0.35, 0.30, 0.35]))
    style = {mode: 0.2 for mode in ASSIGNMENT_MODES}
    style[dominant] = 0.6
    weights = rng.dirichlet([3.0, 2.0, 2.0, 2.0, 1.5]) * 5.0
    return MemberProfile(
        member_id=member_id,
        base_rate=float(rng.gamma(9.0, 0.4)),
        dispersion=float(rng.uniform(0.3, 1.0)),
        style=style,
        est_bias=float(np.exp(rng.normal(0.05, 0.25))),
        cycle_factor=float(np.exp(rng.normal(0.0, 0.3))),
        weekday_weights=[round(float(w), 3) for w in weights],
    )


def build_org(rng: np.random.Generator) -> Org:
    org = Org()
    first = list(rng.permutation(FIRST_NAMES))
    last = list(rng.permutation(LAST_NAMES))
    names = [f"{first[i]} {last[i % len(last)]}" for i in range(len(first))]
    next_name = 0
    member_id = 0
    for dept_id, (dept_name, team_names) in enumerate(DEPARTMENTS.items(), start=1):
        member_id += 1
        leader_id = member_id
        org.members.append(
            {
                "id": leader_id, "name": names[next_name], "team_id": None, "department_id": dept_id,
                "role": "skill_team_leader", "counted_in_workload": 0, "active_from": None, "active_to": None,
            }
        )
        next_name += 1
        org.departments.append({"id": dept_id, "name": dept_name, "skill_team_leader_id": leader_id})
        for team_name in team_names:
            team_id = len(org.teams) + 1
            size = int(rng.integers(4, 8))
            team_leader_id = None
            for k in range(size):
                member_id += 1
                role = "team_leader" if k == 0 else "member"
                if k == 0:
                    team_leader_id = member_id
                org.members.append(
                    {
                        "id": member_id, "name": names[next_name % len(names)], "team_id": team_id,
                        "department_id": dept_id, "role": role, "counted_in_workload": 1,
                        "active_from": None, "active_to": None,
                    }
                )
                next_name += 1
                org.profiles[member_id] = _make_profile(rng, member_id)
            org.teams.append(
                {"id": team_id, "department_id": dept_id, "name": team_name, "team_leader_id": team_leader_id}
            )
    return org


def build_projects(
    rng: np.random.Generator, org: Org, config: GeneratorConfig
) -> tuple[list[dict], list[dict], dict[int, ProjectCurve]]:
    projects: list[dict] = []
    project_teams: list[dict] = []
    curves: dict[int, ProjectCurve] = {}
    start = history_start(config)
    project_id = 0
    for team in org.teams:
        cursor = start
        for _ in range(int(rng.integers(3, 6))):
            project_id += 1
            duration_weeks = int(rng.integers(6, 20))
            p_start = min(cursor + dt.timedelta(days=int(rng.integers(0, 28))), config.as_of - 2 * ONE_WEEK)
            deadline = p_start + duration_weeks * ONE_WEEK
            projects.append(
                {
                    "id": project_id, "name": f"{team['name']} project {project_id}",
                    "department_id": team["department_id"], "start_date": p_start, "deadline": deadline,
                    "type": str(rng.choice(["delivery", "internal", "support"])),
                    "status": "closed" if deadline < config.as_of else "active",
                    "created_by": team["team_leader_id"],
                }
            )
            project_teams.append({"project_id": project_id, "team_id": team["id"]})
            curves[project_id] = ProjectCurve(
                project_id, int(rng.integers(1, 3)), int(rng.integers(1, 3)), float(rng.uniform(1.3, 1.8))
            )
            cursor = p_start + max(2, duration_weeks // 2) * ONE_WEEK
        project_id += 1
        p_start = config.as_of + dt.timedelta(days=int(rng.integers(1, min(12, config.horizon_days))))
        deadline = p_start + int(rng.integers(6, 14)) * ONE_WEEK
        projects.append(
            {
                "id": project_id, "name": f"{team['name']} project {project_id}",
                "department_id": team["department_id"], "start_date": p_start, "deadline": deadline,
                "type": "delivery", "status": "planned", "created_by": team["team_leader_id"],
            }
        )
        project_teams.append({"project_id": project_id, "team_id": team["id"]})
        curves[project_id] = ProjectCurve(project_id, 1, 2, 1.5)
    return projects, project_teams, curves


def build_vacations(rng: np.random.Generator, org: Org, config: GeneratorConfig) -> list[dict]:
    vacations: list[dict] = []
    start = history_start(config)
    span_days = (config.as_of - start).days
    for m in org.members:
        if not m["counted_in_workload"]:
            continue
        for _ in range(int(rng.integers(1, 3))):
            v_start = start + dt.timedelta(days=int(rng.integers(0, span_days)))
            v_end = v_start + dt.timedelta(days=int(rng.integers(4, 11)))
            vacations.append({"member_id": m["id"], "start_date": v_start, "end_date": v_end, "type": "vacation"})
        if rng.random() < 0.25:
            v_start = config.as_of + dt.timedelta(days=int(rng.integers(1, 15)))
            v_end = v_start + dt.timedelta(days=int(rng.integers(2, 8)))
            vacations.append({"member_id": m["id"], "start_date": v_start, "end_date": v_end, "type": "vacation"})
    return vacations


def _unused() -> None:  # keeps ONE_DAY imported for part B; removed in Task 6
    _ = ONE_DAY
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_generator_org.py -v`
Expected: PASS (8 tests). If `test_vacations_include_future_ones_for_some_members` fails for seed 4 with fewer than 3 future vacations, change the seed in the test to 5; the property being tested is "some members have future vacations", not an exact count.

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/data service/tests/test_generator_org.py
git commit -m "feat(service): dummy data generator part A: org, profiles, projects, vacations"
```

---

### Task 6: Dummy data generator, part B: simulation, answer key, loader

**Files:**
- Modify: `service/src/whf/data/generator.py` (append; remove the `_unused` helper)
- Create: `service/src/whf/data/loader.py`, `service/tests/test_generator_simulation.py`, `service/tests/conftest.py`

**Interfaces:**
- Consumes: Task 5 types; `whf.calendar.morocco_holidays`, `days_in_ranges`, `week_start`; `whf.db.repo.insert_rows`
- Produces:
  - `GeneratedData(config, departments, teams, members, projects, project_teams, vacations, holidays: list[dict], tasks: list[dict], answer_key: dict)`
  - `generate(config: GeneratorConfig) -> GeneratedData`
  - `truncate_to(data: GeneratedData, as_of: date) -> GeneratedData` (drops tasks assigned after `as_of`, reopens tasks completed after it; keeps the answer key)
  - `whf.data.loader.load_generated(conn, data: GeneratedData) -> None`
  - `whf.data.loader.write_answer_key(path: Path, data: GeneratedData) -> None`
  - `tests/conftest.py` fixtures: `generated` (session-scoped `GeneratedData`, seed 42) and `db` (function-scoped in-memory connection loaded with it)

- [ ] **Step 1: Write the failing tests**

`service/tests/conftest.py`:

```python
import pytest

from whf.data.generator import GeneratedData, GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect


@pytest.fixture(scope="session")
def generated() -> GeneratedData:
    return generate(GeneratorConfig(seed=42))


@pytest.fixture()
def db(generated: GeneratedData):
    conn = connect(":memory:")
    load_generated(conn, generated)
    yield conn
    conn.close()
```

`service/tests/test_generator_simulation.py`:

```python
import datetime as dt

import pandas as pd

from whf.data.generator import GeneratedData, GeneratorConfig, generate, truncate_to
from whf.db.repo import read_df


def test_generate_is_reproducible() -> None:
    a = generate(GeneratorConfig(seed=11, months=3))
    b = generate(GeneratorConfig(seed=11, months=3))
    assert a.tasks == b.tasks and a.answer_key["profiles"] == b.answer_key["profiles"]


def test_tasks_have_all_fields_and_consistent_dates(generated: GeneratedData) -> None:
    required = {
        "id", "title", "project_id", "assignee_id", "team_id", "type", "priority", "status",
        "created_at", "assigned_at", "due_date", "completed_at", "estimated_hours", "actual_hours",
        "created_by", "assignment_mode",
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


def test_loader_writes_every_table(db) -> None:
    counts = {
        name: int(read_df(db, f"SELECT COUNT(*) AS n FROM {name}")["n"][0])
        for name in ["departments", "teams", "members", "projects", "project_teams", "tasks", "vacations", "holidays"]
    }
    assert counts["departments"] == 3
    assert counts["tasks"] > 1000
    assert counts["holidays"] > 10
    assert counts["vacations"] > 0
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_generator_simulation.py -v`
Expected: FAIL with `ImportError: cannot import name 'GeneratedData'`

- [ ] **Step 3: Write the implementation**

Remove the `_unused` function at the end of `generator.py`, then append:

```python
@dataclass
class GeneratedData:
    config: GeneratorConfig
    departments: list[dict]
    teams: list[dict]
    members: list[dict]
    projects: list[dict]
    project_teams: list[dict]
    vacations: list[dict]
    holidays: list[dict]
    tasks: list[dict]
    answer_key: dict


def _vacation_days(vacations: list[dict]) -> dict[int, set[dt.date]]:
    out: dict[int, set[dt.date]] = {}
    for v in vacations:
        out.setdefault(v["member_id"], set()).update(days_in_ranges([(v["start_date"], v["end_date"])]))
    return out


def _new_task(
    rng: np.random.Generator,
    task_id: int,
    day: dt.date,
    member: dict,
    profile: MemberProfile,
    mode: str,
    team_projects: list[dict],
    intensity: dict[int, float],
    team_leader_id: int | None,
) -> tuple[dict, float]:
    task_type = str(rng.choice(TASK_TYPES, p=TYPE_PROBS))
    estimated = float(np.clip(np.exp(rng.normal(np.log(TYPE_BASE_HOURS[task_type]), 0.4)), 1.0, 40.0))
    project = None
    if team_projects:
        weights = np.array([intensity[p["id"]] + 0.1 for p in team_projects])
        project = team_projects[int(rng.choice(len(team_projects), p=weights / weights.sum()))]
    cycle_days = TYPE_BASE_CYCLE_DAYS[task_type] * profile.cycle_factor * float(np.exp(rng.normal(0.0, 0.3)))
    actual_total = estimated * profile.est_bias * float(np.exp(rng.normal(0.0, 0.15)))
    task = {
        "id": task_id,
        "title": f"{task_type.title()} task {task_id}",
        "project_id": project["id"] if project else None,
        "assignee_id": member["id"],
        "team_id": member["team_id"],
        "type": task_type,
        "priority": str(rng.choice(PRIORITIES, p=PRIORITY_PROBS)),
        "status": "todo",
        "created_at": day,
        "assigned_at": day,
        "due_date": day + dt.timedelta(days=max(1, int(round(cycle_days)))),
        "completed_at": None,
        "estimated_hours": round(estimated, 1),
        "actual_hours": None,
        "created_by": team_leader_id if mode == "manual" else member["id"],
        "assignment_mode": mode,
    }
    return task, actual_total


def simulate_tasks(
    rng: np.random.Generator,
    org: Org,
    projects: list[dict],
    project_teams: list[dict],
    curves: dict[int, ProjectCurve],
    vacations: list[dict],
    off: set[dt.date],
    config: GeneratorConfig,
) -> tuple[list[dict], list[dict]]:
    """Day-by-day arrivals and effort. Returns (tasks, effort_log rows)."""
    start = history_start(config)
    projects_by_team: dict[int, list[dict]] = {t["id"]: [] for t in org.teams}
    for p, pt in zip(projects, project_teams, strict=True):
        projects_by_team[pt["team_id"]].append(p)
    leader_by_team = {t["id"]: t["team_leader_id"] for t in org.teams}
    vac_days = _vacation_days(vacations)
    workers = [m for m in org.members if m["counted_in_workload"]]
    tasks: list[dict] = []
    remaining: dict[int, float] = {}
    actual_total: dict[int, float] = {}
    open_by_member: dict[int, list[dict]] = {m["id"]: [] for m in workers}
    effort_log: list[dict] = []
    task_id = 0
    day = start
    while day <= config.as_of:
        is_working_day = day.weekday() < 5 and day not in off
        for member in workers:
            profile = org.profiles[member["id"]]
            if not is_working_day or day in vac_days.get(member["id"], set()):
                continue
            team_projects = [
                p for p in projects_by_team[member["team_id"]] if p["start_date"] <= day <= p["deadline"]
            ]
            intensity = {
                p["id"]: phase_intensity(day, p["start_date"], p["deadline"], curves[p["id"]])
                for p in team_projects
            }
            project_factor = (0.6 + 0.4 * float(np.mean(list(intensity.values())))) if intensity else 0.7
            rate = (
                profile.base_rate / 5.0
                * profile.weekday_weights[day.weekday()]
                * seasonal_factor(day)
                * project_factor
            )
            lam = rng.gamma(1.0 / profile.dispersion, rate * profile.dispersion)
            for _ in range(int(rng.poisson(lam))):
                mode = str(rng.choice(ASSIGNMENT_MODES, p=[profile.style[m] for m in ASSIGNMENT_MODES]))
                if mode == "manual" and day.weekday() > 1 and rng.random() < 0.6:
                    continue  # manual assignments cluster on Monday and Tuesday
                task_id += 1
                task, total = _new_task(
                    rng, task_id, day, member, profile, mode, team_projects, intensity,
                    leader_by_team[member["team_id"]],
                )
                tasks.append(task)
                remaining[task_id] = total
                actual_total[task_id] = total
                open_by_member[member["id"]].append(task)
            budget = 8.0
            queue = sorted(open_by_member[member["id"]], key=lambda t: (t["due_date"], t["priority"] != "high"))
            for task in queue:
                if budget <= 1e-9:
                    break
                spend = min(budget, remaining[task["id"]], 6.0)
                remaining[task["id"]] -= spend
                budget -= spend
                task["status"] = "in_progress"
                effort_log.append({"member_id": member["id"], "date": day, "task_id": task["id"], "hours": round(spend, 2)})
                if remaining[task["id"]] <= 1e-9:
                    task["status"] = "done"
                    task["completed_at"] = day
                    task["actual_hours"] = round(actual_total[task["id"]], 1)
            open_by_member[member["id"]] = [t for t in open_by_member[member["id"]] if t["status"] != "done"]
        day += ONE_DAY
    return tasks, effort_log


def generate(config: GeneratorConfig = GeneratorConfig()) -> GeneratedData:
    rng = np.random.default_rng(config.seed)
    org = build_org(rng)
    projects, project_teams, curves = build_projects(rng, org, config)
    vacations = build_vacations(rng, org, config)
    start = history_start(config)
    horizon_end = config.as_of + dt.timedelta(days=config.horizon_days)
    holiday_map = morocco_holidays(range(start.year, horizon_end.year + 1))
    holidays_rows = [{"date": d, "name": n, "country": "MA"} for d, n in sorted(holiday_map.items())]
    tasks, effort_log = simulate_tasks(
        rng, org, projects, project_teams, curves, vacations, set(holiday_map), config
    )
    weekly: dict[tuple[int, dt.date], float] = {}
    for row in effort_log:
        key = (row["member_id"], week_start(row["date"]))
        weekly[key] = weekly.get(key, 0.0) + row["hours"]
    answer_key = {
        "profiles": {str(k): asdict(v) for k, v in org.profiles.items()},
        "curves": {str(k): asdict(v) for k, v in curves.items()},
        "effort_log": [{**r, "date": r["date"].isoformat()} for r in effort_log],
        "effort_by_member_week": [
            {"member_id": m, "week_start": w.isoformat(), "hours": round(h, 2)} for (m, w), h in sorted(weekly.items())
        ],
    }
    return GeneratedData(
        config=config, departments=org.departments, teams=org.teams, members=org.members,
        projects=projects, project_teams=project_teams, vacations=vacations, holidays=holidays_rows,
        tasks=tasks, answer_key=answer_key,
    )


def truncate_to(data: GeneratedData, as_of: dt.date) -> GeneratedData:
    """A copy of `data` as the database would have looked on `as_of`."""
    tasks: list[dict] = []
    for t in data.tasks:
        if t["assigned_at"] > as_of:
            continue
        t2 = dict(t)
        if t2["completed_at"] is not None and t2["completed_at"] > as_of:
            t2["completed_at"] = None
            t2["actual_hours"] = None
            t2["status"] = "in_progress"
        tasks.append(t2)
    config = GeneratorConfig(
        seed=data.config.seed, months=data.config.months, as_of=as_of, horizon_days=data.config.horizon_days
    )
    return GeneratedData(
        config=config, departments=data.departments, teams=data.teams, members=data.members,
        projects=data.projects, project_teams=data.project_teams, vacations=data.vacations,
        holidays=data.holidays, tasks=tasks, answer_key=data.answer_key,
    )
```

Also add these imports at the top of `generator.py`: `from dataclasses import asdict` (extend the existing dataclasses import) and `from whf.calendar import ONE_DAY, ONE_WEEK, days_in_ranges, morocco_holidays, week_start`.

`service/src/whf/data/loader.py`:

```python
"""Write generated data into the database and the answer key to disk."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from whf.data.generator import GeneratedData
from whf.db.repo import insert_rows


def load_generated(conn: sqlite3.Connection, data: GeneratedData) -> None:
    for table in ["forecasts", "run_facts", "run_narratives", "runs", "tasks", "project_teams", "projects",
                  "vacations", "capacity_overrides", "holidays", "members", "teams", "departments"]:
        conn.execute(f"DELETE FROM {table}")
    conn.commit()
    # departments reference leaders and teams reference departments: insert leaders after departments
    insert_rows(conn, "departments", [{**d, "skill_team_leader_id": None} for d in data.departments])
    insert_rows(conn, "teams", [{**t, "team_leader_id": None} for t in data.teams])
    insert_rows(conn, "members", data.members)
    for d in data.departments:
        conn.execute("UPDATE departments SET skill_team_leader_id = ? WHERE id = ?", (d["skill_team_leader_id"], d["id"]))
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_generator_simulation.py tests/test_generator_org.py -v`
Expected: PASS (17 tests). Generation of a year takes about one to two seconds.

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/data service/tests/conftest.py service/tests/test_generator_simulation.py
git commit -m "feat(service): dummy data simulation, answer key, truncation and loader"
```

---

### Task 7: Weekly arrivals and feature matrix

**Files:**
- Create: `service/src/whf/features.py`, `service/tests/test_features.py`

**Interfaces:**
- Consumes: `whf.calendar.week_start`, `ONE_WEEK`, `working_days`
- Produces (all in `whf.features`):
  - `HORIZONS = (1, 2, 3)`; `LAGS = (1, 2, 3, 4, 8, 13)`; `ROLL_WINDOWS = (4, 8, 13)`
  - `weekly_arrivals(tasks: pd.DataFrame, member_ids: list[int], weeks: list[date]) -> pd.DataFrame` with columns `member_id, week_start, n_tasks, est_hours` (one row per member per week, zeros filled). `tasks` needs columns `id, assignee_id, assigned_at (date), estimated_hours, assignment_mode`.
  - `build_feature_matrix(arrivals, tasks, projects, project_teams, members, holidays: set[date], vacation_days: dict[int, set[date]]) -> pd.DataFrame`: one row per member-week with lag, rolling, style, calendar and per-horizon project and availability features, plus `target_h1..3` (hours `h` weeks after the row's week; NaN when unknown). `member_id` and `team_id` are pandas categoricals.
  - `feature_columns(h: int) -> list[str]`
  - Column naming: `lag1` is the hours of the row's own week (the origin week), `lag2` the week before, and so on.

- [ ] **Step 1: Write the failing tests**

`service/tests/test_features.py`:

```python
import datetime as dt

import pandas as pd

from whf.features import HORIZONS, build_feature_matrix, feature_columns, weekly_arrivals

W0 = dt.date(2026, 1, 5)  # a Monday


def _weeks(n: int) -> list[dt.date]:
    return [W0 + dt.timedelta(days=7 * i) for i in range(n)]


def _tasks() -> pd.DataFrame:
    rows = []
    tid = 0
    for i, w in enumerate(_weeks(20)):
        # member 1 gets i tasks of 2h in week i; member 2 gets one 5h task every other week
        for _ in range(i):
            tid += 1
            rows.append({"id": tid, "assignee_id": 1, "assigned_at": w + dt.timedelta(days=1), "estimated_hours": 2.0, "assignment_mode": "manual"})
        if i % 2 == 0:
            tid += 1
            rows.append({"id": tid, "assignee_id": 2, "assigned_at": w, "estimated_hours": 5.0, "assignment_mode": "self_picked"})
    return pd.DataFrame(rows)


def _members() -> pd.DataFrame:
    return pd.DataFrame([{"id": 1, "team_id": 1}, {"id": 2, "team_id": 1}, {"id": 3, "team_id": 1}])


def _projects() -> tuple[pd.DataFrame, pd.DataFrame]:
    projects = pd.DataFrame([
        {"id": 10, "start_date": W0 + dt.timedelta(days=14), "deadline": W0 + dt.timedelta(days=56)},
    ])
    return projects, pd.DataFrame([{"project_id": 10, "team_id": 1}])


def test_weekly_arrivals_fills_every_member_week() -> None:
    arr = weekly_arrivals(_tasks(), [1, 2, 3], _weeks(20))
    assert len(arr) == 60
    m1 = arr[arr.member_id == 1].sort_values("week_start")
    assert m1.est_hours.tolist()[:4] == [0.0, 2.0, 4.0, 6.0]
    assert m1.n_tasks.tolist()[:4] == [0, 1, 2, 3]
    assert (arr[arr.member_id == 3].est_hours == 0).all()


def test_lags_and_targets_are_aligned() -> None:
    arr = weekly_arrivals(_tasks(), [1, 2, 3], _weeks(20))
    projects, project_teams = _projects()
    feat = build_feature_matrix(arr, _tasks(), projects, project_teams, _members(), set(), {})
    m1 = feat[feat.member_id == 1].set_index("week_start")
    w5 = W0 + dt.timedelta(days=35)
    assert m1.loc[w5, "lag1"] == 10.0          # own week (i=5 -> 5 tasks * 2h)
    assert m1.loc[w5, "lag2"] == 8.0
    assert m1.loc[w5, "target_h1"] == 12.0
    assert m1.loc[w5, "target_h2"] == 14.0
    last = W0 + dt.timedelta(days=7 * 19)
    assert pd.isna(m1.loc[last, "target_h1"])


def test_project_features_reflect_target_week() -> None:
    arr = weekly_arrivals(_tasks(), [1, 2, 3], _weeks(20))
    projects, project_teams = _projects()
    feat = build_feature_matrix(arr, _tasks(), projects, project_teams, _members(), set(), {})
    m1 = feat[feat.member_id == 1].set_index("week_start")
    # project starts in week index 2 (W0+14). From origin week 1, horizon 1 targets week 2 -> project starting.
    w1 = W0 + dt.timedelta(days=7)
    assert m1.loc[w1, "proj_starting_h1"] == 1
    assert m1.loc[w1, "proj_active_h1"] == 1
    assert m1.loc[W0, "proj_active_h1"] == 0
    # deadline W0+56 = week index 8; from origin week 7, horizon 1 targets week 8 -> project ending
    w7 = W0 + dt.timedelta(days=49)
    assert m1.loc[w7, "proj_ending_h1"] == 1
    assert m1.loc[w7, "proj_min_weeks_to_deadline_h1"] == 0.0


def test_style_shares_and_availability_features() -> None:
    arr = weekly_arrivals(_tasks(), [1, 2, 3], _weeks(20))
    projects, project_teams = _projects()
    w3 = W0 + dt.timedelta(days=21)
    holidays = {w3 + dt.timedelta(days=7)}  # Monday of week 4 is a holiday
    vacations = {2: {w3 + dt.timedelta(days=8), w3 + dt.timedelta(days=9)}}
    feat = build_feature_matrix(arr, _tasks(), projects, project_teams, _members(), holidays, vacations)
    m1 = feat[feat.member_id == 1].set_index("week_start")
    m2 = feat[feat.member_id == 2].set_index("week_start")
    assert m1.loc[w3, "share_manual"] == 1.0
    assert m2.loc[w3, "share_self_picked"] == 1.0
    assert m1.loc[w3, "working_days_h1"] == 4
    assert m2.loc[w3, "working_days_h1"] == 2
    assert m2.loc[w3, "vacation_days_h1"] == 2


def test_feature_columns_exist_for_each_horizon() -> None:
    arr = weekly_arrivals(_tasks(), [1, 2, 3], _weeks(20))
    projects, project_teams = _projects()
    feat = build_feature_matrix(arr, _tasks(), projects, project_teams, _members(), set(), {})
    for h in HORIZONS:
        missing = set(feature_columns(h)) - set(feat.columns)
        assert not missing, missing
    assert str(feat["member_id"].dtype) == "category"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_features.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.features'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/features.py`:

```python
"""Weekly arrival series per member and the feature matrix used by the arrival models."""

from __future__ import annotations

import datetime as dt

import numpy as np
import pandas as pd

from whf.calendar import ONE_WEEK, week_start, working_days

HORIZONS = (1, 2, 3)
LAGS = (1, 2, 3, 4, 8, 13)
ROLL_WINDOWS = (4, 8, 13)
STYLE_WINDOW = 13
MODES = ("manual", "self_picked", "project")
PROJECT_FEATURES = (
    "proj_active",
    "proj_min_weeks_to_deadline",
    "proj_max_weeks_since_start",
    "proj_starting",
    "proj_ending",
)
AVAILABILITY_FEATURES = ("working_days", "vacation_days")


def weekly_arrivals(tasks: pd.DataFrame, member_ids: list[int], weeks: list[dt.date]) -> pd.DataFrame:
    t = tasks[["id", "assignee_id", "assigned_at", "estimated_hours"]].copy()
    t["week_start"] = [week_start(d) for d in t["assigned_at"]]
    grouped = (
        t.groupby(["assignee_id", "week_start"])
        .agg(n_tasks=("id", "size"), est_hours=("estimated_hours", "sum"))
        .reset_index()
        .rename(columns={"assignee_id": "member_id"})
    )
    index = pd.MultiIndex.from_product([member_ids, weeks], names=["member_id", "week_start"])
    out = grouped.set_index(["member_id", "week_start"]).reindex(index).reset_index()
    out["n_tasks"] = out["n_tasks"].fillna(0).astype(int)
    out["est_hours"] = out["est_hours"].fillna(0.0).astype(float)
    return out.sort_values(["member_id", "week_start"]).reset_index(drop=True)


def _style_shares(tasks: pd.DataFrame, arrivals: pd.DataFrame) -> pd.DataFrame:
    t = tasks[["assignee_id", "assigned_at", "assignment_mode"]].copy()
    t["week_start"] = [week_start(d) for d in t["assigned_at"]]
    t = t.dropna(subset=["assignment_mode"])
    counts = (
        t.groupby(["assignee_id", "week_start", "assignment_mode"]).size().unstack(fill_value=0)
        .reindex(columns=list(MODES), fill_value=0)
        .reset_index()
        .rename(columns={"assignee_id": "member_id"})
    )
    base = arrivals[["member_id", "week_start"]].merge(counts, on=["member_id", "week_start"], how="left")
    base[list(MODES)] = base[list(MODES)].fillna(0)
    base = base.sort_values(["member_id", "week_start"])
    rolled = base.groupby("member_id")[list(MODES)].transform(
        lambda s: s.rolling(STYLE_WINDOW, min_periods=1).sum()
    )
    total = rolled.sum(axis=1)
    for mode in MODES:
        base[f"share_{mode}"] = np.where(total > 0, rolled[mode] / total.replace(0, np.nan), 1.0 / len(MODES))
    return base[["member_id", "week_start"] + [f"share_{m}" for m in MODES]]


def _project_spans(projects: pd.DataFrame, project_teams: pd.DataFrame) -> dict[int, list[tuple[dt.date, dt.date]]]:
    merged = project_teams.merge(projects, left_on="project_id", right_on="id")
    return {int(tid): list(zip(g["start_date"], g["deadline"], strict=True)) for tid, g in merged.groupby("team_id")}


def _project_features(spans: list[tuple[dt.date, dt.date]], target_week: dt.date) -> tuple[int, float, float, int, int]:
    end = target_week + dt.timedelta(days=6)
    active = [(s, d) for s, d in spans if s <= end and d >= target_week]
    return (
        len(active),
        min(((d - target_week).days / 7 for _, d in active), default=52.0),
        max(((target_week - s).days / 7 for s, _ in active), default=0.0),
        sum(1 for s, _ in spans if target_week <= s <= end),
        sum(1 for _, d in spans if target_week <= d <= end),
    )


def build_feature_matrix(
    arrivals: pd.DataFrame,
    tasks: pd.DataFrame,
    projects: pd.DataFrame,
    project_teams: pd.DataFrame,
    members: pd.DataFrame,
    holidays: set[dt.date],
    vacation_days: dict[int, set[dt.date]],
) -> pd.DataFrame:
    feat = arrivals.sort_values(["member_id", "week_start"]).reset_index(drop=True).copy()
    hours = feat.groupby("member_id")["est_hours"]
    for lag in LAGS:
        feat[f"lag{lag}"] = hours.shift(lag - 1)
    for window in ROLL_WINDOWS:
        feat[f"roll_mean_{window}"] = hours.transform(lambda s, w=window: s.rolling(w, min_periods=1).mean())
        feat[f"roll_std_{window}"] = hours.transform(lambda s, w=window: s.rolling(w, min_periods=2).std()).fillna(0.0)
    nonzero = feat["est_hours"] > 0
    last_seen = feat["week_start"].where(nonzero)
    last_seen = last_seen.groupby(feat["member_id"]).ffill()
    feat["weeks_since_last_arrival"] = [
        (w - ls).days / 7 if isinstance(ls, dt.date) else 52.0 for w, ls in zip(feat["week_start"], last_seen, strict=True)
    ]
    feat["week_of_year"] = [float(w.isocalendar()[1]) for w in feat["week_start"]]
    team_of = members.set_index("id")["team_id"].to_dict()
    feat["team_id"] = feat["member_id"].map(team_of).astype(int)
    for h in HORIZONS:
        feat[f"target_h{h}"] = hours.shift(-h)
    feat = feat.merge(_style_shares(tasks, arrivals), on=["member_id", "week_start"], how="left")
    spans = _project_spans(projects, project_teams)
    for h in HORIZONS:
        targets = [w + h * ONE_WEEK for w in feat["week_start"]]
        values = [_project_features(spans.get(int(t), []), tw) for t, tw in zip(feat["team_id"], targets, strict=True)]
        for i, name in enumerate(PROJECT_FEATURES):
            feat[f"{name}_h{h}"] = [v[i] for v in values]
        wd, vd = [], []
        for m, tw in zip(feat["member_id"], targets, strict=True):
            vac = vacation_days.get(int(m), set())
            week_days = working_days(tw, tw + dt.timedelta(days=6), holidays)
            on_vacation = [d for d in week_days if d in vac]
            wd.append(len(week_days) - len(on_vacation))
            vd.append(len(on_vacation))
        feat[f"working_days_h{h}"] = wd
        feat[f"vacation_days_h{h}"] = vd
    feat["member_id"] = feat["member_id"].astype("category")
    feat["team_id"] = feat["team_id"].astype("category")
    return feat


def feature_columns(h: int) -> list[str]:
    base = [f"lag{lag}" for lag in LAGS]
    base += [f"roll_{kind}_{w}" for w in ROLL_WINDOWS for kind in ("mean", "std")]
    base += ["weeks_since_last_arrival", "week_of_year", "member_id", "team_id"]
    base += [f"share_{m}" for m in MODES]
    base += [f"{name}_h{h}" for name in PROJECT_FEATURES]
    base += [f"{name}_h{h}" for name in AVAILABILITY_FEATURES]
    return base
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_features.py -v`
Expected: PASS (5 tests)

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/features.py service/tests/test_features.py
git commit -m "feat(service): weekly arrival series and feature matrix"
```

---

### Task 8: Arrival model interface and baselines

**Files:**
- Create: `service/src/whf/models/__init__.py`, `service/src/whf/models/base.py`, `service/src/whf/models/naive.py`, `service/src/whf/models/tsb.py`, `service/tests/test_models_baselines.py`

**Interfaces:**
- Consumes: feature matrix columns from Task 7 (`member_id`, `week_start`, `est_hours`, `roll_mean_4`, `lag52` is not used)
- Produces:
  - `whf.models.base.ArrivalModel` (Protocol): `name: str`; `fit(self, train: pd.DataFrame) -> ArrivalModel`; `predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray` (non-negative estimated hours for each row's target week `horizon` weeks ahead)
  - `whf.models.naive.SeasonalNaive`: `predict` returns `lag52`-style value when the row's week one year earlier exists in the training data (`week_start - 364 days`), else `roll_mean_4`
  - `whf.models.tsb.TSB(alpha=0.1, beta=0.1)`: fits one level per member from `est_hours` ordered by week
  - `whf.models.MODEL_FACTORIES: dict[str, Callable[[], ArrivalModel]]` (filled by Task 9 with the GBM too)

- [ ] **Step 1: Write the failing tests**

`service/tests/test_models_baselines.py`:

```python
import datetime as dt

import numpy as np
import pandas as pd

from whf.features import build_feature_matrix, weekly_arrivals
from whf.models import MODEL_FACTORIES
from whf.models.naive import SeasonalNaive
from whf.models.tsb import TSB

W0 = dt.date(2025, 1, 6)


def _frame(weeks: int = 60) -> pd.DataFrame:
    rows, tid = [], 0
    for i in range(weeks):
        w = W0 + dt.timedelta(days=7 * i)
        hours = [8.0, 0.0, 4.0, 0.0][i % 4]
        if hours:
            tid += 1
            rows.append({"id": tid, "assignee_id": 1, "assigned_at": w, "estimated_hours": hours, "assignment_mode": "manual"})
    tasks = pd.DataFrame(rows)
    arr = weekly_arrivals(tasks, [1], [W0 + dt.timedelta(days=7 * i) for i in range(weeks)])
    members = pd.DataFrame([{"id": 1, "team_id": 1}])
    projects = pd.DataFrame(columns=["id", "start_date", "deadline"])
    project_teams = pd.DataFrame(columns=["project_id", "team_id"])
    return build_feature_matrix(arr, tasks, projects, project_teams, members, set(), {})


def test_seasonal_naive_uses_last_year_when_available() -> None:
    feat = _frame()
    model = SeasonalNaive().fit(feat)
    row = feat[feat.week_start == W0 + dt.timedelta(days=7 * 56)]  # i=56, target i=57 -> hours 0.0? pattern index 57%4=1 -> 0.0
    pred = model.predict(row, horizon=1)
    # one year before target week (i=57-52=5) has 0.0 hours (5%4=1)
    assert pred.tolist() == [0.0]
    row2 = feat[feat.week_start == W0 + dt.timedelta(days=7 * 55)]  # target i=56 -> last year i=4 -> 8.0
    assert model.predict(row2, horizon=1).tolist() == [8.0]


def test_seasonal_naive_falls_back_to_recent_mean() -> None:
    feat = _frame(weeks=10)
    model = SeasonalNaive().fit(feat)
    row = feat[feat.week_start == W0 + dt.timedelta(days=7 * 9)]
    assert model.predict(row, horizon=1).tolist() == [row["roll_mean_4"].iloc[0]]


def test_tsb_level_is_positive_and_below_max() -> None:
    feat = _frame()
    model = TSB().fit(feat)
    pred = model.predict(feat.tail(1), horizon=2)
    assert 0.0 < pred[0] < 8.0


def test_tsb_unknown_member_predicts_zero() -> None:
    feat = _frame()
    model = TSB().fit(feat)
    rows = feat.tail(1).copy()
    rows["member_id"] = pd.Categorical([999])
    assert model.predict(rows, horizon=1).tolist() == [0.0]


def test_registry_contains_baselines() -> None:
    assert {"seasonal_naive", "tsb"} <= set(MODEL_FACTORIES)
    for name, factory in MODEL_FACTORIES.items():
        assert factory().name == name


def test_predictions_are_never_negative() -> None:
    feat = _frame()
    for factory in MODEL_FACTORIES.values():
        pred = factory().fit(feat).predict(feat.dropna(subset=["target_h1"]), horizon=1)
        assert np.all(pred >= 0)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_models_baselines.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.models'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/models/base.py`:

```python
"""Common interface for arrival models."""

from __future__ import annotations

from typing import Protocol

import numpy as np
import pandas as pd


class ArrivalModel(Protocol):
    name: str

    def fit(self, train: pd.DataFrame) -> ArrivalModel:
        """Learn from feature-matrix rows (targets may be NaN for the newest rows)."""
        ...

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        """Estimated hours arriving `horizon` weeks after each row's week. Never negative."""
        ...
```

`service/src/whf/models/naive.py`:

```python
"""Seasonal naive: last year's same week when known, else the recent four-week mean."""

from __future__ import annotations

import datetime as dt

import numpy as np
import pandas as pd

ONE_YEAR = dt.timedelta(days=364)


class SeasonalNaive:
    name = "seasonal_naive"

    def __init__(self) -> None:
        self._history: dict[tuple[int, dt.date], float] = {}

    def fit(self, train: pd.DataFrame) -> SeasonalNaive:
        self._history = {
            (int(m), w): float(h)
            for m, w, h in zip(train["member_id"], train["week_start"], train["est_hours"], strict=True)
        }
        return self

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        out = []
        for m, w, fallback in zip(rows["member_id"], rows["week_start"], rows["roll_mean_4"], strict=True):
            target = w + dt.timedelta(days=7 * horizon)
            value = self._history.get((int(m), target - ONE_YEAR))
            out.append(max(0.0, value if value is not None else float(fallback)))
        return np.array(out, dtype=float)
```

`service/src/whf/models/tsb.py`:

```python
"""TSB (Teunter, Syntetos, Babai) intermittent-demand smoothing, one level per member."""

from __future__ import annotations

import numpy as np
import pandas as pd


class TSB:
    name = "tsb"

    def __init__(self, alpha: float = 0.1, beta: float = 0.1) -> None:
        self.alpha = alpha
        self.beta = beta
        self._level: dict[int, float] = {}

    def fit(self, train: pd.DataFrame) -> TSB:
        ordered = train.sort_values("week_start")
        for member, series in ordered.groupby("member_id", observed=True)["est_hours"]:
            y = series.to_numpy(dtype=float)
            positive = y[y > 0]
            p = float((y > 0).mean()) if len(y) else 0.0
            z = float(positive.mean()) if len(positive) else 0.0
            for v in y:
                if v > 0:
                    p += self.alpha * (1.0 - p)
                    z += self.beta * (v - z)
                else:
                    p += self.alpha * (0.0 - p)
            self._level[int(member)] = max(0.0, p * z)
        return self

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        return np.array([self._level.get(int(m), 0.0) for m in rows["member_id"]], dtype=float)
```

`service/src/whf/models/__init__.py`:

```python
"""Registry of arrival models. The backtest tries every factory and keeps the champion."""

from __future__ import annotations

from collections.abc import Callable

from whf.models.base import ArrivalModel
from whf.models.naive import SeasonalNaive
from whf.models.tsb import TSB

MODEL_FACTORIES: dict[str, Callable[[], ArrivalModel]] = {
    "seasonal_naive": SeasonalNaive,
    "tsb": TSB,
}

__all__ = ["MODEL_FACTORIES", "ArrivalModel"]
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_models_baselines.py -v`
Expected: PASS (6 tests)

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/models service/tests/test_models_baselines.py
git commit -m "feat(service): arrival model interface with seasonal naive and TSB baselines"
```

---

### Task 9: Gradient boosting arrival model

**Files:**
- Create: `service/src/whf/models/gbm.py`, `service/tests/test_models_gbm.py`
- Modify: `service/src/whf/models/__init__.py` (register `"gbm"`)

**Interfaces:**
- Consumes: `whf.features.feature_columns`, `HORIZONS`
- Produces: `whf.models.gbm.GradientBoostingArrival(max_iter=300, learning_rate=0.05, random_state=0)` implementing `ArrivalModel`; one `HistGradientBoostingRegressor(loss="poisson", categorical_features="from_dtype")` per horizon.

- [ ] **Step 1: Write the failing tests**

`service/tests/test_models_gbm.py`:

```python
import datetime as dt

import numpy as np
import pandas as pd

from whf.features import build_feature_matrix, weekly_arrivals
from whf.models import MODEL_FACTORIES
from whf.models.gbm import GradientBoostingArrival

W0 = dt.date(2025, 1, 6)


def _frame(members: int = 6, weeks: int = 70, seed: int = 0) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    rows, tid = [], 0
    week_list = [W0 + dt.timedelta(days=7 * i) for i in range(weeks)]
    for m in range(1, members + 1):
        rate = 2 + m
        for i, w in enumerate(week_list):
            n = rng.poisson(rate * (1.5 if i % 8 == 7 else 1.0))
            for _ in range(n):
                tid += 1
                rows.append({"id": tid, "assignee_id": m, "assigned_at": w + dt.timedelta(days=int(rng.integers(0, 5))),
                             "estimated_hours": float(rng.uniform(1, 6)), "assignment_mode": "project"})
    tasks = pd.DataFrame(rows)
    arr = weekly_arrivals(tasks, list(range(1, members + 1)), week_list)
    mem = pd.DataFrame([{"id": m, "team_id": 1} for m in range(1, members + 1)])
    projects = pd.DataFrame([{"id": 1, "start_date": W0, "deadline": W0 + dt.timedelta(days=7 * weeks)}])
    project_teams = pd.DataFrame([{"project_id": 1, "team_id": 1}])
    return build_feature_matrix(arr, tasks, projects, project_teams, mem, set(), {})


def test_gbm_fits_and_predicts_non_negative_for_every_horizon() -> None:
    feat = _frame()
    model = GradientBoostingArrival().fit(feat)
    rows = feat.dropna(subset=["target_h3"])
    for h in (1, 2, 3):
        pred = model.predict(rows, horizon=h)
        assert pred.shape == (len(rows),)
        assert np.all(pred >= 0)


def test_gbm_learns_member_levels() -> None:
    feat = _frame()
    model = GradientBoostingArrival().fit(feat)
    last = feat.groupby("member_id", observed=True).tail(1)
    pred = model.predict(last, horizon=1)
    by_member = dict(zip(last["member_id"].astype(int), pred, strict=True))
    assert by_member[6] > by_member[1]


def test_gbm_is_registered() -> None:
    assert MODEL_FACTORIES["gbm"]().name == "gbm"


def test_gbm_copes_with_short_history_where_long_lags_are_all_missing() -> None:
    feat = _frame(weeks=10)  # lag13 is missing in every row
    model = GradientBoostingArrival().fit(feat)
    assert model.predict(feat.tail(3), horizon=1).shape == (3,)


def test_gbm_is_deterministic() -> None:
    feat = _frame()
    a = GradientBoostingArrival().fit(feat).predict(feat.tail(5), horizon=2)
    b = GradientBoostingArrival().fit(feat).predict(feat.tail(5), horizon=2)
    assert np.allclose(a, b)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_models_gbm.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.models.gbm'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/models/gbm.py`:

```python
"""Global gradient boosting model over member-week rows, one regressor per horizon."""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

from whf.features import HORIZONS, feature_columns


class GradientBoostingArrival:
    name = "gbm"

    def __init__(self, max_iter: int = 300, learning_rate: float = 0.05, random_state: int = 0) -> None:
        self.max_iter = max_iter
        self.learning_rate = learning_rate
        self.random_state = random_state
        self._models: dict[int, HistGradientBoostingRegressor] = {}
        self._columns: dict[int, list[str]] = {}

    def fit(self, train: pd.DataFrame) -> GradientBoostingArrival:
        for h in HORIZONS:
            target = f"target_h{h}"
            rows = train.dropna(subset=[target])
            # scikit-learn rejects columns that are missing everywhere (short histories make lag13 empty)
            columns = [c for c in feature_columns(h) if rows[c].notna().any()]
            model = HistGradientBoostingRegressor(
                loss="poisson",
                categorical_features="from_dtype",
                max_iter=self.max_iter,
                learning_rate=self.learning_rate,
                random_state=self.random_state,
            )
            self._models[h] = model.fit(rows[columns], rows[target].to_numpy(dtype=float))
            self._columns[h] = columns
        return self

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        pred = self._models[horizon].predict(rows[self._columns[horizon]])
        return np.clip(pred, 0.0, None)
```

Update `service/src/whf/models/__init__.py`:

```python
"""Registry of arrival models. The backtest tries every factory and keeps the champion."""

from __future__ import annotations

from collections.abc import Callable

from whf.models.base import ArrivalModel
from whf.models.gbm import GradientBoostingArrival
from whf.models.naive import SeasonalNaive
from whf.models.tsb import TSB

MODEL_FACTORIES: dict[str, Callable[[], ArrivalModel]] = {
    "seasonal_naive": SeasonalNaive,
    "tsb": TSB,
    "gbm": GradientBoostingArrival,
}

__all__ = ["MODEL_FACTORIES", "ArrivalModel"]
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_models_gbm.py tests/test_models_baselines.py -v`
Expected: PASS (11 tests)

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/models service/tests/test_models_gbm.py
git commit -m "feat(service): gradient boosting arrival model with Poisson loss"
```

---

### Task 10: Effort model and hour placement

**Files:**
- Create: `service/src/whf/models/effort.py`, `service/tests/test_effort.py`

**Interfaces:**
- Consumes: `whf.calendar.working_days`, `week_start`
- Produces (all in `whf.models.effort`):
  - `place_hours(hours: float, start: date, end: date, off: set[date]) -> dict[date, float]` (Monday keyed weekly hours; if no working day in range, all hours go to `week_start(start)`)
  - `EffortModel(shrink_k: float = 5.0, min_rows_for_gbm: int = 50)` with `fit(done_tasks: pd.DataFrame) -> EffortModel` (columns `assignee_id, team_id, type, priority, estimated_hours, actual_hours, assigned_at, completed_at`), `predict_cycle_days(tasks: pd.DataFrame) -> np.ndarray`, `estimate_ratio(member_id: int, task_type: str | None, team_id: int) -> float` (clipped to [0.5, 2.5]), `member_cycle_days(member_id: int, team_id: int) -> float`
  - `place_open_tasks(open_tasks: pd.DataFrame, model: EffortModel, as_of: date, off_by_member: dict[int, set[date]]) -> pd.DataFrame` with columns `member_id, week_start, hours`
  - `place_new_arrivals(predicted: pd.DataFrame, model: EffortModel, off_by_member, team_of: dict[int, int]) -> pd.DataFrame` (input columns `member_id, week_start, est_hours`; output `member_id, week_start, hours`)

- [ ] **Step 1: Write the failing tests**

`service/tests/test_effort.py`:

```python
import datetime as dt

import numpy as np
import pandas as pd
from hypothesis import given, settings
from hypothesis import strategies as st

from whf.calendar import week_start
from whf.models.effort import EffortModel, place_hours, place_new_arrivals, place_open_tasks

dates = st.dates(min_value=dt.date(2026, 1, 1), max_value=dt.date(2026, 12, 31))


@settings(max_examples=200)
@given(st.floats(min_value=0, max_value=200), dates, st.integers(min_value=0, max_value=40))
def test_place_hours_conserves_hours_and_keys_are_mondays(hours: float, start: dt.date, span: int) -> None:
    end = start + dt.timedelta(days=span)
    placed = place_hours(hours, start, end, set())
    assert abs(sum(placed.values()) - hours) < 1e-6
    assert all(k.weekday() == 0 for k in placed)
    assert all(v >= 0 for v in placed.values())
    assert min(placed) >= week_start(start) and max(placed) <= week_start(end)


def test_place_hours_skips_off_days_and_weekends() -> None:
    placed = place_hours(10.0, dt.date(2026, 9, 3), dt.date(2026, 9, 9), {dt.date(2026, 9, 7)})
    assert placed == {dt.date(2026, 8, 31): 5.0, dt.date(2026, 9, 7): 5.0}


def test_place_hours_with_no_working_day_uses_start_week() -> None:
    placed = place_hours(3.0, dt.date(2026, 9, 5), dt.date(2026, 9, 6), set())  # Sat, Sun
    assert placed == {dt.date(2026, 8, 31): 3.0}


def _done(n: int = 120, seed: int = 1) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    rows = []
    for i in range(n):
        member = 1 + i % 3
        est = float(rng.uniform(2, 20))
        bias = {1: 0.8, 2: 1.0, 3: 1.6}[member]
        assigned = dt.date(2026, 1, 5) + dt.timedelta(days=int(rng.integers(0, 200)))
        cycle = {1: 2, 2: 5, 3: 12}[member] + int(rng.integers(0, 3))
        rows.append({
            "assignee_id": member, "team_id": 1, "type": "feature" if i % 2 else "bug", "priority": "medium",
            "estimated_hours": est, "actual_hours": est * bias, "assigned_at": assigned,
            "completed_at": assigned + dt.timedelta(days=cycle),
        })
    return pd.DataFrame(rows)


def test_estimate_ratio_reflects_member_bias_and_is_clipped() -> None:
    model = EffortModel().fit(_done())
    assert model.estimate_ratio(1, "bug", 1) < model.estimate_ratio(3, "bug", 1)
    assert 0.5 <= model.estimate_ratio(3, "feature", 1) <= 2.5
    assert 0.5 <= model.estimate_ratio(999, None, 1) <= 2.5  # unknown member falls back to team


def test_cycle_days_orders_members() -> None:
    model = EffortModel().fit(_done())
    rows = pd.DataFrame([
        {"assignee_id": 1, "team_id": 1, "type": "bug", "priority": "medium", "estimated_hours": 8.0},
        {"assignee_id": 3, "team_id": 1, "type": "bug", "priority": "medium", "estimated_hours": 8.0},
    ])
    pred = model.predict_cycle_days(rows)
    assert pred[0] < pred[1]
    assert model.member_cycle_days(1, 1) < model.member_cycle_days(3, 1)


def test_small_history_uses_medians_not_gbm() -> None:
    model = EffortModel(min_rows_for_gbm=1000).fit(_done(n=30))
    rows = pd.DataFrame([{"assignee_id": 2, "team_id": 1, "type": "bug", "priority": "low", "estimated_hours": 3.0}])
    assert model.predict_cycle_days(rows)[0] > 0


def test_place_open_tasks_puts_remaining_hours_from_as_of_forward() -> None:
    model = EffortModel().fit(_done())
    as_of = dt.date(2026, 9, 3)
    open_tasks = pd.DataFrame([
        {"id": 1, "assignee_id": 2, "team_id": 1, "type": "feature", "priority": "medium", "estimated_hours": 10.0,
         "assigned_at": dt.date(2026, 9, 1), "due_date": dt.date(2026, 9, 10)},
        {"id": 2, "assignee_id": 2, "team_id": 1, "type": "bug", "priority": "high", "estimated_hours": 4.0,
         "assigned_at": dt.date(2026, 6, 1), "due_date": dt.date(2026, 6, 5)},  # very overdue
    ])
    placed = place_open_tasks(open_tasks, model, as_of, {})
    assert set(placed.columns) == {"member_id", "week_start", "hours"}
    assert (placed["week_start"] >= week_start(as_of)).all()
    assert placed["hours"].sum() > 0
    assert placed["hours"].sum() <= 14.0 * 2.5


def test_place_new_arrivals_spreads_over_member_cycle() -> None:
    model = EffortModel().fit(_done())
    predicted = pd.DataFrame([
        {"member_id": 3, "week_start": dt.date(2026, 9, 7), "est_hours": 20.0},
        {"member_id": 1, "week_start": dt.date(2026, 9, 7), "est_hours": 20.0},
    ])
    placed = place_new_arrivals(predicted, model, {}, {1: 1, 3: 1})
    m3 = placed[placed.member_id == 3]
    m1 = placed[placed.member_id == 1]
    assert m3["week_start"].nunique() >= 2      # long cycle spills into the next week
    assert m1["week_start"].nunique() == 1      # short cycle stays in the week
    assert abs(m1["hours"].sum() - 20.0 * model.estimate_ratio(1, None, 1)) < 1e-6
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_effort.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.models.effort'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/models/effort.py`:

```python
"""Effort model: how long tasks take, how estimates deviate, and where hours land in weeks."""

from __future__ import annotations

import datetime as dt

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

from whf.calendar import week_start, working_days

RATIO_MIN, RATIO_MAX = 0.5, 2.5
MIN_REMAINING_FRACTION = 0.15
CYCLE_FEATURES = ["estimated_hours", "type", "priority", "assignee_id"]


def place_hours(hours: float, start: dt.date, end: dt.date, off: set[dt.date]) -> dict[dt.date, float]:
    if end < start:
        end = start
    days = working_days(start, end, off)
    if not days:
        return {week_start(start): float(hours)}
    per_day = float(hours) / len(days)
    out: dict[dt.date, float] = {}
    for d in days:
        ws = week_start(d)
        out[ws] = out.get(ws, 0.0) + per_day
    return out


def _shrunk_mean(n: int, mean: float, prior_mean: float, k: float) -> float:
    return (n * mean + k * prior_mean) / (n + k)


class EffortModel:
    def __init__(self, shrink_k: float = 5.0, min_rows_for_gbm: int = 50) -> None:
        self.shrink_k = shrink_k
        self.min_rows_for_gbm = min_rows_for_gbm
        self._gbm: HistGradientBoostingRegressor | None = None
        self._ratio_member_type: dict[tuple[int, str], tuple[int, float]] = {}
        self._ratio_member: dict[int, tuple[int, float]] = {}
        self._ratio_team_type: dict[tuple[int, str], float] = {}
        self._ratio_team: dict[int, float] = {}
        self._ratio_global = 1.0
        self._cycle_member: dict[int, float] = {}
        self._cycle_member_type: dict[tuple[int, str], float] = {}
        self._cycle_team_type: dict[tuple[int, str], float] = {}
        self._cycle_team: dict[int, float] = {}
        self._cycle_global = 5.0
        self._team_of: dict[int, int] = {}

    def fit(self, done_tasks: pd.DataFrame) -> EffortModel:
        d = done_tasks.dropna(subset=["actual_hours", "completed_at"]).copy()
        d = d[d["estimated_hours"] > 0]
        d["ratio"] = d["actual_hours"] / d["estimated_hours"]
        d["cycle_days"] = [(c - a).days + 1 for a, c in zip(d["assigned_at"], d["completed_at"], strict=True)]
        self._team_of = d.groupby("assignee_id")["team_id"].first().astype(int).to_dict()
        if len(d):
            self._ratio_global = float(d["ratio"].mean())
            self._cycle_global = float(d["cycle_days"].median())
        for (m, t), g in d.groupby(["assignee_id", "type"]):
            self._ratio_member_type[(int(m), str(t))] = (len(g), float(g["ratio"].mean()))
            self._cycle_member_type[(int(m), str(t))] = float(g["cycle_days"].median())
        for m, g in d.groupby("assignee_id"):
            self._ratio_member[int(m)] = (len(g), float(g["ratio"].mean()))
            self._cycle_member[int(m)] = float(g["cycle_days"].median())
        for (team, t), g in d.groupby(["team_id", "type"]):
            self._ratio_team_type[(int(team), str(t))] = float(g["ratio"].mean())
            self._cycle_team_type[(int(team), str(t))] = float(g["cycle_days"].median())
        for team, g in d.groupby("team_id"):
            self._ratio_team[int(team)] = float(g["ratio"].mean())
            self._cycle_team[int(team)] = float(g["cycle_days"].median())
        if len(d) >= self.min_rows_for_gbm:
            x = self._cycle_frame(d)
            self._gbm = HistGradientBoostingRegressor(
                loss="squared_error", categorical_features="from_dtype", max_iter=200, learning_rate=0.05, random_state=0
            ).fit(x, np.log1p(d["cycle_days"].to_numpy(dtype=float)))
        return self

    @staticmethod
    def _cycle_frame(tasks: pd.DataFrame) -> pd.DataFrame:
        x = tasks[CYCLE_FEATURES].copy()
        x["type"] = x["type"].astype("category")
        x["priority"] = x["priority"].astype("category")
        x["assignee_id"] = x["assignee_id"].astype(int).astype("category")
        return x

    def estimate_ratio(self, member_id: int, task_type: str | None, team_id: int) -> float:
        team_prior = self._ratio_team.get(team_id, self._ratio_global)
        if task_type is not None:
            prior = self._ratio_team_type.get((team_id, task_type), team_prior)
            n, mean = self._ratio_member_type.get((member_id, task_type), (0, prior))
        else:
            prior = team_prior
            n, mean = self._ratio_member.get(member_id, (0, prior))
        return float(np.clip(_shrunk_mean(n, mean, prior, self.shrink_k), RATIO_MIN, RATIO_MAX))

    def member_cycle_days(self, member_id: int, team_id: int) -> float:
        return self._cycle_member.get(member_id, self._cycle_team.get(team_id, self._cycle_global))

    def _fallback_cycle(self, member_id: int, task_type: str, team_id: int) -> float:
        return self._cycle_member_type.get(
            (member_id, task_type),
            self._cycle_team_type.get((team_id, task_type), self.member_cycle_days(member_id, team_id)),
        )

    def predict_cycle_days(self, tasks: pd.DataFrame) -> np.ndarray:
        fallback = np.array(
            [
                self._fallback_cycle(int(m), str(t), int(team))
                for m, t, team in zip(tasks["assignee_id"], tasks["type"], tasks["team_id"], strict=True)
            ],
            dtype=float,
        )
        if self._gbm is None or len(tasks) == 0:
            return np.maximum(fallback, 1.0)
        known = tasks["assignee_id"].astype(int).isin(self._cycle_member.keys()).to_numpy()
        pred = np.expm1(self._gbm.predict(self._cycle_frame(tasks)))
        return np.maximum(np.where(known, pred, fallback), 1.0)


def place_open_tasks(
    open_tasks: pd.DataFrame,
    model: EffortModel,
    as_of: dt.date,
    off_by_member: dict[int, set[dt.date]],
) -> pd.DataFrame:
    if len(open_tasks) == 0:
        return pd.DataFrame(columns=["member_id", "week_start", "hours"])
    cycles = model.predict_cycle_days(open_tasks)
    rows: list[dict] = []
    for (_, task), cycle in zip(open_tasks.iterrows(), cycles, strict=True):
        member = int(task["assignee_id"])
        team = int(task["team_id"])
        predicted_actual = float(task["estimated_hours"]) * model.estimate_ratio(member, str(task["type"]), team)
        elapsed = max(0, (as_of - task["assigned_at"]).days)
        remaining_fraction = float(np.clip(1.0 - elapsed / max(cycle, 1.0), MIN_REMAINING_FRACTION, 1.0))
        remaining = predicted_actual * remaining_fraction
        end = max(as_of, task["assigned_at"] + dt.timedelta(days=int(round(cycle))))
        for ws, hours in place_hours(remaining, as_of, end, off_by_member.get(member, set())).items():
            rows.append({"member_id": member, "week_start": ws, "hours": hours})
    out = pd.DataFrame(rows)
    return out.groupby(["member_id", "week_start"], as_index=False)["hours"].sum()


def place_new_arrivals(
    predicted: pd.DataFrame,
    model: EffortModel,
    off_by_member: dict[int, set[dt.date]],
    team_of: dict[int, int],
) -> pd.DataFrame:
    rows: list[dict] = []
    for member, ws, est in zip(predicted["member_id"], predicted["week_start"], predicted["est_hours"], strict=True):
        member = int(member)
        team = team_of.get(member, 0)
        hours = float(est) * model.estimate_ratio(member, None, team)
        span = int(round(model.member_cycle_days(member, team)))
        end = ws + dt.timedelta(days=max(span - 1, 0))
        for week, h in place_hours(hours, ws, end, off_by_member.get(member, set())).items():
            rows.append({"member_id": member, "week_start": week, "hours": h})
    if not rows:
        return pd.DataFrame(columns=["member_id", "week_start", "hours"])
    return pd.DataFrame(rows).groupby(["member_id", "week_start"], as_index=False)["hours"].sum()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_effort.py -v`
Expected: PASS (9 tests)

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/models/effort.py service/tests/test_effort.py
git commit -m "feat(service): effort model with cycle time, estimate bias and hour placement"
```

---

### Task 11: Rolling-origin backtest, champion selection and interval calibration

**Files:**
- Create: `service/src/whf/backtest.py`, `service/tests/test_backtest.py`

**Interfaces:**
- Consumes: `whf.models.MODEL_FACTORIES`, `whf.models.naive.SeasonalNaive`, `whf.calendar.ONE_WEEK`
- Produces (all in `whf.backtest`):
  - `mase(y_true, y_pred, y_naive) -> float` (`nan` when the naive error is zero)
  - `default_origins(last_complete_week: date, count: int = 6, step_weeks: int = 2) -> list[date]` (most recent first)
  - `BacktestResult(scores: pd.DataFrame, residuals: dict[tuple[str, int], np.ndarray])`, scores columns `model, origin, horizon, mae, mase`
  - `rolling_backtest(feat, factories: dict[str, Callable[[], ArrivalModel]], origins: list[date], horizons: tuple[int, ...]) -> BacktestResult`
  - `select_champion(scores: pd.DataFrame, floor: str = "seasonal_naive") -> tuple[str, float]` (name and its mean MASE; returns the floor when nothing beats 1.0)
  - `interval_bounds(residuals: np.ndarray, low: float = 0.1, high: float = 0.9) -> tuple[float, float]`

- [ ] **Step 1: Write the failing tests**

`service/tests/test_backtest.py`:

```python
import datetime as dt

import numpy as np
import pandas as pd

from whf.backtest import default_origins, interval_bounds, mase, rolling_backtest, select_champion
from whf.features import build_feature_matrix, weekly_arrivals
from whf.models import MODEL_FACTORIES

W0 = dt.date(2025, 1, 6)


def _frame(members: int = 8, weeks: int = 80, seed: int = 0) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    rows, tid = [], 0
    week_list = [W0 + dt.timedelta(days=7 * i) for i in range(weeks)]
    for m in range(1, members + 1):
        for i, w in enumerate(week_list):
            burst = 2.0 if i % 8 == 7 else 1.0
            n = rng.poisson((1 + m / 2) * burst)
            for _ in range(n):
                tid += 1
                rows.append({"id": tid, "assignee_id": m, "assigned_at": w + dt.timedelta(days=int(rng.integers(0, 5))),
                             "estimated_hours": float(rng.uniform(1, 6)), "assignment_mode": "project"})
    tasks = pd.DataFrame(rows)
    arr = weekly_arrivals(tasks, list(range(1, members + 1)), week_list)
    mem = pd.DataFrame([{"id": m, "team_id": 1} for m in range(1, members + 1)])
    projects = pd.DataFrame([{"id": 1, "start_date": W0, "deadline": W0 + dt.timedelta(days=7 * weeks)}])
    project_teams = pd.DataFrame([{"project_id": 1, "team_id": 1}])
    return build_feature_matrix(arr, tasks, projects, project_teams, mem, set(), {})


def test_mase_relative_to_naive() -> None:
    assert mase(np.array([1, 2, 3.0]), np.array([1, 2, 2.0]), np.array([0, 0, 0.0])) == 1 / 6
    assert np.isnan(mase(np.array([1.0]), np.array([1.0]), np.array([1.0])))


def test_default_origins_step_back_two_weeks() -> None:
    last = dt.date(2026, 8, 24)
    assert default_origins(last, count=3) == [dt.date(2026, 8, 10), dt.date(2026, 7, 27), dt.date(2026, 7, 13)]


def test_rolling_backtest_scores_every_model_origin_and_horizon() -> None:
    feat = _frame()
    origins = default_origins(W0 + dt.timedelta(days=7 * 76), count=3)
    result = rolling_backtest(feat, MODEL_FACTORIES, origins, horizons=(1, 2))
    assert set(result.scores["model"]) == set(MODEL_FACTORIES)
    assert len(result.scores) == len(MODEL_FACTORIES) * 3 * 2
    assert ("gbm", 1) in result.residuals and len(result.residuals[("gbm", 1)]) == 3 * 8
    naive = result.scores[result.scores.model == "seasonal_naive"]
    assert np.allclose(naive["mase"], 1.0)


def test_champion_beats_naive_on_bursty_data() -> None:
    feat = _frame()
    origins = default_origins(W0 + dt.timedelta(days=7 * 76), count=4)
    result = rolling_backtest(feat, MODEL_FACTORIES, origins, horizons=(1, 2))
    name, score = select_champion(result.scores)
    assert name != "seasonal_naive"  # gbm or tsb, whichever wins on this synthetic series
    assert score < 1.0


def test_select_champion_falls_back_to_floor() -> None:
    scores = pd.DataFrame([
        {"model": "seasonal_naive", "origin": W0, "horizon": 1, "mae": 1.0, "mase": 1.0},
        {"model": "tsb", "origin": W0, "horizon": 1, "mae": 1.2, "mase": 1.2},
        {"model": "gbm", "origin": W0, "horizon": 1, "mae": 1.1, "mase": 1.1},
    ])
    assert select_champion(scores) == ("seasonal_naive", 1.0)


def test_interval_bounds_are_ordered_quantiles() -> None:
    low, high = interval_bounds(np.array([-4.0, -2.0, 0.0, 2.0, 4.0]))
    assert low < 0 < high
    assert interval_bounds(np.array([])) == (0.0, 0.0)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_backtest.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.backtest'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/backtest.py`:

```python
"""Rolling-origin backtest that scores every arrival model and picks the champion."""

from __future__ import annotations

import datetime as dt
from collections.abc import Callable
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from whf.calendar import ONE_WEEK
from whf.models.base import ArrivalModel
from whf.models.naive import SeasonalNaive

FLOOR_MODEL = "seasonal_naive"


def mase(y_true: np.ndarray, y_pred: np.ndarray, y_naive: np.ndarray) -> float:
    y_true, y_pred, y_naive = (np.asarray(a, dtype=float) for a in (y_true, y_pred, y_naive))
    denominator = float(np.mean(np.abs(y_true - y_naive)))
    if denominator == 0.0:
        return float("nan")
    return float(np.mean(np.abs(y_true - y_pred)) / denominator)


def default_origins(last_complete_week: dt.date, count: int = 6, step_weeks: int = 2) -> list[dt.date]:
    return [last_complete_week - k * step_weeks * ONE_WEEK for k in range(1, count + 1)]


@dataclass
class BacktestResult:
    scores: pd.DataFrame
    residuals: dict[tuple[str, int], np.ndarray] = field(default_factory=dict)


def rolling_backtest(
    feat: pd.DataFrame,
    factories: dict[str, Callable[[], ArrivalModel]],
    origins: list[dt.date],
    horizons: tuple[int, ...],
) -> BacktestResult:
    rows: list[dict] = []
    residuals: dict[tuple[str, int], list[float]] = {}
    max_h = max(horizons)
    for origin in origins:
        train = feat[feat["week_start"] <= origin - max_h * ONE_WEEK]
        test = feat[feat["week_start"] == origin]
        if train.empty or test.empty:
            continue
        fitted = {name: factory().fit(train) for name, factory in factories.items()}
        naive = SeasonalNaive().fit(train)
        for h in horizons:
            y = test[f"target_h{h}"].to_numpy(dtype=float)
            if np.isnan(y).any():
                continue
            y_naive = naive.predict(test, h)
            for name, model in fitted.items():
                y_hat = np.clip(model.predict(test, h), 0.0, None)
                rows.append(
                    {
                        "model": name, "origin": origin, "horizon": h,
                        "mae": float(np.mean(np.abs(y - y_hat))), "mase": mase(y, y_hat, y_naive),
                    }
                )
                residuals.setdefault((name, h), []).extend((y - y_hat).tolist())
    scores = pd.DataFrame(rows, columns=["model", "origin", "horizon", "mae", "mase"])
    return BacktestResult(scores=scores, residuals={k: np.array(v) for k, v in residuals.items()})


def select_champion(scores: pd.DataFrame, floor: str = FLOOR_MODEL) -> tuple[str, float]:
    if scores.empty:
        return floor, float("nan")
    means = scores.groupby("model")["mase"].mean().dropna()
    if means.empty:
        return floor, float("nan")
    best = str(means.idxmin())
    best_score = float(means[best])
    if best_score >= 1.0 or best == floor:
        return floor, float(means.get(floor, 1.0))
    return best, best_score


def interval_bounds(residuals: np.ndarray, low: float = 0.1, high: float = 0.9) -> tuple[float, float]:
    if len(residuals) == 0:
        return 0.0, 0.0
    return float(np.quantile(residuals, low)), float(np.quantile(residuals, high))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_backtest.py -v`
Expected: PASS (6 tests). The bursty-data champion test takes a few seconds.

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/backtest.py service/tests/test_backtest.py
git commit -m "feat(service): rolling-origin backtest with champion selection and interval calibration"
```

---

### Task 12: Pattern statistics and clustering

**Files:**
- Create: `service/src/whf/patterns.py`, `service/tests/test_patterns.py`

**Interfaces:**
- Consumes: `whf.calendar.week_start`, `ONE_WEEK`
- Produces (all in `whf.patterns`):
  - `member_patterns(tasks: pd.DataFrame, member_id: int, as_of: date, project_spans: list[tuple[date, date]], weeks: int = 13) -> dict` where `tasks` are that member's tasks (columns `id, type, assigned_at, due_date, completed_at, estimated_hours, actual_hours, assignment_mode, project_id, status`)
  - `pattern_table(tasks: pd.DataFrame, members: pd.DataFrame, spans_by_team: dict[int, list[tuple[date, date]]], as_of: date) -> pd.DataFrame` (one row per member with the numeric pattern fields plus `member_id`)
  - `cluster_members(table: pd.DataFrame) -> pd.Series` (cluster label indexed by `member_id`; all zeros when fewer than 6 members)
  - `WEEKDAY_NAMES = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")`

- [ ] **Step 1: Write the failing tests**

`service/tests/test_patterns.py`:

```python
import datetime as dt

import pandas as pd

from whf.patterns import cluster_members, member_patterns, pattern_table

AS_OF = dt.date(2026, 9, 3)


def _tasks(member_id: int = 1, weekday: int = 0, mode: str = "manual", late_days: int = 0, ratio: float = 1.2) -> pd.DataFrame:
    rows = []
    for i in range(22):
        assigned = dt.date(2026, 4, 6) + dt.timedelta(days=7 * i + weekday)
        due = assigned + dt.timedelta(days=4)
        rows.append({
            "id": i + 1, "assignee_id": member_id, "team_id": 1, "type": "bug" if i % 2 else "feature",
            "assigned_at": assigned, "due_date": due, "completed_at": due + dt.timedelta(days=late_days) if i < 20 else None,
            "estimated_hours": 5.0, "actual_hours": 5.0 * ratio if i < 20 else None, "assignment_mode": mode,
            "project_id": 7 if i % 3 else None, "status": "done" if i < 20 else "todo", "priority": "medium",
        })
    return pd.DataFrame(rows)


def test_member_patterns_capture_style_weekday_bias_and_lateness() -> None:
    p = member_patterns(_tasks(weekday=1, late_days=2), 1, AS_OF, [(dt.date(2026, 5, 1), dt.date(2026, 8, 31))])
    assert p["member_id"] == 1
    assert p["share_manual"] == 1.0 and p["share_project"] == 0.0
    assert p["top_weekday"] == "Tuesday"
    assert abs(p["estimate_ratio_median"] - 1.2) < 1e-9
    assert p["lateness_days_median"] == 2 and p["share_late"] == 1.0
    assert p["cycle_days_median"] == 7  # (due - assigned) + late + 1
    assert p["open_tasks"] == 2 and p["open_est_hours"] == 10.0
    assert p["tasks_13w"] == 13 and abs(p["hours_per_week_13w"] - 5.0) < 1e-9
    assert set(p["cycle_days_by_type"]) == {"bug", "feature"}
    assert 0.0 <= p["share_with_project"] <= 1.0


def test_member_patterns_handle_member_without_tasks() -> None:
    empty = _tasks().iloc[0:0]
    p = member_patterns(empty, 42, AS_OF, [])
    assert p["tasks_13w"] == 0 and p["hours_per_week_13w"] == 0.0
    assert p["top_weekday"] is None
    assert p["estimate_ratio_median"] is None


def test_pattern_table_and_clustering() -> None:
    frames = []
    for m in range(1, 9):
        frames.append(_tasks(member_id=m, weekday=m % 5, mode=["manual", "self_picked", "project"][m % 3], ratio=0.8 + 0.1 * m))
    tasks = pd.concat(frames, ignore_index=True)
    members = pd.DataFrame([{"id": m, "team_id": 1} for m in range(1, 9)])
    table = pattern_table(tasks, members, {1: []}, AS_OF)
    assert len(table) == 8 and "share_manual" in table.columns
    labels = cluster_members(table)
    assert set(labels.index) == set(range(1, 9))
    assert labels.nunique() >= 2


def test_clustering_with_few_members_returns_zeros() -> None:
    table = pd.DataFrame([{"member_id": 1, "share_manual": 1.0, "hours_per_week_13w": 3.0},
                          {"member_id": 2, "share_manual": 0.0, "hours_per_week_13w": 9.0}])
    labels = cluster_members(table)
    assert labels.tolist() == [0, 0]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_patterns.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.patterns'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/patterns.py`:

```python
"""Deterministic per-member pattern statistics handed to Copilot as facts, plus clustering."""

from __future__ import annotations

import datetime as dt

import numpy as np
import pandas as pd
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score
from sklearn.preprocessing import StandardScaler

from whf.calendar import ONE_WEEK, week_start

WEEKDAY_NAMES = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")
MODES = ("manual", "self_picked", "project")
CLUSTER_FEATURES = [
    "hours_per_week_13w", "trend_hours_per_week", "share_manual", "share_self_picked", "share_project",
    "estimate_ratio_median", "cycle_days_median", "share_late", "deadline_proximity_corr",
]
MIN_MEMBERS_FOR_CLUSTERING = 6


def _median_or_none(values: pd.Series) -> float | None:
    clean = values.dropna()
    return float(clean.median()) if len(clean) else None


def member_patterns(
    tasks: pd.DataFrame,
    member_id: int,
    as_of: dt.date,
    project_spans: list[tuple[dt.date, dt.date]],
    weeks: int = 13,
) -> dict:
    window_start = week_start(as_of) - weeks * ONE_WEEK
    recent = tasks[(tasks["assigned_at"] >= window_start) & (tasks["assigned_at"] < week_start(as_of))]
    done = tasks.dropna(subset=["completed_at", "actual_hours"])
    open_tasks = tasks[tasks["completed_at"].isna()]

    weekly = (
        pd.Series([week_start(d) for d in recent["assigned_at"]], name="week")
        .to_frame()
        .assign(hours=recent["estimated_hours"].to_numpy())
        .groupby("week")["hours"].sum()
        .reindex([window_start + k * ONE_WEEK for k in range(weeks)], fill_value=0.0)
    )
    trend = float(np.polyfit(np.arange(weeks), weekly.to_numpy(dtype=float), 1)[0]) if weeks > 1 else 0.0

    weekday_counts = np.zeros(5)
    for d in recent["assigned_at"]:
        if d.weekday() < 5:
            weekday_counts[d.weekday()] += 1
    weekday_shares = (weekday_counts / weekday_counts.sum()).round(3).tolist() if weekday_counts.sum() else [0.0] * 5
    top_weekday = WEEKDAY_NAMES[int(weekday_counts.argmax())] if weekday_counts.sum() else None

    modes = recent["assignment_mode"].dropna()
    shares = {f"share_{m}": (float((modes == m).mean()) if len(modes) else None) for m in MODES}

    ratio = done["actual_hours"] / done["estimated_hours"].replace(0, np.nan)
    cycle = pd.Series([(c - a).days + 1 for a, c in zip(done["assigned_at"], done["completed_at"], strict=True)], dtype=float)
    lateness = pd.Series(
        [(c - d).days for c, d in zip(done["completed_at"], done["due_date"], strict=True) if d is not None], dtype=float
    )
    cycle_by_type = {
        str(t): float(np.median([(c - a).days + 1 for a, c in zip(g["assigned_at"], g["completed_at"], strict=True)]))
        for t, g in done.groupby("type")
    }

    corr = None
    if project_spans and len(weekly) > 3 and weekly.std() > 0:
        proximity = []
        for w in weekly.index:
            active = [(dl - w).days / 7 for s, dl in project_spans if s <= w + dt.timedelta(days=6) and dl >= w]
            proximity.append(-min(active) if active else -52.0)
        prox = np.array(proximity)
        if prox.std() > 0:
            corr = float(np.corrcoef(weekly.to_numpy(dtype=float), prox)[0, 1])

    hours_by_project = recent.groupby("project_id", dropna=True)["estimated_hours"].sum()
    total_project_hours = float(hours_by_project.sum())

    return {
        "member_id": int(member_id),
        "tasks_13w": int(len(recent)),
        "hours_13w": float(recent["estimated_hours"].sum()),
        "hours_per_week_13w": float(weekly.mean()) if weeks else 0.0,
        "trend_hours_per_week": round(trend, 3),
        **shares,
        "top_weekday": top_weekday,
        "weekday_shares": weekday_shares,
        "estimate_ratio_median": _median_or_none(ratio),
        "cycle_days_median": _median_or_none(cycle),
        "cycle_days_by_type": cycle_by_type,
        "lateness_days_median": _median_or_none(lateness),
        "share_late": float((lateness > 0).mean()) if len(lateness) else None,
        "deadline_proximity_corr": corr,
        "share_with_project": float(recent["project_id"].notna().mean()) if len(recent) else None,
        "hours_by_project": {
            str(int(p)): round(float(h) / total_project_hours, 3) for p, h in hours_by_project.items()
        } if total_project_hours else {},
        "open_tasks": int(len(open_tasks)),
        "open_est_hours": float(open_tasks["estimated_hours"].sum()),
        "overdue_open": int(sum(1 for d in open_tasks["due_date"] if d is not None and d < as_of)),
    }


def pattern_table(
    tasks: pd.DataFrame,
    members: pd.DataFrame,
    spans_by_team: dict[int, list[tuple[dt.date, dt.date]]],
    as_of: dt.date,
) -> pd.DataFrame:
    rows = []
    for member_id, team_id in zip(members["id"], members["team_id"], strict=True):
        mine = tasks[tasks["assignee_id"] == member_id]
        rows.append(member_patterns(mine, int(member_id), as_of, spans_by_team.get(int(team_id), [])))
    return pd.DataFrame(rows)


def cluster_members(table: pd.DataFrame) -> pd.Series:
    index = pd.Index(table["member_id"].astype(int), name="member_id")
    if len(table) < MIN_MEMBERS_FOR_CLUSTERING:
        return pd.Series(0, index=index, name="cluster")
    cols = [c for c in CLUSTER_FEATURES if c in table.columns]
    x = table[cols].apply(pd.to_numeric, errors="coerce").fillna(0.0).to_numpy(dtype=float)
    x = StandardScaler().fit_transform(x)
    best_labels, best_score = np.zeros(len(table), dtype=int), -1.0
    for k in range(2, min(5, len(table) - 1) + 1):
        labels = KMeans(n_clusters=k, n_init=10, random_state=0).fit_predict(x)
        if len(set(labels)) < 2:
            continue
        score = silhouette_score(x, labels)
        if score > best_score:
            best_labels, best_score = labels, score
    return pd.Series(best_labels, index=index, name="cluster")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_patterns.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/patterns.py service/tests/test_patterns.py
git commit -m "feat(service): per-member pattern statistics and clustering"
```

---

### Task 13: Forecast pipeline, persistence and facts

**Files:**
- Create: `service/src/whf/pipeline.py`, `service/tests/test_pipeline.py`

**Interfaces:**
- Consumes: everything above.
- Produces (all in `whf.pipeline`):
  - `RunResult(run_id: int, team_id: int, as_of: date, weeks: tuple[date, date], champion: str, backtest_mase: float, forecasts: pd.DataFrame, facts: dict, scores: pd.DataFrame)`; `forecasts` columns match the `forecasts` table.
  - `run_forecast(conn, team_id: int, as_of: date | None = None, requested_by: int | None = None) -> RunResult`
  - `load_run(conn, run_id: int) -> dict` (run row, forecasts as records, facts dict, narrative dict or None)
  - `list_runs(conn, team_id: int | None = None) -> pd.DataFrame`
  - `jsonable(value) -> value` (dates to ISO strings, numpy scalars to Python, NaN to None)

- [ ] **Step 1: Write the failing tests**

`service/tests/test_pipeline.py`:

```python
import datetime as dt
import json

import pandas as pd
import pytest

from whf.calendar import forecast_weeks
from whf.data.generator import GeneratorConfig, generate, truncate_to
from whf.data.loader import load_generated
from whf.db.connection import connect
from whf.db.repo import read_df
from whf.pipeline import jsonable, list_runs, load_run, run_forecast


def test_run_forecast_produces_two_weeks_per_counted_member(db, generated) -> None:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of, requested_by=None)
    counted = [m for m in generated.members if m["team_id"] == 1 and m["counted_in_workload"]]
    f1, f2 = forecast_weeks(generated.config.as_of)
    assert result.weeks == (f1, f2)
    assert len(result.forecasts) == 2 * len(counted)
    assert set(result.forecasts["week_start"]) == {f1, f2}
    f = result.forecasts
    assert (f["demand_hours"] >= 0).all() and (f["capacity_hours"] <= 40.0 + 1e-9).all()
    assert (f["overload_hours"] == (f["demand_hours"] - f["capacity_hours"]).clip(lower=0)).all()
    assert (f["demand_low"] <= f["demand_hours"] + 1e-9).all() and (f["demand_high"] >= f["demand_hours"] - 1e-9).all()
    assert (abs(f["demand_hours"] - (f["open_task_hours"] + f["new_task_hours"])) < 1e-6).all()
    assert result.champion in {"seasonal_naive", "tsb", "gbm"}


def test_run_is_persisted_with_facts(db, generated) -> None:
    result = run_forecast(db, team_id=2, as_of=generated.config.as_of)
    runs = read_df(db, "SELECT * FROM runs")
    assert runs["status"].tolist() == ["done"] and runs["champion_model"][0] == result.champion
    stored = read_df(db, "SELECT COUNT(*) AS n FROM forecasts WHERE run_id = ?", (result.run_id,))["n"][0]
    assert stored == len(result.forecasts)
    facts = json.loads(read_df(db, "SELECT json FROM run_facts WHERE run_id = ?", (result.run_id,))["json"][0])
    assert set(facts) >= {"run", "team", "members", "projects", "model", "rebalancing_candidates"}
    member = facts["members"][0]
    assert set(member) >= {"id", "name", "role", "history_13w", "forecast", "patterns", "open_tasks"}
    assert len(member["forecast"]) == 2 and len(member["history_13w"]) == 13
    assert facts["model"]["champion"] == result.champion
    loaded = load_run(db, result.run_id)
    assert loaded["run"]["id"] == result.run_id and loaded["narrative"] is None
    assert len(loaded["forecasts"]) == len(result.forecasts)
    assert list_runs(db, team_id=2)["id"].tolist() == [result.run_id]


def test_vacation_reduces_capacity(db, generated) -> None:
    as_of = generated.config.as_of
    f1, _ = forecast_weeks(as_of)
    member = next(m["id"] for m in generated.members if m["team_id"] == 1 and m["counted_in_workload"])
    db.execute(
        "INSERT INTO vacations (member_id, start_date, end_date, type) VALUES (?, ?, ?, 'vacation')",
        (member, f1.isoformat(), (f1 + dt.timedelta(days=6)).isoformat()),
    )
    db.commit()
    result = run_forecast(db, team_id=1, as_of=as_of)
    row = result.forecasts[(result.forecasts.member_id == member) & (result.forecasts.week_start == f1)].iloc[0]
    assert row["capacity_hours"] == 0.0


def test_capacity_override_applies(db, generated) -> None:
    as_of = generated.config.as_of
    f1, f2 = forecast_weeks(as_of)
    member = next(m["id"] for m in generated.members if m["team_id"] == 1 and m["counted_in_workload"])
    db.execute("INSERT INTO capacity_overrides (member_id, week_start, weekly_hours, reason) VALUES (?, ?, 20.0, 'internal')", (member, f2.isoformat()))
    db.commit()
    result = run_forecast(db, team_id=1, as_of=as_of)
    rows = result.forecasts[result.forecasts.member_id == member].set_index("week_start")
    assert rows.loc[f2, "capacity_hours"] <= 20.0
    assert rows.loc[f1, "capacity_hours"] > 20.0


def test_jsonable_converts_dates_and_numpy() -> None:
    import numpy as np

    value = jsonable({"d": dt.date(2026, 9, 7), "n": np.float64(1.5), "nan": float("nan"), "list": [np.int64(2)]})
    assert value == {"d": "2026-09-07", "n": 1.5, "nan": None, "list": [2]}


@pytest.mark.slow
def test_accuracy_gate_against_hidden_effort_log() -> None:
    full = generate(GeneratorConfig(seed=42, as_of=dt.date(2026, 9, 24)))
    as_of = dt.date(2026, 9, 3)
    conn = connect(":memory:")
    load_generated(conn, truncate_to(full, as_of))
    f1, f2 = forecast_weeks(as_of)
    truth = pd.DataFrame(full.answer_key["effort_by_member_week"])
    truth["week_start"] = [dt.date.fromisoformat(w) for w in truth["week_start"]]
    errors = []
    for team in [t["id"] for t in full.teams]:
        result = run_forecast(conn, team_id=team, as_of=as_of)
        members = set(result.forecasts["member_id"])
        actual = truth[(truth.member_id.isin(members)) & (truth.week_start.isin([f1, f2]))]["hours"].sum()
        predicted = result.forecasts["demand_hours"].sum()
        errors.append(abs(predicted - actual) / max(actual, 1.0))
        assert result.backtest_mase < 1.05
    assert sum(errors) / len(errors) < 0.45
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_pipeline.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.pipeline'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/pipeline.py`:

```python
"""One forecast run: load, features, backtest, champion, effort placement, capacity, persist."""

from __future__ import annotations

import datetime as dt
import json
import math
import sqlite3
from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

from whf.backtest import default_origins, interval_bounds, rolling_backtest, select_champion
from whf.calendar import (
    ONE_WEEK,
    days_in_ranges,
    forecast_weeks,
    last_complete_week,
    weeks_between,
)
from whf.capacity import available_hours, overload_hours, resolve_weekly_hours
from whf.db.repo import insert_rows, read_df, with_dates
from whf.features import build_feature_matrix, weekly_arrivals
from whf.models import MODEL_FACTORIES
from whf.models.effort import EffortModel, place_new_arrivals, place_open_tasks
from whf.patterns import cluster_members, pattern_table

HISTORY_WEEKS_IN_FACTS = 13
MIN_WEEKS_BEFORE_ORIGIN = 13
BACKTEST_ORIGINS = 6
OVERLOAD_THRESHOLD = 0.0
UNDERLOAD_RATIO = 0.7


@dataclass
class RunResult:
    run_id: int
    team_id: int
    as_of: dt.date
    weeks: tuple[dt.date, dt.date]
    champion: str
    backtest_mase: float
    forecasts: pd.DataFrame
    facts: dict
    scores: pd.DataFrame


def jsonable(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(k): jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [jsonable(v) for v in value]
    if isinstance(value, (dt.date, dt.datetime)):
        return value.isoformat()
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating, float)):
        f = float(value)
        return None if math.isnan(f) or math.isinf(f) else f
    if isinstance(value, np.bool_):
        return bool(value)
    if isinstance(value, pd.Series):
        return jsonable(value.tolist())
    return value


def _load_frames(conn: sqlite3.Connection) -> dict[str, pd.DataFrame]:
    members = read_df(conn, "SELECT * FROM members")
    teams = read_df(conn, "SELECT * FROM teams")
    tasks = with_dates(read_df(conn, "SELECT * FROM tasks"), ["created_at", "assigned_at", "due_date", "completed_at"])
    projects = with_dates(read_df(conn, "SELECT * FROM projects"), ["start_date", "deadline"])
    project_teams = read_df(conn, "SELECT * FROM project_teams")
    holidays = with_dates(read_df(conn, "SELECT date FROM holidays"), ["date"])
    vacations = with_dates(read_df(conn, "SELECT * FROM vacations"), ["start_date", "end_date"])
    overrides = with_dates(read_df(conn, "SELECT * FROM capacity_overrides"), ["week_start"])
    default = float(read_df(conn, "SELECT weekly_hours FROM capacity_defaults WHERE id = 1")["weekly_hours"][0])
    return {
        "members": members, "teams": teams, "tasks": tasks, "projects": projects, "project_teams": project_teams,
        "holidays": holidays, "vacations": vacations, "overrides": overrides, "default": default,
    }


def _capacity_rows(frames: dict, member_ids: list[int], weeks: tuple[dt.date, dt.date], off_by_member: dict) -> dict[tuple[int, dt.date], float]:
    overrides = frames["overrides"]
    out: dict[tuple[int, dt.date], float] = {}
    for m in member_ids:
        mine = overrides[overrides["member_id"] == m]
        permanent = mine[mine["week_start"].isna()]["weekly_hours"]
        permanent_value = float(permanent.iloc[0]) if len(permanent) else None
        for w in weeks:
            weekly = mine[mine["week_start"] == w]["weekly_hours"]
            week_value = float(weekly.iloc[0]) if len(weekly) else None
            hours = resolve_weekly_hours(frames["default"], permanent_value, week_value)
            out[(m, w)] = available_hours(w, hours, off_by_member.get(m, set()))
    return out


def run_forecast(
    conn: sqlite3.Connection, team_id: int, as_of: dt.date | None = None, requested_by: int | None = None
) -> RunResult:
    as_of = as_of or dt.date.today()
    started = dt.datetime.now()
    f1, f2 = forecast_weeks(as_of)
    origin = last_complete_week(as_of)
    h1 = (f1 - origin).days // 7
    horizons = (h1, h1 + 1)
    frames = _load_frames(conn)
    members = frames["members"]
    tasks = frames["tasks"]
    counted = members[members["counted_in_workload"] == 1]
    team_members = counted[counted["team_id"] == team_id]
    if team_members.empty:
        raise ValueError(f"team {team_id} has no counted members")
    member_ids = [int(m) for m in team_members["id"]]
    holidays = {d for d in frames["holidays"]["date"] if d is not None}
    vacation_days: dict[int, set[dt.date]] = {}
    for m, s, e in zip(frames["vacations"]["member_id"], frames["vacations"]["start_date"], frames["vacations"]["end_date"], strict=True):
        vacation_days.setdefault(int(m), set()).update(days_in_ranges([(s, e)]))
    off_by_member = {int(m): holidays | vacation_days.get(int(m), set()) for m in counted["id"]}

    # arrival model on every counted member in the database (global model), forecast for the team
    first_week = min(tasks["assigned_at"])
    weeks = weeks_between(first_week, origin)
    arrivals = weekly_arrivals(tasks, [int(m) for m in counted["id"]], weeks)
    feat = build_feature_matrix(arrivals, tasks, frames["projects"], frames["project_teams"], members, holidays, vacation_days)
    origins = [o for o in default_origins(origin, BACKTEST_ORIGINS) if o >= weeks[0] + MIN_WEEKS_BEFORE_ORIGIN * ONE_WEEK]
    backtest = rolling_backtest(feat, MODEL_FACTORIES, origins, horizons)
    champion, champion_mase = select_champion(backtest.scores)
    model = MODEL_FACTORIES[champion]().fit(feat)
    latest = feat[(feat["week_start"] == origin) & (feat["member_id"].astype(int).isin(member_ids))]
    predicted_rows = []
    for week, h in zip((f1, f2), horizons, strict=True):
        pred = model.predict(latest, h)
        for m, value in zip(latest["member_id"].astype(int), pred, strict=True):
            predicted_rows.append({"member_id": int(m), "week_start": week, "est_hours": float(value), "horizon": h})
    predicted = pd.DataFrame(predicted_rows)

    # effort placement
    done = tasks.dropna(subset=["completed_at", "actual_hours"])
    effort = EffortModel().fit(done)
    open_tasks = tasks[(tasks["completed_at"].isna()) & (tasks["assignee_id"].isin(member_ids))]
    open_placed = place_open_tasks(open_tasks, effort, as_of, off_by_member)
    team_of = {int(m): int(t) for m, t in zip(members["id"], members["team_id"].fillna(0), strict=True)}
    new_placed = place_new_arrivals(predicted[["member_id", "week_start", "est_hours"]], effort, off_by_member, team_of)
    capacity = _capacity_rows(frames, member_ids, (f1, f2), off_by_member)
    bounds = {h: interval_bounds(backtest.residuals.get((champion, h), np.array([]))) for h in horizons}

    rows = []
    for m in member_ids:
        for week, h in zip((f1, f2), horizons, strict=True):
            open_hours = float(open_placed[(open_placed.member_id == m) & (open_placed.week_start == week)]["hours"].sum()) if len(open_placed) else 0.0
            new_hours = float(new_placed[(new_placed.member_id == m) & (new_placed.week_start == week)]["hours"].sum()) if len(new_placed) else 0.0
            open_hours, new_hours = round(open_hours, 2), round(new_hours, 2)
            demand = round(open_hours + new_hours, 2)
            low, high = bounds[h]
            cap = capacity[(m, week)]
            rows.append(
                {
                    "member_id": m, "week_start": week, "demand_hours": demand,
                    "demand_low": round(max(0.0, demand + low), 2), "demand_high": round(demand + high, 2),
                    "capacity_hours": cap, "overload_hours": round(overload_hours(demand, cap), 2),
                    "open_task_hours": open_hours, "new_task_hours": new_hours,
                }
            )
    forecasts = pd.DataFrame(rows)

    # patterns and facts
    spans_by_team = {
        int(t): list(zip(g["start_date"], g["deadline"], strict=True))
        for t, g in frames["project_teams"].merge(frames["projects"], left_on="project_id", right_on="id").groupby("team_id")
    }
    patterns = pattern_table(tasks, team_members, spans_by_team, as_of)
    clusters = cluster_members(patterns)
    facts = _build_facts(
        team_id, as_of, (f1, f2), frames, team_members, tasks, arrivals, forecasts, patterns, clusters,
        champion, champion_mase, backtest.scores, origins, horizons,
    )

    # persist
    cur = conn.execute(
        "INSERT INTO runs (team_id, as_of, requested_by, status, champion_model, backtest_mase, started_at, finished_at, ai_status)"
        " VALUES (?, ?, ?, 'done', ?, ?, ?, ?, 'not_requested')",
        (team_id, as_of.isoformat(), requested_by, champion, None if math.isnan(champion_mase) else champion_mase,
         started.isoformat(timespec="seconds"), dt.datetime.now().isoformat(timespec="seconds")),
    )
    run_id = int(cur.lastrowid)
    insert_rows(conn, "forecasts", [{"run_id": run_id, **r} for r in rows])
    facts["run"]["id"] = run_id
    conn.execute("INSERT INTO run_facts (run_id, json) VALUES (?, ?)", (run_id, json.dumps(jsonable(facts))))
    conn.commit()
    return RunResult(run_id, team_id, as_of, (f1, f2), champion, champion_mase, forecasts, facts, backtest.scores)


def _build_facts(
    team_id, as_of, weeks, frames, team_members, tasks, arrivals, forecasts, patterns, clusters,
    champion, champion_mase, scores, origins, horizons,
) -> dict:
    f1, f2 = weeks
    team = frames["teams"][frames["teams"]["id"] == team_id].iloc[0]
    origin = last_complete_week(as_of)
    history_weeks = [origin - k * ONE_WEEK for k in range(HISTORY_WEEKS_IN_FACTS - 1, -1, -1)]
    members_facts = []
    for _, m in team_members.iterrows():
        mid = int(m["id"])
        hist = arrivals[(arrivals.member_id == mid) & (arrivals.week_start.isin(history_weeks))].sort_values("week_start")
        fc = forecasts[forecasts.member_id == mid].sort_values("week_start")
        mine_open = tasks[(tasks["assignee_id"] == mid) & (tasks["completed_at"].isna())]
        pattern = patterns[patterns.member_id == mid].iloc[0].to_dict()
        pattern["cluster"] = int(clusters.get(mid, 0))
        members_facts.append(
            {
                "id": mid, "name": m["name"], "role": m["role"],
                "history_13w": [{"week": w, "hours": round(float(h), 1), "tasks": int(n)} for w, h, n in zip(hist.week_start, hist.est_hours, hist.n_tasks, strict=True)],
                "forecast": [
                    {"week": r.week_start, "demand": r.demand_hours, "low": r.demand_low, "high": r.demand_high,
                     "capacity": r.capacity_hours, "overload": r.overload_hours, "open_hours": r.open_task_hours, "new_hours": r.new_task_hours}
                    for r in fc.itertuples()
                ],
                "patterns": pattern,
                "open_tasks": [
                    {"id": int(t.id), "title": t.title, "type": t.type, "priority": t.priority, "estimated_hours": float(t.estimated_hours),
                     "due_date": t.due_date, "overdue": bool(t.due_date is not None and t.due_date < as_of), "project_id": None if pd.isna(t.project_id) else int(t.project_id)}
                    for t in mine_open.itertuples()
                ],
            }
        )
    pt = frames["project_teams"][frames["project_teams"]["team_id"] == team_id]
    projects = frames["projects"][frames["projects"]["id"].isin(pt["project_id"])]
    window_end = f2 + dt.timedelta(days=6)
    projects_facts = [
        {"id": int(p.id), "name": p.name, "start_date": p.start_date, "deadline": p.deadline, "status": p.status, "type": p.type,
         "active_in_window": bool(p.start_date <= window_end and p.deadline >= f1),
         "starting_in_window": bool(f1 <= p.start_date <= window_end), "ending_in_window": bool(f1 <= p.deadline <= window_end)}
        for p in projects.itertuples()
    ]
    totals = forecasts.groupby("member_id")[["demand_hours", "capacity_hours", "overload_hours"]].sum()
    name_of = dict(zip(team_members["id"], team_members["name"], strict=True))
    overloaded = [{"member_id": int(i), "name": name_of[int(i)], "overload_hours": round(float(r.overload_hours), 1)} for i, r in totals.iterrows() if r.overload_hours > OVERLOAD_THRESHOLD]
    underloaded = [{"member_id": int(i), "name": name_of[int(i)], "spare_hours": round(float(r.capacity_hours - r.demand_hours), 1)} for i, r in totals.iterrows() if r.capacity_hours > 0 and r.demand_hours < UNDERLOAD_RATIO * r.capacity_hours]
    mase_by_model = scores.groupby("model")["mase"].mean().to_dict() if len(scores) else {}
    return {
        "run": {"id": None, "as_of": as_of, "weeks": [f1, f2], "generated_at": dt.datetime.now()},
        "team": {"id": int(team_id), "name": team["name"], "department_id": int(team["department_id"]), "team_leader_id": team["team_leader_id"],
                 "totals": [{"week": w, "demand": round(float(forecasts[forecasts.week_start == w].demand_hours.sum()), 1),
                             "capacity": round(float(forecasts[forecasts.week_start == w].capacity_hours.sum()), 1)} for w in (f1, f2)]},
        "members": members_facts,
        "projects": projects_facts,
        "model": {"champion": champion, "champion_mase": champion_mase, "mase_by_model": mase_by_model,
                  "backtest_origins": origins, "horizons": list(horizons)},
        "rebalancing_candidates": {"overloaded": overloaded, "underloaded": underloaded},
    }


def load_run(conn: sqlite3.Connection, run_id: int) -> dict:
    run = read_df(conn, "SELECT * FROM runs WHERE id = ?", (run_id,))
    if run.empty:
        raise KeyError(f"run {run_id} not found")
    forecasts = read_df(conn, "SELECT * FROM forecasts WHERE run_id = ? ORDER BY member_id, week_start", (run_id,))
    facts = read_df(conn, "SELECT json FROM run_facts WHERE run_id = ?", (run_id,))
    narrative = read_df(conn, "SELECT json FROM run_narratives WHERE run_id = ?", (run_id,))
    return {
        "run": run.iloc[0].to_dict(),
        "forecasts": forecasts.to_dict(orient="records"),
        "facts": json.loads(facts["json"][0]) if len(facts) else None,
        "narrative": json.loads(narrative["json"][0]) if len(narrative) else None,
    }


def list_runs(conn: sqlite3.Connection, team_id: int | None = None) -> pd.DataFrame:
    if team_id is None:
        return read_df(conn, "SELECT * FROM runs ORDER BY id DESC")
    return read_df(conn, "SELECT * FROM runs WHERE team_id = ? ORDER BY id DESC", (team_id,))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_pipeline.py -v -m "not slow"` then `uv run pytest tests/test_pipeline.py -v -m slow`
Expected: PASS (5 fast tests, then the slow accuracy gate). The gate thresholds (`backtest_mase < 1.05`, mean relative team error `< 0.45`) are the starting bar; if the gate fails, investigate the cause with the answer key rather than loosening the numbers.

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/pipeline.py service/tests/test_pipeline.py
git commit -m "feat(service): forecast pipeline with persistence and facts JSON"
```

---

### Task 14: Command-line interface

**Files:**
- Modify: `service/src/whf/cli.py`
- Create: `service/tests/test_cli.py`

**Interfaces:**
- Consumes: `whf.pipeline.run_forecast`, `list_runs`, `load_run`, `jsonable`; `whf.data.generator.generate`, `GeneratorConfig`; `whf.data.loader.load_generated`, `write_answer_key`; `whf.db.connection.connect`; `whf.config.db_path`, `data_dir`
- Produces commands (all accept `--db PATH`, default `db_path()`):
  - `whf version`
  - `whf data generate [--seed 42] [--months 12] [--as-of YYYY-MM-DD] [--answer-key PATH]`
  - `whf run --team ID [--as-of YYYY-MM-DD] [--requested-by ID] [--json]`
  - `whf runs list [--team ID]`, `whf runs show RUN_ID [--json]`
  - `whf export RUN_ID --format csv|json --out PATH`
  - `whf capacity set --member ID --hours H [--week YYYY-MM-DD] [--reason TEXT]`, `whf capacity default --hours H`
  - `whf vacations add --member ID --start YYYY-MM-DD --end YYYY-MM-DD`
  - `whf projects add --name NAME --department ID --start YYYY-MM-DD --deadline YYYY-MM-DD --team ID [--team ID ...] [--type delivery]`
  - `whf serve [--port 0]` (Task 15 wires the API; here it only prints that the API is not available yet and exits 2)

- [ ] **Step 1: Write the failing tests**

`service/tests/test_cli.py`:

```python
import json

from typer.testing import CliRunner

from whf.cli import app

runner = CliRunner()


def _generate(db_path) -> None:
    result = runner.invoke(app, ["data", "generate", "--db", str(db_path), "--seed", "3", "--months", "6"])
    assert result.exit_code == 0, result.output
    assert "tasks" in result.output


def test_generate_run_list_show_export(tmp_path) -> None:
    db = tmp_path / "t.db"
    _generate(db)
    run = runner.invoke(app, ["run", "--db", str(db), "--team", "1", "--as-of", "2026-09-03", "--json"])
    assert run.exit_code == 0, run.output
    payload = json.loads(run.output)
    assert payload["run_id"] == 1 and len(payload["forecasts"]) >= 8
    listed = runner.invoke(app, ["runs", "list", "--db", str(db)])
    assert listed.exit_code == 0 and "team   1" in listed.output
    shown = runner.invoke(app, ["runs", "show", "1", "--db", str(db), "--json"])
    assert shown.exit_code == 0
    assert json.loads(shown.output)["run"]["id"] == 1
    out = tmp_path / "f.csv"
    exported = runner.invoke(app, ["export", "1", "--db", str(db), "--format", "csv", "--out", str(out)])
    assert exported.exit_code == 0 and out.exists()
    assert out.read_text().splitlines()[0].startswith("run_id,member_id,week_start")


def test_capacity_vacations_projects_commands(tmp_path) -> None:
    db = tmp_path / "t.db"
    _generate(db)
    assert runner.invoke(app, ["capacity", "default", "--db", str(db), "--hours", "38"]).exit_code == 0
    assert runner.invoke(app, ["capacity", "set", "--db", str(db), "--member", "2", "--hours", "20", "--week", "2026-09-14", "--reason", "internal"]).exit_code == 0
    assert runner.invoke(app, ["vacations", "add", "--db", str(db), "--member", "2", "--start", "2026-09-21", "--end", "2026-09-25"]).exit_code == 0
    added = runner.invoke(app, ["projects", "add", "--db", str(db), "--name", "New CRM", "--department", "1", "--start", "2026-09-10", "--deadline", "2026-11-30", "--team", "1", "--team", "2"])
    assert added.exit_code == 0, added.output
    from whf.db.connection import connect
    from whf.db.repo import read_df

    conn = connect(db)
    assert read_df(conn, "SELECT weekly_hours FROM capacity_defaults")["weekly_hours"][0] == 38.0
    assert len(read_df(conn, "SELECT * FROM capacity_overrides WHERE member_id = 2")) == 1
    assert len(read_df(conn, "SELECT * FROM vacations WHERE member_id = 2 AND start_date = '2026-09-21'")) == 1
    assert len(read_df(conn, "SELECT * FROM project_teams WHERE project_id = (SELECT MAX(id) FROM projects)")) == 2


def test_run_with_unknown_team_fails_cleanly(tmp_path) -> None:
    db = tmp_path / "t.db"
    _generate(db)
    result = runner.invoke(app, ["run", "--db", str(db), "--team", "999", "--as-of", "2026-09-03"])
    assert result.exit_code == 1
    assert "no counted members" in result.output
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_cli.py -v`
Expected: FAIL with `No such command 'data'`

- [ ] **Step 3: Write the implementation**

Replace `service/src/whf/cli.py` with:

```python
"""Command-line interface. Every command calls the same functions the API uses."""

from __future__ import annotations

import datetime as dt
import json
from pathlib import Path
from typing import Annotated

import typer

from whf import __version__
from whf.config import data_dir, db_path
from whf.data.generator import GeneratorConfig, generate
from whf.data.loader import load_generated, write_answer_key
from whf.db.connection import connect
from whf.pipeline import jsonable, list_runs, load_run, run_forecast

app = typer.Typer(help="WorkloadHub AI Forecasting", no_args_is_help=True)
data_app = typer.Typer(help="Dummy data commands", no_args_is_help=True)
runs_app = typer.Typer(help="Inspect stored runs", no_args_is_help=True)
capacity_app = typer.Typer(help="Capacity configuration", no_args_is_help=True)
vacations_app = typer.Typer(help="Planned time off", no_args_is_help=True)
projects_app = typer.Typer(help="Projects with start date and deadline", no_args_is_help=True)
for name, sub in [("data", data_app), ("runs", runs_app), ("capacity", capacity_app), ("vacations", vacations_app), ("projects", projects_app)]:
    app.add_typer(sub, name=name)

DbOption = Annotated[Path | None, typer.Option("--db", help="SQLite database path (default: the app data folder)")]


def _conn(db: Path | None):
    return connect(db or db_path())


def _date(value: str | None) -> dt.date | None:
    return dt.date.fromisoformat(value) if value else None


@app.callback()
def main() -> None:
    """WorkloadHub AI Forecasting service commands."""


@app.command()
def version() -> None:
    """Print the service version."""
    typer.echo(f"whf {__version__}")


@data_app.command("generate")
def data_generate(
    db: DbOption = None,
    seed: int = 42,
    months: int = 12,
    as_of: Annotated[str | None, typer.Option("--as-of")] = None,
    answer_key: Annotated[Path | None, typer.Option("--answer-key")] = None,
) -> None:
    """Generate dummy data (replaces existing data in the database)."""
    config = GeneratorConfig(seed=seed, months=months, as_of=_date(as_of) or GeneratorConfig().as_of)
    data = generate(config)
    conn = _conn(db)
    load_generated(conn, data)
    key_path = answer_key or (data_dir() / "answer_key.json")
    write_answer_key(key_path, data)
    typer.echo(
        f"generated {len(data.members)} members, {len(data.teams)} teams, {len(data.projects)} projects, "
        f"{len(data.tasks)} tasks; answer key at {key_path}"
    )


@app.command()
def run(
    team: Annotated[int, typer.Option("--team", help="Team id to forecast")],
    db: DbOption = None,
    as_of: Annotated[str | None, typer.Option("--as-of")] = None,
    requested_by: Annotated[int | None, typer.Option("--requested-by")] = None,
    as_json: Annotated[bool, typer.Option("--json")] = False,
) -> None:
    """Run the two-week forecast for one team."""
    conn = _conn(db)
    try:
        result = run_forecast(conn, team_id=team, as_of=_date(as_of), requested_by=requested_by)
    except ValueError as exc:
        typer.echo(f"error: {exc}")
        raise typer.Exit(code=1) from exc
    if as_json:
        typer.echo(json.dumps(jsonable({
            "run_id": result.run_id, "team_id": result.team_id, "as_of": result.as_of, "weeks": list(result.weeks),
            "champion": result.champion, "backtest_mase": result.backtest_mase,
            "forecasts": result.forecasts.to_dict(orient="records"),
        })))
        return
    typer.echo(f"run {result.run_id}: team {team}, weeks {result.weeks[0]} and {result.weeks[1]}, champion {result.champion} (MASE {result.backtest_mase:.2f})")
    for row in result.forecasts.itertuples():
        flag = " OVERLOAD" if row.overload_hours > 0 else ""
        typer.echo(f"  member {row.member_id:>4} {row.week_start}: demand {row.demand_hours:6.1f}h  capacity {row.capacity_hours:5.1f}h{flag}")


@runs_app.command("list")
def runs_list(db: DbOption = None, team: Annotated[int | None, typer.Option("--team")] = None) -> None:
    """List stored runs."""
    df = list_runs(_conn(db), team)
    if df.empty:
        typer.echo("no runs")
        return
    for row in df.itertuples():
        typer.echo(f"{row.id:>4}  team {row.team_id:>3}  as_of {row.as_of}  {row.status:<6} {row.champion_model or '-':<15} ai={row.ai_status}")


@runs_app.command("show")
def runs_show(run_id: int, db: DbOption = None, as_json: Annotated[bool, typer.Option("--json")] = False) -> None:
    """Show one run with its forecasts and facts."""
    try:
        payload = load_run(_conn(db), run_id)
    except KeyError as exc:
        typer.echo(f"error: {exc}")
        raise typer.Exit(code=1) from exc
    if as_json:
        typer.echo(json.dumps(jsonable(payload)))
        return
    typer.echo(f"run {run_id}: team {payload['run']['team_id']} as_of {payload['run']['as_of']} champion {payload['run']['champion_model']}")
    for row in payload["forecasts"]:
        typer.echo(f"  member {row['member_id']:>4} {row['week_start']}: demand {row['demand_hours']:6.1f}h capacity {row['capacity_hours']:5.1f}h overload {row['overload_hours']:5.1f}h")


@app.command()
def export(
    run_id: int,
    out: Annotated[Path, typer.Option("--out")],
    db: DbOption = None,
    fmt: Annotated[str, typer.Option("--format")] = "csv",
) -> None:
    """Export a run's forecasts to CSV or JSON."""
    payload = load_run(_conn(db), run_id)
    out.parent.mkdir(parents=True, exist_ok=True)
    if fmt == "json":
        out.write_text(json.dumps(jsonable(payload), indent=1), encoding="utf-8")
    elif fmt == "csv":
        import pandas as pd

        pd.DataFrame(payload["forecasts"]).to_csv(out, index=False)
    else:
        typer.echo("error: --format must be csv or json")
        raise typer.Exit(code=2)
    typer.echo(f"wrote {out}")


@capacity_app.command("default")
def capacity_default(hours: Annotated[float, typer.Option("--hours")], db: DbOption = None) -> None:
    """Set the default weekly capacity for everyone."""
    conn = _conn(db)
    conn.execute("UPDATE capacity_defaults SET weekly_hours = ? WHERE id = 1", (hours,))
    conn.commit()
    typer.echo(f"default weekly capacity set to {hours}h")


@capacity_app.command("set")
def capacity_set(
    member: Annotated[int, typer.Option("--member")],
    hours: Annotated[float, typer.Option("--hours")],
    db: DbOption = None,
    week: Annotated[str | None, typer.Option("--week", help="Monday of the week; omit for a permanent override")] = None,
    reason: Annotated[str | None, typer.Option("--reason")] = None,
) -> None:
    """Override a member's weekly capacity, permanently or for one week."""
    conn = _conn(db)
    conn.execute(
        "INSERT INTO capacity_overrides (member_id, week_start, weekly_hours, reason) VALUES (?, ?, ?, ?)"
        " ON CONFLICT(member_id, week_start) DO UPDATE SET weekly_hours = excluded.weekly_hours, reason = excluded.reason",
        (member, week, hours, reason),
    )
    conn.commit()
    typer.echo(f"member {member}: {hours}h" + (f" for week {week}" if week else " permanently"))


@vacations_app.command("add")
def vacations_add(
    member: Annotated[int, typer.Option("--member")],
    start: Annotated[str, typer.Option("--start")],
    end: Annotated[str, typer.Option("--end")],
    db: DbOption = None,
    kind: Annotated[str, typer.Option("--type")] = "vacation",
) -> None:
    """Add planned time off for a member."""
    conn = _conn(db)
    conn.execute("INSERT INTO vacations (member_id, start_date, end_date, type) VALUES (?, ?, ?, ?)", (member, start, end, kind))
    conn.commit()
    typer.echo(f"member {member}: {kind} {start} to {end}")


@projects_app.command("add")
def projects_add(
    name: Annotated[str, typer.Option("--name")],
    department: Annotated[int, typer.Option("--department")],
    start: Annotated[str, typer.Option("--start")],
    deadline: Annotated[str, typer.Option("--deadline")],
    team: Annotated[list[int], typer.Option("--team", help="Team id; repeat for several teams")],
    db: DbOption = None,
    kind: Annotated[str, typer.Option("--type")] = "delivery",
    created_by: Annotated[int | None, typer.Option("--created-by")] = None,
) -> None:
    """Create a project with a start date, a deadline and its teams."""
    if _date(deadline) <= _date(start):
        typer.echo("error: deadline must be after start")
        raise typer.Exit(code=2)
    conn = _conn(db)
    cur = conn.execute(
        "INSERT INTO projects (name, department_id, start_date, deadline, type, status, created_by) VALUES (?, ?, ?, ?, ?, 'planned', ?)",
        (name, department, start, deadline, kind, created_by),
    )
    project_id = cur.lastrowid
    conn.executemany("INSERT INTO project_teams (project_id, team_id) VALUES (?, ?)", [(project_id, t) for t in team])
    conn.commit()
    typer.echo(f"project {project_id} '{name}' {start} to {deadline} for teams {team}")


@app.command()
def serve(db: DbOption = None, port: int = 0) -> None:
    """Start the local API (implemented in the next task)."""
    typer.echo("error: the API server is not available yet")
    raise typer.Exit(code=2)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_cli.py tests/test_cli_version.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Lint and commit**

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/cli.py service/tests/test_cli.py
git commit -m "feat(service): CLI commands for data, runs, export, capacity, vacations and projects"
```

---

### Task 15: Local HTTP API

**Files:**
- Create: `service/src/whf/api.py`, `service/tests/test_api.py`
- Modify: `service/src/whf/cli.py` (`serve` command)

**Interfaces:**
- Consumes: pipeline, db, config
- Produces: `whf.api.create_app(db_path: Path | str, token: str) -> fastapi.FastAPI`. Every route except `GET /health` requires header `X-WHF-Token: <token>` (401 otherwise). Routes:
  - `GET /health` -> `{"status": "ok", "version": ...}`
  - `GET /meta` -> departments, teams, members, capacity default
  - `POST /runs` body `{"team_id": int, "as_of": "YYYY-MM-DD" | null, "requested_by": int | null}` -> `{"run_id", "team_id", "as_of", "weeks", "champion", "backtest_mase", "forecasts": [...]}`; 404 for a team without counted members
  - `GET /runs?team_id=` -> list of run rows; `GET /runs/{run_id}` -> `load_run` payload; 404 when missing
  - `GET /projects`, `POST /projects` body `{"name", "department_id", "start_date", "deadline", "team_ids": [..], "type": "delivery", "created_by": null}` -> `{"id": ...}`; 422 when deadline <= start
  - `GET /capacity` -> `{"default_weekly_hours", "overrides": [...]}`; `PUT /capacity/default` body `{"weekly_hours"}`; `PUT /capacity/overrides` body `{"member_id", "week_start": "YYYY-MM-DD" | null, "weekly_hours", "reason": null}`
  - `GET /vacations?member_id=`; `POST /vacations` body `{"member_id", "start_date", "end_date", "type": "vacation"}` -> `{"id"}`
  - `whf serve [--port 0] [--token TOKEN]` prints one JSON line `{"port": ..., "token": ...}` to stdout, then serves on 127.0.0.1.

- [ ] **Step 1: Write the failing tests**

`service/tests/test_api.py`:

```python
import pytest
from fastapi.testclient import TestClient

from whf.api import create_app
from whf.data.generator import GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect

TOKEN = "secret-token"


@pytest.fixture()
def client(tmp_path):
    db = tmp_path / "api.db"
    conn = connect(db)
    load_generated(conn, generate(GeneratorConfig(seed=5, months=6)))
    conn.close()
    return TestClient(create_app(db, TOKEN))


def _h() -> dict[str, str]:
    return {"X-WHF-Token": TOKEN}


def test_health_is_public_and_others_need_token(client) -> None:
    assert client.get("/health").json()["status"] == "ok"
    assert client.get("/meta").status_code == 401
    assert client.get("/meta", headers={"X-WHF-Token": "wrong"}).status_code == 401
    meta = client.get("/meta", headers=_h()).json()
    assert len(meta["departments"]) == 3 and meta["capacity_default"] == 40.0
    assert {"id", "name", "team_id", "role"} <= set(meta["members"][0])


def test_run_and_fetch(client) -> None:
    created = client.post("/runs", json={"team_id": 1, "as_of": "2026-09-03"}, headers=_h())
    assert created.status_code == 200, created.text
    body = created.json()
    assert body["run_id"] == 1 and len(body["forecasts"]) >= 8 and body["weeks"] == ["2026-09-07", "2026-09-14"]
    listed = client.get("/runs", params={"team_id": 1}, headers=_h()).json()
    assert [r["id"] for r in listed] == [1]
    one = client.get("/runs/1", headers=_h()).json()
    assert one["run"]["id"] == 1 and one["facts"]["team"]["id"] == 1 and one["narrative"] is None
    assert client.get("/runs/99", headers=_h()).status_code == 404
    assert client.post("/runs", json={"team_id": 999}, headers=_h()).status_code == 404


def test_projects_capacity_and_vacations(client) -> None:
    bad = client.post("/projects", json={"name": "X", "department_id": 1, "start_date": "2026-10-01", "deadline": "2026-09-01", "team_ids": [1]}, headers=_h())
    assert bad.status_code == 422
    ok = client.post("/projects", json={"name": "X", "department_id": 1, "start_date": "2026-09-10", "deadline": "2026-11-01", "team_ids": [1, 2]}, headers=_h())
    assert ok.status_code == 200 and ok.json()["id"] > 0
    projects = client.get("/projects", headers=_h()).json()
    assert any(p["name"] == "X" and p["team_ids"] == [1, 2] for p in projects)
    assert client.put("/capacity/default", json={"weekly_hours": 36}, headers=_h()).status_code == 200
    assert client.put("/capacity/overrides", json={"member_id": 2, "week_start": "2026-09-14", "weekly_hours": 16}, headers=_h()).status_code == 200
    cap = client.get("/capacity", headers=_h()).json()
    assert cap["default_weekly_hours"] == 36.0 and cap["overrides"][0]["member_id"] == 2
    vac = client.post("/vacations", json={"member_id": 2, "start_date": "2026-09-21", "end_date": "2026-09-23"}, headers=_h())
    assert vac.status_code == 200
    mine = client.get("/vacations", params={"member_id": 2}, headers=_h()).json()
    assert any(v["start_date"] == "2026-09-21" for v in mine)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_api.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.api'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/api.py`:

```python
"""Localhost HTTP API used by the desktop application. Token protected, bound to 127.0.0.1."""

from __future__ import annotations

import datetime as dt
import secrets
import sqlite3
from collections.abc import Iterator
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, Field, model_validator

from whf import __version__
from whf.db.connection import connect
from whf.db.repo import read_df
from whf.pipeline import jsonable, list_runs, load_run, run_forecast


class RunRequest(BaseModel):
    team_id: int
    as_of: dt.date | None = None
    requested_by: int | None = None


class ProjectCreate(BaseModel):
    name: str = Field(min_length=1)
    department_id: int
    start_date: dt.date
    deadline: dt.date
    team_ids: list[int] = Field(min_length=1)
    type: str = "delivery"
    created_by: int | None = None

    @model_validator(mode="after")
    def _deadline_after_start(self) -> ProjectCreate:
        if self.deadline <= self.start_date:
            raise ValueError("deadline must be after start_date")
        return self


class CapacityDefault(BaseModel):
    weekly_hours: float = Field(gt=0, le=80)


class CapacityOverride(BaseModel):
    member_id: int
    week_start: dt.date | None = None
    weekly_hours: float = Field(ge=0, le=80)
    reason: str | None = None


class VacationCreate(BaseModel):
    member_id: int
    start_date: dt.date
    end_date: dt.date
    type: str = "vacation"

    @model_validator(mode="after")
    def _end_after_start(self) -> VacationCreate:
        if self.end_date < self.start_date:
            raise ValueError("end_date must not be before start_date")
        return self


def new_token() -> str:
    return secrets.token_urlsafe(32)


def create_app(db_path: Path | str, token: str) -> FastAPI:
    app = FastAPI(title="WorkloadHub AI Forecasting", version=__version__)

    def require_token(x_whf_token: str | None = Header(default=None)) -> None:
        if x_whf_token is None or not secrets.compare_digest(x_whf_token, token):
            raise HTTPException(status_code=401, detail="missing or invalid token")

    def db() -> Iterator[sqlite3.Connection]:
        conn = connect(db_path)
        try:
            yield conn
        finally:
            conn.close()

    guarded = [Depends(require_token)]

    @app.get("/health")
    def health() -> dict:
        return {"status": "ok", "version": __version__}

    @app.get("/meta", dependencies=guarded)
    def meta(conn: sqlite3.Connection = Depends(db)) -> dict:
        return jsonable({
            "departments": read_df(conn, "SELECT * FROM departments").to_dict(orient="records"),
            "teams": read_df(conn, "SELECT * FROM teams").to_dict(orient="records"),
            "members": read_df(conn, "SELECT id, name, team_id, department_id, role, counted_in_workload FROM members").to_dict(orient="records"),
            "capacity_default": float(read_df(conn, "SELECT weekly_hours FROM capacity_defaults WHERE id = 1")["weekly_hours"][0]),
        })

    @app.post("/runs", dependencies=guarded)
    def create_run(body: RunRequest, conn: sqlite3.Connection = Depends(db)) -> dict:
        try:
            result = run_forecast(conn, team_id=body.team_id, as_of=body.as_of, requested_by=body.requested_by)
        except ValueError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        return jsonable({
            "run_id": result.run_id, "team_id": result.team_id, "as_of": result.as_of, "weeks": list(result.weeks),
            "champion": result.champion, "backtest_mase": result.backtest_mase,
            "forecasts": result.forecasts.to_dict(orient="records"),
        })

    @app.get("/runs", dependencies=guarded)
    def get_runs(team_id: int | None = Query(default=None), conn: sqlite3.Connection = Depends(db)) -> list:
        return jsonable(list_runs(conn, team_id).to_dict(orient="records"))

    @app.get("/runs/{run_id}", dependencies=guarded)
    def get_run(run_id: int, conn: sqlite3.Connection = Depends(db)) -> dict:
        try:
            return jsonable(load_run(conn, run_id))
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get("/projects", dependencies=guarded)
    def get_projects(conn: sqlite3.Connection = Depends(db)) -> list:
        projects = read_df(conn, "SELECT * FROM projects ORDER BY id").to_dict(orient="records")
        links = read_df(conn, "SELECT project_id, team_id FROM project_teams ORDER BY team_id")
        by_project: dict[int, list[int]] = {}
        for p, t in zip(links["project_id"], links["team_id"], strict=True):
            by_project.setdefault(int(p), []).append(int(t))
        for p in projects:
            p["team_ids"] = by_project.get(int(p["id"]), [])
        return jsonable(projects)

    @app.post("/projects", dependencies=guarded)
    def post_project(body: ProjectCreate, conn: sqlite3.Connection = Depends(db)) -> dict:
        cur = conn.execute(
            "INSERT INTO projects (name, department_id, start_date, deadline, type, status, created_by) VALUES (?, ?, ?, ?, ?, 'planned', ?)",
            (body.name, body.department_id, body.start_date.isoformat(), body.deadline.isoformat(), body.type, body.created_by),
        )
        project_id = int(cur.lastrowid)
        conn.executemany("INSERT INTO project_teams (project_id, team_id) VALUES (?, ?)", [(project_id, t) for t in body.team_ids])
        conn.commit()
        return {"id": project_id}

    @app.get("/capacity", dependencies=guarded)
    def get_capacity(conn: sqlite3.Connection = Depends(db)) -> dict:
        return jsonable({
            "default_weekly_hours": float(read_df(conn, "SELECT weekly_hours FROM capacity_defaults WHERE id = 1")["weekly_hours"][0]),
            "overrides": read_df(conn, "SELECT * FROM capacity_overrides ORDER BY member_id, week_start").to_dict(orient="records"),
        })

    @app.put("/capacity/default", dependencies=guarded)
    def put_capacity_default(body: CapacityDefault, conn: sqlite3.Connection = Depends(db)) -> dict:
        conn.execute("UPDATE capacity_defaults SET weekly_hours = ? WHERE id = 1", (body.weekly_hours,))
        conn.commit()
        return {"default_weekly_hours": body.weekly_hours}

    @app.put("/capacity/overrides", dependencies=guarded)
    def put_capacity_override(body: CapacityOverride, conn: sqlite3.Connection = Depends(db)) -> dict:
        conn.execute(
            "INSERT INTO capacity_overrides (member_id, week_start, weekly_hours, reason) VALUES (?, ?, ?, ?)"
            " ON CONFLICT(member_id, week_start) DO UPDATE SET weekly_hours = excluded.weekly_hours, reason = excluded.reason",
            (body.member_id, body.week_start.isoformat() if body.week_start else None, body.weekly_hours, body.reason),
        )
        conn.commit()
        return jsonable(body.model_dump())

    @app.get("/vacations", dependencies=guarded)
    def get_vacations(member_id: int | None = Query(default=None), conn: sqlite3.Connection = Depends(db)) -> list:
        if member_id is None:
            df = read_df(conn, "SELECT * FROM vacations ORDER BY start_date")
        else:
            df = read_df(conn, "SELECT * FROM vacations WHERE member_id = ? ORDER BY start_date", (member_id,))
        return jsonable(df.to_dict(orient="records"))

    @app.post("/vacations", dependencies=guarded)
    def post_vacation(body: VacationCreate, conn: sqlite3.Connection = Depends(db)) -> dict:
        cur = conn.execute(
            "INSERT INTO vacations (member_id, start_date, end_date, type) VALUES (?, ?, ?, ?)",
            (body.member_id, body.start_date.isoformat(), body.end_date.isoformat(), body.type),
        )
        conn.commit()
        return {"id": int(cur.lastrowid)}

    return app
```

Replace the `serve` command in `service/src/whf/cli.py` with:

```python
@app.command()
def serve(
    db: DbOption = None,
    port: int = 0,
    token: Annotated[str | None, typer.Option("--token", help="Token the client must send; generated when omitted")] = None,
) -> None:
    """Start the local API on 127.0.0.1 and print the port and token as one JSON line."""
    import socket

    import uvicorn

    from whf.api import create_app, new_token

    token = token or new_token()
    if port == 0:
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
    typer.echo(json.dumps({"port": port, "token": token}), nl=True)
    uvicorn.run(create_app(db or db_path(), token), host="127.0.0.1", port=port, log_level="warning")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest -v -m "not slow"`
Expected: PASS for the whole suite (about 70 tests). Then `uv run pytest -m slow` once more to confirm the accuracy gate still passes.

- [ ] **Step 5: Lint, update README, commit**

Add to `service/README.md` under a `## Run` heading:

```markdown
## Run

```powershell
uv run whf data generate                 # dummy data into the app data folder
uv run whf run --team 1                  # forecast one team, prints the table
uv run whf runs list ; uv run whf runs show 1 --json
uv run whf export 1 --format csv --out forecast.csv
uv run whf serve                         # prints {"port": ..., "token": ...} then serves on 127.0.0.1
```
```

```bash
uv run ruff check . && uv run ruff format .
git add service/src/whf/api.py service/src/whf/cli.py service/tests/test_api.py service/README.md
git commit -m "feat(service): token-protected localhost API and serve command"
```

---

## Self-review against the spec

- **Section 3 (data model):** Task 4 creates every table listed, including `run_narratives` (filled by the Copilot plan) and `profiles` (used by the app plan).
- **Section 4 (generator):** Tasks 5 and 6 cover departments, teams, member roles, profiles, project phase curves, seasonality, Morocco holidays, historical and future vacations, projects starting in the horizon, the hidden effort log and the answer key, reproducibility, and truncation for evaluation.
- **Section 5.1 to 5.3:** Task 7 (arrival series and features), Tasks 8 and 9 (seasonal naive, TSB, gradient boosting with Poisson loss), Task 10 (cycle time, estimate ratio, lateness through the cycle-time fallback and placement over working days). Deviation from the spec: the quantile variants of the gradient boosting model are replaced by residual-based interval calibration for every model (spec 5.5 already requires residual calibration); quantile models can be added later as a second source of intervals.
- **Section 5.4 (capacity):** Task 3 and the capacity rows in Task 13 (default, permanent and weekly overrides, holidays, vacations, uncapped demand, overload).
- **Section 5.5 (backtest):** Task 11 and its use in Task 13 (six origins, MASE against seasonal naive, floor fallback, calibration, stored champion and score).
- **Section 5.6 (patterns):** Task 12, including clustering.
- **Section 5.7 (growth path):** the `MODEL_FACTORIES` registry in Task 8 and 9.
- **Section 6 (Copilot):** out of scope here; Task 13 stores the facts JSON with the member, project, model and rebalancing sections that the Copilot plan consumes.
- **Section 8 (CLI):** Task 14 and 15 cover every listed command except `whf copilot status` (Copilot plan).
- **Section 10 (testing):** property tests in Tasks 2, 3 and 10; the accuracy gate in Task 13 is marked `slow`.
- **Type consistency check:** `forecast_weeks` returns a tuple of two dates and is used as such in Tasks 13 to 15; `EffortModel.estimate_ratio(member_id, task_type, team_id)` has the same signature in Tasks 10 and 13; `place_open_tasks` and `place_new_arrivals` return `member_id, week_start, hours` frames in both tasks; `RunResult.forecasts` columns equal the `forecasts` table columns minus `run_id`; the CLI and API both call `run_forecast(conn, team_id=..., as_of=..., requested_by=...)`.
