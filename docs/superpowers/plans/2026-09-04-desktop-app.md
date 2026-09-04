# Desktop Application (Electron) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the Windows desktop application that starts the local forecast service, lets a team leader or skill team leader run a forecast without a terminal, shows every screen of spec section 7, and notifies the user when a forecast is due or members are overloaded.

**Architecture:** An Electron main process (TypeScript) spawns the Python service (`whf serve`), reads the port and token from its first stdout line, waits for `/health`, and is the only process that holds the token: the React renderer calls the service through a preload bridge (`window.whf.request`) that main forwards over IPC, so no CORS and no token in the renderer. The renderer is a React 19 single-page app with hash routing and one page per spec screen. Missing service endpoints the screens need (profile, holidays, project edit, deletes, due check, department overview) are added to the Python API first, test-driven.

**Tech Stack:** Electron 44, electron-vite 5 (Vite 7), React 19, react-router-dom 7, Recharts 3, TypeScript 5.9, ESLint 10 + typescript-eslint, Vitest 5 (node project for main, jsdom project for renderer), Testing Library; Python side unchanged (FastAPI, pytest).

**Spec:** `docs/superpowers/specs/2026-09-03-workload-forecast-design.md` (sections 2, 7, 10, 11). Owner answers: `docs/requirements/2026-09-03-discovery-qa.md` (Q3, Q6, Q7, Q13-Q16).

## Global Constraints

