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
                loss="squared_error",
                categorical_features="from_dtype",
                max_iter=200,
                learning_rate=0.05,
                random_state=0,
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
