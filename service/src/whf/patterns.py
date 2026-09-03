"""Deterministic per-member pattern statistics handed to Copilot as facts, plus clustering.

For each member, member_patterns() computes arrival-based statistics (tasks_13w, hours_13w,
hours_per_week_13w, trend_hours_per_week, weekday shares, style shares, hours_by_project,
share_with_project, deadline_proximity_corr) using the recent N-week window (default 13 weeks),
and completion-based statistics (estimate_ratio_median, cycle_days_median, cycle_days_by_type,
lateness_days_median, share_late) using the member's full completed task history, consistent
with the effort model.
"""

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
    "hours_per_week_13w",
    "trend_hours_per_week",
    "share_manual",
    "share_self_picked",
    "share_project",
    "estimate_ratio_median",
    "cycle_days_median",
    "share_late",
    "deadline_proximity_corr",
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
        .groupby("week")["hours"]
        .sum()
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
    cycle = pd.Series(
        [(c - a).days + 1 for a, c in zip(done["assigned_at"], done["completed_at"], strict=True)], dtype=float
    )
    lateness = pd.Series(
        [(c - d).days for c, d in zip(done["completed_at"], done["due_date"], strict=True) if d is not None],
        dtype=float,
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
        "hours_by_project": {str(int(p)): round(float(h) / total_project_hours, 3) for p, h in hours_by_project.items()}
        if total_project_hours
        else {},
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