- Everything runs on Windows in PowerShell and on Linux CI; no WSL; paths through `path`/`pathlib`; no shell-specific code in the app (use `child_process.spawn` with argument arrays, never a shell string).
- The language model never produces a forecast number: the app only displays what the service stores; the narrative's `suggested_adjustments` are shown as suggestions and never merged into forecast rows.
- The service token is a per-launch secret: it lives in the main process only; the renderer never receives it; `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true` on every window.
- The service binds `127.0.0.1` on a random free port and prints `{"port": <int>, "token": "<str>"}` as its first stdout line (`whf serve`, `service/src/whf/cli.py`); the main process must poll `GET /health` until it answers `{"status": "ok"}` because the line is printed before the socket is bound.
- Data directory: the service decides (`%LOCALAPPDATA%\WorkloadHubForecast\` on Windows, `WHF_HOME` override); the app stores its own settings JSON under Electron's `userData`.
- Roles: a `team_leader` sees and runs only their own team; a `skill_team_leader` sees all teams of their department and runs any single team on behalf of its leader (one team per run, never all). The profile is chosen in Settings (dummy data has no real identity) and stored by the service (`profiles` table, `PUT /profile`).
- Notifications: at app start and every 24 hours ask the service whether the profile's team (or, for a skill team leader, each team of the department) has a successful run in the last 14 days; if not, show a Windows notification "Forecast due". After a run, notify with the members whose total overload over the two weeks exceeds 0 hours.
- Dates are ISO 8601 strings in the API; weeks start on Monday; hours are shown with one decimal.
- Quality gates before each commit: in `service/` `uv run ruff check --fix . && uv run ruff format .` then `uv run ruff check . && uv run ruff format --check .` and `uv run pytest -q -m "not slow"`; in `app/` `npm run lint`, `npm run typecheck`, `npm test` (all three green). TDD for every task.
- Versions (pin with caret in `package.json`, commit `package-lock.json`): electron ^44.1, electron-vite ^5.0, vite ^7.3, react ^19.2, react-dom ^19.2, react-router-dom ^7.18, recharts ^3.10, typescript ~5.9 (typescript-eslint does not support 6 yet), eslint ^10.9, typescript-eslint ^8.69, @eslint/js ^10, eslint-plugin-react-hooks ^7.1, globals ^17, vitest ^5.0, jsdom ^30, @testing-library/react ^16.3, @testing-library/jest-dom ^7, @vitejs/plugin-react ^6.1, @types/react ^19.2, @types/react-dom ^19.2, @types/node ^22 (matches Node 22). Do not add other runtime dependencies.
- No `any` in app code; `strict: true`; every IPC channel name is a constant in `src/shared/ipc.ts`.
- Playwright end-to-end tests are out of scope (spec section 10: "later"); packaging is plan 4.

---

## File structure

```
service/src/whf/admin.py          add set_profile, update_project, delete_capacity_override, delete_vacation
service/src/whf/api.py            add GET/PUT /profile, GET /holidays, PUT /projects/{id}, DELETE /capacity/overrides/{id},
                                  DELETE /vacations/{id}, GET /teams/{id}/due, GET /departments/{id}/overview
service/src/whf/overview.py       new: pure functions run_is_due(...) and department_overview(...)
service/tests/test_admin.py       extend
service/tests/test_api.py         extend
service/tests/test_overview.py    new

app/package.json                  scripts: dev, build, lint, typecheck, test, icon
app/electron.vite.config.ts       main / preload / renderer builds
app/tsconfig.json, tsconfig.node.json, tsconfig.web.json
app/eslint.config.js              flat config
app/vitest.config.ts              two projects: main (node), renderer (jsdom)
app/scripts/make-icon.mjs         writes resources/icon.png (256x256 solid PNG, no dependencies)
app/resources/icon.png            generated, committed
app/src/shared/ipc.ts             channel names and the ApiRequest / ApiResponse / Settings / ServiceState types
app/src/shared/types.ts           API payload types mirrored from the Python service
app/src/main/index.ts             app lifecycle: single instance, service start, window, tray, notifications, quit
app/src/main/service-launcher.ts  serviceCommand(), parseHandshake(), waitForHealth(), ServiceProcess class
app/src/main/api-client.ts        ApiClient: fetch to 127.0.0.1 with the token header, typed errors
app/src/main/settings-store.ts    SettingsStore: JSON file in userData, defaults, atomic write
app/src/main/ipc.ts               registerIpc(): api:request, settings:get/set, copilot:login, app:state
app/src/main/copilot-login.ts     opens a PowerShell/terminal window running `<cli> login`
app/src/main/due-check.ts         pure: teamsToCheck(), isDue(); DueChecker with timer
app/src/main/notifications.ts     notifyDue(), notifyOverload(); wraps Electron Notification
app/src/main/tray.ts              createTray(): Open, Check forecast due, Quit
app/src/preload/index.ts          contextBridge.exposeInMainWorld('whf', ...)
app/src/renderer/index.html
app/src/renderer/src/main.tsx     createRoot + HashRouter + AppProvider
app/src/renderer/src/app.tsx      routes and Layout (nav, header with profile and service state)
app/src/renderer/src/api.ts       typed functions over window.whf.request (getMeta, createRun, ...)
app/src/renderer/src/context.tsx  AppProvider: meta, profile, settings, visibleTeams(), refresh()
app/src/renderer/src/i18n.ts      t(key), dictionaries en (complete) and fr (partial, falls back to en)
app/src/renderer/src/format.ts    hours(), isoWeek(), weekLabel()
app/src/renderer/src/styles.css   tokens and layout
app/src/renderer/src/components/  WeekTable.tsx, IntervalBar.tsx, RiskBadge.tsx, DemandCapacityChart.tsx, HistoryChart.tsx,
                                  StatusMessage.tsx, Field.tsx
app/src/renderer/src/pages/       Dashboard.tsx, Run.tsx, TeamResult.tsx, MemberDetail.tsx, Rebalancing.tsx, Projects.tsx,
                                  Capacity.tsx, TimeOff.tsx, Runs.tsx, Settings.tsx
app/src/renderer/src/test/        setup.ts (jest-dom), fake-whf.ts (window.whf stub with canned responses)
app/src/main/__tests__/           service-launcher.test.ts, api-client.test.ts, settings-store.test.ts, due-check.test.ts, ipc.test.ts
app/src/renderer/src/__tests__/   one test file per page plus i18n.test.ts, format.test.ts
app/README.md
CLAUDE.md                         toolchain lines for app/
docs/superpowers/specs/...        deviation notes (IPC bridge instead of renderer HTTP; language setting scope)
```

---

### Task 1: Profile and holidays endpoints

**Files:**
- Modify: `service/src/whf/admin.py` (append), `service/src/whf/api.py` (models and routes)
- Test: `service/tests/test_admin.py`, `service/tests/test_api.py`

**Interfaces:**
- Consumes: `profiles(id=1, member_id, role)` and `holidays(date, name, country)` tables (`service/src/whf/db/schema.sql`), `members.role`.
- Produces: `set_profile(conn, member_id: int | None) -> dict` (stores the member's role from `members`, or clears both with `None`), `GET /profile -> {"member_id": int|null, "role": str|null}`, `PUT /profile {"member_id": int|null}` (404 if the member does not exist), `GET /holidays?year=YYYY -> [{"date","name","country"}]` (all holidays when `year` is omitted).

- [ ] **Step 1: Write the failing admin test**

Append to `service/tests/test_admin.py`:

```python
def test_set_profile_stores_member_and_role(db) -> None:
    from whf.admin import set_profile
    from whf.db.repo import read_df

    leader = read_df(db, "SELECT id, role FROM members WHERE role = 'team_leader' LIMIT 1").iloc[0]
    assert set_profile(db, int(leader["id"])) == {"member_id": int(leader["id"]), "role": "team_leader"}
    stored = read_df(db, "SELECT member_id, role FROM profiles WHERE id = 1").iloc[0]
    assert int(stored["member_id"]) == int(leader["id"]) and stored["role"] == "team_leader"
    assert set_profile(db, None) == {"member_id": None, "role": None}


def test_set_profile_rejects_unknown_member(db) -> None:
    import pytest

    from whf.admin import set_profile

    with pytest.raises(ValueError, match="member 999999"):
        set_profile(db, 999999)
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd service && uv run pytest -q tests/test_admin.py -k profile`
Expected: FAIL with `ImportError: cannot import name 'set_profile'`

- [ ] **Step 3: Implement `set_profile`**

Append to `service/src/whf/admin.py`:

```python
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
```

- [ ] **Step 4: Run the admin tests**

Run: `cd service && uv run pytest -q tests/test_admin.py`
Expected: PASS

- [ ] **Step 5: Write the failing API tests**

Append to `service/tests/test_api.py`:

```python
def test_profile_round_trip(client) -> None:
    assert client.get("/profile", headers=_h()).json() == {"member_id": None, "role": None}
    meta = client.get("/meta", headers=_h()).json()
    leader = next(m for m in meta["members"] if m["role"] == "skill_team_leader")
    put = client.put("/profile", json={"member_id": leader["id"]}, headers=_h())
    assert put.status_code == 200 and put.json() == {"member_id": leader["id"], "role": "skill_team_leader"}
    assert client.get("/profile", headers=_h()).json()["role"] == "skill_team_leader"
    assert client.put("/profile", json={"member_id": 999999}, headers=_h()).status_code == 404
    assert client.put("/profile", json={"member_id": None}, headers=_h()).json() == {"member_id": None, "role": None}


def test_holidays_are_listed_and_filtered_by_year(client) -> None:
    rows = client.get("/holidays", headers=_h()).json()
    assert rows and {"date", "name", "country"} <= set(rows[0])
    year = int(rows[0]["date"][:4])
    filtered = client.get(f"/holidays?year={year}", headers=_h()).json()
    assert filtered and all(r["date"].startswith(str(year)) for r in filtered)
    assert client.get("/holidays?year=1900", headers=_h()).json() == []
```

- [ ] **Step 6: Run them to verify they fail**

Run: `cd service && uv run pytest -q tests/test_api.py -k "profile or holidays"`
Expected: FAIL with status 404 (routes missing)

- [ ] **Step 7: Add the routes**

In `service/src/whf/api.py`, extend the admin import to `from whf.admin import add_project, add_vacation, set_capacity_default, set_capacity_override, set_profile`, add the model next to the others:

```python
class ProfileUpdate(BaseModel):
    member_id: int | None = None
```

and add before `return app`:

```python
    @app.get("/profile", dependencies=guarded)
    def get_profile(conn: sqlite3.Connection = Depends(db)) -> dict:
        row = conn.execute("SELECT member_id, role FROM profiles WHERE id = 1").fetchone()
        if row is None:
            return {"member_id": None, "role": None}
        return {"member_id": None if row[0] is None else int(row[0]), "role": row[1]}

    @app.put("/profile", dependencies=guarded)
    def put_profile(body: ProfileUpdate, conn: sqlite3.Connection = Depends(db)) -> dict:
        try:
            return set_profile(conn, body.member_id)
        except ValueError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get("/holidays", dependencies=guarded)
    def get_holidays(year: int | None = Query(default=None), conn: sqlite3.Connection = Depends(db)) -> list:
        if year is None:
            df = read_df(conn, "SELECT date, name, country FROM holidays ORDER BY date")
        else:
            df = read_df(
                conn, "SELECT date, name, country FROM holidays WHERE date LIKE ? ORDER BY date", (f"{year:04d}-%",)
            )
        return jsonable(df.to_dict(orient="records"))
```

- [ ] **Step 8: Run the API tests, lint, commit**

Run: `cd service && uv run pytest -q tests/test_api.py tests/test_admin.py && uv run ruff check --fix . && uv run ruff format . && uv run ruff check . && uv run ruff format --check .`
Expected: PASS, `All checks passed!`

```bash
git add service/src/whf/admin.py service/src/whf/api.py service/tests/test_admin.py service/tests/test_api.py
git commit -m "feat(api): profile and holidays endpoints for the desktop app"
```

---

### Task 2: Project update and delete endpoints for overrides and vacations

**Files:**
- Modify: `service/src/whf/admin.py`, `service/src/whf/api.py`
- Test: `service/tests/test_admin.py`, `service/tests/test_api.py`

**Interfaces:**
- Consumes: `add_project`, `set_capacity_override`, `add_vacation` from `whf.admin`; tables `projects`, `project_teams`, `capacity_overrides(id AUTOINCREMENT)`, `vacations(id AUTOINCREMENT)`.
- Produces: `update_project(conn, project_id, *, name, start_date, deadline, team_ids, kind, status) -> None` (all keyword, all required; raises `KeyError` when the project is missing, `ValueError` when deadline <= start_date or `team_ids` empty), `delete_capacity_override(conn, override_id) -> bool`, `delete_vacation(conn, vacation_id) -> bool` (False when nothing was deleted). Routes `PUT /projects/{id}` (body `ProjectUpdate`: `name`, `start_date`, `deadline`, `team_ids`, `type`, `status` in `planned|active|done`), `DELETE /capacity/overrides/{id}` and `DELETE /vacations/{id}` returning `{"deleted": true}` or 404.

- [ ] **Step 1: Write the failing admin tests**

Append to `service/tests/test_admin.py`:

```python
def test_update_project_replaces_fields_and_teams(db) -> None:
    import datetime as dt

    from whf.admin import add_project, update_project
    from whf.db.repo import read_df

    pid = add_project(db, "Alpha", 1, dt.date(2026, 10, 5), dt.date(2026, 11, 27), [1])
    update_project(
        db, pid, name="Alpha 2", start_date=dt.date(2026, 10, 12), deadline=dt.date(2026, 12, 4),
        team_ids=[1, 2], kind="maintenance", status="active",
    )
    row = read_df(db, "SELECT * FROM projects WHERE id = ?", (pid,)).iloc[0]
    assert row["name"] == "Alpha 2" and row["start_date"] == "2026-10-12" and row["deadline"] == "2026-12-04"
    assert row["type"] == "maintenance" and row["status"] == "active"
    teams = read_df(db, "SELECT team_id FROM project_teams WHERE project_id = ? ORDER BY team_id", (pid,))
    assert list(teams["team_id"]) == [1, 2]


def test_update_project_validates(db) -> None:
    import datetime as dt

    import pytest

    from whf.admin import add_project, update_project

    pid = add_project(db, "Beta", 1, dt.date(2026, 10, 5), dt.date(2026, 11, 27), [1])
    with pytest.raises(ValueError, match="deadline"):
        update_project(db, pid, name="B", start_date=dt.date(2026, 10, 5), deadline=dt.date(2026, 10, 5),
                       team_ids=[1], kind="delivery", status="planned")
    with pytest.raises(ValueError, match="team"):
        update_project(db, pid, name="B", start_date=dt.date(2026, 10, 5), deadline=dt.date(2026, 10, 9),
                       team_ids=[], kind="delivery", status="planned")
    with pytest.raises(KeyError):
        update_project(db, 999999, name="B", start_date=dt.date(2026, 10, 5), deadline=dt.date(2026, 10, 9),
                       team_ids=[1], kind="delivery", status="planned")


def test_delete_override_and_vacation(db) -> None:
    import datetime as dt

    from whf.admin import add_vacation, delete_capacity_override, delete_vacation, set_capacity_override
    from whf.db.repo import read_df

    set_capacity_override(db, 1, 32.0, dt.date(2026, 10, 5), "training")
    oid = int(read_df(db, "SELECT id FROM capacity_overrides WHERE member_id = 1 AND week_start = '2026-10-05'")["id"][0])
    assert delete_capacity_override(db, oid) is True
    assert delete_capacity_override(db, oid) is False
    vid = add_vacation(db, 1, dt.date(2026, 10, 5), dt.date(2026, 10, 7))
    assert delete_vacation(db, vid) is True
    assert delete_vacation(db, vid) is False
```

- [ ] **Step 2: Run to verify failure**

Run: `cd service && uv run pytest -q tests/test_admin.py -k "update_project or delete"`
Expected: FAIL with ImportError

- [ ] **Step 3: Implement the admin functions**

Append to `service/src/whf/admin.py`:

```python
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
```

(`import datetime as dt` is already at the top of `admin.py`; keep it.)

- [ ] **Step 4: Run the admin tests**

Run: `cd service && uv run pytest -q tests/test_admin.py`
Expected: PASS

- [ ] **Step 5: Write the failing API tests**

Append to `service/tests/test_api.py`:

```python
def test_project_update_and_deletes(client) -> None:
    created = client.post(
        "/projects",
        json={"name": "Gamma", "department_id": 1, "start_date": "2026-10-05", "deadline": "2026-11-27", "team_ids": [1]},
        headers=_h(),
    ).json()
    body = {"name": "Gamma 2", "start_date": "2026-10-12", "deadline": "2026-12-04", "team_ids": [1, 2],
            "type": "delivery", "status": "active"}
    updated = client.put(f"/projects/{created['id']}", json=body, headers=_h())
    assert updated.status_code == 200 and updated.json()["team_ids"] == [1, 2] and updated.json()["status"] == "active"
    assert client.put("/projects/999999", json=body, headers=_h()).status_code == 404
    bad = {**body, "deadline": "2026-10-12"}
    assert client.put(f"/projects/{created['id']}", json=bad, headers=_h()).status_code == 422

    client.put("/capacity/overrides", json={"member_id": 1, "weekly_hours": 30, "week_start": "2026-10-05"}, headers=_h())
    overrides = client.get("/capacity", headers=_h()).json()["overrides"]
    oid = next(o["id"] for o in overrides if o["member_id"] == 1 and o["week_start"] == "2026-10-05")
    assert client.delete(f"/capacity/overrides/{oid}", headers=_h()).json() == {"deleted": True}
    assert client.delete(f"/capacity/overrides/{oid}", headers=_h()).status_code == 404

    vid = client.post("/vacations", json={"member_id": 1, "start_date": "2026-10-05", "end_date": "2026-10-06"},
                      headers=_h()).json()["id"]
    assert client.delete(f"/vacations/{vid}", headers=_h()).json() == {"deleted": True}
    assert client.delete(f"/vacations/{vid}", headers=_h()).status_code == 404
```

- [ ] **Step 6: Run to verify failure**

Run: `cd service && uv run pytest -q tests/test_api.py -k "update_and_deletes"`
Expected: FAIL (405 or 404)

- [ ] **Step 7: Add the routes**

In `service/src/whf/api.py` extend the admin import with `delete_capacity_override, delete_vacation, update_project`, add the model:

```python
class ProjectUpdate(BaseModel):
    name: str = Field(min_length=1)
    start_date: dt.date
    deadline: dt.date
    team_ids: list[int] = Field(min_length=1)
    type: str = "delivery"
    status: Literal["planned", "active", "done"] = "planned"

    @model_validator(mode="after")
    def _deadline_after_start(self) -> ProjectUpdate:
        if self.deadline <= self.start_date:
            raise ValueError("deadline must be after start_date")
        return self
```

(add `from typing import Literal` to the imports) and the routes before `return app`:

```python
    @app.put("/projects/{project_id}", dependencies=guarded)
    def put_project(project_id: int, body: ProjectUpdate, conn: sqlite3.Connection = Depends(db)) -> dict:
        try:
            update_project(
                conn, project_id, name=body.name, start_date=body.start_date, deadline=body.deadline,
                team_ids=body.team_ids, kind=body.type, status=body.status,
            )
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return jsonable({"id": project_id, **body.model_dump()})

    @app.delete("/capacity/overrides/{override_id}", dependencies=guarded)
    def delete_override(override_id: int, conn: sqlite3.Connection = Depends(db)) -> dict:
        if not delete_capacity_override(conn, override_id):
            raise HTTPException(status_code=404, detail=f"override {override_id} not found")
        return {"deleted": True}

    @app.delete("/vacations/{vacation_id}", dependencies=guarded)
    def delete_vacation_route(vacation_id: int, conn: sqlite3.Connection = Depends(db)) -> dict:
        if not delete_vacation(conn, vacation_id):
            raise HTTPException(status_code=404, detail=f"vacation {vacation_id} not found")
        return {"deleted": True}
```

- [ ] **Step 8: Run, lint, commit**

Run: `cd service && uv run pytest -q tests/test_api.py tests/test_admin.py && uv run ruff check --fix . && uv run ruff format . && uv run ruff check . && uv run ruff format --check .`
Expected: PASS

```bash
git add service/src/whf/admin.py service/src/whf/api.py service/tests/test_admin.py service/tests/test_api.py
git commit -m "feat(api): edit projects, delete capacity overrides and vacations"
```

---

### Task 3: Due check and department overview

**Files:**
- Create: `service/src/whf/overview.py`, `service/tests/test_overview.py`
- Modify: `service/src/whf/api.py`
- Test: `service/tests/test_api.py`

**Interfaces:**
- Consumes: `list_runs(conn, team_id) -> DataFrame` and `load_run(conn, run_id) -> dict` from `whf.pipeline`; `runs.status == "ok"` marks a successful run; `forecasts` rows carry `member_id, week_start, demand_hours, capacity_hours, overload_hours`.
- Produces: `run_is_due(last_finished_at: str | None, now: dt.datetime, max_age_days: int = 14) -> bool`; `latest_ok_run_id(conn, team_id) -> int | None`; `department_overview(conn, department_id, now) -> dict` with shape `{"department_id", "teams": [{"team_id", "team_name", "run_id", "as_of", "finished_at", "due", "weeks": [{"week", "demand", "capacity", "overload"}], "overloaded": [{"member_id", "name", "overload_hours"}]}]}` (a team without a successful run has `run_id: None`, `due: True`, `weeks: []`, `overloaded: []`). Routes `GET /teams/{team_id}/due -> {"team_id", "due", "last_run_id", "last_finished_at"}` and `GET /departments/{department_id}/overview`.

- [ ] **Step 1: Write the failing unit tests**

`service/tests/test_overview.py`:

```python
import datetime as dt

from whf.overview import department_overview, latest_ok_run_id, run_is_due
from whf.pipeline import run_forecast

NOW = dt.datetime(2026, 9, 21, 9, 0)


def test_run_is_due_without_a_run_or_after_14_days() -> None:
    assert run_is_due(None, NOW) is True
    assert run_is_due("2026-09-08T10:00:00", NOW) is False
    assert run_is_due("2026-09-07T08:59:00", NOW) is True
    assert run_is_due("2026-09-07T09:00:00", NOW) is False


def test_latest_ok_run_and_overview(db, generated) -> None:
    as_of = generated.config.as_of
    assert latest_ok_run_id(db, 1) is None
    first = run_forecast(db, team_id=1, as_of=as_of)
    second = run_forecast(db, team_id=1, as_of=as_of)
    assert latest_ok_run_id(db, 1) == second.run_id > first.run_id

    overview = department_overview(db, 1, dt.datetime.combine(as_of, dt.time(9)))
    assert overview["department_id"] == 1
    teams = {t["team_id"]: t for t in overview["teams"]}
    ran = teams[1]
    assert ran["run_id"] == second.run_id and ran["due"] is False and len(ran["weeks"]) == 2
    assert ran["weeks"][0]["week"] == second.weeks[0].isoformat()
    assert abs(ran["weeks"][0]["demand"] - float(second.forecasts[second.forecasts.week_start == second.weeks[0]].demand_hours.sum())) < 0.11
    assert all({"member_id", "name", "overload_hours"} <= set(o) for o in ran["overloaded"])
    not_run = next(t for tid, t in teams.items() if tid != 1)
    assert not_run["run_id"] is None and not_run["due"] is True and not_run["weeks"] == []
```

- [ ] **Step 2: Run to verify failure**

Run: `cd service && uv run pytest -q tests/test_overview.py`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.overview'`

- [ ] **Step 3: Implement `overview.py`**

```python
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
    row = conn.execute(
        "SELECT id FROM runs WHERE team_id = ? AND status = 'ok' ORDER BY id DESC LIMIT 1", (team_id,)
    ).fetchone()
    return None if row is None else int(row[0])


def team_due(conn: sqlite3.Connection, team_id: int, now: dt.datetime) -> dict:
    row = conn.execute(
        "SELECT id, finished_at FROM runs WHERE team_id = ? AND status = 'ok' ORDER BY id DESC LIMIT 1", (team_id,)
    ).fetchone()
    last_id, finished = (None, None) if row is None else (int(row[0]), row[1])
    return {"team_id": team_id, "due": run_is_due(finished, now), "last_run_id": last_id, "last_finished_at": finished}


def _team_block(conn: sqlite3.Connection, team_id: int, team_name: str, now: dt.datetime) -> dict:
    due = team_due(conn, team_id, now)
    block = {
        "team_id": team_id, "team_name": team_name, "run_id": due["last_run_id"], "as_of": None,
        "finished_at": due["last_finished_at"], "due": due["due"], "weeks": [], "overloaded": [],
    }
    if due["last_run_id"] is None:
        return block
    run = conn.execute("SELECT as_of FROM runs WHERE id = ?", (due["last_run_id"],)).fetchone()
    block["as_of"] = run[0]
    fc = read_df(conn, "SELECT * FROM forecasts WHERE run_id = ?", (due["last_run_id"],))
    by_week = fc.groupby("week_start")[["demand_hours", "capacity_hours", "overload_hours"]].sum().sort_index()
    block["weeks"] = [
        {"week": str(w)[:10], "demand": round(float(r.demand_hours), 1), "capacity": round(float(r.capacity_hours), 1),
         "overload": round(float(r.overload_hours), 1)}
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
```

- [ ] **Step 4: Run the unit tests**

Run: `cd service && uv run pytest -q tests/test_overview.py`
Expected: PASS

- [ ] **Step 5: Write the failing API tests**

Append to `service/tests/test_api.py`:

```python
def test_due_and_overview_routes(client) -> None:
    due = client.get("/teams/1/due", headers=_h()).json()
    assert due == {"team_id": 1, "due": True, "last_run_id": None, "last_finished_at": None}
    run = client.post("/runs", json={"team_id": 1}, headers=_h()).json()
    due = client.get("/teams/1/due", headers=_h()).json()
    assert due["due"] is False and due["last_run_id"] == run["run_id"]
    overview = client.get("/departments/1/overview", headers=_h()).json()
    team = next(t for t in overview["teams"] if t["team_id"] == 1)
    assert team["run_id"] == run["run_id"] and len(team["weeks"]) == 2
    assert client.get("/departments/999/overview", headers=_h()).json()["teams"] == []
```

- [ ] **Step 6: Run to verify failure**

Run: `cd service && uv run pytest -q tests/test_api.py -k due_and_overview`
Expected: FAIL with 404

- [ ] **Step 7: Add the routes**

In `service/src/whf/api.py` add `from whf.overview import department_overview, team_due` and before `return app`:

```python
    @app.get("/teams/{team_id}/due", dependencies=guarded)
    def get_team_due(team_id: int, conn: sqlite3.Connection = Depends(db)) -> dict:
        return team_due(conn, team_id, dt.datetime.now())

    @app.get("/departments/{department_id}/overview", dependencies=guarded)
    def get_department_overview(department_id: int, conn: sqlite3.Connection = Depends(db)) -> dict:
        return jsonable(department_overview(conn, department_id, dt.datetime.now()))
```

- [ ] **Step 8: Run everything, lint, commit**

Run: `cd service && uv run pytest -q -m "not slow" && uv run ruff check --fix . && uv run ruff format . && uv run ruff check . && uv run ruff format --check .`
Expected: all passed

```bash
git add service/src/whf/overview.py service/src/whf/api.py service/tests/test_overview.py service/tests/test_api.py
git commit -m "feat(api): forecast due check and department overview"
```

---

### Task 4: App scaffold (electron-vite, lint, typecheck, tests, shared types)

**Files:**
- Create: `app/package.json`, `app/electron.vite.config.ts`, `app/tsconfig.json`, `app/tsconfig.node.json`, `app/tsconfig.web.json`, `app/eslint.config.js`, `app/vitest.config.ts`, `app/scripts/make-icon.mjs`, `app/resources/icon.png`, `app/src/shared/ipc.ts`, `app/src/shared/types.ts`, `app/src/main/index.ts` (minimal), `app/src/preload/index.ts` (minimal), `app/src/renderer/index.html`, `app/src/renderer/src/main.tsx`, `app/src/renderer/src/app.tsx`, `app/src/renderer/src/styles.css`, `app/src/renderer/src/test/setup.ts`, `app/src/shared/__tests__/types.test.ts`, `app/README.md`
- Modify: `.gitignore`, `CLAUDE.md`

**Interfaces:**
- Produces: every type in `src/shared/types.ts` and every channel and bridge type in `src/shared/ipc.ts` below; later tasks import them verbatim. `npm run dev`, `npm run build`, `npm run lint`, `npm run typecheck`, `npm test`.

- [ ] **Step 1: Create the package and configs**

`app/package.json`:

```json
{
  "name": "workloadhub-forecast",
  "productName": "WorkloadHub Forecast",
  "version": "0.1.0",
  "description": "Forecasts team workload for the next two weeks and explains it with GitHub Copilot",
  "main": "./out/main/index.js",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "electron-vite dev",
    "build": "electron-vite build",
    "preview": "electron-vite preview",
    "lint": "eslint .",
    "typecheck": "tsc --noEmit -p tsconfig.node.json && tsc --noEmit -p tsconfig.web.json",
    "test": "vitest run",
    "icon": "node scripts/make-icon.mjs"
  },
  "dependencies": {
    "react": "^19.2.8",
    "react-dom": "^19.2.8",
    "react-router-dom": "^7.18.3",
    "recharts": "^3.10.1"
  },
  "devDependencies": {
    "@eslint/js": "^10.0.1",
    "@testing-library/jest-dom": "^7.0.1",
    "@testing-library/react": "^16.3.3",
    "@types/node": "^22.0.0",
    "@types/react": "^19.2.18",
    "@types/react-dom": "^19.2.7",
    "@vitejs/plugin-react": "^6.1.1",
    "electron": "^44.1.1",
    "electron-vite": "^5.0.0",
    "eslint": "^10.9.1",
    "eslint-plugin-react-hooks": "^7.1.1",
    "globals": "^17.12.0",
    "jsdom": "^30.0.1",
    "typescript": "~5.9.3",
    "typescript-eslint": "^8.69.0",
    "vite": "^7.3.6",
    "vitest": "^5.0.0"
  }
}
```

`app/electron.vite.config.ts`:

```ts
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'electron-vite'

export default defineConfig({
  main: { build: { rollupOptions: { input: resolve(__dirname, 'src/main/index.ts') } } },
  preload: { build: { rollupOptions: { input: resolve(__dirname, 'src/preload/index.ts') } } },
  renderer: {
    root: resolve(__dirname, 'src/renderer'),
    build: { rollupOptions: { input: resolve(__dirname, 'src/renderer/index.html') } },
    plugins: [react()],
  },
})
```

`app/tsconfig.json`:

```json
{
  "files": [],
  "references": [{ "path": "./tsconfig.node.json" }, { "path": "./tsconfig.web.json" }]
}
```

`app/tsconfig.node.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "lib": ["ES2022"],
    "types": ["node"],
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "noImplicitOverride": true,
    "skipLibCheck": true,
    "composite": true,
    "esModuleInterop": true,
    "isolatedModules": true,
    "outDir": "out/types-node"
  },
  "include": ["electron.vite.config.ts", "vitest.config.ts", "src/main/**/*", "src/preload/**/*", "src/shared/**/*"]
}
```

`app/tsconfig.web.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "jsx": "react-jsx",
    "types": ["vitest/globals", "@testing-library/jest-dom"],
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "noImplicitOverride": true,
    "skipLibCheck": true,
    "composite": true,
    "isolatedModules": true,
    "outDir": "out/types-web"
  },
  "include": ["src/renderer/**/*", "src/shared/**/*"]
}
```

`app/eslint.config.js`:

```js
import js from '@eslint/js'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['out/**', 'dist/**', 'node_modules/**', 'resources/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/consistent-type-imports': 'error',
    },
  },
  { files: ['src/main/**/*.ts', 'src/preload/**/*.ts'], languageOptions: { globals: globals.node } },
  { files: ['src/renderer/**/*.{ts,tsx}'], languageOptions: { globals: globals.browser } },
  { files: ['scripts/**/*.mjs', 'eslint.config.js'], languageOptions: { globals: globals.node } },
)
```

`app/vitest.config.ts`:

```ts
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    projects: [
      {
        test: { name: 'main', environment: 'node', include: ['src/main/**/*.test.ts', 'src/shared/**/*.test.ts'] },
      },
      {
        plugins: [react()],
        test: {
          name: 'renderer',
          environment: 'jsdom',
          globals: true,
          setupFiles: ['src/renderer/src/test/setup.ts'],
          include: ['src/renderer/**/*.test.{ts,tsx}'],
        },
      },
    ],
  },
})
```

`app/src/renderer/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest'
```

- [ ] **Step 2: Write the shared types**

`app/src/shared/types.ts` (mirrors `service/src/whf/api.py`, `pipeline._build_facts`, `ai/schema.py`, `overview.py`):

```ts
export type Role = 'member' | 'team_leader' | 'skill_team_leader'

export interface Department { id: number; name: string; skill_team_leader_id: number | null }
export interface Team { id: number; department_id: number; name: string; team_leader_id: number | null }
export interface Member {
  id: number; name: string; team_id: number | null; department_id: number; role: Role; counted_in_workload: number
}
export interface Meta { departments: Department[]; teams: Team[]; members: Member[]; capacity_default: number }
export interface Profile { member_id: number | null; role: Role | null }

export interface RunSummary {
  id: number; team_id: number; as_of: string; requested_by: number | null; status: string
  champion_model: string | null; backtest_mase: number | null; started_at: string; finished_at: string | null
  ai_status: string
}
export interface ForecastRow {
  run_id: number; member_id: number; week_start: string; demand_hours: number; demand_low: number; demand_high: number
  capacity_hours: number; overload_hours: number; open_task_hours: number; new_task_hours: number
}
export interface RunCreated {
  run_id: number; team_id: number; as_of: string; weeks: string[]; champion: string; backtest_mase: number
  forecasts: ForecastRow[]
}

export interface HistoryPoint { week: string; hours: number; tasks: number }
export interface MemberForecastFact {
  week: string; demand: number; low: number; high: number; capacity: number; overload: number
  open_hours: number; new_hours: number
}
export interface OpenTaskFact {
  id: number; title: string; type: string; priority: string; estimated_hours: number; due_date: string | null
  overdue: boolean; project_id: number | null
}
export interface PatternStats {
  member_id: number; trend_hours_per_week: number | null; top_weekday: string | null
  weekday_shares: Record<string, number>; estimate_ratio_median: number | null; cycle_days_median: number | null
  cycle_days_by_type: Record<string, number>; lateness_days_median: number | null; share_late: number | null
  deadline_proximity_corr: number | null; share_with_project: number | null; hours_by_project: Record<string, number>
  open_tasks: number; open_est_hours: number; overdue_open: number; cluster: number
  [extra: string]: unknown
}
export interface MemberFacts {
  id: number; name: string; role: Role; history_13w: HistoryPoint[]; forecast: MemberForecastFact[]
  patterns: PatternStats; open_tasks: OpenTaskFact[]
}
export interface ProjectFact {
  id: number; name: string; start_date: string; deadline: string; status: string; type: string
  active_in_window: boolean; starting_in_window: boolean; ending_in_window: boolean
}
export interface RunFacts {
  run: { id: number | null; as_of: string; weeks: string[]; generated_at: string }
  team: { id: number; name: string; department_id: number; team_leader_id: number | null
    totals: { week: string; demand: number; capacity: number }[] }
  members: MemberFacts[]
  projects: ProjectFact[]
  model: { champion: string; champion_mase: number; mase_by_model: Record<string, number>; backtest_origins: string[]
    horizons: number[]; limitations: string; interval: { basis: string; horizons: Record<string, { low: number; high: number }> } }
  rebalancing_candidates: { overloaded: { member_id: number; name: string; overload_hours: number }[]
    underloaded: { member_id: number; name: string; spare_hours: number }[] }
}

export type RiskLevel = 'low' | 'medium' | 'high'
export interface PatternFinding { kind: string; statement: string; evidence: string }
export interface MemberNarrative {
  member_id: number; name: string; risk_level: RiskLevel; summary: string; patterns: PatternFinding[]; warnings: string[]
}
export interface TeamRisk { title: string; detail: string; severity: RiskLevel; member_ids: number[] }
export interface RebalancingMove {
  from_member_id: number; to_member_id: number; week: string; hours: number; reason: string; confidence: RiskLevel
}
export interface SuggestedAdjustment { member_id: number; week: string; delta_hours: number; reason: string }
export interface Narrative {
  run_summary: string; members: MemberNarrative[]; team_risks: TeamRisk[]; rebalancing: RebalancingMove[]
  suggested_adjustments: SuggestedAdjustment[]; model_notes: string
}
export interface RunDetail { run: RunSummary; forecasts: ForecastRow[]; facts: RunFacts | null; narrative: Narrative | null }
export interface NarrativeOutcome {
  run_id: number; status: 'ok' | 'unverified' | 'failed'; ai_status: string; narrative: Narrative | null
  error: string | null; reason: string | null; attempts: number; tool_calls: string[]
}

export interface CopilotStatus {
  cli_path: string | null; cli_source: string; authenticated: boolean | null; login: string | null; message: string
  ready: boolean
}

export interface Project {
  id: number; name: string; department_id: number; start_date: string; deadline: string; type: string; status: string
  created_by: number | null; team_ids: number[]
}
export interface ProjectInput {
  name: string; department_id: number; start_date: string; deadline: string; team_ids: number[]; type: string
}
export interface ProjectUpdate {
  name: string; start_date: string; deadline: string; team_ids: number[]; type: string
  status: 'planned' | 'active' | 'done'
}
export interface CapacityOverride {
  id: number; member_id: number; week_start: string | null; weekly_hours: number; reason: string | null
}
export interface Capacity { default_weekly_hours: number; overrides: CapacityOverride[] }
export interface Holiday { date: string; name: string; country: string }
export interface Vacation { id: number; member_id: number; start_date: string; end_date: string; type: string }
export interface TeamDue { team_id: number; due: boolean; last_run_id: number | null; last_finished_at: string | null }
export interface OverviewTeam {
  team_id: number; team_name: string; run_id: number | null; as_of: string | null; finished_at: string | null
  due: boolean; weeks: { week: string; demand: number; capacity: number; overload: number }[]
  overloaded: { member_id: number; name: string; overload_hours: number }[]
}
export interface DepartmentOverview { department_id: number; teams: OverviewTeam[] }
```

`app/src/shared/ipc.ts`:

```ts
export const IPC = {
  apiRequest: 'api:request',
  settingsGet: 'settings:get',
  settingsSet: 'settings:set',
  copilotLogin: 'copilot:login',
  appState: 'app:state',
  appStateChanged: 'app:state-changed',
  openExternal: 'app:open-external',
} as const

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

export interface ApiRequest { method: HttpMethod; path: string; body?: unknown }
export type ApiResponse =
  | { ok: true; status: number; data: unknown }
  | { ok: false; status: number; error: string }

export type Language = 'en' | 'fr'
export interface Settings { language: Language; model: string | null; launchAtLogin: boolean; closeToTray: boolean }
export const DEFAULT_SETTINGS: Settings = { language: 'en', model: null, launchAtLogin: false, closeToTray: true }

export type ServicePhase = 'starting' | 'ready' | 'failed' | 'stopped'
export interface AppState { service: ServicePhase; serviceMessage: string; version: string; platform: string }

export interface WhfBridge {
  request(req: ApiRequest): Promise<ApiResponse>
  getSettings(): Promise<Settings>
  setSettings(patch: Partial<Settings>): Promise<Settings>
  copilotLogin(): Promise<{ started: boolean; message: string }>
  getState(): Promise<AppState>
  onStateChanged(listener: (state: AppState) => void): () => void
  openExternal(url: string): Promise<void>
}

declare global {
  interface Window { whf: WhfBridge }
}
```

- [ ] **Step 3: Write a failing test for the shared defaults**

`app/src/shared/__tests__/types.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { DEFAULT_SETTINGS, IPC } from '../ipc'

describe('shared contracts', () => {
  it('has unique channel names', () => {
    const names = Object.values(IPC)
    expect(new Set(names).size).toBe(names.length)
  })
  it('defaults to English, no model, no auto-start, close to tray', () => {
    expect(DEFAULT_SETTINGS).toEqual({ language: 'en', model: null, launchAtLogin: false, closeToTray: true })
  })
})
```

- [ ] **Step 4: Minimal main, preload and renderer so the build runs**

`app/src/main/index.ts` (replaced in Task 6):

```ts
import { app, BrowserWindow } from 'electron'
import { join } from 'node:path'

function createWindow(): void {
  const win = new BrowserWindow({
    width: 1280, height: 800, show: false,
    webPreferences: { preload: join(__dirname, '../preload/index.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
  })
  win.once('ready-to-show', () => win.show())
  if (process.env['ELECTRON_RENDERER_URL']) void win.loadURL(process.env['ELECTRON_RENDERER_URL'])
  else void win.loadFile(join(__dirname, '../renderer/index.html'))
}

void app.whenReady().then(createWindow)
app.on('window-all-closed', () => app.quit())
```

`app/src/preload/index.ts` (replaced in Task 6):

```ts
import { contextBridge } from 'electron'

contextBridge.exposeInMainWorld('whf', {})
```

`app/src/renderer/index.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta http-equiv="Content-Security-Policy" content="default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:" />
    <title>WorkloadHub Forecast</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`app/src/renderer/src/main.tsx`:

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import { App } from './app'
import './styles.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <HashRouter>
      <App />
    </HashRouter>
  </StrictMode>,
)
```

`app/src/renderer/src/app.tsx` (replaced in Task 8):

```tsx
import type React from 'react'

export function App(): React.JSX.Element {
  return <h1>WorkloadHub Forecast</h1>
}
```

(Every component uses `React.JSX.Element` with `import type React from 'react'`; keep that form in every later file.)

`app/src/renderer/src/styles.css`:

```css
:root {
  --bg: #f6f7f9; --panel: #ffffff; --ink: #1c1f24; --muted: #6b7280; --line: #e5e7eb;
  --accent: #2457c5; --ok: #1a7f4b; --warn: #b7791f; --bad: #b42318; --radius: 8px;
  font-family: "Segoe UI", system-ui, sans-serif; font-size: 14px; color: var(--ink); background: var(--bg);
}
* { box-sizing: border-box; }
body { margin: 0; }
.layout { display: grid; grid-template-columns: 220px 1fr; min-height: 100vh; }
.nav { background: var(--panel); border-right: 1px solid var(--line); padding: 16px; }
.nav a { display: block; padding: 8px 10px; border-radius: var(--radius); color: var(--ink); text-decoration: none; }
.nav a.active { background: var(--accent); color: #fff; }
.content { padding: 24px; }
.panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); padding: 16px; margin-bottom: 16px; }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--line); }
td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 12px; color: #fff; }
.badge.low { background: var(--ok); } .badge.medium { background: var(--warn); } .badge.high { background: var(--bad); }
.bar { position: relative; height: 10px; background: var(--line); border-radius: 5px; }
.bar > span { position: absolute; top: 0; bottom: 0; background: var(--accent); border-radius: 5px; }
.bar > i { position: absolute; top: -3px; width: 2px; height: 16px; background: var(--ink); }
.field { display: flex; flex-direction: column; gap: 4px; margin-bottom: 12px; }
.field label { color: var(--muted); font-size: 12px; }
input, select, button { font: inherit; padding: 6px 8px; border: 1px solid var(--line); border-radius: var(--radius); }
button.primary { background: var(--accent); color: #fff; border-color: var(--accent); cursor: pointer; }
button:disabled { opacity: 0.6; cursor: default; }
.status { padding: 8px 12px; border-radius: var(--radius); margin-bottom: 12px; }
.status.info { background: #e8effc; } .status.error { background: #fde8e6; } .status.success { background: #e3f5ea; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.muted { color: var(--muted); }
```

- [ ] **Step 5: The icon generator**

`app/scripts/make-icon.mjs` (no dependencies; writes a 256x256 solid PNG with a lighter square, enough for a tray and window icon until design assets exist):

```js
import { deflateSync } from 'node:zlib'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const size = 256
const rows = []
for (let y = 0; y < size; y++) {
  const row = [0]
  for (let x = 0; x < size; x++) {
    const inner = x > 48 && x < 208 && y > 48 && y < 208
    row.push(...(inner ? [255, 255, 255] : [36, 87, 197]))
  }
  rows.push(Buffer.from(row))
}
const raw = Buffer.concat(rows)
const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
const crc32 = (buf) => {
  let c = 0xffffffff
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}
const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
])
const out = resolve(dirname(fileURLToPath(import.meta.url)), '../resources/icon.png')
mkdirSync(dirname(out), { recursive: true })
writeFileSync(out, png)
console.log(`wrote ${out} (${png.length} bytes)`)
```

- [ ] **Step 6: Install, generate the icon, run every gate**

Run from `app/`: `npm install` then `npm run icon`, `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`.
Expected: install succeeds (Chromium download for Electron may take a minute; `ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm install` is acceptable on Linux CI where the binary is not needed), the icon is written, lint and typecheck report no errors, vitest reports 2 passed, build writes `out/main`, `out/preload`, `out/renderer`.
If `npm run build` fails only because the Electron binary is missing, that is acceptable: electron-vite build does not need it; report the exact error if anything else fails.

- [ ] **Step 7: Ignore build outputs, document, commit**

Append to `.gitignore`:

```
# Node / Electron
node_modules/
app/out/
app/dist/
```

`app/README.md`:

```markdown
# WorkloadHub Forecast desktop app

Electron + React front end for the forecast service in `../service`.

## Develop (PowerShell)

```powershell
cd app
npm install
npm run dev          # starts Vite for the renderer and Electron; the main process starts `uv run whf serve` from ../service
npm test             # vitest: main-process logic (node) and renderer components (jsdom)
npm run lint; npm run typecheck
npm run build        # electron-vite build into out/
```

Set `WHF_SERVICE_COMMAND` to override how the service is started, as a JSON array, for example
`$env:WHF_SERVICE_COMMAND = '["C:\\path\\whf.exe","serve"]'`.

## Layout

- `src/main`: Electron main process (service supervisor, IPC, tray, notifications, settings)
- `src/preload`: the `window.whf` bridge
- `src/renderer`: React app (pages, components, typed API client)
- `src/shared`: types shared by all three
```

In `CLAUDE.md` under "Toolchain", replace the Node line with: "- Node: Node 22, `npm`, `electron-vite` (Vite 7), `vitest`, `eslint` 10, `tsc`; `electron-builder` in plan 4." and the commands line with: "- Commands: `uv run pytest` in `service/`; `npm test`, `npm run lint`, `npm run typecheck` in `app/`."

```bash
git add .gitignore CLAUDE.md app/package.json app/package-lock.json app/electron.vite.config.ts app/tsconfig.json app/tsconfig.node.json app/tsconfig.web.json app/eslint.config.js app/vitest.config.ts app/scripts/make-icon.mjs app/resources/icon.png app/src app/README.md
git commit -m "feat(app): scaffold the Electron desktop app with shared API types"
```

---

### Task 5: Main process building blocks: service launcher, API client, settings store

**Files:**
- Create: `app/src/main/service-launcher.ts`, `app/src/main/api-client.ts`, `app/src/main/settings-store.ts`, `app/src/main/__tests__/service-launcher.test.ts`, `app/src/main/__tests__/api-client.test.ts`, `app/src/main/__tests__/settings-store.test.ts`

**Interfaces:**
- Consumes: `ApiRequest`, `ApiResponse`, `Settings`, `DEFAULT_SETTINGS` from `src/shared/ipc.ts`.
- Produces:
  - `serviceCommand(opts: { isPackaged: boolean; resourcesPath: string; appPath: string; env: NodeJS.ProcessEnv; platform: NodeJS.Platform }): { command: string; args: string[]; cwd: string }`
  - `parseHandshake(line: string): { port: number; token: string } | null`
  - `waitForHealth(port: number, opts: { fetchFn: typeof fetch; timeoutMs: number; intervalMs: number; sleep: (ms: number) => Promise<void> }): Promise<void>` (rejects with `Error('service did not become healthy within <ms> ms')`)
  - `class ServiceProcess { start(): Promise<{ port: number; token: string }>; stop(): void; onExit(listener: (code: number | null) => void): void }` constructed with `{ spawnFn, fetchFn, command, args, cwd, env, log }`
  - `class ApiClient { constructor(baseUrl: string, token: string, fetchFn?: typeof fetch); request(req: ApiRequest): Promise<ApiResponse> }` sending header `X-WHF-Token`, JSON body, and returning `{ok:false, status, error: detail}` on non-2xx, `{ok:false, status: 0, error: message}` on network errors.
  - `class SettingsStore { constructor(filePath: string); get(): Settings; set(patch: Partial<Settings>): Settings }` (missing or invalid file yields defaults; write is atomic: temp file then rename).

- [ ] **Step 1: Write the failing launcher tests**

`app/src/main/__tests__/service-launcher.test.ts`:

```ts
import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import { describe, expect, it, vi } from 'vitest'
import { ServiceProcess, parseHandshake, serviceCommand, waitForHealth } from '../service-launcher'

describe('serviceCommand', () => {
  const base = { resourcesPath: '/res', appPath: '/app', platform: 'win32' as const }
  it('uses uv from the sibling service directory in development', () => {
    const cmd = serviceCommand({ ...base, isPackaged: false, env: {} })
    expect(cmd).toEqual({ command: 'uv', args: ['run', 'whf', 'serve'], cwd: '/app/../service' })
  })
  it('uses the frozen executable when packaged', () => {
    const cmd = serviceCommand({ ...base, isPackaged: true, env: {} })
    expect(cmd.command.replace(/\\/g, '/')).toBe('/res/service/whf/whf.exe')
    expect(cmd.args).toEqual(['serve'])
  })
  it('uses whf without .exe on other platforms when packaged', () => {
    const cmd = serviceCommand({ ...base, platform: 'linux', isPackaged: true, env: {} })
    expect(cmd.command.replace(/\\/g, '/')).toBe('/res/service/whf/whf')
  })
  it('honours WHF_SERVICE_COMMAND as a JSON array', () => {
    const cmd = serviceCommand({ ...base, isPackaged: true, env: { WHF_SERVICE_COMMAND: '["C:\\\\x\\\\whf.exe","serve","--port","0"]' } })
    expect(cmd).toEqual({ command: 'C:\\x\\whf.exe', args: ['serve', '--port', '0'], cwd: '/app' })
  })
})

describe('parseHandshake', () => {
  it('reads the port and token from the first JSON line', () => {
    expect(parseHandshake('{"port": 51234, "token": "abc"}')).toEqual({ port: 51234, token: 'abc' })
  })
  it('ignores other lines', () => {
    expect(parseHandshake('INFO: started')).toBeNull()
    expect(parseHandshake('{"port": "x"}')).toBeNull()
  })
})

describe('waitForHealth', () => {
  it('resolves once /health answers ok', async () => {
    const answers = [Promise.reject(new Error('refused')), Promise.resolve(new Response('{"status":"ok"}', { status: 200 }))]
    const fetchFn = vi.fn(() => answers.shift()!) as unknown as typeof fetch
    await waitForHealth(5000, { fetchFn, timeoutMs: 1000, intervalMs: 1, sleep: async () => {} })
    expect(fetchFn).toHaveBeenCalledTimes(2)
    expect(fetchFn).toHaveBeenCalledWith('http://127.0.0.1:5000/health')
  })
  it('rejects after the timeout', async () => {
    const fetchFn = vi.fn(() => Promise.reject(new Error('refused'))) as unknown as typeof fetch
    let now = 0
    await expect(
      waitForHealth(5000, { fetchFn, timeoutMs: 10, intervalMs: 5, sleep: async (ms) => { now += ms }, now: () => now }),
    ).rejects.toThrow('did not become healthy')
  })
})

function fakeChild(): { child: EventEmitter & { stdout: PassThrough; stderr: PassThrough; kill: ReturnType<typeof vi.fn>; pid: number }; } {
  const child = Object.assign(new EventEmitter(), { stdout: new PassThrough(), stderr: new PassThrough(), kill: vi.fn(), pid: 42 })
  return { child }
}

describe('ServiceProcess', () => {
  it('spawns, reads the handshake, waits for health and reports exit', async () => {
    const { child } = fakeChild()
    const spawnFn = vi.fn(() => child)
    const fetchFn = vi.fn(() => Promise.resolve(new Response('{"status":"ok"}', { status: 200 }))) as unknown as typeof fetch
    const proc = new ServiceProcess({ spawnFn: spawnFn as never, fetchFn, command: 'whf', args: ['serve'], cwd: '/x', env: {}, log: () => {}, sleep: async () => {} })
    const exit = vi.fn()
    proc.onExit(exit)
    const started = proc.start()
    child.stdout.write('{"port": 6001, "token": "tok"}\n')
    await expect(started).resolves.toEqual({ port: 6001, token: 'tok' })
    expect(spawnFn).toHaveBeenCalledWith('whf', ['serve'], expect.objectContaining({ cwd: '/x', windowsHide: true }))
    child.emit('exit', 0)
    expect(exit).toHaveBeenCalledWith(0)
    proc.stop()
    expect(child.kill).toHaveBeenCalled()
  })
  it('rejects when the process exits before the handshake', async () => {
    const { child } = fakeChild()
    const proc = new ServiceProcess({ spawnFn: (() => child) as never, fetchFn: fetch, command: 'whf', args: [], cwd: '/x', env: {}, log: () => {}, sleep: async () => {} })
    const started = proc.start()
    child.stderr.write('Traceback: boom\n')
    child.emit('exit', 1)
    await expect(started).rejects.toThrow(/exited with code 1.*boom/s)
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd app && npx vitest run src/main/__tests__/service-launcher.test.ts`
Expected: FAIL, module not found

- [ ] **Step 3: Implement `service-launcher.ts`**

```ts
import type { ChildProcess, SpawnOptions } from 'node:child_process'
import { join } from 'node:path'
import { createInterface } from 'node:readline'

export interface ServiceCommand { command: string; args: string[]; cwd: string }

export function serviceCommand(opts: {
  isPackaged: boolean; resourcesPath: string; appPath: string; env: NodeJS.ProcessEnv; platform: NodeJS.Platform
}): ServiceCommand {
  const override = opts.env['WHF_SERVICE_COMMAND']
  if (override) {
    const parsed: unknown = JSON.parse(override)
    if (!Array.isArray(parsed) || parsed.length === 0 || !parsed.every((p) => typeof p === 'string')) {
      throw new Error('WHF_SERVICE_COMMAND must be a JSON array of strings')
    }
    const [command, ...args] = parsed as string[]
    return { command: command!, args, cwd: opts.appPath }
  }
  if (opts.isPackaged) {
    const exe = opts.platform === 'win32' ? 'whf.exe' : 'whf'
    return { command: join(opts.resourcesPath, 'service', 'whf', exe), args: ['serve'], cwd: opts.resourcesPath }
  }
  return { command: 'uv', args: ['run', 'whf', 'serve'], cwd: join(opts.appPath, '..', 'service') }
}

export function parseHandshake(line: string): { port: number; token: string } | null {
  const trimmed = line.trim()
  if (!trimmed.startsWith('{')) return null
  try {
    const obj: unknown = JSON.parse(trimmed)
    if (typeof obj === 'object' && obj !== null && 'port' in obj && 'token' in obj) {
      const { port, token } = obj as { port: unknown; token: unknown }
      if (typeof port === 'number' && Number.isInteger(port) && typeof token === 'string' && token) return { port, token }
    }
  } catch { /* not JSON */ }
  return null
}

const defaultSleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms))

export async function waitForHealth(
  port: number,
  opts: { fetchFn: typeof fetch; timeoutMs: number; intervalMs: number; sleep?: (ms: number) => Promise<void>; now?: () => number },
): Promise<void> {
  const sleep = opts.sleep ?? defaultSleep
  const now = opts.now ?? Date.now
  const start = now()
  for (;;) {
    try {
      const res = await opts.fetchFn(`http://127.0.0.1:${port}/health`)
      if (res.ok) {
        const body = (await res.json()) as { status?: string }
        if (body.status === 'ok') return
      }
    } catch { /* not up yet */ }
    if (now() - start >= opts.timeoutMs) throw new Error(`service did not become healthy within ${opts.timeoutMs} ms`)
    await sleep(opts.intervalMs)
  }
}

