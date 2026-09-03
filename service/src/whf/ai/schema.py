"""The JSON contract Copilot must return, and its consistency checks against the run facts."""

from __future__ import annotations

import datetime as dt
import json
import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

RISK_LEVELS = ("low", "medium", "high")
PATTERN_KINDS = (
    "assignment_style",
    "weekday_rhythm",
    "trend",
    "estimate_bias",
    "cycle_time",
    "lateness",
    "project_phase",
    "cluster",
    "other",
)
_FENCE = re.compile(r"^\s*```(?:json)?\s*(.*?)\s*```\s*$", re.DOTALL)


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class PatternFinding(_Strict):
    kind: Literal[PATTERN_KINDS]
    statement: str = Field(min_length=1, max_length=400)
    evidence: str = Field(min_length=1, max_length=400)


class MemberNarrative(_Strict):
    member_id: int
    name: str
    risk_level: Literal[RISK_LEVELS]
    summary: str = Field(min_length=1, max_length=1200)
    patterns: list[PatternFinding] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class TeamRisk(_Strict):
    title: str = Field(min_length=1, max_length=120)
    detail: str = Field(min_length=1, max_length=800)
    severity: Literal[RISK_LEVELS]
    member_ids: list[int] = Field(default_factory=list)


class RebalancingMove(_Strict):
    from_member_id: int
    to_member_id: int
    week: dt.date
    hours: float = Field(gt=0)
    reason: str = Field(min_length=1, max_length=600)
    confidence: Literal[RISK_LEVELS]


class SuggestedAdjustment(_Strict):
    member_id: int
    week: dt.date
    delta_hours: float
    reason: str = Field(min_length=1, max_length=600)


class Narrative(_Strict):
    run_summary: str = Field(min_length=1, max_length=2000)
    members: list[MemberNarrative]
    team_risks: list[TeamRisk] = Field(default_factory=list)
    rebalancing: list[RebalancingMove] = Field(default_factory=list)
    suggested_adjustments: list[SuggestedAdjustment] = Field(default_factory=list)
    model_notes: str = Field(default="", max_length=1000)

    def validate_against_facts(self, facts: dict) -> list[str]:
        """Cross-check ids and weeks against the facts; returns human-readable problems."""
        problems: list[str] = []
        known = {int(m["id"]) for m in facts.get("members", [])}
        weeks = {dt.date.fromisoformat(str(w)[:10]) for w in facts.get("run", {}).get("weeks", [])}
        seen: list[int] = []
        for m in self.members:
            if m.member_id not in known:
                problems.append(f"member_id {m.member_id} is not a member of this team")
            seen.append(m.member_id)
        for mid in sorted(known - set(seen)):
            problems.append(f"member {mid} is missing from members")
        for mid in {x for x in seen if seen.count(x) > 1}:
            problems.append(f"member {mid} appears more than once")
        for r in self.team_risks:
            for mid in r.member_ids:
                if mid not in known:
                    problems.append(f"team risk '{r.title}' names unknown member {mid}")
        for mv in self.rebalancing:
            if mv.from_member_id == mv.to_member_id:
                problems.append("a rebalancing move names the same member as source and target")
            for mid in (mv.from_member_id, mv.to_member_id):
                if mid not in known:
                    problems.append(f"rebalancing move names unknown member {mid}")
            if mv.week not in weeks:
                problems.append(f"rebalancing week {mv.week.isoformat()} is not a forecast week")
        for adj in self.suggested_adjustments:
            if adj.member_id not in known:
                problems.append(f"adjustment names unknown member {adj.member_id}")
            if adj.week not in weeks:
                problems.append(f"adjustment week {adj.week.isoformat()} is not a forecast week")
        return problems


def parse_narrative(text: str) -> Narrative:
    body = text.strip()
    match = _FENCE.match(body)
    if match:
        body = match.group(1)
    start, end = body.find("{"), body.rfind("}")
    if start == -1 or end == -1 or end <= start:
        raise ValueError("the answer is not valid JSON: no JSON object found")
    try:
        data = json.loads(body[start : end + 1])
    except json.JSONDecodeError as exc:
        raise ValueError(f"the answer is not valid JSON: {exc.msg} at position {exc.pos}") from exc
    try:
        return Narrative.model_validate(data)
    except ValidationError as exc:
        lines = [f"{'.'.join(str(p) for p in e['loc'])}: {e['msg']}" for e in exc.errors()]
        raise ValueError("the answer does not match the schema: " + "; ".join(lines)) from exc
