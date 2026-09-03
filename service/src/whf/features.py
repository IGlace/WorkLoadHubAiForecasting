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
        t.groupby(["assignee_id", "week_start", "assignment_mode"])
        .size()
        .unstack(fill_value=0)
        .reindex(columns=list(MODES), fill_value=0)
        .reset_index()
        .rename(columns={"assignee_id": "member_id"})
    )
    base = arrivals[["member_id", "week_start"]].merge(counts, on=["member_id", "week_start"], how="left")
    base[list(MODES)] = base[list(MODES)].fillna(0)
    base = base.sort_values(["member_id", "week_start"])
    rolled = base.groupby("member_id")[list(MODES)].transform(lambda s: s.rolling(STYLE_WINDOW, min_periods=1).sum())
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
        (w - ls).days / 7 if isinstance(ls, dt.date) else 52.0
        for w, ls in zip(feat["week_start"], last_seen, strict=True)
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
