"""Localhost HTTP API used by the desktop application. Token protected, bound to 127.0.0.1."""

from __future__ import annotations

import datetime as dt
import secrets
import sqlite3
from collections.abc import Callable, Iterator
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, Field, model_validator

from whf import __version__
from whf.admin import add_project, add_vacation, set_capacity_default, set_capacity_override, set_profile
from whf.ai.session import Narrator, default_narrator
from whf.ai.status import CopilotStatus, copilot_status_sync
from whf.db.connection import connect
from whf.db.repo import read_df
from whf.narrate import RunHasNoFactsError, RunNotFoundError, narrate_run
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


class NarrativeRequest(BaseModel):
    model: str | None = None


class ProfileUpdate(BaseModel):
    member_id: int | None = None


def new_token() -> str:
    return secrets.token_urlsafe(32)


def create_app(
    db_path: Path | str,
    token: str,
    narrator_factory: Callable[[str | None], Narrator] | None = None,
    status_provider: Callable[[], CopilotStatus] | None = None,
) -> FastAPI:
    app = FastAPI(title="WorkloadHub AI Forecasting", version=__version__)
    narrator_factory = narrator_factory or default_narrator
    status_provider = status_provider or copilot_status_sync

    def require_token(x_whf_token: str | None = Header(default=None)) -> None:
        if x_whf_token is None:
            raise HTTPException(status_code=401, detail="missing or invalid token")
        try:
            valid = secrets.compare_digest(
                x_whf_token.encode("utf-8", "surrogateescape"), token.encode("utf-8", "surrogateescape")
            )
        except (UnicodeEncodeError, ValueError):
            raise HTTPException(status_code=401, detail="missing or invalid token") from None
        if not valid:
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
        return jsonable(
            {
                "departments": read_df(conn, "SELECT * FROM departments").to_dict(orient="records"),
                "teams": read_df(conn, "SELECT * FROM teams").to_dict(orient="records"),
                "members": read_df(
                    conn, "SELECT id, name, team_id, department_id, role, counted_in_workload FROM members"
                ).to_dict(orient="records"),
                "capacity_default": float(
                    read_df(conn, "SELECT weekly_hours FROM capacity_defaults WHERE id = 1")["weekly_hours"][0]
                ),
            }
        )

    @app.post("/runs", dependencies=guarded)
    def create_run(body: RunRequest, conn: sqlite3.Connection = Depends(db)) -> dict:
        try:
            result = run_forecast(conn, team_id=body.team_id, as_of=body.as_of, requested_by=body.requested_by)
        except ValueError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        return jsonable(
            {
                "run_id": result.run_id,
                "team_id": result.team_id,
                "as_of": result.as_of,
                "weeks": list(result.weeks),
                "champion": result.champion,
                "backtest_mase": result.backtest_mase,
                "forecasts": result.forecasts.to_dict(orient="records"),
            }
        )

    @app.get("/runs", dependencies=guarded)
    def get_runs(team_id: int | None = Query(default=None), conn: sqlite3.Connection = Depends(db)) -> list:
        return jsonable(list_runs(conn, team_id).to_dict(orient="records"))

    @app.get("/runs/{run_id}", dependencies=guarded)
    def get_run(run_id: int, conn: sqlite3.Connection = Depends(db)) -> dict:
        try:
            return jsonable(load_run(conn, run_id))
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get("/copilot/status", dependencies=guarded)
    def copilot_status_route() -> dict:
        status = status_provider()
        return {**status.__dict__, "ready": status.ready}

    @app.post("/runs/{run_id}/narrative", dependencies=guarded)
    def create_narrative(run_id: int, body: NarrativeRequest, conn: sqlite3.Connection = Depends(db)) -> dict:
        try:
            outcome = narrate_run(conn, run_id, narrator=narrator_factory(body.model))
        except RunNotFoundError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except RunHasNoFactsError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        return jsonable({**outcome.__dict__, "ai_status": outcome.ai_status, "run_id": run_id})

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
        project_id = add_project(
            conn,
            body.name,
            body.department_id,
            body.start_date,
            body.deadline,
            body.team_ids,
            body.type,
            body.created_by,
        )
        return {"id": project_id}

    @app.get("/capacity", dependencies=guarded)
    def get_capacity(conn: sqlite3.Connection = Depends(db)) -> dict:
        return jsonable(
            {
                "default_weekly_hours": float(
                    read_df(conn, "SELECT weekly_hours FROM capacity_defaults WHERE id = 1")["weekly_hours"][0]
                ),
                "overrides": read_df(conn, "SELECT * FROM capacity_overrides ORDER BY member_id, week_start").to_dict(
                    orient="records"
                ),
            }
        )

    @app.put("/capacity/default", dependencies=guarded)
    def put_capacity_default(body: CapacityDefault, conn: sqlite3.Connection = Depends(db)) -> dict:
        set_capacity_default(conn, body.weekly_hours)
        return {"default_weekly_hours": body.weekly_hours}

    @app.put("/capacity/overrides", dependencies=guarded)
    def put_capacity_override(body: CapacityOverride, conn: sqlite3.Connection = Depends(db)) -> dict:
        set_capacity_override(conn, body.member_id, body.weekly_hours, body.week_start, body.reason)
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
        vacation_id = add_vacation(conn, body.member_id, body.start_date, body.end_date, body.type)
        return {"id": vacation_id}

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

    return app
