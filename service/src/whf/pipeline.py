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
        "members": members,
        "teams": teams,
        "tasks": tasks,
        "projects": projects,
        "project_teams": project_teams,
        "holidays": holidays,
        "vacations": vacations,
        "overrides": overrides,
        "default": default,
    }


def _capacity_rows(
    frames: dict, member_ids: list[int], weeks: tuple[dt.date, dt.date], off_by_member: dict
) -> dict[tuple[int, dt.date], float]:
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
    for m, s, e in zip(
        frames["vacations"]["member_id"],
        frames["vacations"]["start_date"],
        frames["vacations"]["end_date"],
        strict=True,
    ):
        vacation_days.setdefault(int(m), set()).update(days_in_ranges([(s, e)]))
    off_by_member = {int(m): holidays | vacation_days.get(int(m), set()) for m in counted["id"]}

    # arrival model on every counted member in the database (global model), forecast for the team
    first_week = min(tasks["assigned_at"])
    weeks = weeks_between(first_week, origin)
    arrivals = weekly_arrivals(tasks, [int(m) for m in counted["id"]], weeks)
    feat = build_feature_matrix(
        arrivals, tasks, frames["projects"], frames["project_teams"], members, holidays, vacation_days
    )
    origins = [
        o for o in default_origins(origin, BACKTEST_ORIGINS) if o >= weeks[0] + MIN_WEEKS_BEFORE_ORIGIN * ONE_WEEK
    ]
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
    open_placed = place_open_tasks(open_tasks, effort, as_of, off_by_member, placement_start=f1)
    team_of = {int(m): int(t) for m, t in zip(members["id"], members["team_id"].fillna(0), strict=True)}
    new_placed = place_new_arrivals(predicted[["member_id", "week_start", "est_hours"]], effort, off_by_member, team_of)
    capacity = _capacity_rows(frames, member_ids, (f1, f2), off_by_member)
    bounds = {h: interval_bounds(backtest.residuals.get((champion, h), np.array([]))) for h in horizons}

    rows = []
    for m in member_ids:
        for week, h in zip((f1, f2), horizons, strict=True):
            open_hours = (
                float(open_placed[(open_placed.member_id == m) & (open_placed.week_start == week)]["hours"].sum())
                if len(open_placed)
                else 0.0
            )
            new_hours = (
                float(new_placed[(new_placed.member_id == m) & (new_placed.week_start == week)]["hours"].sum())
                if len(new_placed)
                else 0.0
            )
            open_hours, new_hours = round(open_hours, 2), round(new_hours, 2)
            demand = round(open_hours + new_hours, 2)
            low, high = bounds[h]
            cap = capacity[(m, week)]
            rows.append(
                {
                    "member_id": m,
                    "week_start": week,
                    "demand_hours": demand,
                    "demand_low": round(max(0.0, demand + low), 2),
                    "demand_high": round(demand + high, 2),
                    "capacity_hours": cap,
                    "overload_hours": round(overload_hours(demand, cap), 2),
                    "open_task_hours": open_hours,
                    "new_task_hours": new_hours,
                }
            )
    forecasts = pd.DataFrame(rows)

    # patterns and facts
    spans_by_team = {
        int(t): list(zip(g["start_date"], g["deadline"], strict=True))
        for t, g in frames["project_teams"]
        .merge(frames["projects"], left_on="project_id", right_on="id")
        .groupby("team_id")
    }
    patterns = pattern_table(tasks, team_members, spans_by_team, as_of)
    clusters = cluster_members(patterns)
    facts = _build_facts(
        team_id,
        as_of,
        (f1, f2),
        frames,
        team_members,
        tasks,
        arrivals,
        forecasts,
        patterns,
        clusters,
        champion,
        champion_mase,
        backtest.scores,
        origins,
        horizons,
    )

    # persist: one run, its forecasts and its facts, in a single transaction
    try:
        cur = conn.execute(
            "INSERT INTO runs (team_id, as_of, requested_by, status, champion_model, backtest_mase, started_at, finished_at, ai_status)"
            " VALUES (?, ?, ?, 'done', ?, ?, ?, ?, 'not_requested')",
            (
                team_id,
                as_of.isoformat(),
                requested_by,
                champion,
                None if math.isnan(champion_mase) else champion_mase,
                started.isoformat(timespec="seconds"),
                dt.datetime.now().isoformat(timespec="seconds"),
            ),
        )
        run_id = int(cur.lastrowid)
        facts["run"]["id"] = run_id
        facts_json = json.dumps(jsonable(facts))
        insert_rows(conn, "forecasts", [{"run_id": run_id, **r} for r in rows], commit=False)
        conn.execute("INSERT INTO run_facts (run_id, json) VALUES (?, ?)", (run_id, facts_json))
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    return RunResult(run_id, team_id, as_of, (f1, f2), champion, champion_mase, forecasts, facts, backtest.scores)