type SpawnFn = (command: string, args: string[], options: SpawnOptions) => ChildProcess

export class ServiceProcess {
  private child: ChildProcess | null = null
  private exitListeners: ((code: number | null) => void)[] = []
  private stderrTail: string[] = []

  constructor(
    private readonly opts: {
      spawnFn: SpawnFn; fetchFn: typeof fetch; command: string; args: string[]; cwd: string
      env: NodeJS.ProcessEnv; log: (line: string) => void; sleep?: (ms: number) => Promise<void>; healthTimeoutMs?: number
    },
  ) {}

  onExit(listener: (code: number | null) => void): void { this.exitListeners.push(listener) }

  start(): Promise<{ port: number; token: string }> {
    const child = this.opts.spawnFn(this.opts.command, this.opts.args, {
      cwd: this.opts.cwd, env: { ...process.env, ...this.opts.env }, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
    })
    this.child = child
    return new Promise((resolve, reject) => {
      let settled = false
      const stderr = createInterface({ input: child.stderr! })
      stderr.on('line', (line) => { this.opts.log(`[service] ${line}`); this.stderrTail = [...this.stderrTail.slice(-19), line] })
      const stdout = createInterface({ input: child.stdout! })
      stdout.on('line', (line) => {
        const hs = parseHandshake(line)
        if (hs && !settled) {
          settled = true
          waitForHealth(hs.port, { fetchFn: this.opts.fetchFn, timeoutMs: this.opts.healthTimeoutMs ?? 60_000, intervalMs: 250, sleep: this.opts.sleep })
            .then(() => resolve(hs), reject)
        } else this.opts.log(`[service] ${line}`)
      })
      child.on('error', (err) => { if (!settled) { settled = true; reject(err) } })
      child.on('exit', (code) => {
        if (!settled) { settled = true; reject(new Error(`service exited with code ${code} before it was ready\n${this.stderrTail.join('\n')}`)) }
        for (const l of this.exitListeners) l(code)
      })
    })
  }

  stop(): void {
    if (this.child && this.child.exitCode === null) this.child.kill()
    this.child = null
  }
}
```

- [ ] **Step 4: Run the launcher tests**

Run: `cd app && npx vitest run src/main/__tests__/service-launcher.test.ts`
Expected: PASS (8 tests)

- [ ] **Step 5: Write the failing API client and settings tests**

`app/src/main/__tests__/api-client.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../api-client'

describe('ApiClient', () => {
  it('sends the token header and JSON body, returns parsed data', async () => {
    const fetchFn = vi.fn(async () => new Response('{"id": 3}', { status: 200, headers: { 'content-type': 'application/json' } }))
    const client = new ApiClient('http://127.0.0.1:6001', 'tok', fetchFn as unknown as typeof fetch)
    const res = await client.request({ method: 'POST', path: '/projects', body: { name: 'x' } })
    expect(res).toEqual({ ok: true, status: 200, data: { id: 3 } })
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://127.0.0.1:6001/projects')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('X-WHF-Token')).toBe('tok')
    expect(init.body).toBe('{"name":"x"}')
  })
  it('maps HTTP errors to the FastAPI detail', async () => {
    const fetchFn = vi.fn(async () => new Response('{"detail": "run 9 not found"}', { status: 404 }))
    const client = new ApiClient('http://127.0.0.1:6001', 'tok', fetchFn as unknown as typeof fetch)
    expect(await client.request({ method: 'GET', path: '/runs/9' })).toEqual({ ok: false, status: 404, error: 'run 9 not found' })
  })
  it('maps validation errors to a readable message', async () => {
    const body = '{"detail": [{"loc": ["body", "deadline"], "msg": "deadline must be after start_date"}]}'
    const fetchFn = vi.fn(async () => new Response(body, { status: 422 }))
    const client = new ApiClient('http://127.0.0.1:6001', 'tok', fetchFn as unknown as typeof fetch)
    expect(await client.request({ method: 'GET', path: '/x' })).toEqual({ ok: false, status: 422, error: 'deadline: deadline must be after start_date' })
  })
  it('maps network failures to status 0', async () => {
    const fetchFn = vi.fn(async () => { throw new Error('ECONNREFUSED') })
    const client = new ApiClient('http://127.0.0.1:6001', 'tok', fetchFn as unknown as typeof fetch)
    expect(await client.request({ method: 'GET', path: '/meta' })).toEqual({ ok: false, status: 0, error: 'ECONNREFUSED' })
  })
})
```

`app/src/main/__tests__/settings-store.test.ts`:

```ts
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { DEFAULT_SETTINGS } from '../../shared/ipc'
import { SettingsStore } from '../settings-store'

describe('SettingsStore', () => {
  it('returns defaults when the file is missing or invalid', () => {
    const dir = mkdtempSync(join(tmpdir(), 'whf-'))
    expect(new SettingsStore(join(dir, 'settings.json')).get()).toEqual(DEFAULT_SETTINGS)
    writeFileSync(join(dir, 'bad.json'), '{not json')
    expect(new SettingsStore(join(dir, 'bad.json')).get()).toEqual(DEFAULT_SETTINGS)
  })
  it('merges patches, persists them and ignores unknown keys', () => {
    const dir = mkdtempSync(join(tmpdir(), 'whf-'))
    const file = join(dir, 'settings.json')
    const store = new SettingsStore(file)
    expect(store.set({ language: 'fr', model: 'gpt-5' })).toEqual({ ...DEFAULT_SETTINGS, language: 'fr', model: 'gpt-5' })
    writeFileSync(file, JSON.stringify({ ...JSON.parse(readFileSync(file, 'utf8')), junk: 1, language: 'de' }))
    expect(new SettingsStore(file).get()).toEqual({ ...DEFAULT_SETTINGS, model: 'gpt-5' })
  })
})
```

- [ ] **Step 6: Run to verify failure, then implement**

Run: `cd app && npx vitest run src/main/__tests__/api-client.test.ts src/main/__tests__/settings-store.test.ts`
Expected: FAIL, modules not found

`app/src/main/api-client.ts`:

```ts
import type { ApiRequest, ApiResponse } from '../shared/ipc'

function describeDetail(detail: unknown): string {
  if (typeof detail === 'string') return detail
  if (Array.isArray(detail)) {
    return detail
      .map((d: unknown) => {
        const item = d as { loc?: unknown[]; msg?: string }
        const field = Array.isArray(item.loc) ? String(item.loc[item.loc.length - 1]) : ''
        return field ? `${field}: ${item.msg ?? ''}` : (item.msg ?? '')
      })
      .join('; ')
  }
  return JSON.stringify(detail)
}

export class ApiClient {
  constructor(private readonly baseUrl: string, private readonly token: string, private readonly fetchFn: typeof fetch = fetch) {}

  async request(req: ApiRequest): Promise<ApiResponse> {
    const headers: Record<string, string> = { 'X-WHF-Token': this.token, Accept: 'application/json' }
    const init: RequestInit = { method: req.method, headers }
    if (req.body !== undefined) { headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(req.body) }
    let res: Response
    try { res = await this.fetchFn(`${this.baseUrl}${req.path}`, init) }
    catch (err) { return { ok: false, status: 0, error: err instanceof Error ? err.message : String(err) } }
    const text = await res.text()
    let data: unknown = null
    if (text) { try { data = JSON.parse(text) } catch { data = text } }
    if (!res.ok) {
      const detail = typeof data === 'object' && data !== null && 'detail' in data ? (data as { detail: unknown }).detail : data
      return { ok: false, status: res.status, error: describeDetail(detail) || `HTTP ${res.status}` }
    }
    return { ok: true, status: res.status, data }
  }
}
```

`app/src/main/settings-store.ts`:

```ts
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { DEFAULT_SETTINGS, type Settings } from '../shared/ipc'

const LANGUAGES = new Set(['en', 'fr'])

function sanitize(raw: unknown): Settings {
  const out: Settings = { ...DEFAULT_SETTINGS }
  if (typeof raw !== 'object' || raw === null) return out
  const r = raw as Record<string, unknown>
  if (typeof r['language'] === 'string' && LANGUAGES.has(r['language'])) out.language = r['language'] as Settings['language']
  if (typeof r['model'] === 'string' || r['model'] === null) out.model = r['model'] as string | null
  if (typeof r['launchAtLogin'] === 'boolean') out.launchAtLogin = r['launchAtLogin']
  if (typeof r['closeToTray'] === 'boolean') out.closeToTray = r['closeToTray']
  return out
}

export class SettingsStore {
  constructor(private readonly filePath: string) {}

  get(): Settings {
    try { return sanitize(JSON.parse(readFileSync(this.filePath, 'utf8'))) }
    catch { return { ...DEFAULT_SETTINGS } }
  }

  set(patch: Partial<Settings>): Settings {
    const next = sanitize({ ...this.get(), ...patch })
    mkdirSync(dirname(this.filePath), { recursive: true })
    const tmp = `${this.filePath}.tmp`
    writeFileSync(tmp, JSON.stringify(next, null, 2))
    renameSync(tmp, this.filePath)
    return next
  }
}
```

- [ ] **Step 7: Run all gates, commit**

Run: `cd app && npm test && npm run lint && npm run typecheck`
Expected: PASS

```bash
git add app/src/main/service-launcher.ts app/src/main/api-client.ts app/src/main/settings-store.ts app/src/main/__tests__
git commit -m "feat(app): service launcher, token-holding API client and settings store"
```

---

### Task 6: IPC bridge, preload and the real main process

**Files:**
- Create: `app/src/main/ipc.ts`, `app/src/main/copilot-login.ts`, `app/src/main/__tests__/ipc.test.ts`, `app/src/main/__tests__/copilot-login.test.ts`
- Modify: `app/src/main/index.ts`, `app/src/preload/index.ts`

**Interfaces:**
- Consumes: `ApiClient`, `SettingsStore`, `ServiceProcess`, `serviceCommand` (Task 5); `IPC`, `AppState`, `WhfBridge` (Task 4).
- Produces: `registerIpc(deps: IpcDeps): void` where `IpcDeps = { ipcMain: Pick<IpcMain, 'handle'>; getClient: () => ApiClient | null; settings: SettingsStore; getState: () => AppState; login: () => Promise<{ started: boolean; message: string }>; openExternal: (url: string) => Promise<void>; applyLaunchAtLogin: (on: boolean) => void }`; `copilotLoginCommand(cliPath: string, platform: NodeJS.Platform): { command: string; args: string[] }`; `startCopilotLogin(deps)`; `window.whf` implementing `WhfBridge`; main process `index.ts` with `AppController` (start service, publish state, create window, quit handling).

- [ ] **Step 1: Write the failing IPC tests**

`app/src/main/__tests__/ipc.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { DEFAULT_SETTINGS, IPC } from '../../shared/ipc'
import { registerIpc } from '../ipc'

function harness(clientPresent = true) {
  const handlers = new Map<string, (event: unknown, ...args: unknown[]) => unknown>()
  const ipcMain = { handle: vi.fn((channel: string, fn: (event: unknown, ...args: unknown[]) => unknown) => handlers.set(channel, fn)) }
  const request = vi.fn(async () => ({ ok: true, status: 200, data: { x: 1 } }))
  const settings = { get: vi.fn(() => ({ ...DEFAULT_SETTINGS })), set: vi.fn((p: object) => ({ ...DEFAULT_SETTINGS, ...p })) }
  const applyLaunchAtLogin = vi.fn()
  registerIpc({
    ipcMain, getClient: () => (clientPresent ? ({ request } as never) : null), settings: settings as never,
    getState: () => ({ service: 'ready', serviceMessage: '', version: '0.1.0', platform: 'win32' }),
    login: async () => ({ started: true, message: 'opened' }), openExternal: async () => {}, applyLaunchAtLogin,
  })
  return { handlers, request, settings, applyLaunchAtLogin }
}

describe('registerIpc', () => {
  it('forwards api:request to the client', async () => {
    const { handlers, request } = harness()
    const res = await handlers.get(IPC.apiRequest)!({}, { method: 'GET', path: '/meta' })
    expect(res).toEqual({ ok: true, status: 200, data: { x: 1 } })
    expect(request).toHaveBeenCalledWith({ method: 'GET', path: '/meta' })
  })
  it('answers with status 0 while the service is not ready', async () => {
    const { handlers } = harness(false)
    expect(await handlers.get(IPC.apiRequest)!({}, { method: 'GET', path: '/meta' })).toEqual({ ok: false, status: 0, error: 'service not ready' })
  })
  it('rejects malformed requests without calling the client', async () => {
    const { handlers, request } = harness()
    expect(await handlers.get(IPC.apiRequest)!({}, { method: 'TRACE', path: 'meta' })).toEqual({ ok: false, status: 0, error: 'invalid request' })
    expect(request).not.toHaveBeenCalled()
  })
  it('reads and patches settings, applying launch-at-login', async () => {
    const { handlers, settings, applyLaunchAtLogin } = harness()
    expect(await handlers.get(IPC.settingsGet)!({})).toEqual(DEFAULT_SETTINGS)
    await handlers.get(IPC.settingsSet)!({}, { launchAtLogin: true })
    expect(settings.set).toHaveBeenCalledWith({ launchAtLogin: true })
    expect(applyLaunchAtLogin).toHaveBeenCalledWith(true)
  })
  it('exposes state and login', async () => {
    const { handlers } = harness()
    expect(await handlers.get(IPC.appState)!({})).toMatchObject({ service: 'ready' })
    expect(await handlers.get(IPC.copilotLogin)!({})).toEqual({ started: true, message: 'opened' })
  })
  it('only opens http(s) urls', async () => {
    const { handlers } = harness()
    await expect(handlers.get(IPC.openExternal)!({}, 'file:///etc/passwd')).rejects.toThrow('http')
  })
})
```

`app/src/main/__tests__/copilot-login.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { copilotLoginCommand, startCopilotLogin } from '../copilot-login'

