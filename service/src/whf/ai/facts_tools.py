"""Read-only tools that let Copilot look up facts of one run. Pure functions first, Tool wrappers second."""

from __future__ import annotations

from typing import Any

from copilot import define_tool
from pydantic import BaseModel, Field

TOOL_NAMES = (
    "get_run_overview",
    "get_member_history",
    "get_member_forecast",
    "get_member_patterns",
    "get_member_open_tasks",
    "get_member_capacity",
    "get_project_timelines",
    "get_rebalancing_candidates",
)


class NoParams(BaseModel):
    """No parameters."""


class MemberParams(BaseModel):
    member_id: int = Field(description="The member id from get_run_overview")


class FactsToolbox:
    def __init__(self, facts: dict) -> None:
        self.facts = facts
        self._members = {int(m["id"]): m for m in facts.get("members", [])}

    # ----- pure lookups -------------------------------------------------
    def _unknown(self, member_id: int) -> dict:
        return {"error": f"unknown member_id {member_id}", "known_member_ids": sorted(self._members)}

    def run_overview(self) -> dict:
        f = self.facts
        return {
            "run": f["run"],
            "team": f["team"],
            "members": [{"id": m["id"], "name": m["name"], "role": m["role"]} for m in f["members"]],
            "model": f["model"],
            "rebalancing_candidates": f["rebalancing_candidates"],
            "how_to_proceed": "Call get_member_forecast, get_member_patterns, get_member_history, "
            "get_member_open_tasks and get_member_capacity for every member id listed here, "
            "then get_project_timelines, then answer with the JSON document.",
        }

    def member_history(self, member_id: int) -> dict:
        m = self._members.get(member_id)
        if m is None:
            return self._unknown(member_id)
        return {"member_id": member_id, "name": m["name"], "history_13w": m["history_13w"]}

    def member_forecast(self, member_id: int) -> dict:
        m = self._members.get(member_id)
        if m is None:
            return self._unknown(member_id)
        return {"member_id": member_id, "name": m["name"], "forecast": m["forecast"]}

    def member_patterns(self, member_id: int) -> dict:
        m = self._members.get(member_id)
        if m is None:
            return self._unknown(member_id)
        return {"member_id": member_id, "name": m["name"], "patterns": m["patterns"]}

    def member_open_tasks(self, member_id: int) -> dict:
        m = self._members.get(member_id)
        if m is None:
            return self._unknown(member_id)
        return {"member_id": member_id, "name": m["name"], "open_tasks": m["open_tasks"]}

    def member_capacity(self, member_id: int) -> dict:
        m = self._members.get(member_id)
        if m is None:
            return self._unknown(member_id)
        weeks = [
            {"week": row["week"], "capacity": row["capacity"], "demand": row["demand"], "overload": row["overload"]}
            for row in m["forecast"]
        ]
        return {"member_id": member_id, "name": m["name"], "weeks": weeks}

    def project_timelines(self) -> dict:
        return {"weeks": self.facts["run"]["weeks"], "projects": self.facts["projects"]}

    def rebalancing_candidates(self) -> dict:
        return dict(self.facts["rebalancing_candidates"])

    # ----- Copilot tools ------------------------------------------------
    def tools(self) -> list[Any]:
        box = self

        def member_tool(name: str, description: str, fn):
            return define_tool(
                name,
                description=description,
                handler=lambda params, _inv: fn(params.member_id),
                params_type=MemberParams,
                skip_permission=True,
            )

        def plain_tool(name: str, description: str, fn):
            return define_tool(
                name,
                description=description,
                handler=lambda _params, _inv: fn(),
                params_type=NoParams,
                skip_permission=True,
            )

        return [
            plain_tool(
                "get_run_overview",
                "Run, team, member list, model quality and rebalancing candidates. Call this first.",
                box.run_overview,
            ),
            member_tool(
                "get_member_history",
                "Last 13 weeks of task arrivals (hours and counts) for one member.",
                box.member_history,
            ),
            member_tool(
                "get_member_forecast",
                "Two-week forecast rows (demand, low, high, capacity, overload, open and new hours) for one member.",
                box.member_forecast,
            ),
            member_tool(
                "get_member_patterns",
                "Deterministic pattern statistics for one member (assignment style, weekday rhythm, trend, estimate bias, cycle time, lateness, cluster).",
                box.member_patterns,
            ),
            member_tool(
                "get_member_open_tasks",
                "Open tasks of one member with due dates and overdue flags.",
                box.member_open_tasks,
            ),
            member_tool(
                "get_member_capacity",
                "Capacity, demand and overload per forecast week for one member.",
                box.member_capacity,
            ),
            plain_tool(
                "get_project_timelines",
                "Projects of the team with start dates, deadlines and whether they start, end or run inside the forecast window.",
                box.project_timelines,
            ),
            plain_tool(
                "get_rebalancing_candidates",
                "Members with overload and members with spare capacity over the two weeks.",
                box.rebalancing_candidates,
            ),
        ]
