"""What Copilot is told: the rules, the task, the JSON contract, and where the product skills live."""

from __future__ import annotations

import json
from importlib import resources
from pathlib import Path

from whf.ai.schema import Narrative

PRODUCT_SKILLS = (
    "whf-domain",
    "whf-pattern-discovery",
    "whf-forecast-interpretation",
    "whf-rebalancing-advice",
    "whf-report-style",
)

SYSTEM_PROMPT = """You are the workload analyst inside WorkloadHub AI Forecasting. A deterministic engine has
already computed every number: demand, capacity, overload, intervals, pattern statistics and model quality.
Your job is to read those facts through the tools and explain them to a team leader.

Hard rules:
1. Never invent, estimate or recompute a number. Every figure you write must come from a tool result,
   copied exactly as given (hours with one decimal, for example 12.5). If a number is not in the tools, do not write it.
2. Use the tools. Start with get_run_overview, then query every member listed there, then the project timelines.
   Do not answer before you have looked at every member.
3. Answer with one JSON document that matches the contract in the user message. No prose before or after it,
   no Markdown fences. Field names and enumerations must match exactly.
4. Patterns need evidence: quote the statistic (name and value) that supports each statement.
5. Rebalancing moves go from a member with overload to a member with spare capacity in the same week,
   respect the target's capacity, and give the hours moved and the reason.
6. Write in the language given in the facts (default English), plainly, for a busy team leader.
"""


def _contract_text() -> str:
    schema = Narrative.model_json_schema()
    return json.dumps(schema, indent=1)


def build_user_prompt(facts: dict) -> str:
    run = facts["run"]
    team = facts["team"]
    members = ", ".join(f"member_id {m['id']} ({m['name']}, {m.get('role', 'member')})" for m in facts["members"])
    weeks = ", ".join(str(w) for w in run["weeks"])
    language = run.get("language", "en")
    return (
        f"Analyse forecast run {run['id']} for team '{team['name']}' (team id {team['id']}), "
        f"run date {run['as_of']}, forecast weeks {weeks}. Language: {language}.\n"
        f"Members to cover, each exactly once: {members}.\n\n"
        "Procedure: 1) get_run_overview; 2) for each member: get_member_forecast, get_member_capacity, "
        "get_member_patterns, get_member_history, get_member_open_tasks; 3) get_project_timelines; "
        "4) get_rebalancing_candidates; 5) write the JSON document.\n\n"
        "Contract (JSON Schema):\n" + _contract_text() + "\n\n"
        "Return only the JSON document."
    )


def build_retry_prompt(problems: list[str]) -> str:
    bullets = "\n".join(f"- {p}" for p in problems)
    return (
        "Your previous answer was rejected for these reasons:\n"
        f"{bullets}\n\n"
        "Fix every point and return only the JSON document, with no text around it."
    )


def skills_root() -> Path:
    return Path(str(resources.files("whf.ai").joinpath("skills")))


def skill_directories() -> list[str]:
    root = skills_root()
    return [str(root / name) for name in sorted(PRODUCT_SKILLS)]