describe('copilot login', () => {
  it('opens a PowerShell window that stays open on Windows', () => {
    expect(copilotLoginCommand('C:\\cli\\copilot.exe', 'win32')).toEqual({
      command: 'powershell.exe', args: ['-NoExit', '-NoProfile', '-Command', "& 'C:\\cli\\copilot.exe' login"],
    })
  })
  it('runs the CLI directly elsewhere', () => {
    expect(copilotLoginCommand('/usr/bin/copilot', 'linux')).toEqual({ command: '/usr/bin/copilot', args: ['login'] })
  })
  it('asks the service where the CLI is and reports when it is missing', async () => {
    const spawnFn = vi.fn(() => ({ unref: vi.fn(), on: vi.fn() }))
    const missing = await startCopilotLogin({ status: async () => ({ cli_path: null, message: 'no cli' }), spawnFn: spawnFn as never, platform: 'win32' })
    expect(missing).toEqual({ started: false, message: 'no cli' })
    const started = await startCopilotLogin({ status: async () => ({ cli_path: 'C:\\c.exe', message: '' }), spawnFn: spawnFn as never, platform: 'win32' })
    expect(started.started).toBe(true)
    expect(spawnFn).toHaveBeenCalledWith('powershell.exe', expect.arrayContaining(['-NoExit']), expect.objectContaining({ detached: true }))
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd app && npx vitest run src/main/__tests__/ipc.test.ts src/main/__tests__/copilot-login.test.ts`
Expected: FAIL, modules not found

- [ ] **Step 3: Implement `ipc.ts` and `copilot-login.ts`**

`app/src/main/ipc.ts`:

```ts
import type { IpcMain } from 'electron'
import { IPC, type ApiRequest, type ApiResponse, type AppState, type Settings } from '../shared/ipc'
import type { ApiClient } from './api-client'
import type { SettingsStore } from './settings-store'

export interface IpcDeps {
  ipcMain: Pick<IpcMain, 'handle'>
  getClient: () => ApiClient | null
  settings: SettingsStore
  getState: () => AppState
  login: () => Promise<{ started: boolean; message: string }>
  openExternal: (url: string) => Promise<void>
  applyLaunchAtLogin: (on: boolean) => void
}

const METHODS = new Set(['GET', 'POST', 'PUT', 'DELETE'])

function isApiRequest(value: unknown): value is ApiRequest {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return typeof v['method'] === 'string' && METHODS.has(v['method']) && typeof v['path'] === 'string' && v['path'].startsWith('/')
}

export function registerIpc(deps: IpcDeps): void {
  deps.ipcMain.handle(IPC.apiRequest, async (_e, raw: unknown): Promise<ApiResponse> => {
    if (!isApiRequest(raw)) return { ok: false, status: 0, error: 'invalid request' }
    const client = deps.getClient()
    if (!client) return { ok: false, status: 0, error: 'service not ready' }
    return client.request(raw)
  })
  deps.ipcMain.handle(IPC.settingsGet, () => deps.settings.get())
  deps.ipcMain.handle(IPC.settingsSet, (_e, patch: Partial<Settings>) => {
    const next = deps.settings.set(patch)
    if ('launchAtLogin' in patch) deps.applyLaunchAtLogin(next.launchAtLogin)
    return next
  })
  deps.ipcMain.handle(IPC.appState, () => deps.getState())
  deps.ipcMain.handle(IPC.copilotLogin, () => deps.login())
  deps.ipcMain.handle(IPC.openExternal, async (_e, url: unknown) => {
    if (typeof url !== 'string' || !/^https?:\/\//.test(url)) throw new Error('only http(s) urls can be opened')
    await deps.openExternal(url)
  })
}
```

`app/src/main/copilot-login.ts`:

```ts
import type { ChildProcess, SpawnOptions } from 'node:child_process'

export function copilotLoginCommand(cliPath: string, platform: NodeJS.Platform): { command: string; args: string[] } {
  if (platform === 'win32') {
    const quoted = cliPath.replace(/'/g, "''")
    return { command: 'powershell.exe', args: ['-NoExit', '-NoProfile', '-Command', `& '${quoted}' login`] }
  }
  return { command: cliPath, args: ['login'] }
}

export async function startCopilotLogin(deps: {
  status: () => Promise<{ cli_path: string | null; message: string }>
  spawnFn: (command: string, args: string[], options: SpawnOptions) => ChildProcess
  platform: NodeJS.Platform
}): Promise<{ started: boolean; message: string }> {
  const status = await deps.status()
  if (!status.cli_path) return { started: false, message: status.message || 'Copilot CLI not found' }
  const { command, args } = copilotLoginCommand(status.cli_path, deps.platform)
  const child = deps.spawnFn(command, args, { detached: true, stdio: 'ignore', windowsHide: false })
  child.on('error', () => {})
  child.unref()
  return { started: true, message: 'A terminal window opened with the GitHub device-login flow. Return here when it says you are signed in.' }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd app && npx vitest run src/main`
Expected: PASS

- [ ] **Step 5: The preload bridge**

`app/src/preload/index.ts`:

```ts
import { contextBridge, ipcRenderer } from 'electron'
import { IPC, type ApiRequest, type AppState, type Settings, type WhfBridge } from '../shared/ipc'

const bridge: WhfBridge = {
  request: (req: ApiRequest) => ipcRenderer.invoke(IPC.apiRequest, req),
  getSettings: () => ipcRenderer.invoke(IPC.settingsGet),
  setSettings: (patch: Partial<Settings>) => ipcRenderer.invoke(IPC.settingsSet, patch),
  copilotLogin: () => ipcRenderer.invoke(IPC.copilotLogin),
  getState: () => ipcRenderer.invoke(IPC.appState),
  onStateChanged: (listener: (state: AppState) => void) => {
    const handler = (_e: unknown, state: AppState): void => listener(state)
    ipcRenderer.on(IPC.appStateChanged, handler)
    return () => ipcRenderer.removeListener(IPC.appStateChanged, handler)
  },
  openExternal: (url: string) => ipcRenderer.invoke(IPC.openExternal, url),
}

contextBridge.exposeInMainWorld('whf', bridge)
```

- [ ] **Step 6: The real main process**

Replace `app/src/main/index.ts`:

```ts
import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { app, BrowserWindow, ipcMain, shell } from 'electron'
import { IPC, type AppState } from '../shared/ipc'
import { ApiClient } from './api-client'
import { startCopilotLogin } from './copilot-login'
import { registerIpc } from './ipc'
import { ServiceProcess, serviceCommand } from './service-launcher'
import { SettingsStore } from './settings-store'
import type { CopilotStatus } from '../shared/types'

export class AppController {
  private client: ApiClient | null = null
  private service: ServiceProcess | null = null
  private window: BrowserWindow | null = null
  private state: AppState = { service: 'starting', serviceMessage: 'Starting the forecast service…', version: app.getVersion(), platform: process.platform }
  readonly settings = new SettingsStore(join(app.getPath('userData'), 'settings.json'))
  quitting = false

  getClient(): ApiClient | null { return this.client }
  getState(): AppState { return this.state }

  setState(patch: Partial<AppState>): void {
    this.state = { ...this.state, ...patch }
    for (const win of BrowserWindow.getAllWindows()) win.webContents.send(IPC.appStateChanged, this.state)
  }

  async startService(): Promise<void> {
    const cmd = serviceCommand({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath, appPath: app.getAppPath(), env: process.env, platform: process.platform })
    this.service = new ServiceProcess({ spawnFn: spawn, fetchFn: fetch, ...cmd, env: {}, log: (l) => console.log(l) })
    this.service.onExit((code) => {
      this.client = null
      if (!this.quitting) this.setState({ service: 'failed', serviceMessage: `The forecast service stopped (exit code ${code}). Restart the application.` })
    })
    try {
      const { port, token } = await this.service.start()
      this.client = new ApiClient(`http://127.0.0.1:${port}`, token)
      this.setState({ service: 'ready', serviceMessage: '' })
    } catch (err) {
      this.setState({ service: 'failed', serviceMessage: err instanceof Error ? err.message : String(err) })
    }
  }

  async copilotStatus(): Promise<{ cli_path: string | null; message: string }> {
    const res = await this.client?.request({ method: 'GET', path: '/copilot/status' })
    if (!res || !res.ok) return { cli_path: null, message: res && !res.ok ? res.error : 'service not ready' }
    const status = res.data as CopilotStatus
    return { cli_path: status.cli_path, message: status.message }
  }

  createWindow(): BrowserWindow {
    const win = new BrowserWindow({
      width: 1280, height: 820, minWidth: 960, minHeight: 600, show: false, icon: join(__dirname, '../../resources/icon.png'),
      webPreferences: { preload: join(__dirname, '../preload/index.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
    })
    win.once('ready-to-show', () => win.show())
    win.on('close', (e) => {
      if (!this.quitting && this.settings.get().closeToTray) { e.preventDefault(); win.hide() }
    })
    win.on('closed', () => { this.window = null })
    win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:\/\//.test(url)) void shell.openExternal(url); return { action: 'deny' } })
    if (process.env['ELECTRON_RENDERER_URL']) void win.loadURL(process.env['ELECTRON_RENDERER_URL'])
    else void win.loadFile(join(__dirname, '../renderer/index.html'))
    this.window = win
    return win
  }

  showWindow(): void {
    if (this.window) { this.window.show(); this.window.focus() } else this.createWindow()
  }

  applyLaunchAtLogin(on: boolean): void {
    if (process.platform === 'win32') app.setLoginItemSettings({ openAtLogin: on, args: ['--hidden'] })
  }

  shutdown(): void {
    this.quitting = true
    this.service?.stop()
  }
}

export const controller = new AppController()

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.setAppUserModelId('com.workloadhub.forecast')
  app.on('second-instance', () => controller.showWindow())
  registerIpc({
    ipcMain, getClient: () => controller.getClient(), settings: controller.settings, getState: () => controller.getState(),
    login: () => startCopilotLogin({ status: () => controller.copilotStatus(), spawnFn: spawn, platform: process.platform }),
    openExternal: (url) => shell.openExternal(url), applyLaunchAtLogin: (on) => controller.applyLaunchAtLogin(on),
  })
  void app.whenReady().then(async () => {
    if (!process.argv.includes('--hidden')) controller.createWindow()
    await controller.startService()
  })
  app.on('activate', () => controller.showWindow())
  app.on('before-quit', () => controller.shutdown())
  app.on('window-all-closed', () => { /* keep running in the tray */ })
}
```

- [ ] **Step 7: Gates and a manual smoke run, then commit**

Run: `cd app && npm test && npm run lint && npm run typecheck && npm run build`
Expected: PASS. If a display is available (Windows dev machine), `npm run dev` must open the window and the console must show the service handshake; on headless Linux, skip the manual run and say so in the report.

```bash
git add app/src/main app/src/preload
git commit -m "feat(app): IPC bridge, preload and service-supervising main process"
```

---

### Task 7: Due check, notifications and tray

**Files:**
- Create: `app/src/main/due-check.ts`, `app/src/main/notifications.ts`, `app/src/main/tray.ts`, `app/src/main/__tests__/due-check.test.ts`, `app/src/main/__tests__/notifications.test.ts`
- Modify: `app/src/main/index.ts`

**Interfaces:**
- Consumes: `ApiClient.request`, `Meta`, `Profile`, `TeamDue`, `RunCreated`/`ForecastRow` (Task 4 types).
- Produces: `teamsToCheck(meta: Meta, profile: Profile): Team[]` (team leader: own team; skill team leader: all teams of the department; nobody: `[]`), `overloadedMembers(forecasts: ForecastRow[], members: Member[]): { name: string; hours: number }[]` (sum of `overload_hours` per member > 0, sorted descending), `class DueChecker { constructor(deps: { request: ApiClient['request']; notify: (title: string, body: string) => void; intervalMs?: number }); checkNow(): Promise<{ due: Team[] }>; start(): void; stop(): void }`, `notifyDue(notify, teams)`, `notifyOverload(notify, teamName, members)`, `createTray(deps: { showWindow; checkNow; quit; iconPath }): Tray`.

- [ ] **Step 1: Write the failing tests**

`app/src/main/__tests__/due-check.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import type { Meta, Profile } from '../../shared/types'
import { DueChecker, overloadedMembers, teamsToCheck } from '../due-check'

const meta: Meta = {
  departments: [{ id: 1, name: 'D1', skill_team_leader_id: 10 }, { id: 2, name: 'D2', skill_team_leader_id: 20 }],
  teams: [{ id: 1, department_id: 1, name: 'T1', team_leader_id: 11 }, { id: 2, department_id: 1, name: 'T2', team_leader_id: 12 }, { id: 3, department_id: 2, name: 'T3', team_leader_id: 21 }],
  members: [
    { id: 10, name: 'Sara', team_id: null, department_id: 1, role: 'skill_team_leader', counted_in_workload: 0 },
    { id: 11, name: 'Ali', team_id: 1, department_id: 1, role: 'team_leader', counted_in_workload: 1 },
    { id: 12, name: 'Nour', team_id: 2, department_id: 1, role: 'team_leader', counted_in_workload: 1 },
  ],
  capacity_default: 40,
}

describe('teamsToCheck', () => {
  it('is the own team for a team leader', () => {
    expect(teamsToCheck(meta, { member_id: 11, role: 'team_leader' }).map((t) => t.id)).toEqual([1])
  })
  it('is every team of the department for a skill team leader', () => {
    expect(teamsToCheck(meta, { member_id: 10, role: 'skill_team_leader' }).map((t) => t.id)).toEqual([1, 2])
  })
  it('is empty without a profile', () => {
    expect(teamsToCheck(meta, { member_id: null, role: null })).toEqual([])
  })
})

describe('overloadedMembers', () => {
  it('sums overload over the weeks and keeps only positive totals, largest first', () => {
    const rows = [
      { member_id: 11, week_start: '2026-09-07', overload_hours: 2 }, { member_id: 11, week_start: '2026-09-14', overload_hours: 3.5 },
      { member_id: 12, week_start: '2026-09-07', overload_hours: 0 }, { member_id: 12, week_start: '2026-09-14', overload_hours: 0 },
      { member_id: 13, week_start: '2026-09-07', overload_hours: 8 },
    ].map((r) => ({ run_id: 1, demand_hours: 0, demand_low: 0, demand_high: 0, capacity_hours: 40, open_task_hours: 0, new_task_hours: 0, ...r }))
    const members = [...meta.members, { id: 13, name: 'Yara', team_id: 1, department_id: 1, role: 'member' as const, counted_in_workload: 1 }]
    expect(overloadedMembers(rows, members)).toEqual([{ name: 'Yara', hours: 8 }, { name: 'Ali', hours: 5.5 }])
  })
})

describe('DueChecker', () => {
  it('asks the service per team and notifies once for the due ones', async () => {
    const request = vi.fn(async (req: { path: string }) => {
      if (req.path === '/meta') return { ok: true as const, status: 200, data: meta }
      if (req.path === '/profile') return { ok: true as const, status: 200, data: { member_id: 10, role: 'skill_team_leader' } satisfies Profile }
      if (req.path === '/teams/1/due') return { ok: true as const, status: 200, data: { team_id: 1, due: true, last_run_id: null, last_finished_at: null } }
      if (req.path === '/teams/2/due') return { ok: true as const, status: 200, data: { team_id: 2, due: false, last_run_id: 4, last_finished_at: '2026-09-01T10:00:00' } }
      return { ok: false as const, status: 404, error: 'nope' }
    })
    const notify = vi.fn()
    const checker = new DueChecker({ request: request as never, notify })
    const result = await checker.checkNow()
    expect(result.due.map((t) => t.name)).toEqual(['T1'])
    expect(notify).toHaveBeenCalledTimes(1)
    expect(notify.mock.calls[0]![1]).toContain('T1')
  })
  it('stays silent when the service is not ready', async () => {
    const notify = vi.fn()
    const checker = new DueChecker({ request: (async () => ({ ok: false as const, status: 0, error: 'service not ready' })) as never, notify })
    expect(await checker.checkNow()).toEqual({ due: [] })
    expect(notify).not.toHaveBeenCalled()
  })
})
```

`app/src/main/__tests__/notifications.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { notifyDue, notifyOverload } from '../notifications'

describe('notification texts', () => {
  it('lists due teams', () => {
    const notify = vi.fn()
    notifyDue(notify, [{ id: 1, name: 'T1', department_id: 1, team_leader_id: null }, { id: 2, name: 'T2', department_id: 1, team_leader_id: null }])
    expect(notify).toHaveBeenCalledWith('Forecast due', 'No forecast in the last 14 days for T1, T2. Open WorkloadHub Forecast to run one.')
  })
  it('lists overloaded members with one decimal', () => {
    const notify = vi.fn()
    notifyOverload(notify, 'T1', [{ name: 'Yara', hours: 8 }, { name: 'Ali', hours: 5.55 }])
    expect(notify).toHaveBeenCalledWith('Overload predicted for T1', 'Yara (+8.0 h), Ali (+5.6 h) exceed capacity in the next two weeks.')
  })
  it('does nothing when nobody is overloaded', () => {
    const notify = vi.fn()
    notifyOverload(notify, 'T1', [])
    expect(notify).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd app && npx vitest run src/main/__tests__/due-check.test.ts src/main/__tests__/notifications.test.ts`
Expected: FAIL, modules not found

- [ ] **Step 3: Implement**

`app/src/main/due-check.ts`:

```ts
import type { ApiRequest, ApiResponse } from '../shared/ipc'
import type { ForecastRow, Member, Meta, Profile, Team, TeamDue } from '../shared/types'
import { notifyDue } from './notifications'

export function teamsToCheck(meta: Meta, profile: Profile): Team[] {
  if (profile.member_id === null) return []
  const me = meta.members.find((m) => m.id === profile.member_id)
  if (!me) return []
  if (me.role === 'skill_team_leader') return meta.teams.filter((t) => t.department_id === me.department_id)
  return meta.teams.filter((t) => t.id === me.team_id)
}

export function overloadedMembers(forecasts: ForecastRow[], members: Member[]): { name: string; hours: number }[] {
  const totals = new Map<number, number>()
  for (const row of forecasts) totals.set(row.member_id, (totals.get(row.member_id) ?? 0) + row.overload_hours)
  const nameOf = new Map(members.map((m) => [m.id, m.name]))
  return [...totals.entries()]
    .filter(([, hours]) => hours > 0)
    .sort((a, b) => b[1] - a[1])
    .map(([id, hours]) => ({ name: nameOf.get(id) ?? String(id), hours: Math.round(hours * 100) / 100 }))
}

export const DAY_MS = 24 * 60 * 60 * 1000

export class DueChecker {
  private timer: ReturnType<typeof setInterval> | null = null

  constructor(private readonly deps: { request: (req: ApiRequest) => Promise<ApiResponse>; notify: (title: string, body: string) => void; intervalMs?: number }) {}

  async checkNow(): Promise<{ due: Team[] }> {
    const [meta, profile] = await Promise.all([this.get<Meta>('/meta'), this.get<Profile>('/profile')])
    if (!meta || !profile) return { due: [] }
    const due: Team[] = []
    for (const team of teamsToCheck(meta, profile)) {
      const status = await this.get<TeamDue>(`/teams/${team.id}/due`)
      if (status?.due) due.push(team)
    }
    if (due.length) notifyDue(this.deps.notify, due)
    return { due }
  }

  start(): void {
    this.stop()
    this.timer = setInterval(() => { void this.checkNow() }, this.deps.intervalMs ?? DAY_MS)
  }

  stop(): void { if (this.timer) { clearInterval(this.timer); this.timer = null } }

  private async get<T>(path: string): Promise<T | null> {
    const res = await this.deps.request({ method: 'GET', path })
    return res.ok ? (res.data as T) : null
  }
}
```

`app/src/main/notifications.ts`:

```ts
import { Notification } from 'electron'
import type { Team } from '../shared/types'

export type Notify = (title: string, body: string) => void

export const electronNotify: Notify = (title, body) => {
  if (Notification.isSupported()) new Notification({ title, body }).show()
}

export function notifyDue(notify: Notify, teams: Team[]): void {
  if (!teams.length) return
  notify('Forecast due', `No forecast in the last 14 days for ${teams.map((t) => t.name).join(', ')}. Open WorkloadHub Forecast to run one.`)
}

export function notifyOverload(notify: Notify, teamName: string, members: { name: string; hours: number }[]): void {
  if (!members.length) return
  const list = members.map((m) => `${m.name} (+${m.hours.toFixed(1)} h)`).join(', ')
  notify(`Overload predicted for ${teamName}`, `${list} exceed capacity in the next two weeks.`)
}
```

`app/src/main/tray.ts`:

```ts
import { Menu, Tray, nativeImage } from 'electron'

export function createTray(deps: { showWindow: () => void; checkNow: () => Promise<unknown>; quit: () => void; iconPath: string }): Tray {
  const image = nativeImage.createFromPath(deps.iconPath).resize({ width: 16, height: 16 })
  const tray = new Tray(image)
  tray.setToolTip('WorkloadHub Forecast')
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open', click: () => deps.showWindow() },
    { label: 'Check whether a forecast is due', click: () => { void deps.checkNow() } },
    { type: 'separator' },
    { label: 'Quit', click: () => deps.quit() },
  ]))
  tray.on('click', () => deps.showWindow())
  tray.on('double-click', () => deps.showWindow())
  return tray
}
```

- [ ] **Step 4: Wire into `index.ts`**

In `app/src/main/index.ts`: import `DueChecker`, `overloadedMembers`, `electronNotify`, `notifyOverload`, `createTray`, `Tray`, `IPC`, and types `Meta`, `RunCreated`, `Team`. Add to `AppController`:

```ts
  private tray: Tray | null = null
  readonly dueChecker = new DueChecker({ request: (req) => this.client ? this.client.request(req) : Promise.resolve({ ok: false, status: 0, error: 'service not ready' }), notify: electronNotify })

  createTray(): void {
    if (this.tray) return
    this.tray = createTray({ showWindow: () => this.showWindow(), checkNow: () => this.dueChecker.checkNow(), quit: () => { this.quitting = true; app.quit() }, iconPath: join(__dirname, '../../resources/icon.png') })
  }

  async afterRun(run: RunCreated): Promise<void> {
    const meta = await this.client?.request({ method: 'GET', path: '/meta' })
    if (!meta || !meta.ok) return
    const m = meta.data as Meta
    const team = m.teams.find((t: Team) => t.id === run.team_id)
    notifyOverload(electronNotify, team?.name ?? `team ${run.team_id}`, overloadedMembers(run.forecasts, m.members))
  }
```

In `registerIpc`'s `api:request` path this is the one place the main process looks at a response: after `startService()` succeeds, the controller must call `this.createTray()`, `void this.dueChecker.checkNow()` and `this.dueChecker.start()`. For the post-run notification, add an `onRunCreated` hook to `IpcDeps` (`onRunCreated?: (run: RunCreated) => void`) that `registerIpc` calls when a request was `POST /runs` and the response is ok; the controller passes `(run) => void this.afterRun(run)`. Extend `ipc.test.ts` with one test proving the hook fires for `POST /runs` and not for `GET /runs`:

```ts
  it('reports created runs to the hook', async () => {
    const handlers = new Map<string, (event: unknown, ...args: unknown[]) => unknown>()
    const onRunCreated = vi.fn()
    registerIpc({
      ipcMain: { handle: vi.fn((c: string, fn: (event: unknown, ...args: unknown[]) => unknown) => handlers.set(c, fn)) },
      getClient: () => ({ request: async () => ({ ok: true, status: 200, data: { run_id: 7, team_id: 1, forecasts: [] } }) } as never),
      settings: { get: () => DEFAULT_SETTINGS, set: () => DEFAULT_SETTINGS } as never,
      getState: () => ({ service: 'ready', serviceMessage: '', version: '0', platform: 'win32' }),
      login: async () => ({ started: false, message: '' }), openExternal: async () => {}, applyLaunchAtLogin: () => {}, onRunCreated,
    })
    await handlers.get(IPC.apiRequest)!({}, { method: 'POST', path: '/runs', body: { team_id: 1 } })
    await handlers.get(IPC.apiRequest)!({}, { method: 'GET', path: '/runs' })
    expect(onRunCreated).toHaveBeenCalledTimes(1)
    expect(onRunCreated).toHaveBeenCalledWith(expect.objectContaining({ run_id: 7 }))
  })
```

Also stop the due checker in `shutdown()` and destroy the tray there (`this.tray?.destroy()`).

- [ ] **Step 5: Gates and commit**

Run: `cd app && npm test && npm run lint && npm run typecheck && npm run build`
Expected: PASS

```bash
git add app/src/main
git commit -m "feat(app): daily due check, overload notifications and tray"
```

---

### Task 8: Renderer shell: routing, context, i18n, API wrapper, Settings page

**Files:**
- Create: `app/src/renderer/src/api.ts`, `app/src/renderer/src/context.tsx`, `app/src/renderer/src/i18n.ts`, `app/src/renderer/src/format.ts`, `app/src/renderer/src/components/StatusMessage.tsx`, `app/src/renderer/src/components/Field.tsx`, `app/src/renderer/src/pages/Settings.tsx`, `app/src/renderer/src/test/fake-whf.ts`, `app/src/renderer/src/__tests__/i18n.test.ts`, `app/src/renderer/src/__tests__/format.test.ts`, `app/src/renderer/src/__tests__/Settings.test.tsx`, `app/src/renderer/src/__tests__/app.test.tsx`
- Modify: `app/src/renderer/src/app.tsx`

**Interfaces:**
- Consumes: `window.whf` (`WhfBridge`), every type from `src/shared/types.ts`.
- Produces:
  - `api.ts`: `class ApiError extends Error { status: number }`; `call<T>(method, path, body?) : Promise<T>` (throws `ApiError`); `getMeta()`, `getProfile()`, `setProfile(memberId)`, `getRuns(teamId?)`, `getRun(id)`, `createRun(teamId, asOf?)`, `createNarrative(runId, model)`, `getCopilotStatus()`, `getProjects()`, `createProject(input)`, `updateProject(id, input)`, `getCapacity()`, `setCapacityDefault(hours)`, `setCapacityOverride(o)`, `deleteCapacityOverride(id)`, `getHolidays(year?)`, `getVacations(memberId?)`, `createVacation(v)`, `deleteVacation(id)`, `getTeamDue(teamId)`, `getDepartmentOverview(departmentId)`.
  - `context.tsx`: `AppProvider`, `useApp(): { meta: Meta | null; profile: Profile | null; settings: Settings; state: AppState; me: Member | null; visibleTeams: Team[]; canRun(teamId): boolean; refresh(): Promise<void>; saveSettings(patch): Promise<void>; saveProfile(memberId): Promise<void>; error: string | null }`.
  - `i18n.ts`: `t(key: string, vars?: Record<string, string | number>): string`, `setLanguage(lang)`, `getLanguage()`; keys listed in the `en` dictionary below (later tasks add keys to the same object; `fr` may cover a subset and falls back to `en`).
  - `format.ts`: `hours(n: number | null | undefined): string` ("12.5 h", "–" for null), `weekLabel(iso: string): string` ("Mon 07 Sep"), `pct(n): string`.
  - `test/fake-whf.ts`: `installFakeWhf(routes: Record<string, unknown | ((body: unknown) => unknown)>, options?)` installs `window.whf` where keys are `"GET /meta"` etc.; returns `{ calls: ApiRequest[]; settings: Settings }`.

- [ ] **Step 1: Write the failing tests**

`app/src/renderer/src/test/fake-whf.ts`:

```ts
import { vi } from 'vitest'
import { DEFAULT_SETTINGS, type ApiRequest, type ApiResponse, type AppState, type Settings, type WhfBridge } from '../../../shared/ipc'

type Route = unknown | ((body: unknown, req: ApiRequest) => unknown)

export function installFakeWhf(routes: Record<string, Route>, options: { state?: Partial<AppState>; settings?: Partial<Settings> } = {}) {
  const calls: ApiRequest[] = []
  let settings: Settings = { ...DEFAULT_SETTINGS, ...options.settings }
  const state: AppState = { service: 'ready', serviceMessage: '', version: '0.1.0', platform: 'win32', ...options.state }
  const bridge: WhfBridge = {
    request: vi.fn(async (req: ApiRequest): Promise<ApiResponse> => {
      calls.push(req)
      const key = `${req.method} ${req.path}`
      const exact = routes[key]
      const pattern = Object.keys(routes).find((k) => k.includes('*') && new RegExp('^' + k.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '[^/]+') + '$').test(key))
      const route = exact !== undefined ? exact : pattern ? routes[pattern] : undefined
      if (route === undefined) return { ok: false, status: 404, error: `no fake route for ${key}` }
      const data = typeof route === 'function' ? (route as (b: unknown, r: ApiRequest) => unknown)(req.body, req) : route
      if (data instanceof Error) return { ok: false, status: 400, error: data.message }
      return { ok: true, status: 200, data }
    }),
    getSettings: async () => settings,
    setSettings: async (patch) => { settings = { ...settings, ...patch }; return settings },
    copilotLogin: async () => ({ started: true, message: 'opened' }),
    getState: async () => state,
    onStateChanged: () => () => {},
    openExternal: async () => {},
  }
  Object.defineProperty(window, 'whf', { value: bridge, configurable: true })
  return { calls, get settings() { return settings } }
}

export const META = {
  departments: [{ id: 1, name: 'Platform', skill_team_leader_id: 10 }],
  teams: [{ id: 1, department_id: 1, name: 'Core', team_leader_id: 11 }, { id: 2, department_id: 1, name: 'Data', team_leader_id: 12 }],
  members: [
    { id: 10, name: 'Sara Idrissi', team_id: null, department_id: 1, role: 'skill_team_leader', counted_in_workload: 0 },
    { id: 11, name: 'Ali Benjelloun', team_id: 1, department_id: 1, role: 'team_leader', counted_in_workload: 1 },
    { id: 12, name: 'Nour Alami', team_id: 2, department_id: 1, role: 'team_leader', counted_in_workload: 1 },
    { id: 13, name: 'Yara Tazi', team_id: 1, department_id: 1, role: 'member', counted_in_workload: 1 },
  ],
  capacity_default: 40,
}
```

`app/src/renderer/src/__tests__/i18n.test.ts`:

```ts
import { getLanguage, setLanguage, t } from '../i18n'

describe('i18n', () => {
  it('translates known keys and interpolates', () => {
    setLanguage('en')
    expect(t('nav.dashboard')).toBe('Dashboard')
    expect(t('run.progress.forecasting', { team: 'Core' })).toBe('Forecasting Core…')
  })
  it('falls back to English for missing French keys and to the key itself when unknown', () => {
    setLanguage('fr')
    expect(getLanguage()).toBe('fr')
    expect(t('nav.dashboard')).toBe('Tableau de bord')
    expect(t('run.progress.forecasting', { team: 'Core' })).toBe('Forecasting Core…')
    expect(t('no.such.key')).toBe('no.such.key')
    setLanguage('en')
  })
})
```

`app/src/renderer/src/__tests__/format.test.ts`:

```ts
import { hours, pct, weekLabel } from '../format'

describe('format', () => {
  it('formats hours with one decimal', () => {
    expect(hours(12.34)).toBe('12.3 h'); expect(hours(0)).toBe('0.0 h'); expect(hours(null)).toBe('–')
  })
  it('labels a Monday', () => { expect(weekLabel('2026-09-07')).toBe('Mon 07 Sep') })
  it('formats shares as percentages', () => { expect(pct(0.256)).toBe('26%') })
})
```

`app/src/renderer/src/__tests__/Settings.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Settings } from '../pages/Settings'
import { installFakeWhf, META } from '../test/fake-whf'

function mount() {
  return render(<MemoryRouter><AppProvider><Settings /></AppProvider></MemoryRouter>)
}

describe('Settings', () => {
  it('lets the user pick a profile and shows Copilot status', async () => {
    let profile = { member_id: null as number | null, role: null as string | null }
    const fake = installFakeWhf({
      'GET /meta': META,
      'GET /profile': () => profile,
      'PUT /profile': (body) => { const b = body as { member_id: number }; profile = { member_id: b.member_id, role: 'team_leader' }; return profile },
      'GET /copilot/status': { cli_path: 'C:\\copilot.exe', cli_source: 'path', authenticated: false, login: null, message: 'Not signed in', ready: false },
    })
    mount()
    const select = await screen.findByLabelText('I am')
    await userEvent.selectOptions(select, '11')
    await waitFor(() => expect(fake.calls.some((c) => c.method === 'PUT' && c.path === '/profile')).toBe(true))
    expect(await screen.findByText('Not signed in')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in to GitHub Copilot' }))
    expect(await screen.findByText('opened')).toBeInTheDocument()
  })
  it('saves language, model and launch at login', async () => {
    const fake = installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': { cli_path: null, cli_source: 'none', authenticated: null, login: null, message: 'no cli', ready: false } })
    mount()
    await userEvent.selectOptions(await screen.findByLabelText('Language'), 'fr')
    await waitFor(() => expect(fake.settings.language).toBe('fr'))
    await userEvent.type(screen.getByLabelText(/Mod/), 'gpt-5')
    await userEvent.tab()
    await waitFor(() => expect(fake.settings.model).toBe('gpt-5'))
    await userEvent.click(screen.getByLabelText(/Windows/))
    await waitFor(() => expect(fake.settings.launchAtLogin).toBe(true))
  })
})
```

(`@testing-library/user-event` is one more dev dependency: add `"@testing-library/user-event": "^14.6.1"` to `package.json` and `npm install` it.)

`app/src/renderer/src/__tests__/app.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../app'
import { installFakeWhf, META } from '../test/fake-whf'

describe('App shell', () => {
  it('shows the navigation and the service state banner when the service failed', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: null, role: null } }, { state: { service: 'failed', serviceMessage: 'boom' } })
    render(<MemoryRouter initialEntries={['/']}><App /></MemoryRouter>)
    expect(await screen.findByText('boom')).toBeInTheDocument()
    for (const label of ['Dashboard', 'Run', 'Rebalancing', 'Projects', 'Capacity', 'Time off', 'Runs', 'Settings']) {
      expect(screen.getByRole('link', { name: label })).toBeInTheDocument()
    }
  })
  it('asks for a profile when none is set', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: null, role: null } })
    render(<MemoryRouter initialEntries={['/']}><App /></MemoryRouter>)
    expect(await screen.findByText('Choose who you are in Settings to see your teams.')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd app && npx vitest run --project renderer`
Expected: FAIL, modules not found

- [ ] **Step 3: Implement the shell**

`app/src/renderer/src/api.ts`:

```ts
import type { HttpMethod } from '../../shared/ipc'
import type {
  Capacity, CapacityOverride, CopilotStatus, DepartmentOverview, Holiday, Meta, NarrativeOutcome, Profile, Project,
  ProjectInput, ProjectUpdate, RunCreated, RunDetail, RunSummary, TeamDue, Vacation,
} from '../../shared/types'

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); this.name = 'ApiError' }
}

export async function call<T>(method: HttpMethod, path: string, body?: unknown): Promise<T> {
  const res = await window.whf.request({ method, path, body })
  if (!res.ok) throw new ApiError(res.error, res.status)
  return res.data as T
}

const q = (params: Record<string, string | number | undefined>): string => {
  const parts = Object.entries(params).filter(([, v]) => v !== undefined).map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

export const getMeta = () => call<Meta>('GET', '/meta')
export const getProfile = () => call<Profile>('GET', '/profile')
export const setProfile = (member_id: number | null) => call<Profile>('PUT', '/profile', { member_id })
export const getRuns = (team_id?: number) => call<RunSummary[]>('GET', `/runs${q({ team_id })}`)
export const getRun = (id: number) => call<RunDetail>('GET', `/runs/${id}`)
export const createRun = (team_id: number, as_of?: string, requested_by?: number | null) =>
  call<RunCreated>('POST', '/runs', { team_id, as_of, requested_by })
export const createNarrative = (run_id: number, model: string | null) => call<NarrativeOutcome>('POST', `/runs/${run_id}/narrative`, { model })
export const getCopilotStatus = () => call<CopilotStatus>('GET', '/copilot/status')
export const getProjects = () => call<Project[]>('GET', '/projects')
export const createProject = (input: ProjectInput) => call<{ id: number }>('POST', '/projects', input)
export const updateProject = (id: number, input: ProjectUpdate) => call<Project>('PUT', `/projects/${id}`, input)
export const getCapacity = () => call<Capacity>('GET', '/capacity')
export const setCapacityDefault = (weekly_hours: number) => call<{ default_weekly_hours: number }>('PUT', '/capacity/default', { weekly_hours })
export const setCapacityOverride = (o: Omit<CapacityOverride, 'id'>) => call<CapacityOverride>('PUT', '/capacity/overrides', o)
export const deleteCapacityOverride = (id: number) => call<{ deleted: boolean }>('DELETE', `/capacity/overrides/${id}`)
export const getHolidays = (year?: number) => call<Holiday[]>('GET', `/holidays${q({ year })}`)
export const getVacations = (member_id?: number) => call<Vacation[]>('GET', `/vacations${q({ member_id })}`)
export const createVacation = (v: Omit<Vacation, 'id'>) => call<{ id: number }>('POST', '/vacations', v)
export const deleteVacation = (id: number) => call<{ deleted: boolean }>('DELETE', `/vacations/${id}`)
export const getTeamDue = (team_id: number) => call<TeamDue>('GET', `/teams/${team_id}/due`)
export const getDepartmentOverview = (department_id: number) => call<DepartmentOverview>('GET', `/departments/${department_id}/overview`)
```

`app/src/renderer/src/i18n.ts`:

```ts
import type { Language } from '../../shared/ipc'

const en: Record<string, string> = {
  'app.title': 'WorkloadHub Forecast',
  'nav.dashboard': 'Dashboard', 'nav.run': 'Run', 'nav.rebalancing': 'Rebalancing', 'nav.projects': 'Projects',
  'nav.capacity': 'Capacity', 'nav.timeoff': 'Time off', 'nav.runs': 'Runs', 'nav.settings': 'Settings',
  'profile.none': 'Choose who you are in Settings to see your teams.',
  'service.starting': 'Starting the forecast service…',
  'settings.title': 'Settings', 'settings.profile': 'Profile', 'settings.iam': 'I am', 'settings.nobody': 'Nobody selected',
  'settings.copilot': 'GitHub Copilot', 'settings.signin': 'Sign in to GitHub Copilot', 'settings.recheck': 'Check again',
  'settings.language': 'Language', 'settings.model': 'Model (blank uses your Copilot default)',
  'settings.launch': 'Start with Windows (hidden in the tray)', 'settings.tray': 'Keep running in the tray when the window is closed',
  'settings.ready': 'Signed in as {login}', 'settings.saved': 'Saved',
  'run.title': 'Run a forecast', 'run.team': 'Team', 'run.asof': 'As of', 'run.start': 'Run forecast', 'run.withai': 'Ask Copilot for the narrative',
  'run.progress.forecasting': 'Forecasting {team}…', 'run.progress.narrating': 'Asking Copilot to explain the forecast…',
  'run.done': 'Forecast complete', 'run.open': 'Open the result', 'run.aiFailed': 'Copilot narrative failed: {reason}',
  'run.onBehalf': 'You are running this forecast on behalf of {leader}.',
  'dashboard.title': 'Dashboard', 'dashboard.due': 'Forecast due', 'dashboard.lastRun': 'Last run {date}', 'dashboard.noRun': 'No forecast yet',
  'dashboard.overloaded': 'Overloaded', 'dashboard.demand': 'Demand', 'dashboard.capacity': 'Capacity', 'dashboard.overload': 'Overload',
  'team.title': 'Team result', 'team.member': 'Member', 'team.champion': 'Champion model', 'team.mase': 'Backtest MASE',
  'team.summary': 'AI summary', 'team.warnings': 'Warnings', 'team.risks': 'Team risks', 'team.narrate': 'Ask Copilot',
  'team.narrativeStatus': 'Narrative status: {status}', 'team.unverified': 'Some numbers in this narrative could not be matched to the forecast facts.',
  'team.notes': 'Model notes', 'team.interval': 'Interval', 'team.total': 'Total',
  'member.title': 'Member detail', 'member.history': 'Arrivals in the last 13 weeks', 'member.forecast': 'Forecast', 'member.patterns': 'Patterns',
  'member.open': 'Open tasks', 'member.narrative': 'Narrative', 'member.week': 'Week', 'member.demand': 'Demand', 'member.range': 'Range',
  'member.capacity': 'Capacity', 'member.overload': 'Overload', 'member.openHours': 'From open tasks', 'member.newHours': 'From new tasks',
  'rebalancing.title': 'Rebalancing', 'rebalancing.overloaded': 'Overloaded', 'rebalancing.underloaded': 'Under-loaded', 'rebalancing.moves': 'Suggested moves',
  'rebalancing.none': 'No moves suggested for this run.', 'rebalancing.spare': '{hours} spare', 'rebalancing.over': '{hours} over',
  'rebalancing.adjustments': 'Suggested forecast adjustments (not applied)',
  'projects.title': 'Projects', 'projects.new': 'New project', 'projects.name': 'Name', 'projects.start': 'Start date', 'projects.deadline': 'Deadline',
  'projects.teams': 'Teams', 'projects.type': 'Type', 'projects.status': 'Status', 'projects.save': 'Save', 'projects.edit': 'Edit', 'projects.cancel': 'Cancel',
  'projects.deadlineError': 'The deadline must be after the start date.', 'projects.teamsError': 'Pick at least one team.',
  'capacity.title': 'Capacity', 'capacity.default': 'Default weekly hours', 'capacity.overrides': 'Overrides', 'capacity.member': 'Member',
  'capacity.week': 'Week (blank = permanent)', 'capacity.hours': 'Weekly hours', 'capacity.reason': 'Reason', 'capacity.add': 'Add override',
  'capacity.remove': 'Remove', 'capacity.permanent': 'permanent',
  'timeoff.title': 'Time off', 'timeoff.holidays': 'Public holidays', 'timeoff.vacations': 'Vacations', 'timeoff.year': 'Year',
  'timeoff.member': 'Member', 'timeoff.from': 'From', 'timeoff.to': 'To', 'timeoff.type': 'Type', 'timeoff.add': 'Add vacation', 'timeoff.remove': 'Remove',
  'timeoff.rangeError': 'The end date must not be before the start date.',
  'runs.title': 'Runs', 'runs.id': 'Run', 'runs.team': 'Team', 'runs.asof': 'As of', 'runs.status': 'Status', 'runs.ai': 'AI', 'runs.champion': 'Champion',
  'runs.open': 'Open', 'runs.empty': 'No runs yet.',
  'common.loading': 'Loading…', 'common.error': 'Something went wrong: {message}', 'common.week': 'Week of {date}', 'common.all': 'All teams',
}

const fr: Record<string, string> = {
  'app.title': 'WorkloadHub Forecast',
  'nav.dashboard': 'Tableau de bord', 'nav.run': 'Lancer', 'nav.rebalancing': 'Rééquilibrage', 'nav.projects': 'Projets',
  'nav.capacity': 'Capacité', 'nav.timeoff': 'Absences', 'nav.runs': 'Historique', 'nav.settings': 'Paramètres',
  'profile.none': 'Choisissez qui vous êtes dans Paramètres pour voir vos équipes.',
  'common.loading': 'Chargement…',
}

const dictionaries: Record<Language, Record<string, string>> = { en, fr }
let current: Language = 'en'

export function setLanguage(lang: Language): void { current = lang }
export function getLanguage(): Language { return current }
export function t(key: string, vars: Record<string, string | number> = {}): string {
  const template = dictionaries[current][key] ?? en[key] ?? key
  return template.replace(/\{(\w+)\}/g, (_, name: string) => String(vars[name] ?? `{${name}}`))
}
```

`app/src/renderer/src/format.ts`:

```ts
const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

export function hours(n: number | null | undefined): string {
  return n === null || n === undefined || Number.isNaN(n) ? '–' : `${n.toFixed(1)} h`
}

export function weekLabel(iso: string): string {
  const d = new Date(`${iso.slice(0, 10)}T00:00:00`)
  return `${DAYS[d.getDay()]} ${String(d.getDate()).padStart(2, '0')} ${MONTHS[d.getMonth()]}`
}

export function pct(n: number): string { return `${Math.round(n * 100)}%` }

export function today(): string { return new Date().toISOString().slice(0, 10) }
```

`app/src/renderer/src/components/StatusMessage.tsx`:

```tsx
export function StatusMessage({ kind, children }: { kind: 'info' | 'error' | 'success'; children: React.ReactNode }): React.JSX.Element {
  return <div role={kind === 'error' ? 'alert' : 'status'} className={`status ${kind}`}>{children}</div>
}
```

`app/src/renderer/src/components/Field.tsx`:

```tsx
import { useId } from 'react'

export function Field({ label, children }: { label: string; children: (id: string) => React.ReactNode }): React.JSX.Element {
  const id = useId()
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children(id)}
    </div>
  )
}
```

(Every component file that uses `React.JSX.Element` or `React.ReactNode` adds `import type React from 'react'` at the top.)

`app/src/renderer/src/context.tsx`:

```tsx
import type React from 'react'
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { DEFAULT_SETTINGS, type AppState, type Settings } from '../../shared/ipc'
import type { Member, Meta, Profile, Team } from '../../shared/types'
import { getMeta, getProfile, setProfile } from './api'
import { setLanguage } from './i18n'

export interface AppContextValue {
  meta: Meta | null; profile: Profile | null; settings: Settings; state: AppState; me: Member | null; visibleTeams: Team[]
  error: string | null
  canRun(teamId: number): boolean
  refresh(): Promise<void>
  saveSettings(patch: Partial<Settings>): Promise<void>
  saveProfile(memberId: number | null): Promise<void>
}

const Ctx = createContext<AppContextValue | null>(null)

export function AppProvider({ children }: { children: React.ReactNode }): React.JSX.Element {
  const [meta, setMeta] = useState<Meta | null>(null)
  const [profile, setProfileState] = useState<Profile | null>(null)
  const [settings, setSettings] = useState<Settings>(DEFAULT_SETTINGS)
  const [state, setState] = useState<AppState>({ service: 'starting', serviceMessage: '', version: '', platform: '' })
  const [error, setError] = useState<string | null>(null)
  const [, bump] = useState(0)

  const refresh = useCallback(async () => {
    try {
      const [m, p] = await Promise.all([getMeta(), getProfile()])
      setMeta(m); setProfileState(p); setError(null)
    } catch (err) { setError(err instanceof Error ? err.message : String(err)) }
  }, [])

  useEffect(() => {
    void window.whf.getSettings().then((s) => { setSettings(s); setLanguage(s.language); bump((n) => n + 1) })
    void window.whf.getState().then(setState)
    const off = window.whf.onStateChanged((s) => { setState(s); if (s.service === 'ready') void refresh() })
    void refresh()
    return off
  }, [refresh])

  const me = useMemo(() => meta?.members.find((m) => m.id === profile?.member_id) ?? null, [meta, profile])
  const visibleTeams = useMemo(() => {
    if (!meta || !me) return []
    if (me.role === 'skill_team_leader') return meta.teams.filter((t) => t.department_id === me.department_id)
    return meta.teams.filter((t) => t.id === me.team_id)
  }, [meta, me])

  const value: AppContextValue = {
    meta, profile, settings, state, me, visibleTeams, error,
    canRun: (teamId) => visibleTeams.some((t) => t.id === teamId),
    refresh,
    saveSettings: async (patch) => { const s = await window.whf.setSettings(patch); setSettings(s); setLanguage(s.language); bump((n) => n + 1) },
    saveProfile: async (memberId) => { setProfileState(await setProfile(memberId)) },
  }
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useApp(): AppContextValue {
  const v = useContext(Ctx)
  if (!v) throw new Error('useApp must be used inside AppProvider')
  return v
}
```

`app/src/renderer/src/pages/Settings.tsx`:

```tsx
import type React from 'react'
import { useEffect, useState } from 'react'
import type { CopilotStatus } from '../../../shared/types'
import { getCopilotStatus } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { t } from '../i18n'

export function Settings(): React.JSX.Element {
  const { meta, profile, settings, saveSettings, saveProfile } = useApp()
  const [copilot, setCopilot] = useState<CopilotStatus | null>(null)
  const [loginMessage, setLoginMessage] = useState<string | null>(null)
  const [model, setModel] = useState(settings.model ?? '')
  const [error, setError] = useState<string | null>(null)

  const loadStatus = (): void => { getCopilotStatus().then(setCopilot).catch((e: Error) => setError(e.message)) }
  useEffect(loadStatus, [])
  useEffect(() => { setModel(settings.model ?? '') }, [settings.model])

  const leaders = (meta?.members ?? []).filter((m) => m.role !== 'member')
  return (
    <div>
      <h1>{t('settings.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <section className="panel">
        <h2>{t('settings.profile')}</h2>
        <Field label={t('settings.iam')}>
          {(id) => (
            <select id={id} value={profile?.member_id ?? ''} onChange={(e) => { void saveProfile(e.target.value ? Number(e.target.value) : null).catch((err: Error) => setError(err.message)) }}>
              <option value="">{t('settings.nobody')}</option>
              {leaders.map((m) => <option key={m.id} value={m.id}>{m.name} ({m.role.replace(/_/g, ' ')})</option>)}
            </select>
          )}
        </Field>
      </section>
      <section className="panel">
        <h2>{t('settings.copilot')}</h2>
        {copilot && (
          <p>{copilot.ready ? t('settings.ready', { login: copilot.login ?? '' }) : copilot.message}
            {copilot.cli_path && <span className="muted"> · {copilot.cli_path}</span>}</p>
        )}
        {loginMessage && <StatusMessage kind="info">{loginMessage}</StatusMessage>}
        <button className="primary" onClick={() => { void window.whf.copilotLogin().then((r) => setLoginMessage(r.message)) }}>{t('settings.signin')}</button>{' '}
        <button onClick={loadStatus}>{t('settings.recheck')}</button>
      </section>
      <section className="panel">
        <Field label={t('settings.language')}>
          {(id) => (
            <select id={id} value={settings.language} onChange={(e) => { void saveSettings({ language: e.target.value as 'en' | 'fr' }) }}>
              <option value="en">English</option>
              <option value="fr">Français</option>
            </select>
          )}
        </Field>
        <Field label={t('settings.model')}>
          {(id) => <input id={id} value={model} onChange={(e) => setModel(e.target.value)} onBlur={() => { void saveSettings({ model: model.trim() || null }) }} />}
        </Field>
        <div className="field">
          <label><input type="checkbox" checked={settings.launchAtLogin} onChange={(e) => { void saveSettings({ launchAtLogin: e.target.checked }) }} /> {t('settings.launch')}</label>
        </div>
        <div className="field">
          <label><input type="checkbox" checked={settings.closeToTray} onChange={(e) => { void saveSettings({ closeToTray: e.target.checked }) }} /> {t('settings.tray')}</label>
        </div>
      </section>
    </div>
  )
}
```

`app/src/renderer/src/app.tsx`:

```tsx
import type React from 'react'
import { NavLink, Route, Routes } from 'react-router-dom'
import { StatusMessage } from './components/StatusMessage'
import { AppProvider, useApp } from './context'
import { t } from './i18n'
import { Settings } from './pages/Settings'

const NAV: [string, string][] = [
  ['/', 'nav.dashboard'], ['/run', 'nav.run'], ['/rebalancing', 'nav.rebalancing'], ['/projects', 'nav.projects'],
  ['/capacity', 'nav.capacity'], ['/timeoff', 'nav.timeoff'], ['/runs', 'nav.runs'], ['/settings', 'nav.settings'],
]

function Placeholder({ title }: { title: string }): React.JSX.Element { return <h1>{title}</h1> }

function Shell(): React.JSX.Element {
  const { state, me, error } = useApp()
  return (
    <div className="layout">
      <nav className="nav">
        <h2>{t('app.title')}</h2>
        {NAV.map(([to, key]) => <NavLink key={to} to={to} end={to === '/'}>{t(key)}</NavLink>)}
        {me && <p className="muted">{me.name}</p>}
      </nav>
      <main className="content">
        {state.service === 'starting' && <StatusMessage kind="info">{t('service.starting')}</StatusMessage>}
        {state.service === 'failed' && <StatusMessage kind="error">{state.serviceMessage}</StatusMessage>}
        {error && state.service === 'ready' && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
        {!me && state.service === 'ready' && <StatusMessage kind="info">{t('profile.none')}</StatusMessage>}
        <Routes>
          <Route path="/" element={<Placeholder title={t('nav.dashboard')} />} />
          <Route path="/run" element={<Placeholder title={t('nav.run')} />} />
          <Route path="/runs" element={<Placeholder title={t('nav.runs')} />} />
          <Route path="/runs/:runId" element={<Placeholder title={t('team.title')} />} />
          <Route path="/runs/:runId/members/:memberId" element={<Placeholder title={t('member.title')} />} />
          <Route path="/rebalancing" element={<Placeholder title={t('nav.rebalancing')} />} />
          <Route path="/projects" element={<Placeholder title={t('nav.projects')} />} />
          <Route path="/capacity" element={<Placeholder title={t('nav.capacity')} />} />
          <Route path="/timeoff" element={<Placeholder title={t('nav.timeoff')} />} />
          <Route path="/settings" element={<Settings />} />
        </Routes>
      </main>
    </div>
  )
}

export function App(): React.JSX.Element {
  return <AppProvider><Shell /></AppProvider>
}
```

(Tasks 9 to 12 replace the `Placeholder` routes one by one; the `Placeholder` component is deleted in Task 12.)

- [ ] **Step 4: Gates and commit**

Run: `cd app && npm test && npm run lint && npm run typecheck`
Expected: PASS

```bash
git add app/package.json app/package-lock.json app/src/renderer app/src/shared
git commit -m "feat(app): renderer shell with routing, context, i18n and Settings"
```

---

### Task 9: Dashboard and Runs pages

**Files:**
- Create: `app/src/renderer/src/pages/Dashboard.tsx`, `app/src/renderer/src/pages/Runs.tsx`, `app/src/renderer/src/components/DemandCapacityChart.tsx`, `app/src/renderer/src/__tests__/Dashboard.test.tsx`, `app/src/renderer/src/__tests__/Runs.test.tsx`
- Modify: `app/src/renderer/src/app.tsx` (routes `/` and `/runs`)

**Interfaces:**
- Consumes: `useApp()`, `getDepartmentOverview`, `getRuns`, types `DepartmentOverview`, `OverviewTeam`, `RunSummary`; `t`, `hours`, `weekLabel`.
- Produces: `Dashboard` (department of the profile; one card per visible team: due badge, last run date, per-week demand/capacity/overload rows, overloaded member chips, links "Open result" to `/runs/<id>` and "Run" to `/run?team=<id>`), `DemandCapacityChart({ data: { week: string; demand: number; capacity: number }[] })` (Recharts bar chart, demand vs capacity), `Runs` (table of runs for visible teams, newest first, team filter, link to `/runs/<id>`).

- [ ] **Step 1: Write the failing tests**

`app/src/renderer/src/__tests__/Dashboard.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Dashboard } from '../pages/Dashboard'
import { installFakeWhf, META } from '../test/fake-whf'

const overview = {
  department_id: 1,
  teams: [
    { team_id: 1, team_name: 'Core', run_id: 5, as_of: '2026-09-04', finished_at: '2026-09-04T10:00:00', due: false,
      weeks: [{ week: '2026-09-07', demand: 152.5, capacity: 160, overload: 4 }, { week: '2026-09-14', demand: 170, capacity: 160, overload: 12 }],
      overloaded: [{ member_id: 13, name: 'Yara Tazi', overload_hours: 16 }] },
    { team_id: 2, team_name: 'Data', run_id: null, as_of: null, finished_at: null, due: true, weeks: [], overloaded: [] },
  ],
}

describe('Dashboard', () => {
  it('shows every visible team with due state, weekly totals and overloaded members', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 10, role: 'skill_team_leader' }, 'GET /departments/1/overview': overview })
    render(<MemoryRouter><AppProvider><Dashboard /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Core')).toBeInTheDocument()
    expect(screen.getByText('Data')).toBeInTheDocument()
    expect(screen.getAllByText('Forecast due')).toHaveLength(1)
    expect(screen.getByText('152.5 h')).toBeInTheDocument()
    expect(screen.getByText('Yara Tazi')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open result' })).toHaveAttribute('href', '/runs/5')
  })
  it('shows only the own team for a team leader', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /departments/1/overview': overview })
    render(<MemoryRouter><AppProvider><Dashboard /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Core')).toBeInTheDocument()
    expect(screen.queryByText('Data')).not.toBeInTheDocument()
  })
})
```

(`MemoryRouter` renders `href="/runs/5"`; every page test uses `MemoryRouter`, so assert plain paths, never `#/…`.)

`app/src/renderer/src/__tests__/Runs.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Runs } from '../pages/Runs'
import { installFakeWhf, META } from '../test/fake-whf'

const runs = [
  { id: 6, team_id: 2, as_of: '2026-09-04', requested_by: 10, status: 'ok', champion_model: 'tsb', backtest_mase: 0.81, started_at: '2026-09-04T10:00:00', finished_at: '2026-09-04T10:00:04', ai_status: 'ok' },
  { id: 5, team_id: 1, as_of: '2026-09-04', requested_by: 11, status: 'ok', champion_model: 'gbm', backtest_mase: 0.77, started_at: '2026-09-04T09:00:00', finished_at: '2026-09-04T09:00:05', ai_status: 'failed:timeout' },
]

describe('Runs', () => {
  it('lists runs of visible teams newest first and filters by team', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 10, role: 'skill_team_leader' }, 'GET /runs': runs })
    render(<MemoryRouter><AppProvider><Runs /></AppProvider></MemoryRouter>)
    const rows = await screen.findAllByRole('row')
    expect(rows).toHaveLength(3)
    expect(rows[1]).toHaveTextContent('Data')
    expect(rows[2]).toHaveTextContent('failed:timeout')
    await userEvent.selectOptions(screen.getByLabelText('Team'), '1')
    expect(screen.getAllByRole('row')).toHaveLength(2)
  })
  it('hides runs of other teams from a team leader', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /runs': runs })
    render(<MemoryRouter><AppProvider><Runs /></AppProvider></MemoryRouter>)
    expect(await screen.findAllByRole('row')).toHaveLength(2)
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd app && npx vitest run --project renderer Dashboard Runs`
Expected: FAIL, modules not found

- [ ] **Step 3: Implement**

`app/src/renderer/src/components/DemandCapacityChart.tsx`:

```tsx
import type React from 'react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { weekLabel } from '../format'

export function DemandCapacityChart({ data }: { data: { week: string; demand: number; capacity: number }[] }): React.JSX.Element {
  const rows = data.map((d) => ({ ...d, label: weekLabel(d.week) }))
  return (
    <div style={{ width: '100%', height: 220 }}>
      <ResponsiveContainer>
        <BarChart data={rows}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="label" />
          <YAxis unit=" h" />
          <Tooltip />
          <Legend />
          <Bar dataKey="demand" name="Demand" fill="#2457c5" />
          <Bar dataKey="capacity" name="Capacity" fill="#9ca3af" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
```

(Recharts' `ResponsiveContainer` renders nothing measurable in jsdom; tests assert on the table rows, not the chart.)

`app/src/renderer/src/pages/Dashboard.tsx`:

```tsx
import type React from 'react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import type { DepartmentOverview, OverviewTeam } from '../../../shared/types'
import { getDepartmentOverview } from '../api'
import { DemandCapacityChart } from '../components/DemandCapacityChart'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { hours, weekLabel } from '../format'
import { t } from '../i18n'

function TeamCard({ team }: { team: OverviewTeam }): React.JSX.Element {
  return (
    <section className="panel">
      <h2>{team.team_name} {team.due && <span className="badge high">{t('dashboard.due')}</span>}</h2>
      <p className="muted">{team.run_id ? t('dashboard.lastRun', { date: team.finished_at?.slice(0, 10) ?? team.as_of ?? '' }) : t('dashboard.noRun')}</p>
      {team.weeks.length > 0 && (
        <>
          <DemandCapacityChart data={team.weeks} />
          <table>
            <thead><tr><th>{t('member.week')}</th><th className="num">{t('dashboard.demand')}</th><th className="num">{t('dashboard.capacity')}</th><th className="num">{t('dashboard.overload')}</th></tr></thead>
            <tbody>
              {team.weeks.map((w) => (
                <tr key={w.week}><td>{weekLabel(w.week)}</td><td className="num">{hours(w.demand)}</td><td className="num">{hours(w.capacity)}</td><td className="num">{hours(w.overload)}</td></tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      {team.overloaded.length > 0 && (
        <p>{t('dashboard.overloaded')}: {team.overloaded.map((m) => <span key={m.member_id} className="badge high" style={{ marginRight: 6 }}>{m.name} +{m.overload_hours.toFixed(1)} h</span>)}</p>
      )}
      <p>
        {team.run_id && <Link to={`/runs/${team.run_id}`}>Open result</Link>}{' '}
        <Link to={`/run?team=${team.team_id}`}>{t('nav.run')}</Link>
      </p>
    </section>
  )
}

export function Dashboard(): React.JSX.Element {
  const { me, visibleTeams } = useApp()
  const [overview, setOverview] = useState<DepartmentOverview | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!me) return
    getDepartmentOverview(me.department_id).then(setOverview).catch((e: Error) => setError(e.message))
  }, [me])
  if (!me) return <h1>{t('dashboard.title')}</h1>
  const visible = new Set(visibleTeams.map((tm) => tm.id))
  return (
    <div>
      <h1>{t('dashboard.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      {!overview && !error && <p>{t('common.loading')}</p>}
      {overview?.teams.filter((tm) => visible.has(tm.team_id)).map((tm) => <TeamCard key={tm.team_id} team={tm} />)}
    </div>
  )
}
```

`app/src/renderer/src/pages/Runs.tsx`:

```tsx
import type React from 'react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import type { RunSummary } from '../../../shared/types'
import { getRuns } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { t } from '../i18n'

export function Runs(): React.JSX.Element {
  const { visibleTeams } = useApp()
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [team, setTeam] = useState<string>('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { getRuns().then(setRuns).catch((e: Error) => setError(e.message)) }, [])
  const nameOf = new Map(visibleTeams.map((tm) => [tm.id, tm.name]))
  const rows = runs.filter((r) => nameOf.has(r.team_id) && (team === '' || r.team_id === Number(team))).sort((a, b) => b.id - a.id)
  return (
    <div>
      <h1>{t('runs.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <Field label={t('runs.team')}>
        {(id) => (
          <select id={id} value={team} onChange={(e) => setTeam(e.target.value)}>
            <option value="">{t('common.all')}</option>
            {visibleTeams.map((tm) => <option key={tm.id} value={tm.id}>{tm.name}</option>)}
          </select>
        )}
      </Field>
      {rows.length === 0 ? <p className="muted">{t('runs.empty')}</p> : (
        <table>
          <thead><tr><th>{t('runs.id')}</th><th>{t('runs.team')}</th><th>{t('runs.asof')}</th><th>{t('runs.status')}</th><th>{t('runs.champion')}</th><th>{t('runs.ai')}</th><th></th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td>{r.id}</td><td>{nameOf.get(r.team_id)}</td><td>{r.as_of}</td><td>{r.status}</td>
                <td>{r.champion_model ?? '–'}{r.backtest_mase !== null && <span className="muted"> (MASE {r.backtest_mase.toFixed(2)})</span>}</td>
                <td>{r.ai_status}</td><td><Link to={`/runs/${r.id}`}>{t('runs.open')}</Link></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
```

Replace the `/` and `/runs` placeholder routes in `app.tsx` with `<Dashboard />` and `<Runs />`.

- [ ] **Step 4: Gates and commit**

Run: `cd app && npm test && npm run lint && npm run typecheck`
Expected: PASS

```bash
git add app/src/renderer
git commit -m "feat(app): dashboard and runs pages"
```

---

### Task 10: Run page and Team result page

**Files:**
- Create: `app/src/renderer/src/pages/Run.tsx`, `app/src/renderer/src/pages/TeamResult.tsx`, `app/src/renderer/src/components/WeekTable.tsx`, `app/src/renderer/src/components/IntervalBar.tsx`, `app/src/renderer/src/components/RiskBadge.tsx`, `app/src/renderer/src/__tests__/Run.test.tsx`, `app/src/renderer/src/__tests__/TeamResult.test.tsx`, `app/src/renderer/src/test/fixtures.ts`
- Modify: `app/src/renderer/src/app.tsx`

**Interfaces:**
- Consumes: `createRun`, `createNarrative`, `getRun`, `getCopilotStatus`, `useApp().canRun/visibleTeams/me/settings`, types `RunCreated`, `RunDetail`, `NarrativeOutcome`, `MemberNarrative`.
- Produces: `Run` (query `?team=` preselects; steps "Forecasting…" then optional "Asking Copilot…"; on success shows totals and a link to `/runs/<id>`; the narrative failure is shown but the forecast is still complete), `TeamResult` (table members × two weeks with demand, interval bar, capacity, overload badge; champion + MASE; AI summary, warnings, team risks; "Ask Copilot" button when there is no narrative; links to member detail), `WeekTable({ weeks, rows })` where `rows: { member_id; name; cells: Record<week, ForecastRow>; risk?: RiskLevel }[]`, `IntervalBar({ low, high, value, max })`, `RiskBadge({ level })`, and `test/fixtures.ts` exporting `RUN_DETAIL: RunDetail` (two members, two weeks, facts and a narrative) and `RUN_CREATED: RunCreated`.

- [ ] **Step 1: Fixtures and failing tests**

`app/src/renderer/src/test/fixtures.ts`:

```ts
import type { RunCreated, RunDetail } from '../../../shared/types'

const W1 = '2026-09-07', W2 = '2026-09-14'
const row = (member_id: number, week_start: string, demand: number, capacity: number, low = demand - 3, high = demand + 3) => ({
  run_id: 5, member_id, week_start, demand_hours: demand, demand_low: low, demand_high: high, capacity_hours: capacity,
  overload_hours: Math.max(0, demand - capacity), open_task_hours: demand * 0.6, new_task_hours: demand * 0.4,
})

export const RUN_CREATED: RunCreated = {
  run_id: 5, team_id: 1, as_of: '2026-09-04', weeks: [W1, W2], champion: 'gbm', backtest_mase: 0.77,
  forecasts: [row(11, W1, 36, 40), row(11, W2, 38, 40), row(13, W1, 46, 40), row(13, W2, 44, 32)],
}

export const RUN_DETAIL: RunDetail = {
  run: { id: 5, team_id: 1, as_of: '2026-09-04', requested_by: 11, status: 'ok', champion_model: 'gbm', backtest_mase: 0.77, started_at: '2026-09-04T09:00:00', finished_at: '2026-09-04T09:00:05', ai_status: 'ok' },
  forecasts: RUN_CREATED.forecasts,
  facts: {
    run: { id: 5, as_of: '2026-09-04', weeks: [W1, W2], generated_at: '2026-09-04T09:00:05' },
    team: { id: 1, name: 'Core', department_id: 1, team_leader_id: 11, totals: [{ week: W1, demand: 82, capacity: 80 }, { week: W2, demand: 82, capacity: 72 }] },
    members: [
      { id: 11, name: 'Ali Benjelloun', role: 'team_leader', history_13w: Array.from({ length: 13 }, (_, i) => ({ week: `2026-0${i < 4 ? 6 : i < 8 ? 7 : 8}-0${(i % 4) + 1}`, hours: 30 + i, tasks: 3 })),
        forecast: [{ week: W1, demand: 36, low: 33, high: 39, capacity: 40, overload: 0, open_hours: 21.6, new_hours: 14.4 }, { week: W2, demand: 38, low: 35, high: 41, capacity: 40, overload: 0, open_hours: 22.8, new_hours: 15.2 }],
        patterns: { member_id: 11, trend_hours_per_week: 0.4, top_weekday: 'Monday', weekday_shares: { Monday: 0.4, Tuesday: 0.2, Wednesday: 0.2, Thursday: 0.1, Friday: 0.1 }, estimate_ratio_median: 1.1, cycle_days_median: 4, cycle_days_by_type: { bug: 2, feature: 6 }, lateness_days_median: 0, share_late: 0.1, deadline_proximity_corr: null, share_with_project: 0.7, hours_by_project: { '3': 0.7 }, open_tasks: 4, open_est_hours: 30, overdue_open: 1, cluster: 0 },
        open_tasks: [{ id: 900, title: 'Fix login', type: 'bug', priority: 'high', estimated_hours: 6, due_date: '2026-09-02', overdue: true, project_id: 3 }] },
      { id: 13, name: 'Yara Tazi', role: 'member', history_13w: [], forecast: [{ week: W1, demand: 46, low: 43, high: 49, capacity: 40, overload: 6, open_hours: 27.6, new_hours: 18.4 }, { week: W2, demand: 44, low: 41, high: 47, capacity: 32, overload: 12, open_hours: 26.4, new_hours: 17.6 }],
        patterns: { member_id: 13, trend_hours_per_week: 1.2, top_weekday: 'Friday', weekday_shares: {}, estimate_ratio_median: 0.9, cycle_days_median: 5, cycle_days_by_type: {}, lateness_days_median: 1, share_late: 0.3, deadline_proximity_corr: 0.4, share_with_project: 0.5, hours_by_project: {}, open_tasks: 6, open_est_hours: 44, overdue_open: 2, cluster: 1 }, open_tasks: [] },
    ],
    projects: [{ id: 3, name: 'Billing v2', start_date: '2026-08-03', deadline: '2026-09-18', status: 'active', type: 'delivery', active_in_window: true, starting_in_window: false, ending_in_window: true }],
    model: { champion: 'gbm', champion_mase: 0.77, mase_by_model: { gbm: 0.77, tsb: 0.85, seasonal_naive: 1.0 }, backtest_origins: [], horizons: [1, 2], limitations: '', interval: { basis: '', horizons: {} } },
    rebalancing_candidates: { overloaded: [{ member_id: 13, name: 'Yara Tazi', overload_hours: 18 }], underloaded: [{ member_id: 11, name: 'Ali Benjelloun', spare_hours: 6 }] },
  },
  narrative: {
    run_summary: 'Core is slightly over capacity in both weeks, driven by Yara.',
    members: [
      { member_id: 11, name: 'Ali Benjelloun', risk_level: 'low', summary: 'Steady load around 36.0 h.', patterns: [{ kind: 'weekday_rhythm', statement: 'Most tasks arrive on Monday.', evidence: 'Monday share 40%.' }], warnings: [] },
      { member_id: 13, name: 'Yara Tazi', risk_level: 'high', summary: 'Demand of 46.0 h against 40.0 h capacity.', patterns: [], warnings: ['Two overdue tasks.'] },
    ],
    team_risks: [{ title: 'Billing v2 deadline', detail: 'Ends in week 2 while Yara is overloaded.', severity: 'medium', member_ids: [13] }],
    rebalancing: [{ from_member_id: 13, to_member_id: 11, week: W1, hours: 4, reason: 'Ali has 4.0 h spare.', confidence: 'medium' }],
    suggested_adjustments: [],
    model_notes: 'Champion gbm beat TSB.',
  },
}
```

`app/src/renderer/src/__tests__/Run.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Run } from '../pages/Run'
import { installFakeWhf, META } from '../test/fake-whf'
import { RUN_CREATED } from '../test/fixtures'

const ready = { cli_path: 'c', cli_source: 'path', authenticated: true, login: 'ali', message: 'ok', ready: true }

describe('Run', () => {
  it('runs the forecast then the narrative and links to the result', async () => {
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': ready,
      'POST /runs': RUN_CREATED, 'POST /runs/5/narrative': { run_id: 5, status: 'ok', ai_status: 'ok', narrative: null, error: null, reason: null, attempts: 1, tool_calls: ['get_run_overview'] },
    })
    render(<MemoryRouter initialEntries={['/run?team=1']}><AppProvider><Run /></AppProvider></MemoryRouter>)
    await userEvent.click(await screen.findByRole('button', { name: 'Run forecast' }))
    expect(await screen.findByText('Forecast complete')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the result' })).toHaveAttribute('href', '/runs/5')
    await waitFor(() => expect(fake.calls.some((c) => c.path === '/runs/5/narrative')).toBe(true))
    const body = fake.calls.find((c) => c.path === '/runs')!.body as { team_id: number; requested_by: number }
    expect(body.team_id).toBe(1)
    expect(body.requested_by).toBe(11)
  })
  it('keeps the forecast when the narrative fails and shows the reason', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 10, role: 'skill_team_leader' }, 'GET /copilot/status': ready,
      'POST /runs': RUN_CREATED, 'POST /runs/5/narrative': { run_id: 5, status: 'failed', ai_status: 'failed:timeout', narrative: null, error: 'timed out', reason: 'timeout', attempts: 1, tool_calls: [] },
    })
    render(<MemoryRouter initialEntries={['/run']}><AppProvider><Run /></AppProvider></MemoryRouter>)
    await userEvent.selectOptions(await screen.findByLabelText('Team'), '1')
    expect(screen.getByText('You are running this forecast on behalf of Ali Benjelloun.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Run forecast' }))
    expect(await screen.findByText('Forecast complete')).toBeInTheDocument()
    expect(await screen.findByText('Copilot narrative failed: timed out')).toBeInTheDocument()
  })
  it('disables the AI step when Copilot is not ready', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /copilot/status': { ...ready, ready: false, authenticated: false, message: 'Not signed in' } })
    render(<MemoryRouter><AppProvider><Run /></AppProvider></MemoryRouter>)
    const ai = await screen.findByLabelText('Ask Copilot for the narrative')
    expect(ai).toBeDisabled()
    expect(screen.getByText('Not signed in')).toBeInTheDocument()
  })
})
```

`app/src/renderer/src/__tests__/TeamResult.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AppProvider } from '../context'
import { TeamResult } from '../pages/TeamResult'
import { installFakeWhf, META } from '../test/fake-whf'
import { RUN_DETAIL } from '../test/fixtures'

function mount() {
  return render(
    <MemoryRouter initialEntries={['/runs/5']}><AppProvider>
      <Routes><Route path="/runs/:runId" element={<TeamResult />} /></Routes>
    </AppProvider></MemoryRouter>,
  )
}

describe('TeamResult', () => {
  it('shows members by week with overload, champion, summary and warnings', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /runs/5': RUN_DETAIL })
    mount()
    expect(await screen.findByText('Core')).toBeInTheDocument()
    expect(screen.getByText('gbm')).toBeInTheDocument()
    expect(screen.getByText('0.77')).toBeInTheDocument()
    const yara = screen.getByRole('row', { name: /Yara Tazi/ })
    expect(yara).toHaveTextContent('46.0 h')
    expect(yara).toHaveTextContent('+6.0 h')
    expect(yara).toHaveTextContent('+12.0 h')
    expect(screen.getByText('Core is slightly over capacity in both weeks, driven by Yara.')).toBeInTheDocument()
    expect(screen.getByText('Two overdue tasks.')).toBeInTheDocument()
    expect(screen.getByText('Billing v2 deadline')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Yara Tazi' })).toHaveAttribute('href', '/runs/5/members/13')
  })
  it('offers to ask Copilot when there is no narrative and flags unverified ones', async () => {
    let detail = { ...RUN_DETAIL, narrative: null, run: { ...RUN_DETAIL.run, ai_status: 'not_requested' } }
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs/5': () => detail,
      'POST /runs/5/narrative': () => {
        detail = { ...RUN_DETAIL, run: { ...RUN_DETAIL.run, ai_status: 'unverified' } }
        return { run_id: 5, status: 'unverified', ai_status: 'unverified', narrative: RUN_DETAIL.narrative, error: null, reason: null, attempts: 2, tool_calls: [] }
      },
    })
    mount()
    await userEvent.click(await screen.findByRole('button', { name: 'Ask Copilot' }))
    expect(await screen.findByText('Some numbers in this narrative could not be matched to the forecast facts.')).toBeInTheDocument()
    expect(fake.calls.some((c) => c.method === 'POST' && c.path === '/runs/5/narrative')).toBe(true)
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd app && npx vitest run --project renderer Run TeamResult`
Expected: FAIL

- [ ] **Step 3: Implement the components**

`app/src/renderer/src/components/RiskBadge.tsx`:

```tsx
import type React from 'react'
import type { RiskLevel } from '../../../shared/types'

export function RiskBadge({ level }: { level: RiskLevel }): React.JSX.Element {
  return <span className={`badge ${level}`}>{level}</span>
}
```

`app/src/renderer/src/components/IntervalBar.tsx`:

```tsx
import type React from 'react'

export function IntervalBar({ low, high, value, max }: { low: number; high: number; value: number; max: number }): React.JSX.Element {
  const scale = max > 0 ? 100 / max : 0
  const clamp = (n: number): number => Math.max(0, Math.min(100, n * scale))
  return (
    <div className="bar" title={`${low.toFixed(1)} – ${high.toFixed(1)} h`} aria-label={`interval ${low.toFixed(1)} to ${high.toFixed(1)} hours`}>
      <span style={{ left: `${clamp(low)}%`, width: `${clamp(high) - clamp(low)}%` }} />
      <i style={{ left: `${clamp(value)}%` }} />
    </div>
  )
}
```

`app/src/renderer/src/components/WeekTable.tsx`:

```tsx
import type React from 'react'
import { Link } from 'react-router-dom'
import type { ForecastRow, RiskLevel } from '../../../shared/types'
import { hours, weekLabel } from '../format'
import { t } from '../i18n'
import { IntervalBar } from './IntervalBar'
import { RiskBadge } from './RiskBadge'

export interface WeekTableRow { member_id: number; name: string; cells: Record<string, ForecastRow | undefined>; risk?: RiskLevel; href?: string }

export function WeekTable({ weeks, rows }: { weeks: string[]; rows: WeekTableRow[] }): React.JSX.Element {
  const max = Math.max(1, ...rows.flatMap((r) => weeks.map((w) => Math.max(r.cells[w]?.demand_high ?? 0, r.cells[w]?.capacity_hours ?? 0))))
  return (
    <table>
      <thead>
        <tr>
          <th>{t('team.member')}</th>
          {weeks.map((w) => <th key={w} colSpan={3}>{weekLabel(w)}</th>)}
        </tr>
        <tr>
          <th></th>
          {weeks.map((w) => [<th key={`${w}d`} className="num">{t('member.demand')}</th>, <th key={`${w}i`}>{t('team.interval')}</th>, <th key={`${w}c`} className="num">{t('member.capacity')}</th>])}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.member_id} aria-label={r.name}>
            <td>{r.href ? <Link to={r.href}>{r.name}</Link> : r.name} {r.risk && <RiskBadge level={r.risk} />}</td>
            {weeks.map((w) => {
              const c = r.cells[w]
              if (!c) return [<td key={`${w}d`} className="num">–</td>, <td key={`${w}i`}></td>, <td key={`${w}c`} className="num">–</td>]
              return [
                <td key={`${w}d`} className="num">{hours(c.demand_hours)}</td>,
                <td key={`${w}i`}><IntervalBar low={c.demand_low} high={c.demand_high} value={c.demand_hours} max={max} /></td>,
                <td key={`${w}c`} className="num">{hours(c.capacity_hours)} {c.overload_hours > 0 && <span className="badge high">+{c.overload_hours.toFixed(1)} h</span>}</td>,
              ]
            })}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
```

`app/src/renderer/src/pages/Run.tsx`:

```tsx
import type React from 'react'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import type { CopilotStatus, RunCreated } from '../../../shared/types'
import { createNarrative, createRun, getCopilotStatus } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { hours, today } from '../format'
import { t } from '../i18n'

type Phase = 'idle' | 'forecasting' | 'narrating' | 'done'

export function Run(): React.JSX.Element {
  const { meta, me, visibleTeams, settings } = useApp()
  const [params] = useSearchParams()
  const [team, setTeam] = useState(params.get('team') ?? '')
  const [asOf, setAsOf] = useState(today())
  const [withAi, setWithAi] = useState(true)
  const [copilot, setCopilot] = useState<CopilotStatus | null>(null)
  const [phase, setPhase] = useState<Phase>('idle')
  const [result, setResult] = useState<RunCreated | null>(null)
  const [aiError, setAiError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { getCopilotStatus().then(setCopilot).catch(() => setCopilot(null)) }, [])
  useEffect(() => { if (!team && visibleTeams.length === 1) setTeam(String(visibleTeams[0]!.id)) }, [team, visibleTeams])

  const selected = visibleTeams.find((tm) => tm.id === Number(team))
  const leader = selected && meta?.members.find((m) => m.id === selected.team_leader_id)
  const onBehalf = me?.role === 'skill_team_leader' && leader && leader.id !== me.id
  const aiPossible = copilot?.ready === true

  async function start(): Promise<void> {
    if (!selected || !me) return
    setError(null); setAiError(null); setResult(null); setPhase('forecasting')
    try {
      const run = await createRun(selected.id, asOf, me.id)
      setResult(run)
      if (withAi && aiPossible) {
        setPhase('narrating')
        const outcome = await createNarrative(run.run_id, settings.model)
        if (outcome.status === 'failed') setAiError(outcome.error ?? outcome.ai_status)
      }
      setPhase('done')
    } catch (err) { setError(err instanceof Error ? err.message : String(err)); setPhase('idle') }
  }

  const busy = phase === 'forecasting' || phase === 'narrating'
  return (
    <div>
      <h1>{t('run.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <section className="panel">
        <Field label={t('run.team')}>
          {(id) => (
            <select id={id} value={team} onChange={(e) => setTeam(e.target.value)} disabled={busy}>
              <option value="">–</option>
              {visibleTeams.map((tm) => <option key={tm.id} value={tm.id}>{tm.name}</option>)}
            </select>
          )}
        </Field>
        {onBehalf && <StatusMessage kind="info">{t('run.onBehalf', { leader: leader.name })}</StatusMessage>}
        <Field label={t('run.asof')}>{(id) => <input id={id} type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} disabled={busy} />}</Field>
        <div className="field">
          <label><input type="checkbox" checked={withAi && aiPossible} disabled={!aiPossible || busy} onChange={(e) => setWithAi(e.target.checked)} aria-label={t('run.withai')} /> {t('run.withai')}</label>
          {copilot && !copilot.ready && <span className="muted"> {copilot.message}</span>}
        </div>
        <button className="primary" disabled={!selected || busy} onClick={() => { void start() }}>{t('run.start')}</button>
      </section>
      {phase === 'forecasting' && <StatusMessage kind="info">{t('run.progress.forecasting', { team: selected?.name ?? '' })}</StatusMessage>}
      {phase === 'narrating' && <StatusMessage kind="info">{t('run.progress.narrating')}</StatusMessage>}
      {aiError && <StatusMessage kind="error">{t('run.aiFailed', { reason: aiError })}</StatusMessage>}
      {phase === 'done' && result && (
        <section className="panel">
          <StatusMessage kind="success">{t('run.done')}</StatusMessage>
          <p>{t('team.champion')}: {result.champion} · {t('team.mase')}: {result.backtest_mase.toFixed(2)}</p>
          <ul>
            {result.weeks.map((w) => {
              const rows = result.forecasts.filter((f) => f.week_start === w)
              return <li key={w}>{t('common.week', { date: w })}: {hours(rows.reduce((s, f) => s + f.demand_hours, 0))} / {hours(rows.reduce((s, f) => s + f.capacity_hours, 0))}</li>
            })}
          </ul>
          <Link to={`/runs/${result.run_id}`}>{t('run.open')}</Link>
        </section>
      )}
    </div>
  )
}
```

`app/src/renderer/src/pages/TeamResult.tsx`:

```tsx
import type React from 'react'
import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import type { RunDetail } from '../../../shared/types'
import { createNarrative, getRun } from '../api'
import { RiskBadge } from '../components/RiskBadge'
import { StatusMessage } from '../components/StatusMessage'
import { WeekTable, type WeekTableRow } from '../components/WeekTable'
import { useApp } from '../context'
import { t } from '../i18n'

export function TeamResult(): React.JSX.Element {
  const { runId } = useParams()
  const { settings } = useApp()
  const [detail, setDetail] = useState<RunDetail | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const id = Number(runId)

  const load = useCallback(() => { getRun(id).then(setDetail).catch((e: Error) => setError(e.message)) }, [id])
  useEffect(load, [load])

  async function narrate(): Promise<void> {
    setBusy(true); setError(null)
    try {
      const outcome = await createNarrative(id, settings.model)
      if (outcome.status === 'failed') setError(outcome.error ?? outcome.ai_status)
      load()
    } catch (err) { setError(err instanceof Error ? err.message : String(err)) } finally { setBusy(false) }
  }

  if (error && !detail) return <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>
  if (!detail) return <p>{t('common.loading')}</p>
  const facts = detail.facts
  const weeks = facts?.run.weeks ?? [...new Set(detail.forecasts.map((f) => f.week_start))].sort()
  const narrative = detail.narrative
  const riskOf = new Map(narrative?.members.map((m) => [m.member_id, m.risk_level]) ?? [])
  const rows: WeekTableRow[] = (facts?.members ?? []).map((m) => ({
    member_id: m.id, name: m.name, risk: riskOf.get(m.id), href: `/runs/${id}/members/${m.id}`,
    cells: Object.fromEntries(weeks.map((w) => [w, detail.forecasts.find((f) => f.member_id === m.id && f.week_start === w)])),
  }))
  return (
    <div>
      <h1>{t('team.title')}: {facts?.team.name ?? `team ${detail.run.team_id}`}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <p className="muted">{t('runs.asof')} {detail.run.as_of} · {t('team.champion')}: <strong>{detail.run.champion_model}</strong> · {t('team.mase')}: <strong>{detail.run.backtest_mase?.toFixed(2)}</strong></p>
      <section className="panel"><WeekTable weeks={weeks} rows={rows} /></section>
      <section className="panel">
        <h2>{t('team.summary')}</h2>
        <p className="muted">{t('team.narrativeStatus', { status: detail.run.ai_status })}</p>
        {detail.run.ai_status === 'unverified' && <StatusMessage kind="info">{t('team.unverified')}</StatusMessage>}
        {!narrative && <button className="primary" disabled={busy} onClick={() => { void narrate() }}>{t('team.narrate')}</button>}
        {narrative && (
          <>
            <p>{narrative.run_summary}</p>
            {narrative.members.some((m) => m.warnings.length) && (
              <>
                <h3>{t('team.warnings')}</h3>
                <ul>{narrative.members.flatMap((m) => m.warnings.map((w, i) => <li key={`${m.member_id}-${i}`}><strong>{m.name}</strong>: {w}</li>))}</ul>
              </>
            )}
            {narrative.team_risks.length > 0 && (
              <>
                <h3>{t('team.risks')}</h3>
                <ul>{narrative.team_risks.map((r) => <li key={r.title}><RiskBadge level={r.severity} /> <strong>{r.title}</strong> — {r.detail}</li>)}</ul>
              </>
            )}
            {narrative.model_notes && <p className="muted">{t('team.notes')}: {narrative.model_notes}</p>}
          </>
        )}
      </section>
    </div>
  )
}
```

Replace the `/run` and `/runs/:runId` placeholders in `app.tsx`.

- [ ] **Step 4: Gates and commit**

Run: `cd app && npm test && npm run lint && npm run typecheck`
Expected: PASS

```bash
git add app/src/renderer
git commit -m "feat(app): run page and team result page"
```

---

### Task 11: Member detail and Rebalancing pages

**Files:**
- Create: `app/src/renderer/src/pages/MemberDetail.tsx`, `app/src/renderer/src/pages/Rebalancing.tsx`, `app/src/renderer/src/components/HistoryChart.tsx`, `app/src/renderer/src/__tests__/MemberDetail.test.tsx`, `app/src/renderer/src/__tests__/Rebalancing.test.tsx`
- Modify: `app/src/renderer/src/app.tsx`

**Interfaces:**
- Consumes: `getRun`, `getRuns`, `RUN_DETAIL` fixture, `useApp().visibleTeams`, `HistoryPoint`, `MemberFacts`, `RebalancingMove`.
- Produces: `MemberDetail` (route `/runs/:runId/members/:memberId`: history chart of 13 weeks, forecast table per week with range, capacity, overload, open/new split; patterns list from stats; open tasks with overdue mark; narrative summary, patterns findings, warnings), `HistoryChart({ points: HistoryPoint[] })` (Recharts line chart of hours per week), `Rebalancing` (select a team among visible ones, loads the latest successful run: overloaded and under-loaded side by side from `facts.rebalancing_candidates`, suggested moves from `narrative.rebalancing` with hours, week, reason and confidence badge, suggested adjustments listed as "not applied").

- [ ] **Step 1: Write the failing tests**

`app/src/renderer/src/__tests__/MemberDetail.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AppProvider } from '../context'
import { MemberDetail } from '../pages/MemberDetail'
import { installFakeWhf, META } from '../test/fake-whf'
import { RUN_DETAIL } from '../test/fixtures'

describe('MemberDetail', () => {
  it('shows forecast, patterns, open tasks and narrative for one member', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /runs/5': RUN_DETAIL })
    render(
      <MemoryRouter initialEntries={['/runs/5/members/11']}><AppProvider>
        <Routes><Route path="/runs/:runId/members/:memberId" element={<MemberDetail />} /></Routes>
      </AppProvider></MemoryRouter>,
    )
    expect(await screen.findByRole('heading', { name: /Ali Benjelloun/ })).toBeInTheDocument()
    const w1 = screen.getByRole('row', { name: /Mon 07 Sep/ })
    expect(w1).toHaveTextContent('36.0 h')
    expect(w1).toHaveTextContent('33.0 – 39.0 h')
    expect(screen.getByText(/Most tasks arrive on Monday/)).toBeInTheDocument()
    expect(screen.getByText(/estimate ratio/i)).toHaveTextContent('1.10')
    expect(screen.getByText('Fix login')).toBeInTheDocument()
    expect(screen.getByText('overdue')).toBeInTheDocument()
    expect(screen.getByText('Steady load around 36.0 h.')).toBeInTheDocument()
  })
})
```

`app/src/renderer/src/__tests__/Rebalancing.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Rebalancing } from '../pages/Rebalancing'
import { installFakeWhf, META } from '../test/fake-whf'
import { RUN_DETAIL } from '../test/fixtures'

describe('Rebalancing', () => {
  it('shows candidates and suggested moves for the latest run of the team', async () => {
    installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /runs?team_id=1': [{ ...RUN_DETAIL.run, id: 4, status: 'failed' }, RUN_DETAIL.run],
      'GET /runs/5': RUN_DETAIL,
    })
    render(<MemoryRouter><AppProvider><Rebalancing /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('18.0 h over')).toBeInTheDocument()
    expect(screen.getByText('6.0 h spare')).toBeInTheDocument()
    const move = screen.getByRole('row', { name: /Yara Tazi/ })
    expect(move).toHaveTextContent('Ali Benjelloun')
    expect(move).toHaveTextContent('4.0 h')
    expect(move).toHaveTextContent('Mon 07 Sep')
    expect(move).toHaveTextContent('medium')
  })
  it('says so when no run exists', async () => {
    installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /runs?team_id=1': [] })
    render(<MemoryRouter><AppProvider><Rebalancing /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('No forecast yet')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd app && npx vitest run --project renderer MemberDetail Rebalancing`
Expected: FAIL

- [ ] **Step 3: Implement**

`app/src/renderer/src/components/HistoryChart.tsx`:

```tsx
import type React from 'react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { HistoryPoint } from '../../../shared/types'
import { weekLabel } from '../format'

export function HistoryChart({ points }: { points: HistoryPoint[] }): React.JSX.Element {
  const rows = points.map((p) => ({ ...p, label: weekLabel(p.week) }))
  return (
    <div style={{ width: '100%', height: 200 }}>
      <ResponsiveContainer>
        <LineChart data={rows}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="label" />
          <YAxis unit=" h" />
          <Tooltip />
          <Line type="monotone" dataKey="hours" name="Estimated hours" stroke="#2457c5" dot={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
```

`app/src/renderer/src/pages/MemberDetail.tsx`:

```tsx
import type React from 'react'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import type { PatternStats, RunDetail } from '../../../shared/types'
import { getRun } from '../api'
import { HistoryChart } from '../components/HistoryChart'
import { RiskBadge } from '../components/RiskBadge'
import { StatusMessage } from '../components/StatusMessage'
import { hours, pct, weekLabel } from '../format'
import { t } from '../i18n'

function patternLines(p: PatternStats): string[] {
  const lines: string[] = []
  if (p.trend_hours_per_week !== null) lines.push(`Trend: ${p.trend_hours_per_week >= 0 ? '+' : ''}${p.trend_hours_per_week.toFixed(2)} h per week`)
  if (p.top_weekday) lines.push(`Busiest arrival day: ${p.top_weekday}${p.weekday_shares[p.top_weekday] !== undefined ? ` (${pct(p.weekday_shares[p.top_weekday]!)})` : ''}`)
  if (p.estimate_ratio_median !== null) lines.push(`Median estimate ratio (actual / estimate): ${p.estimate_ratio_median.toFixed(2)}`)
  if (p.cycle_days_median !== null) lines.push(`Median cycle time: ${p.cycle_days_median.toFixed(1)} days`)
  if (p.share_late !== null) lines.push(`Share of late tasks: ${pct(p.share_late)}`)
  if (p.share_with_project !== null) lines.push(`Share of work on projects: ${pct(p.share_with_project)}`)
  lines.push(`Open tasks: ${p.open_tasks} (${hours(p.open_est_hours)}), overdue: ${p.overdue_open}`)
  return lines
}

export function MemberDetail(): React.JSX.Element {
  const { runId, memberId } = useParams()
  const [detail, setDetail] = useState<RunDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { getRun(Number(runId)).then(setDetail).catch((e: Error) => setError(e.message)) }, [runId])
  if (error) return <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>
  if (!detail) return <p>{t('common.loading')}</p>
  const member = detail.facts?.members.find((m) => m.id === Number(memberId))
  if (!member) return <StatusMessage kind="error">{t('common.error', { message: `member ${memberId} is not in run ${runId}` })}</StatusMessage>
  const story = detail.narrative?.members.find((m) => m.member_id === member.id)
  return (
    <div>
      <p><Link to={`/runs/${runId}`}>← {t('team.title')}</Link></p>
      <h1>{member.name} {story && <RiskBadge level={story.risk_level} />}</h1>
      <section className="panel">
        <h2>{t('member.history')}</h2>
        {member.history_13w.length ? <HistoryChart points={member.history_13w} /> : <p className="muted">–</p>}
      </section>
      <section className="panel">
        <h2>{t('member.forecast')}</h2>
        <table>
          <thead><tr><th>{t('member.week')}</th><th className="num">{t('member.demand')}</th><th>{t('member.range')}</th><th className="num">{t('member.capacity')}</th><th className="num">{t('member.overload')}</th><th className="num">{t('member.openHours')}</th><th className="num">{t('member.newHours')}</th></tr></thead>
          <tbody>
            {member.forecast.map((f) => (
              <tr key={f.week} aria-label={weekLabel(f.week)}>
                <td>{weekLabel(f.week)}</td><td className="num">{hours(f.demand)}</td><td>{f.low.toFixed(1)} – {f.high.toFixed(1)} h</td>
                <td className="num">{hours(f.capacity)}</td><td className="num">{f.overload > 0 ? <span className="badge high">+{f.overload.toFixed(1)} h</span> : '–'}</td>
                <td className="num">{hours(f.open_hours)}</td><td className="num">{hours(f.new_hours)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <div className="grid-2">
        <section className="panel">
          <h2>{t('member.patterns')}</h2>
          <ul>{patternLines(member.patterns).map((l) => <li key={l}>{l}</li>)}</ul>
          {story && story.patterns.length > 0 && <ul>{story.patterns.map((p, i) => <li key={i}><strong>{p.kind.replace(/_/g, ' ')}</strong>: {p.statement} <span className="muted">({p.evidence})</span></li>)}</ul>}
        </section>
        <section className="panel">
          <h2>{t('member.open')}</h2>
          {member.open_tasks.length === 0 ? <p className="muted">–</p> : (
            <table>
              <tbody>
                {member.open_tasks.map((task) => (
                  <tr key={task.id}><td>{task.title}</td><td>{task.type} · {task.priority}</td><td className="num">{hours(task.estimated_hours)}</td><td>{task.due_date ?? ''} {task.overdue && <span className="badge high">overdue</span>}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
      {story && (
        <section className="panel">
          <h2>{t('member.narrative')}</h2>
          <p>{story.summary}</p>
          {story.warnings.length > 0 && <ul>{story.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>}
        </section>
      )}
    </div>
  )
}
```

`app/src/renderer/src/pages/Rebalancing.tsx`:

```tsx
import type React from 'react'
import { useEffect, useState } from 'react'
import type { RunDetail } from '../../../shared/types'
import { getRun, getRuns } from '../api'
import { Field } from '../components/Field'
import { RiskBadge } from '../components/RiskBadge'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { hours, weekLabel } from '../format'
import { t } from '../i18n'

export function Rebalancing(): React.JSX.Element {
  const { visibleTeams } = useApp()
  const [team, setTeam] = useState('')
  const [detail, setDetail] = useState<RunDetail | null | 'none'>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { if (!team && visibleTeams.length) setTeam(String(visibleTeams[0]!.id)) }, [team, visibleTeams])
  useEffect(() => {
    if (!team) return
    setDetail(null)
    getRuns(Number(team))
      .then((runs) => { const ok = runs.filter((r) => r.status === 'ok').sort((a, b) => b.id - a.id)[0]; return ok ? getRun(ok.id) : 'none' as const })
      .then(setDetail)
      .catch((e: Error) => setError(e.message))
  }, [team])
  const facts = detail && detail !== 'none' ? detail.facts : null
  const names = new Map(facts?.members.map((m) => [m.id, m.name]) ?? [])
  const narrative = detail && detail !== 'none' ? detail.narrative : null
  return (
    <div>
      <h1>{t('rebalancing.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <Field label={t('run.team')}>
        {(id) => <select id={id} value={team} onChange={(e) => setTeam(e.target.value)}>{visibleTeams.map((tm) => <option key={tm.id} value={tm.id}>{tm.name}</option>)}</select>}
      </Field>
      {detail === 'none' && <p className="muted">{t('dashboard.noRun')}</p>}
      {facts && (
        <>
          <div className="grid-2">
            <section className="panel">
              <h2>{t('rebalancing.overloaded')}</h2>
              <ul>{facts.rebalancing_candidates.overloaded.map((m) => <li key={m.member_id}>{m.name} — <span className="badge high">{t('rebalancing.over', { hours: hours(m.overload_hours) })}</span></li>)}</ul>
            </section>
            <section className="panel">
              <h2>{t('rebalancing.underloaded')}</h2>
              <ul>{facts.rebalancing_candidates.underloaded.map((m) => <li key={m.member_id}>{m.name} — <span className="badge low">{t('rebalancing.spare', { hours: hours(m.spare_hours) })}</span></li>)}</ul>
            </section>
          </div>
          <section className="panel">
            <h2>{t('rebalancing.moves')}</h2>
            {!narrative || narrative.rebalancing.length === 0 ? <p className="muted">{t('rebalancing.none')}</p> : (
              <table>
                <thead><tr><th>From</th><th>To</th><th>{t('member.week')}</th><th className="num">Hours</th><th>Reason</th><th>Confidence</th></tr></thead>
                <tbody>
                  {narrative.rebalancing.map((mv, i) => (
                    <tr key={i} aria-label={`${names.get(mv.from_member_id)} to ${names.get(mv.to_member_id)}`}>
                      <td>{names.get(mv.from_member_id) ?? mv.from_member_id}</td><td>{names.get(mv.to_member_id) ?? mv.to_member_id}</td>
                      <td>{weekLabel(mv.week)}</td><td className="num">{hours(mv.hours)}</td><td>{mv.reason}</td><td><RiskBadge level={mv.confidence} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {narrative && narrative.suggested_adjustments.length > 0 && (
              <>
                <h3>{t('rebalancing.adjustments')}</h3>
                <ul>{narrative.suggested_adjustments.map((a, i) => <li key={i}>{names.get(a.member_id) ?? a.member_id}, {weekLabel(a.week)}: {a.delta_hours >= 0 ? '+' : ''}{a.delta_hours.toFixed(1)} h — {a.reason}</li>)}</ul>
              </>
            )}
          </section>
        </>
      )}
    </div>
  )
}
```

Replace the `/runs/:runId/members/:memberId` and `/rebalancing` placeholders in `app.tsx`.

- [ ] **Step 4: Gates and commit**

Run: `cd app && npm test && npm run lint && npm run typecheck`
Expected: PASS

```bash
git add app/src/renderer
git commit -m "feat(app): member detail and rebalancing pages"
```

---

### Task 12: Projects, Capacity and Time off pages

**Files:**
- Create: `app/src/renderer/src/pages/Projects.tsx`, `app/src/renderer/src/pages/Capacity.tsx`, `app/src/renderer/src/pages/TimeOff.tsx`, `app/src/renderer/src/__tests__/Projects.test.tsx`, `app/src/renderer/src/__tests__/Capacity.test.tsx`, `app/src/renderer/src/__tests__/TimeOff.test.tsx`
- Modify: `app/src/renderer/src/app.tsx` (remove `Placeholder`)

**Interfaces:**
- Consumes: `getProjects/createProject/updateProject`, `getCapacity/setCapacityDefault/setCapacityOverride/deleteCapacityOverride`, `getHolidays/getVacations/createVacation/deleteVacation`, `useApp().meta/me/visibleTeams`.
- Produces: `Projects` (list of the department's projects; "New project" form: name, start, deadline, teams (checkboxes over visible teams), type; inline "Edit" per project with status; client-side validation messages `projects.deadlineError` and `projects.teamsError`), `Capacity` (default weekly hours input saved on blur; overrides table for counted members of visible teams; add form member/week/hours/reason; remove button), `TimeOff` (holidays for a chosen year; vacations for members of visible teams with add form and remove; validation `timeoff.rangeError`).

- [ ] **Step 1: Write the failing tests**

`app/src/renderer/src/__tests__/Projects.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Projects } from '../pages/Projects'
import { installFakeWhf, META } from '../test/fake-whf'

const existing = [{ id: 3, name: 'Billing v2', department_id: 1, start_date: '2026-08-03', deadline: '2026-09-18', type: 'delivery', status: 'active', created_by: 11, team_ids: [1] }]

describe('Projects', () => {
  it('creates a project after validating dates and teams', async () => {
    const fake = installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 10, role: 'skill_team_leader' }, 'GET /projects': existing, 'POST /projects': { id: 4 } })
    render(<MemoryRouter><AppProvider><Projects /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Billing v2')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'New project' }))
    await userEvent.type(screen.getByLabelText('Name'), 'Search')
    await userEvent.type(screen.getByLabelText('Start date'), '2026-10-05')
    await userEvent.type(screen.getByLabelText('Deadline'), '2026-10-05')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('The deadline must be after the start date.')).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('Deadline'))
    await userEvent.type(screen.getByLabelText('Deadline'), '2026-11-27')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Pick at least one team.')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Data'))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(fake.calls.some((c) => c.method === 'POST' && c.path === '/projects')).toBe(true))
    const body = fake.calls.find((c) => c.method === 'POST')!.body as { name: string; team_ids: number[]; department_id: number }
    expect(body).toMatchObject({ name: 'Search', team_ids: [2], department_id: 1 })
  })
  it('edits status and deadline of an existing project', async () => {
    const fake = installFakeWhf({ 'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' }, 'GET /projects': existing, 'PUT /projects/3': (b) => ({ id: 3, ...(b as object) }) })
    render(<MemoryRouter><AppProvider><Projects /></AppProvider></MemoryRouter>)
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'done')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(fake.calls.some((c) => c.method === 'PUT' && c.path === '/projects/3')).toBe(true))
    expect((fake.calls.find((c) => c.method === 'PUT')!.body as { status: string }).status).toBe('done')
  })
})
```

`app/src/renderer/src/__tests__/Capacity.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { Capacity } from '../pages/Capacity'
import { installFakeWhf, META } from '../test/fake-whf'

describe('Capacity', () => {
  it('shows the default and overrides, adds and removes an override', async () => {
    let overrides = [{ id: 7, member_id: 13, week_start: '2026-09-14', weekly_hours: 32, reason: 'training' }]
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /capacity': () => ({ default_weekly_hours: 40, overrides }),
      'PUT /capacity/default': (b) => b,
      'PUT /capacity/overrides': (b) => { const o = b as { member_id: number; weekly_hours: number; week_start: string | null; reason: string | null }; overrides = [...overrides, { id: 8, ...o }]; return o },
      'DELETE /capacity/overrides/7': () => { overrides = overrides.filter((o) => o.id !== 7); return { deleted: true } },
    })
    render(<MemoryRouter><AppProvider><Capacity /></AppProvider></MemoryRouter>)
    expect(await screen.findByDisplayValue('40')).toBeInTheDocument()
    expect(screen.getByText('training')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Member'), '13')
    await userEvent.type(screen.getByLabelText('Weekly hours'), '20')
    await userEvent.type(screen.getByLabelText('Reason'), 'internal project')
    await userEvent.click(screen.getByRole('button', { name: 'Add override' }))
    expect(await screen.findByText('internal project')).toBeInTheDocument()
    expect(screen.getByText('permanent')).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]!)
    await waitFor(() => expect(screen.queryByText('training')).not.toBeInTheDocument())
    expect(fake.calls.some((c) => c.method === 'DELETE' && c.path === '/capacity/overrides/7')).toBe(true)
  })
})
```

`app/src/renderer/src/__tests__/TimeOff.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AppProvider } from '../context'
import { TimeOff } from '../pages/TimeOff'
import { installFakeWhf, META } from '../test/fake-whf'

describe('TimeOff', () => {
  it('lists holidays and vacations, validates and adds a vacation', async () => {
    let vacations = [{ id: 2, member_id: 13, start_date: '2026-09-21', end_date: '2026-09-25', type: 'vacation' }]
    const fake = installFakeWhf({
      'GET /meta': META, 'GET /profile': { member_id: 11, role: 'team_leader' },
      'GET /holidays?year=*': [{ date: '2026-11-06', name: 'Green March', country: 'MA' }],
      'GET /vacations': () => vacations,
      'POST /vacations': (b) => { vacations = [...vacations, { id: 3, ...(b as { member_id: number; start_date: string; end_date: string; type: string }) }]; return { id: 3 } },
      'DELETE /vacations/2': () => { vacations = vacations.filter((v) => v.id !== 2); return { deleted: true } },
    })
    render(<MemoryRouter><AppProvider><TimeOff /></AppProvider></MemoryRouter>)
    expect(await screen.findByText('Green March')).toBeInTheDocument()
    expect(screen.getByText('2026-09-21')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Member'), '11')
    await userEvent.type(screen.getByLabelText('From'), '2026-10-12')
    await userEvent.type(screen.getByLabelText('To'), '2026-10-09')
    await userEvent.click(screen.getByRole('button', { name: 'Add vacation' }))
    expect(await screen.findByText('The end date must not be before the start date.')).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('To'))
    await userEvent.type(screen.getByLabelText('To'), '2026-10-16')
    await userEvent.click(screen.getByRole('button', { name: 'Add vacation' }))
    expect(await screen.findByText('2026-10-12')).toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]!)
    await waitFor(() => expect(fake.calls.some((c) => c.method === 'DELETE' && c.path === '/vacations/2')).toBe(true))
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd app && npx vitest run --project renderer Projects Capacity TimeOff`
Expected: FAIL

- [ ] **Step 3: Implement**

`app/src/renderer/src/pages/Projects.tsx`:

```tsx
import type React from 'react'
import { useEffect, useState } from 'react'
import type { Project } from '../../../shared/types'
import { createProject, getProjects, updateProject } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { t } from '../i18n'

interface Draft { name: string; start_date: string; deadline: string; team_ids: number[]; type: string; status: 'planned' | 'active' | 'done' }
const empty: Draft = { name: '', start_date: '', deadline: '', team_ids: [], type: 'delivery', status: 'planned' }

function validate(d: Draft): string | null {
  if (!d.name.trim()) return t('projects.name')
  if (!d.start_date || !d.deadline || d.deadline <= d.start_date) return t('projects.deadlineError')
  if (d.team_ids.length === 0) return t('projects.teamsError')
  return null
}

function ProjectForm({ initial, onSave, onCancel, editing }: { initial: Draft; onSave: (d: Draft) => Promise<void>; onCancel: () => void; editing: boolean }): React.JSX.Element {
  const { visibleTeams } = useApp()
  const [draft, setDraft] = useState<Draft>(initial)
  const [error, setError] = useState<string | null>(null)
  const toggle = (id: number): void => setDraft((d) => ({ ...d, team_ids: d.team_ids.includes(id) ? d.team_ids.filter((x) => x !== id) : [...d.team_ids, id].sort((a, b) => a - b) }))
  return (
    <form className="panel" onSubmit={(e) => { e.preventDefault(); const problem = validate(draft); if (problem) { setError(problem); return } setError(null); void onSave(draft).catch((err: Error) => setError(err.message)) }}>
      {error && <StatusMessage kind="error">{error}</StatusMessage>}
      <Field label={t('projects.name')}>{(id) => <input id={id} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />}</Field>
      <Field label={t('projects.start')}>{(id) => <input id={id} type="date" value={draft.start_date} onChange={(e) => setDraft({ ...draft, start_date: e.target.value })} />}</Field>
      <Field label={t('projects.deadline')}>{(id) => <input id={id} type="date" value={draft.deadline} onChange={(e) => setDraft({ ...draft, deadline: e.target.value })} />}</Field>
      <div className="field">
        <label>{t('projects.teams')}</label>
        {visibleTeams.map((tm) => <label key={tm.id}><input type="checkbox" checked={draft.team_ids.includes(tm.id)} onChange={() => toggle(tm.id)} /> {tm.name}</label>)}
      </div>
      <Field label={t('projects.type')}>{(id) => <select id={id} value={draft.type} onChange={(e) => setDraft({ ...draft, type: e.target.value })}><option value="delivery">delivery</option><option value="maintenance">maintenance</option><option value="internal">internal</option></select>}</Field>
      {editing && <Field label={t('projects.status')}>{(id) => <select id={id} value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value as Draft['status'] })}><option value="planned">planned</option><option value="active">active</option><option value="done">done</option></select>}</Field>}
      <button className="primary" type="submit">{t('projects.save')}</button>{' '}
      <button type="button" onClick={onCancel}>{t('projects.cancel')}</button>
    </form>
  )
}

export function Projects(): React.JSX.Element {
  const { me, meta } = useApp()
  const [projects, setProjects] = useState<Project[]>([])
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<Project | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = (): void => { getProjects().then(setProjects).catch((e: Error) => setError(e.message)) }
  useEffect(load, [])
  const teamName = new Map(meta?.teams.map((tm) => [tm.id, tm.name]) ?? [])
  const mine = projects.filter((p) => !me || p.department_id === me.department_id)
  return (
    <div>
      <h1>{t('projects.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      {!creating && !editing && <button className="primary" onClick={() => setCreating(true)}>{t('projects.new')}</button>}
      {creating && me && (
        <ProjectForm initial={empty} editing={false} onCancel={() => setCreating(false)}
          onSave={async (d) => { await createProject({ name: d.name.trim(), department_id: me.department_id, start_date: d.start_date, deadline: d.deadline, team_ids: d.team_ids, type: d.type }); setCreating(false); load() }} />
      )}
      {editing && (
        <ProjectForm initial={{ name: editing.name, start_date: editing.start_date, deadline: editing.deadline, team_ids: editing.team_ids, type: editing.type, status: editing.status as Draft['status'] }} editing onCancel={() => setEditing(null)}
          onSave={async (d) => { await updateProject(editing.id, { name: d.name.trim(), start_date: d.start_date, deadline: d.deadline, team_ids: d.team_ids, type: d.type, status: d.status }); setEditing(null); load() }} />
      )}
      <table>
        <thead><tr><th>{t('projects.name')}</th><th>{t('projects.start')}</th><th>{t('projects.deadline')}</th><th>{t('projects.teams')}</th><th>{t('projects.type')}</th><th>{t('projects.status')}</th><th></th></tr></thead>
        <tbody>
          {mine.map((p) => (
            <tr key={p.id}>
              <td>{p.name}</td><td>{p.start_date}</td><td>{p.deadline}</td><td>{p.team_ids.map((id) => teamName.get(id) ?? id).join(', ')}</td><td>{p.type}</td><td>{p.status}</td>
              <td><button onClick={() => { setCreating(false); setEditing(p) }}>{t('projects.edit')}</button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

`app/src/renderer/src/pages/Capacity.tsx`:

```tsx
import type React from 'react'
import { useEffect, useState } from 'react'
import type { Capacity as CapacityData } from '../../../shared/types'
import { deleteCapacityOverride, getCapacity, setCapacityDefault, setCapacityOverride } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { t } from '../i18n'

export function Capacity(): React.JSX.Element {
  const { meta, visibleTeams } = useApp()
  const [data, setData] = useState<CapacityData | null>(null)
  const [def, setDef] = useState('')
  const [form, setForm] = useState({ member_id: '', week_start: '', weekly_hours: '', reason: '' })
  const [error, setError] = useState<string | null>(null)
  const load = (): void => { getCapacity().then((d) => { setData(d); setDef(String(d.default_weekly_hours)) }).catch((e: Error) => setError(e.message)) }
  useEffect(load, [])
  const teamIds = new Set(visibleTeams.map((tm) => tm.id))
  const members = (meta?.members ?? []).filter((m) => m.team_id !== null && teamIds.has(m.team_id) && m.counted_in_workload)
  const nameOf = new Map(members.map((m) => [m.id, m.name]))
  const guard = (p: Promise<unknown>): void => { p.then(load).catch((e: Error) => setError(e.message)) }
  return (
    <div>
      <h1>{t('capacity.title')}</h1>
      {error && <StatusMessage kind="error">{t('common.error', { message: error })}</StatusMessage>}
      <section className="panel">
        <Field label={t('capacity.default')}>{(id) => <input id={id} type="number" min={1} max={80} step={0.5} value={def} onChange={(e) => setDef(e.target.value)} onBlur={() => { if (data && Number(def) !== data.default_weekly_hours && Number(def) > 0) guard(setCapacityDefault(Number(def))) }} />}</Field>
      </section>
      <section className="panel">
        <h2>{t('capacity.overrides')}</h2>
        <table>
          <thead><tr><th>{t('capacity.member')}</th><th>{t('member.week')}</th><th className="num">{t('capacity.hours')}</th><th>{t('capacity.reason')}</th><th></th></tr></thead>
          <tbody>
            {(data?.overrides ?? []).filter((o) => nameOf.has(o.member_id)).map((o) => (
              <tr key={o.id}><td>{nameOf.get(o.member_id)}</td><td>{o.week_start ?? t('capacity.permanent')}</td><td className="num">{o.weekly_hours.toFixed(1)}</td><td>{o.reason ?? ''}</td>
                <td><button onClick={() => guard(deleteCapacityOverride(o.id))}>{t('capacity.remove')}</button></td></tr>
            ))}
          </tbody>
        </table>
        <form onSubmit={(e) => { e.preventDefault(); if (!form.member_id || !(Number(form.weekly_hours) >= 0)) return; guard(setCapacityOverride({ member_id: Number(form.member_id), week_start: form.week_start || null, weekly_hours: Number(form.weekly_hours), reason: form.reason || null })); setForm({ member_id: '', week_start: '', weekly_hours: '', reason: '' }) }}>
          <Field label={t('capacity.member')}>{(id) => <select id={id} value={form.member_id} onChange={(e) => setForm({ ...form, member_id: e.target.value })}><option value="">–</option>{members.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}</select>}</Field>
          <Field label={t('capacity.week')}>{(id) => <input id={id} type="date" value={form.week_start} onChange={(e) => setForm({ ...form, week_start: e.target.value })} />}</Field>
          <Field label={t('capacity.hours')}>{(id) => <input id={id} type="number" min={0} max={80} step={0.5} value={form.weekly_hours} onChange={(e) => setForm({ ...form, weekly_hours: e.target.value })} />}</Field>
          <Field label={t('capacity.reason')}>{(id) => <input id={id} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} />}</Field>
          <button className="primary" type="submit">{t('capacity.add')}</button>
        </form>
      </section>
    </div>
  )
}
```

`app/src/renderer/src/pages/TimeOff.tsx`:

```tsx
import type React from 'react'
import { useEffect, useState } from 'react'
import type { Holiday, Vacation } from '../../../shared/types'
import { createVacation, deleteVacation, getHolidays, getVacations } from '../api'
import { Field } from '../components/Field'
import { StatusMessage } from '../components/StatusMessage'
import { useApp } from '../context'
import { t } from '../i18n'

export function TimeOff(): React.JSX.Element {
  const { meta, visibleTeams } = useApp()
  const [year, setYear] = useState(String(new Date().getFullYear()))
  const [holidays, setHolidays] = useState<Holiday[]>([])
  const [vacations, setVacations] = useState<Vacation[]>([])
  const [form, setForm] = useState({ member_id: '', start_date: '', end_date: '', type: 'vacation' })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { getHolidays(Number(year)).then(setHolidays).catch((e: Error) => setError(e.message)) }, [year])
  const loadVacations = (): void => { getVacations().then(setVacations).catch((e: Error) => setError(e.message)) }
  useEffect(loadVacations, [])
  const teamIds = new Set(visibleTeams.map((tm) => tm.id))
  const members = (meta?.members ?? []).filter((m) => m.team_id !== null && teamIds.has(m.team_id))
  const nameOf = new Map(members.map((m) => [m.id, m.name]))
  return (
    <div>
      <h1>{t('timeoff.title')}</h1>
      {error && <StatusMessage kind="error">{error}</StatusMessage>}
      <div className="grid-2">
        <section className="panel">
          <h2>{t('timeoff.holidays')}</h2>
          <Field label={t('timeoff.year')}>{(id) => <input id={id} type="number" value={year} onChange={(e) => setYear(e.target.value)} />}</Field>
          <table><tbody>{holidays.map((h) => <tr key={h.date}><td>{h.date}</td><td>{h.name}</td></tr>)}</tbody></table>
        </section>
        <section className="panel">
          <h2>{t('timeoff.vacations')}</h2>
          <table>
            <thead><tr><th>{t('timeoff.member')}</th><th>{t('timeoff.from')}</th><th>{t('timeoff.to')}</th><th>{t('timeoff.type')}</th><th></th></tr></thead>
            <tbody>
              {vacations.filter((v) => nameOf.has(v.member_id)).map((v) => (
                <tr key={v.id}><td>{nameOf.get(v.member_id)}</td><td>{v.start_date}</td><td>{v.end_date}</td><td>{v.type}</td>
                  <td><button onClick={() => { deleteVacation(v.id).then(loadVacations).catch((e: Error) => setError(e.message)) }}>{t('timeoff.remove')}</button></td></tr>
              ))}
            </tbody>
          </table>
          <form onSubmit={(e) => {
            e.preventDefault()
            if (!form.member_id || !form.start_date || !form.end_date) return
            if (form.end_date < form.start_date) { setError(t('timeoff.rangeError')); return }
            setError(null)
            createVacation({ member_id: Number(form.member_id), start_date: form.start_date, end_date: form.end_date, type: form.type })
              .then(() => { setForm({ member_id: '', start_date: '', end_date: '', type: 'vacation' }); loadVacations() })
              .catch((err: Error) => setError(err.message))
          }}>
            <Field label={t('timeoff.member')}>{(id) => <select id={id} value={form.member_id} onChange={(e) => setForm({ ...form, member_id: e.target.value })}><option value="">–</option>{members.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}</select>}</Field>
            <Field label={t('timeoff.from')}>{(id) => <input id={id} type="date" value={form.start_date} onChange={(e) => setForm({ ...form, start_date: e.target.value })} />}</Field>
            <Field label={t('timeoff.to')}>{(id) => <input id={id} type="date" value={form.end_date} onChange={(e) => setForm({ ...form, end_date: e.target.value })} />}</Field>
            <Field label={t('timeoff.type')}>{(id) => <select id={id} value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })}><option value="vacation">vacation</option><option value="sick">sick</option><option value="other">other</option></select>}</Field>
            <button className="primary" type="submit">{t('timeoff.add')}</button>
          </form>
        </section>
      </div>
    </div>
  )
}
```

Replace the last three placeholders in `app.tsx` and delete the `Placeholder` component.

- [ ] **Step 4: Gates and commit**

Run: `cd app && npm test && npm run lint && npm run typecheck && npm run build`
Expected: PASS

```bash
git add app/src/renderer
git commit -m "feat(app): projects, capacity and time off pages"
```

---

### Task 13: Documentation, spec deviations and a dev smoke script

**Files:**
- Create: `scripts/dev-app.ps1`
- Modify: `app/README.md`, `docs/superpowers/specs/2026-09-03-workload-forecast-design.md` (section 7), `CLAUDE.md`

- [ ] **Step 1: Dev helper**

`scripts/dev-app.ps1`:

```powershell
# Starts the desktop app in development mode. Requires uv (service) and Node 22 (app).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $root "service")
try { uv sync --quiet } finally { Pop-Location }
Push-Location (Join-Path $root "app")
try {
    if (-not (Test-Path "node_modules")) { npm install }
    npm run dev
} finally { Pop-Location }
```

- [ ] **Step 2: Spec deviation notes**

In section 7 of the spec, append two implementation notes:

- "(Implementation note, 2026-09-04: the renderer does not call the local API directly; it calls `window.whf.request` and the main process forwards the call with the token, so the token never enters the renderer and no CORS configuration is needed in the service.)"
- "(Implementation note, 2026-09-04: the Settings language switch stores `en` or `fr`; version 1 ships a complete English dictionary and a French dictionary for navigation and common labels that falls back to English; full French copy is a later task.)"

- [ ] **Step 3: README and CLAUDE.md**

Extend `app/README.md` with a "Screens" list (one line per page and its route) and a "Notifications" paragraph (due check at start and every 24 h; overload notification after a run; Windows toast requires the app user model id, set in `main/index.ts`). In `CLAUDE.md` "Layout", update the `app/` line to "Electron + React + TypeScript desktop app (`src/main`, `src/preload`, `src/renderer`, `src/shared`)" and the `scripts/` line to "PowerShell helpers (`dev-app.ps1`)".

- [ ] **Step 4: Full verification and commit**

Run: `cd service && uv run pytest -q -m "not slow"`; `cd app && npm test && npm run lint && npm run typecheck && npm run build`.
Expected: all green.

```bash
git add scripts/dev-app.ps1 app/README.md docs/superpowers/specs/2026-09-03-workload-forecast-design.md CLAUDE.md
git commit -m "docs(app): dev script, screens and notification docs, spec deviation notes"
```

---

## Self-review against the spec (sections 2, 7, 10)

- **Section 2**: main starts the service (Task 5/6), waits for `/health` (Task 5), opens the renderer (Task 6), keeps a tray alive (Task 7), shows Windows notifications (Task 7), renderer talks only to the local API (through the bridge, Task 6/8; deviation recorded in Task 13).
- **Section 7 screens**: Dashboard (Task 9), Run (Task 10), Team result (Task 10), Member detail (Task 11), Rebalancing (Task 11), Projects (Task 12, create and edit with start, deadline, teams), Capacity (Task 12, default, member and week overrides), Time off (Task 12, holidays and vacations), Runs (Task 9), Settings (Task 8: profile, Copilot sign-in, model, language; plus launch at login from section 9).
- **Roles**: `visibleTeams`/`canRun` in the context (Task 8), on-behalf notice in Run (Task 10), due check per role (Task 7).
- **Notifications**: due check at start and every 24 h, overload after a run (Task 7).
- **Section 10**: Vitest for main-process logic and renderer components in every task; Playwright deferred as the spec allows; Python additions have pytest coverage (Tasks 1 to 3).
- **Type consistency**: `ApiRequest/ApiResponse/Settings/AppState/WhfBridge` defined once in Task 4 and used by Tasks 5 to 8; `serviceCommand/parseHandshake/waitForHealth/ServiceProcess` names match between Task 5 tests and Task 6 `index.ts`; `registerIpc` deps in Task 6 gain `onRunCreated` in Task 7 (optional field, so Task 6 tests keep compiling); `WeekTableRow` (Task 10) is what `TeamResult` builds; fixtures `RUN_DETAIL`/`RUN_CREATED` (Task 10) are reused by Task 11 tests; `t()` keys used by pages exist in the Task 8 dictionary (`dashboard.*`, `team.*`, `member.*`, `rebalancing.*`, `projects.*`, `capacity.*`, `timeoff.*`, `runs.*`, `run.*`, `settings.*`, `common.*`).
- **Known follow-ups for plan 4**: `serviceCommand` packaged path `resources/service/whf/whf.exe`, `COPILOT_CLI_PATH` for the bundled CLI, the `--hidden` start argument for auto-start, installer icon from `resources/icon.png`.