def _build_facts(
    team_id,
    as_of,
    weeks,
    frames,
    team_members,
    tasks,
    arrivals,
    forecasts,
    patterns,
    clusters,
    champion,
    champion_mase,
    scores,
    origins,
    horizons,
) -> dict:
    f1, f2 = weeks
    team = frames["teams"][frames["teams"]["id"] == team_id].iloc[0]
    origin = last_complete_week(as_of)
    history_weeks = [origin - k * ONE_WEEK for k in range(HISTORY_WEEKS_IN_FACTS - 1, -1, -1)]
    members_facts = []
    for _, m in team_members.iterrows():
        mid = int(m["id"])
        hist = arrivals[(arrivals.member_id == mid) & (arrivals.week_start.isin(history_weeks))].sort_values(
            "week_start"
        )
        fc = forecasts[forecasts.member_id == mid].sort_values("week_start")
        mine_open = tasks[(tasks["assignee_id"] == mid) & (tasks["completed_at"].isna())]
        pattern = patterns[patterns.member_id == mid].iloc[0].to_dict()
        pattern["cluster"] = int(clusters.get(mid, 0))
        members_facts.append(
            {
                "id": mid,
                "name": m["name"],
                "role": m["role"],
                "history_13w": [
                    {"week": w, "hours": round(float(h), 1), "tasks": int(n)}
                    for w, h, n in zip(hist.week_start, hist.est_hours, hist.n_tasks, strict=True)
                ],
                "forecast": [
                    {
                        "week": r.week_start,
                        "demand": r.demand_hours,
                        "low": r.demand_low,
                        "high": r.demand_high,
                        "capacity": r.capacity_hours,
                        "overload": r.overload_hours,
                        "open_hours": r.open_task_hours,
                        "new_hours": r.new_task_hours,
                    }
                    for r in fc.itertuples()
                ],
                "patterns": pattern,
                "open_tasks": [
                    {
                        "id": int(t.id),
                        "title": t.title,
                        "type": t.type,
                        "priority": t.priority,
                        "estimated_hours": float(t.estimated_hours),
                        "due_date": t.due_date,
                        "overdue": bool(t.due_date is not None and t.due_date < as_of),
                        "project_id": None if pd.isna(t.project_id) else int(t.project_id),
                    }
                    for t in mine_open.itertuples()
                ],
            }
        )
    pt = frames["project_teams"][frames["project_teams"]["team_id"] == team_id]
    projects = frames["projects"][frames["projects"]["id"].isin(pt["project_id"])]
    window_end = f2 + dt.timedelta(days=6)
    projects_facts = [
        {
            "id": int(p.id),
            "name": p.name,
            "start_date": p.start_date,
            "deadline": p.deadline,
            "status": p.status,
            "type": p.type,
            "active_in_window": bool(p.start_date <= window_end and p.deadline >= f1),
            "starting_in_window": bool(f1 <= p.start_date <= window_end),
            "ending_in_window": bool(f1 <= p.deadline <= window_end),
        }
        for p in projects.itertuples()
    ]
    totals = forecasts.groupby("member_id")[["demand_hours", "capacity_hours", "overload_hours"]].sum()
    name_of = dict(zip(team_members["id"], team_members["name"], strict=True))
    overloaded = [
        {"member_id": int(i), "name": name_of[int(i)], "overload_hours": round(float(r.overload_hours), 1)}
        for i, r in totals.iterrows()
        if r.overload_hours > OVERLOAD_THRESHOLD
    ]
    underloaded = [
        {
            "member_id": int(i),
            "name": name_of[int(i)],
            "spare_hours": round(float(r.capacity_hours - r.demand_hours), 1),
        }
        for i, r in totals.iterrows()
        if r.capacity_hours > 0 and r.demand_hours < UNDERLOAD_RATIO * r.capacity_hours
    ]
    mase_by_model = scores.groupby("model")["mase"].mean().to_dict() if len(scores) else {}
    return {
        "run": {"id": None, "as_of": as_of, "weeks": [f1, f2], "generated_at": dt.datetime.now()},
        "team": {
            "id": int(team_id),
            "name": team["name"],
            "department_id": int(team["department_id"]),
            "team_leader_id": None if pd.isna(team["team_leader_id"]) else int(team["team_leader_id"]),
            "totals": [
                {
                    "week": w,
                    "demand": round(float(forecasts[forecasts.week_start == w].demand_hours.sum()), 1),
                    "capacity": round(float(forecasts[forecasts.week_start == w].capacity_hours.sum()), 1),
                }
                for w in (f1, f2)
            ],
        },
        "members": members_facts,
        "projects": projects_facts,
        "model": {
            "champion": champion,
            "champion_mase": champion_mase,
            "mase_by_model": mase_by_model,
            "backtest_origins": origins,
            "horizons": list(horizons),
            "limitations": (
                "arrivals during the current partial week are not modelled; "
                "open tasks are placed from the first forecast week"
            ),
        },
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
