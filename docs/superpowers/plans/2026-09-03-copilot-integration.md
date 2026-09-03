# Copilot Integration and Product Skills Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a stored forecast run an AI narrative from the user's own GitHub Copilot seat: discovered patterns, per-member summaries, risks, overload warnings and rebalancing suggestions, produced through the Copilot SDK with read-only tools over the run's facts, validated as strict JSON, cross-checked so no number is invented, persisted with the run, and exposed through the CLI and the local API.

**Architecture:** A new `whf.ai` package: `schema.py` (Pydantic output contract and facts-aware validation), `facts_tools.py` (pure functions over the facts dict wrapped as Copilot tools), `prompt.py` (system prompt, user prompt, JSON contract), `verify.py` (number cross-check), `status.py` (CLI location, sign-in status, login command), `session.py` (the `CopilotNarrator` that runs one Copilot session per run with an injectable client factory so tests never need Copilot), and five product skills shipped inside the package. `whf/narrate.py` persists the outcome into `run_narratives` and `runs.ai_status`. The CLI gains `copilot status`, `copilot login`, `narrate` and `run --ai`; the API gains `GET /copilot/status` and `POST /runs/{id}/narrative`. The numeric pipeline is untouched.

**Tech Stack:** Python 3.11+, `github-copilot-sdk>=1.0.11` (package `copilot`; keyword-based `CopilotClient`, `create_session`, `send_and_wait`, `define_tool`, `ToolSet`), pydantic 2, existing service stack (FastAPI, Typer, SQLite), pytest with fakes; one opt-in live test.

**Spec:** `docs/superpowers/specs/2026-09-03-workload-forecast-design.md` section 6 (Copilot integration) and the `whf copilot status` command of section 8.

**Verified SDK facts (installed package 1.0.11, inspected on 2026-09-03):** `CopilotClient(log_level=..., github_token=..., use_logged_in_user=..., env=...)`; `await client.start()`; `await client.get_auth_status()` returns an object with `isAuthenticated: bool`, `login: str | None`, `statusMessage: str | None`; `await client.list_models()`; `await client.stop()`. `await client.create_session(model=None, tools=[...], system_message={"mode": "replace", "content": "..."}, available_tools=ToolSet().add_custom("*"), skill_directories=[...], streaming=False, on_event=callable)` returns `CopilotSession`; `await session.send_and_wait(prompt, timeout=seconds)` returns a `SessionEvent` whose `.type` is `SessionEventType.ASSISTANT_MESSAGE` and `.data.content` is the text, or `None`; `session.on(handler)`; `await session.disconnect()`. `define_tool(name, description=..., handler=fn(params, invocation), params_type=PydanticModel, skip_permission=True)` returns a `Tool`. Event enum members used: `ASSISTANT_MESSAGE`, `SESSION_IDLE`, `SESSION_ERROR`, `TOOL_EXECUTION_START`, `ASSISTANT_USAGE`. The main session has no structured-output mode; JSON is enforced by prompt and validated by Pydantic. The CLI binary is resolved as explicit path > `COPILOT_CLI_PATH` > cached download at `%LOCALAPPDATA%\github-copilot-sdk\cli\<version>\copilot.exe` (Windows) or `~/.cache/github-copilot-sdk/cli/<version>/copilot`; `copilot._cli_download.get_cached_cli_path()` returns the cached path or `None`; `COPILOT_SKIP_CLI_DOWNLOAD=1` disables downloading.

**Validation:** on 2026-09-03 the code of Tasks 1 to 7 was assembled onto a copy of the service and its tests run against the installed SDK 1.0.11 with fakes: 35 tests passed, ruff clean. Task 8 (CLI/API edits) and the live test were not executed. Executors still follow the red-green steps.

## Global Constraints

- Everything runs on Windows in PowerShell and on Linux; paths through `pathlib`; no shell-specific code.
- The language model never produces a forecast number: tools return facts; the narrative must cite them; every number in the narrative is cross-checked and unmatched numbers mark the narrative `unverified`.
- Copilot access only through the GitHub Copilot SDK/CLI with the user's login; no API keys of other providers; `use_logged_in_user` stays at its default.
- Real names are allowed in prompts (owner decision). Everything sent to Copilot is exactly the facts already stored in `run_facts` for that run.
- A run's narrative is stored in `run_narratives` as one JSON document; `runs.ai_status` is one of `not_requested`, `ok`, `unverified`, `failed:<reason>` where `<reason>` is one of `not_signed_in`, `cli_unavailable`, `timeout`, `invalid_output`, `model_error`, `other`.
- Copilot tools are read-only and only the custom tools are available (`available_tools=ToolSet().add_custom("*")`); no shell, file or web tools.
- Tests never contact Copilot except the single opt-in test guarded by `WHF_COPILOT_LIVE=1`.
- `uv run pytest -q -m "not slow"` from `service/`, `uv run ruff check .` and `uv run ruff format --check .` pass before each commit (run `uv run ruff check --fix . && uv run ruff format .` before checking; the `pythonpath = ["tests"]` pytest option makes `ai_fakes` importable); TDD for every task.
- Product skills live in `service/src/whf/ai/skills/<name>/SKILL.md` (single source of truth, shipped with the package); the spec's `.claude/skills/whf-*` location is superseded (Task 9 records the deviation).

---

## File structure

```
service/src/whf/ai/__init__.py
service/src/whf/ai/schema.py         output contract (Narrative and parts) + validate_against_facts
service/src/whf/ai/facts_tools.py    FactsToolbox: pure lookups over facts + Copilot Tool objects
service/src/whf/ai/prompt.py         SYSTEM_PROMPT, build_user_prompt, retry prompt
service/src/whf/ai/verify.py         number extraction and cross-check -> VerificationReport
service/src/whf/ai/status.py         resolve_cli_path, copilot_status (async + sync), login_command
service/src/whf/ai/session.py        NarrativeOutcome, CopilotNarrator, FakeNarrator helpers live in tests
service/src/whf/ai/skills/whf-domain/SKILL.md
service/src/whf/ai/skills/whf-pattern-discovery/SKILL.md
service/src/whf/ai/skills/whf-forecast-interpretation/SKILL.md
service/src/whf/ai/skills/whf-rebalancing-advice/SKILL.md
service/src/whf/ai/skills/whf-report-style/SKILL.md
service/src/whf/narrate.py           narrate_run(conn, run_id, narrator) -> persists, updates ai_status
service/src/whf/cli.py               (modify) copilot status|login, narrate, run --ai
service/src/whf/api.py               (modify) GET /copilot/status, POST /runs/{id}/narrative
service/pyproject.toml               (modify) dependency, pytest marker "copilot"
service/tests/test_ai_schema.py
service/tests/test_ai_tools.py
service/tests/test_ai_verify.py
service/tests/test_ai_prompt_skills.py
service/tests/test_ai_session.py
service/tests/test_ai_status.py
service/tests/test_narrate.py
service/tests/test_ai_cli_api.py
service/tests/test_ai_live.py        opt-in
service/tests/ai_fakes.py            shared fake client/session/narrator (imported by tests)
docs/superpowers/specs/2026-09-03-workload-forecast-design.md (modify: deviation note)
service/README.md, CLAUDE.md          (modify)
```

---

### Task 1: Output contract

**Files:**
- Create: `service/src/whf/ai/__init__.py`, `service/src/whf/ai/schema.py`, `service/tests/test_ai_schema.py`
- Modify: `service/pyproject.toml` (add dependency `github-copilot-sdk>=1.0.11`; add marker `copilot`)

**Interfaces:**
- Produces (in `whf.ai.schema`): `PatternFinding`, `MemberNarrative`, `TeamRisk`, `RebalancingMove`, `SuggestedAdjustment`, `Narrative` (pydantic models, `extra="forbid"`), `Narrative.validate_against_facts(facts: dict) -> list[str]` (empty list when consistent), `parse_narrative(text: str) -> Narrative` (strips Markdown code fences, raises `ValueError` with a readable message on invalid JSON or schema), `RISK_LEVELS = ("low", "medium", "high")`.

- [ ] **Step 1: Add the dependency and marker**

In `service/pyproject.toml` add `"github-copilot-sdk>=1.0.11",` to `dependencies` and change the markers line to:

```toml
markers = ["slow: long-running accuracy gates", "copilot: needs a signed-in Copilot CLI (WHF_COPILOT_LIVE=1)"]
pythonpath = ["tests"]
```

Run `uv sync` in `service/` and confirm `uv run python -c "import copilot; print(copilot.__name__)"` prints `copilot`.

- [ ] **Step 2: Write the failing tests**

`service/tests/test_ai_schema.py`:

```python
import datetime as dt
import json

import pytest

from whf.ai.schema import Narrative, parse_narrative

FACTS = {
    "run": {"id": 7, "as_of": "2026-09-03", "weeks": ["2026-09-07", "2026-09-14"]},
    "team": {"id": 1, "name": "Web Platform"},
    "members": [{"id": 4, "name": "Sara Tazi"}, {"id": 5, "name": "Omar Benali"}],
}


def _good() -> dict:
    return {
        "run_summary": "Two members, one overloaded in week one.",
        "members": [
            {
                "member_id": 4, "name": "Sara Tazi", "risk_level": "high",
                "summary": "Sara has 52.0 h of demand against 40.0 h of capacity in the week of 2026-09-07.",
                "patterns": [{"kind": "assignment_style", "statement": "Mostly project-driven work.", "evidence": "share_project 0.6"}],
                "warnings": ["Overload of 12.0 h in the week of 2026-09-07."],
            },
            {"member_id": 5, "name": "Omar Benali", "risk_level": "low", "summary": "Spare capacity.", "patterns": [], "warnings": []},
        ],
        "team_risks": [{"title": "Week one overload", "detail": "One member above capacity.", "severity": "medium", "member_ids": [4]}],
        "rebalancing": [{"from_member_id": 4, "to_member_id": 5, "week": "2026-09-07", "hours": 8.0, "reason": "Omar has 20.0 h spare.", "confidence": "medium"}],
        "suggested_adjustments": [],
        "model_notes": "Champion gbm, MASE 0.9.",
    }


def test_parse_accepts_plain_json_and_fenced_json() -> None:
    n = parse_narrative(json.dumps(_good()))
    assert n.members[0].risk_level == "high"
    fenced = "```json\n" + json.dumps(_good()) + "\n```"
    assert parse_narrative(fenced).rebalancing[0].week == dt.date(2026, 9, 7)


def test_parse_rejects_invalid_json_and_unknown_fields() -> None:
    with pytest.raises(ValueError, match="not valid JSON"):
        parse_narrative("Here is my analysis: {")
    bad = _good()
    bad["extra_field"] = 1
    with pytest.raises(ValueError, match="extra_field"):
        parse_narrative(json.dumps(bad))


def test_validate_against_facts_flags_unknown_members_and_weeks() -> None:
    n = parse_narrative(json.dumps(_good()))
    assert n.validate_against_facts(FACTS) == []
    bad = _good()
    bad["members"][1]["member_id"] = 99
    bad["rebalancing"][0]["week"] = "2026-09-28"
    problems = Narrative.model_validate(bad).validate_against_facts(FACTS)
    assert any("99" in p for p in problems) and any("2026-09-28" in p for p in problems)


def test_validate_requires_every_team_member_once() -> None:
    only_one = _good()
    only_one["members"] = only_one["members"][:1]
    problems = Narrative.model_validate(only_one).validate_against_facts(FACTS)
    assert any("missing" in p and "5" in p for p in problems)


def test_rebalancing_hours_must_be_positive_and_members_distinct() -> None:
    bad = _good()
    bad["rebalancing"][0]["hours"] = 0
    with pytest.raises(ValueError):
        Narrative.model_validate(bad)
    same = _good()
    same["rebalancing"][0]["to_member_id"] = 4
    assert any("same member" in p for p in Narrative.model_validate(same).validate_against_facts(FACTS))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `uv run pytest tests/test_ai_schema.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.ai'`

- [ ] **Step 4: Write the implementation**

`service/src/whf/ai/__init__.py`:

```python
"""Copilot narrative layer: tools over facts, prompt, session, verification, persistence."""
```

`service/src/whf/ai/schema.py`:

```python
"""The JSON contract Copilot must return, and its consistency checks against the run facts."""

from __future__ import annotations

import datetime as dt
import json
import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

RISK_LEVELS = ("low", "medium", "high")
PATTERN_KINDS = (
    "assignment_style", "weekday_rhythm", "trend", "estimate_bias", "cycle_time",
    "lateness", "project_phase", "cluster", "other",
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
```

- [ ] **Step 5: Run tests, lint, commit**

Run: `uv run pytest tests/test_ai_schema.py -v` → PASS (5 tests). Then `uv run ruff check --fix . && uv run ruff format .`

```bash
git add service/pyproject.toml service/uv.lock service/src/whf/ai service/tests/test_ai_schema.py
git commit -m "feat(ai): narrative output contract with facts consistency checks"
```

---

### Task 2: Facts toolbox

**Files:**
- Create: `service/src/whf/ai/facts_tools.py`, `service/tests/test_ai_tools.py`

**Interfaces:**
- Produces (in `whf.ai.facts_tools`): `class FactsToolbox` with `__init__(self, facts: dict)`; pure methods `run_overview() -> dict`, `member_history(member_id: int) -> dict`, `member_forecast(member_id: int) -> dict`, `member_patterns(member_id: int) -> dict`, `member_open_tasks(member_id: int) -> dict`, `member_capacity(member_id: int) -> dict`, `project_timelines() -> dict`, `rebalancing_candidates() -> dict`; unknown member ids return `{"error": "unknown member_id <n>", "known_member_ids": [...]}`; `tools() -> list` builds the Copilot `Tool` objects (names `get_run_overview`, `get_member_history`, `get_member_forecast`, `get_member_patterns`, `get_member_open_tasks`, `get_member_capacity`, `get_project_timelines`, `get_rebalancing_candidates`); `TOOL_NAMES` tuple in that order; `MemberParams(BaseModel)` with `member_id: int`; `NoParams(BaseModel)`.

- [ ] **Step 1: Write the failing tests**

`service/tests/test_ai_tools.py`:

```python
import asyncio
import json

import pytest
from copilot import ToolInvocation

from whf.ai.facts_tools import TOOL_NAMES, FactsToolbox
from whf.pipeline import jsonable, run_forecast


@pytest.fixture()
def facts(db, generated) -> dict:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of)
    return jsonable(result.facts)


def test_overview_lists_members_and_model(facts) -> None:
    box = FactsToolbox(facts)
    overview = box.run_overview()
    assert overview["run"]["id"] == facts["run"]["id"]
    assert overview["team"]["name"] == facts["team"]["name"]
    assert {"id", "name", "role"} <= set(overview["members"][0])
    assert overview["model"]["champion"] == facts["model"]["champion"]
    assert "history_13w" not in json.dumps(overview)  # overview stays small


def test_member_lookups_return_that_member_only(facts) -> None:
    box = FactsToolbox(facts)
    mid = facts["members"][0]["id"]
    assert box.member_history(mid)["member_id"] == mid and len(box.member_history(mid)["history_13w"]) == 13
    fc = box.member_forecast(mid)
    assert len(fc["forecast"]) == 2 and {"week", "demand", "capacity", "overload", "low", "high"} <= set(fc["forecast"][0])
    assert box.member_patterns(mid)["patterns"]["member_id"] == mid
    assert "open_tasks" in box.member_open_tasks(mid)
    cap = box.member_capacity(mid)
    assert [w["week"] for w in cap["weeks"]] == facts["run"]["weeks"]


def test_unknown_member_returns_error_not_exception(facts) -> None:
    box = FactsToolbox(facts)
    out = box.member_forecast(999)
    assert out["error"].startswith("unknown member_id 999")
    assert facts["members"][0]["id"] in out["known_member_ids"]


def test_projects_and_candidates(facts) -> None:
    box = FactsToolbox(facts)
    assert {"projects", "weeks"} <= set(box.project_timelines())
    cands = box.rebalancing_candidates()
    assert {"overloaded", "underloaded"} <= set(cands)


def test_tools_are_built_with_expected_names_and_call_through(facts) -> None:
    box = FactsToolbox(facts)
    tools = box.tools()
    assert [t.name for t in tools] == list(TOOL_NAMES)
    by_name = {t.name: t for t in tools}
    assert all(t.skip_permission for t in tools)
    assert "member_id" in by_name["get_member_forecast"].parameters["properties"]
    # the SDK wraps handlers: async, takes a ToolInvocation, returns a ToolResult with JSON text
    mid = facts["members"][0]["id"]
    result = asyncio.run(by_name["get_member_forecast"].handler(ToolInvocation(arguments={"member_id": mid})))
    assert result.result_type == "success"
    assert json.loads(result.text_result_for_llm)["member_id"] == mid
    overview = asyncio.run(by_name["get_run_overview"].handler(ToolInvocation(arguments={})))
    assert json.loads(overview.text_result_for_llm)["team"]["id"] == facts["team"]["id"]
    bad = asyncio.run(by_name["get_member_forecast"].handler(ToolInvocation(arguments={"member_id": "x"})))
    assert bad.result_type == "failure"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_ai_tools.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.ai.facts_tools'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/ai/facts_tools.py`:

```python
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
                name, description=description, handler=lambda _params, _inv: fn(), params_type=NoParams, skip_permission=True
            )

        return [
            plain_tool("get_run_overview", "Run, team, member list, model quality and rebalancing candidates. Call this first.", box.run_overview),
            member_tool("get_member_history", "Last 13 weeks of task arrivals (hours and counts) for one member.", box.member_history),
            member_tool("get_member_forecast", "Two-week forecast rows (demand, low, high, capacity, overload, open and new hours) for one member.", box.member_forecast),
            member_tool("get_member_patterns", "Deterministic pattern statistics for one member (assignment style, weekday rhythm, trend, estimate bias, cycle time, lateness, cluster).", box.member_patterns),
            member_tool("get_member_open_tasks", "Open tasks of one member with due dates and overdue flags.", box.member_open_tasks),
            member_tool("get_member_capacity", "Capacity, demand and overload per forecast week for one member.", box.member_capacity),
            plain_tool("get_project_timelines", "Projects of the team with start dates, deadlines and whether they start, end or run inside the forecast window.", box.project_timelines),
            plain_tool("get_rebalancing_candidates", "Members with overload and members with spare capacity over the two weeks.", box.rebalancing_candidates),
        ]
```

- [ ] **Step 4: Run tests, lint, commit**

Run: `uv run pytest tests/test_ai_tools.py -v` → PASS (5 tests). If the SDK's `_normalize_result` does not JSON-encode dict results as `text_result_for_llm` in the installed version, read `copilot/tools.py` and adapt the assertion (report it). Then `uv run ruff check --fix . && uv run ruff format .`.

```bash
git add service/src/whf/ai/facts_tools.py service/tests/test_ai_tools.py
git commit -m "feat(ai): read-only facts toolbox exposed as Copilot tools"
```

---

### Task 3: Number verification

**Files:**
- Create: `service/src/whf/ai/verify.py`, `service/tests/test_ai_verify.py`

**Interfaces:**
- Produces (in `whf.ai.verify`): `fact_numbers(facts: dict) -> set[float]` (every numeric leaf rounded to one decimal, plus the same values rounded to integers); `numbers_in_text(text: str) -> list[float]` (numbers not part of ISO dates, times or percentages); `VerificationReport(checked: int, unverified: list[str], fields: dict[str, list[float]])` with `.ok` property; `verify_narrative(narrative: Narrative, facts: dict) -> VerificationReport`; constants `SMALL_INTEGER_ALLOWANCE = 20` (integers up to this value are treated as counts and never flagged).

- [ ] **Step 1: Write the failing tests**

`service/tests/test_ai_verify.py`:

```python
from whf.ai.schema import Narrative
from whf.ai.verify import SMALL_INTEGER_ALLOWANCE, fact_numbers, numbers_in_text, verify_narrative

FACTS = {
    "run": {"id": 3, "weeks": ["2026-09-07", "2026-09-14"], "generated_at": "2026-09-03T10:00:00"},
    "members": [
        {"id": 4, "name": "A", "forecast": [{"week": "2026-09-07", "demand": 52.04, "capacity": 40.0, "overload": 12.04}]},
        {"id": 5, "name": "B", "forecast": [{"week": "2026-09-07", "demand": 20.5, "capacity": 40.0, "overload": 0.0}]},
    ],
    "model": {"champion": "gbm", "champion_mase": 0.913},
}


def _narrative(summary: str, warnings: list[str] | None = None) -> Narrative:
    return Narrative.model_validate({
        "run_summary": "ok",
        "members": [
            {"member_id": 4, "name": "A", "risk_level": "high", "summary": summary, "patterns": [], "warnings": warnings or []},
            {"member_id": 5, "name": "B", "risk_level": "low", "summary": "fine", "patterns": [], "warnings": []},
        ],
    })


def test_fact_numbers_round_to_one_decimal_and_integers() -> None:
    nums = fact_numbers(FACTS)
    assert {52.0, 52.04 if False else 52.0, 12.0, 40.0, 20.5, 0.9, 1.0, 21.0} <= nums or 21.0 in nums
    assert 3.0 in nums and 0.9 in nums


def test_numbers_in_text_skips_dates_times_and_percentages() -> None:
    text = "In the week of 2026-09-07 at 10:30, demand is 52.0 h (30% above 40 h), MASE 0.91."
    assert numbers_in_text(text) == [52.0, 40.0, 0.91]


def test_verified_when_every_number_matches_facts() -> None:
    report = verify_narrative(_narrative("Demand 52.0 h against 40 h, overload 12.0 h in week 2026-09-07."), FACTS)
    assert report.ok and report.checked == 3 and report.unverified == []


def test_unverified_number_is_reported_with_its_field() -> None:
    report = verify_narrative(_narrative("Demand will reach 63.5 h.", ["Expect 12 h overload."]), FACTS)
    assert not report.ok
    assert any("63.5" in u and "members[0].summary" in u for u in report.unverified)
    assert report.checked == 2  # 12 is a small integer, counted but never flagged


def test_small_integers_are_never_flagged() -> None:
    report = verify_narrative(_narrative(f"Over {SMALL_INTEGER_ALLOWANCE} tasks in 2 weeks, 13 weeks of history."), FACTS)
    assert report.ok
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_ai_verify.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.ai.verify'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/ai/verify.py`:

```python
"""Cross-check every number Copilot wrote against the facts it was given."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from whf.ai.schema import Narrative

SMALL_INTEGER_ALLOWANCE = 20
_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
_TIME = re.compile(r"\b\d{1,2}:\d{2}(?::\d{2})?\b")
_PERCENT = re.compile(r"-?\d+(?:[.,]\d+)?\s*%")
_NUMBER = re.compile(r"(?<![\w.])-?\d+(?:[.,]\d+)?(?![\w.]*\d)")


def _walk(value: Any, out: set[float]) -> None:
    if isinstance(value, bool):
        return
    if isinstance(value, (int, float)):
        out.add(round(float(value), 1))
        out.add(float(round(float(value))))
    elif isinstance(value, dict):
        for v in value.values():
            _walk(v, out)
    elif isinstance(value, (list, tuple)):
        for v in value:
            _walk(v, out)


def fact_numbers(facts: dict) -> set[float]:
    out: set[float] = set()
    _walk(facts, out)
    return out


def numbers_in_text(text: str) -> list[float]:
    cleaned = _PERCENT.sub(" ", _TIME.sub(" ", _DATE.sub(" ", text)))
    return [float(m.group(0).replace(",", ".")) for m in _NUMBER.finditer(cleaned)]


@dataclass
class VerificationReport:
    checked: int = 0
    unverified: list[str] = field(default_factory=list)
    fields: dict[str, list[float]] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.unverified


def _text_fields(narrative: Narrative) -> list[tuple[str, str]]:
    fields: list[tuple[str, str]] = [("run_summary", narrative.run_summary), ("model_notes", narrative.model_notes)]
    for i, m in enumerate(narrative.members):
        fields.append((f"members[{i}].summary", m.summary))
        fields += [(f"members[{i}].warnings[{j}]", w) for j, w in enumerate(m.warnings)]
        for j, p in enumerate(m.patterns):
            fields.append((f"members[{i}].patterns[{j}].statement", p.statement))
            fields.append((f"members[{i}].patterns[{j}].evidence", p.evidence))
    for i, r in enumerate(narrative.team_risks):
        fields.append((f"team_risks[{i}].detail", r.detail))
    for i, mv in enumerate(narrative.rebalancing):
        fields.append((f"rebalancing[{i}].reason", mv.reason))
    for i, adj in enumerate(narrative.suggested_adjustments):
        fields.append((f"suggested_adjustments[{i}].reason", adj.reason))
    return fields


def verify_narrative(narrative: Narrative, facts: dict) -> VerificationReport:
    known = fact_numbers(facts)
    report = VerificationReport()
    for path, text in _text_fields(narrative):
        found = numbers_in_text(text)
        if not found:
            continue
        report.fields[path] = found
        for value in found:
            report.checked += 1
            if float(value).is_integer() and abs(value) <= SMALL_INTEGER_ALLOWANCE:
                continue
            if round(value, 1) in known or float(round(value)) in known:
                continue
            report.unverified.append(f"{path}: {value:g} is not in the facts")
    return report
```

Fix the first test's odd assertion to the intended values before running: replace its body with `nums = fact_numbers(FACTS); assert {52.0, 12.0, 40.0, 20.5, 0.9, 1.0, 3.0} <= nums`.

- [ ] **Step 4: Run tests, lint, commit**

Run: `uv run pytest tests/test_ai_verify.py -v` → PASS (5 tests). Then `uv run ruff check --fix . && uv run ruff format .`.

```bash
git add service/src/whf/ai/verify.py service/tests/test_ai_verify.py
git commit -m "feat(ai): cross-check narrative numbers against run facts"
```

---

### Task 4: Prompt and product skills

**Files:**
- Create: `service/src/whf/ai/prompt.py`, five `service/src/whf/ai/skills/<name>/SKILL.md`, `service/tests/test_ai_prompt_skills.py`

**Interfaces:**
- Produces (in `whf.ai.prompt`): `SYSTEM_PROMPT: str`; `build_user_prompt(facts: dict) -> str` (names the run, team, weeks, member ids, the tool sequence and the JSON contract); `build_retry_prompt(problems: list[str]) -> str`; `skills_root() -> Path` (the packaged `skills` directory); `skill_directories() -> list[str]` (the five skill folders as absolute paths, sorted); `PRODUCT_SKILLS = ("whf-domain", "whf-pattern-discovery", "whf-forecast-interpretation", "whf-rebalancing-advice", "whf-report-style")`.

- [ ] **Step 1: Write the failing tests**

`service/tests/test_ai_prompt_skills.py`:

```python
import json
import re
from pathlib import Path

from whf.ai.prompt import PRODUCT_SKILLS, SYSTEM_PROMPT, build_retry_prompt, build_user_prompt, skill_directories, skills_root
from whf.ai.schema import Narrative

FACTS = {
    "run": {"id": 9, "as_of": "2026-09-03", "weeks": ["2026-09-07", "2026-09-14"], "language": "en"},
    "team": {"id": 2, "name": "Mobile Apps"},
    "members": [{"id": 4, "name": "Sara Tazi", "role": "team_leader"}, {"id": 5, "name": "Omar Benali", "role": "member"}],
    "model": {"champion": "gbm"},
}


def test_system_prompt_states_the_hard_rules() -> None:
    lower = SYSTEM_PROMPT.lower()
    assert "never invent" in lower and "tools" in lower and "json" in lower
    assert "one decimal" in lower


def test_user_prompt_names_run_members_weeks_and_schema() -> None:
    prompt = build_user_prompt(FACTS)
    assert "Mobile Apps" in prompt and "2026-09-07" in prompt and "2026-09-14" in prompt
    assert "member_id 4" in prompt and "member_id 5" in prompt
    assert "get_run_overview" in prompt
    schema = Narrative.model_json_schema()
    assert json.dumps(schema["required"]) in prompt or all(k in prompt for k in schema["required"])


def test_retry_prompt_lists_problems() -> None:
    text = build_retry_prompt(["member 5 is missing from members", "the answer is not valid JSON"])
    assert "member 5 is missing" in text and "only the JSON" in text


def test_skills_are_packaged_with_valid_frontmatter() -> None:
    root = skills_root()
    assert root.is_dir()
    dirs = skill_directories()
    assert [Path(d).name for d in dirs] == sorted(PRODUCT_SKILLS)
    for d in dirs:
        text = (Path(d) / "SKILL.md").read_text(encoding="utf-8")
        front = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
        assert front, d
        assert re.search(r"^name: " + re.escape(Path(d).name) + r"$", front.group(1), re.MULTILINE), d
        assert re.search(r"^description: .{20,}$", front.group(1), re.MULTILINE), d
        assert len(text) < 6000, f"{d} should stay short"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_ai_prompt_skills.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.ai.prompt'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/ai/prompt.py`:

```python
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
```

The five skills. Keep each factual and short.

`service/src/whf/ai/skills/whf-domain/SKILL.md`:

```markdown
---
name: whf-domain
description: Vocabulary and data dictionary of WorkloadHub AI Forecasting. Use when reading run facts about departments, teams, members, tasks, capacity, demand or overload.
---

# WorkloadHub domain

## Organisation
- A **department** is led by a **skill team leader**, who manages but holds no tasks and is never in the workload.
- A **team** belongs to one department and is led by a **team leader**, who also does technical work and is counted like any member.
- A **member** is a person counted in the workload. Weeks start on Monday; working days are Monday to Friday; Morocco public holidays are off days.

## Task record
id, title, project_id, assignee_id, team_id, type (feature, bug, support, analysis, maintenance), priority (low, medium, high), status (todo, in_progress, done), created_at, assigned_at, due_date, completed_at, estimated_hours, actual_hours, assignment_mode (manual: assigned by the team leader; self_picked: taken by the member; project: driven by a project's phase).

## Forecast rows (per member, per forecast week)
- **demand**: predicted hours of work, uncapped. It is the sum of **open_hours** (remaining hours of tasks already assigned, placed by the effort model) and **new_hours** (hours of tasks predicted to arrive, placed the same way).
- **capacity**: available hours after holidays, vacations and overrides (default 40 h per week).
- **overload**: max(0, demand minus capacity). Demand is never cut to fit capacity.
- **low / high**: an interval around demand from the model's backtest residuals; wide bands mean an unstable history.

## Model facts
- **champion**: the arrival model that won the backtest (seasonal_naive, tsb or gbm). **champion_mase** below 1.0 means it beat the seasonal-naive floor; near 1.0 means little better than repeating history.
- **backtest_origins**: the past dates the models were scored on. **limitations**: known blind spots of this run.
```

`service/src/whf/ai/skills/whf-pattern-discovery/SKILL.md`:

```markdown
---
name: whf-pattern-discovery
description: How to read the per-member pattern statistics of a forecast run and turn them into evidenced findings about assignment style, rhythm, trend, estimate bias, cycle time and lateness.
---

# Reading pattern statistics

Each member's `patterns` object is computed deterministically. Report a pattern only when the statistic supports it, and quote the statistic as evidence.

| Statistic | Meaning | Say something when |
|-----------|---------|--------------------|
| share_manual, share_self_picked, share_project | share of the last 13 weeks' tasks by assignment mode | one share is at least 0.5 (dominant style) |
| top_weekday, weekday_shares | weekday on which tasks most often arrive | top share is at least 0.35 |
| hours_per_week_13w, trend_hours_per_week | average weekly arrival hours and its slope per week | the slope is at least 5 percent of the average in either direction |
| estimate_ratio_median | actual hours over estimated hours on completed tasks | below 0.85 (over-estimates) or above 1.15 (under-estimates) |
| cycle_days_median, cycle_days_by_type | assignment-to-completion days | notably longer than the team's other members for the same type |
| lateness_days_median, share_late | completion relative to due date | share_late above 0.4 or median lateness above 2 days |
| deadline_proximity_corr | correlation between weekly hours and closeness of a project deadline | above 0.4 (crunches near deadlines) |
| hours_by_project, share_with_project | how work is spread over projects | one project takes more than 0.6 of the hours |
| cluster | members with similar behaviour share a cluster number | when explaining that a member behaves like others |
| open_tasks, open_est_hours, overdue_open | current backlog | overdue_open is greater than 0 |

Use the kinds `assignment_style`, `weekday_rhythm`, `trend`, `estimate_bias`, `cycle_time`, `lateness`, `project_phase`, `cluster` or `other`. A `null` statistic means there is not enough history: say so rather than guessing.
```

`service/src/whf/ai/skills/whf-forecast-interpretation/SKILL.md`:

```markdown
---
name: whf-forecast-interpretation
description: How to explain a member's two-week forecast, capacity, overload, interval and the model quality facts without adding or changing any number.
---

# Interpreting the forecast

- Lead with the decision-relevant figure: overload hours per week, then demand versus capacity.
- Say where the demand comes from: open_hours (already assigned work) versus new_hours (expected arrivals). A high open share means the backlog, not new work, is the problem.
- Capacity below 40 h means holidays, vacation or an override; name the cause when the facts show it (vacation days appear in capacity, projects in timelines).
- An interval (low, high) that spans more than half of capacity means the history is noisy; say the forecast is uncertain rather than quoting the band as fact.
- Overdue open tasks are placed forward from the first forecast week; they inflate week one by design. Mention overdue_open when it drives the overload.
- Projects starting inside the window raise expected arrivals; projects ending inside the window raise crunch. Cite the project name and date.
- Model quality: champion_mase well below 1.0 is reliable; near or above 1.0 means the numbers are close to a naive repeat of history and the narrative should say so.
- Never round, sum, subtract or convert numbers yourself. If a derived figure is not in the facts, describe the relationship in words.
- Risk levels: high when overload is greater than 0 in either week or overdue_open is at least 3; medium when demand is above 85 percent of capacity or the interval's high crosses capacity; low otherwise.
```

`service/src/whf/ai/skills/whf-rebalancing-advice/SKILL.md`:

```markdown
---
name: whf-rebalancing-advice
description: Rules for proposing task moves between members of the same team when the forecast shows overload, and for warning team leaders and skill team leaders.
---

# Rebalancing rules

1. Source: a member listed under rebalancing_candidates.overloaded. Target: a member listed under underloaded, in the same week, with spare_hours at least the hours moved.
2. Prefer targets whose patterns show the same task types (cycle_days_by_type covers the type) and who are not on vacation that week (capacity is not reduced).
3. Move whole open tasks where possible: pick from the source's open_tasks, prefer tasks not yet started (status todo) and not overdue, and name them.
4. Hours moved must be at most the source's overload in that week. Do not propose moves that would push the target above its capacity.
5. When no target has spare hours, do not invent one: raise a team risk instead and recommend that the skill team leader be told.
6. Confidence: high when the move fits rules 1 to 4 fully; medium when the target's type match is weak; low when the source overload is inside the forecast interval's noise.
7. Warnings: every member with overload above 0 gets a warning naming the week and the hours; a member with overdue_open above 0 gets a backlog warning.
```

`service/src/whf/ai/skills/whf-report-style/SKILL.md`:

```markdown
---
name: whf-report-style
description: Tone, length and structure for the narrative fields returned to a team leader.
---

# Report style

- Audience: a team leader with five minutes. Short sentences, concrete figures with their unit (h) and week (ISO date).
- run_summary: three to six sentences: who is overloaded, by how much, why, and the single most useful action.
- Member summary: at most 120 words. Lead with the number that matters, then the reason, then what to do.
- Patterns: one sentence each, with the statistic and value as evidence, for example "share_project 0.62".
- Warnings: one line each, starting with the week.
- Team risks: a title of at most eight words and a two-sentence detail.
- No praise, no hedging words such as "might" when the facts are clear, no repetition of the same figure in several fields.
- Language: the language given in the facts; keep member names as given.
```

- [ ] **Step 4: Run tests, lint, commit**

Run: `uv run pytest tests/test_ai_prompt_skills.py -v` → PASS (4 tests). Confirm the skills ship in the wheel: `uv run python -c "from whf.ai.prompt import skill_directories; print(skill_directories())"` prints five existing paths. Then `uv run ruff check --fix . && uv run ruff format .`.

```bash
git add service/src/whf/ai/prompt.py service/src/whf/ai/skills service/tests/test_ai_prompt_skills.py
git commit -m "feat(ai): system prompt, JSON contract prompt and five product skills"
```

---

### Task 5: The Copilot narrator

**Files:**
- Create: `service/src/whf/ai/session.py`, `service/tests/ai_fakes.py`, `service/tests/test_ai_session.py`

**Interfaces:**
- Produces (in `whf.ai.session`):
  - `NarrativeOutcome(status: Literal["ok", "unverified", "failed"], narrative: dict | None, error: str | None, reason: str | None, raw_text: str | None, verification: dict | None, model: str | None, usage: dict, attempts: int, tool_calls: list[str])` with `.ai_status` property returning `ok`, `unverified` or `failed:<reason>`.
  - `NarratorConfig(model: str | None = None, timeout_seconds: float = 240.0, max_attempts: int = 2, skill_directories: list[str] | None = None, log_level: str = "error")`.
  - `class CopilotNarrator` with `__init__(self, config: NarratorConfig | None = None, client_factory: Callable[[], Any] | None = None)`; `async narrate(self, facts: dict, progress: Callable[[str], None] | None = None) -> NarrativeOutcome`; `narrate_sync(self, facts, progress=None) -> NarrativeOutcome` (runs `narrate` with `asyncio.run`).
  - `class Narrator(Protocol)` with `narrate_sync(self, facts, progress=None) -> NarrativeOutcome`.
  - Reasons: `not_signed_in`, `cli_unavailable`, `timeout`, `invalid_output`, `model_error`, `other`.
- Behaviour: start client; `get_auth_status()`; create one session with the toolbox tools, the system prompt, `available_tools=ToolSet().add_custom("*")`, the skill directories, `on_event` collecting assistant messages, tool names and usage; send the user prompt with `send_and_wait(prompt, timeout=...)`; parse and validate; on `ValueError` or facts problems send `build_retry_prompt(problems)` once more (`max_attempts`); verify numbers; always disconnect the session and stop the client.

- [ ] **Step 1: Write the fakes and the failing tests**

`service/tests/ai_fakes.py`:

```python
"""Fakes standing in for the Copilot SDK client and session in tests."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from types import SimpleNamespace
from typing import Any

from copilot.generated.session_events import SessionEventType


def make_event(event_type: SessionEventType, **data: Any) -> SimpleNamespace:
    return SimpleNamespace(type=event_type, data=SimpleNamespace(**data))


@dataclass
class FakeSession:
    replies: list[str | Exception]
    tools: list[Any] = field(default_factory=list)
    prompts: list[str] = field(default_factory=list)
    handlers: list[Any] = field(default_factory=list)
    disconnected: bool = False
    call_tools_first: bool = True

    def on(self, handler):
        self.handlers.append(handler)
        return lambda: None

    async def send_and_wait(self, prompt: str, *, timeout: float = 60.0):
        self.prompts.append(prompt)
        if self.call_tools_first and self.tools:
            for tool in self.tools[:2]:
                for h in self.handlers:
                    h(make_event(SessionEventType.TOOL_EXECUTION_START, tool_name=tool.name, tool_call_id="c1", arguments={}))
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        for h in self.handlers:
            h(make_event(SessionEventType.ASSISTANT_USAGE, input_tokens=100, output_tokens=50))
        event = make_event(SessionEventType.ASSISTANT_MESSAGE, content=reply, message_id="m1", model="gpt-5")
        for h in self.handlers:
            h(event)
        return event

    async def disconnect(self) -> None:
        self.disconnected = True


@dataclass
class FakeClient:
    replies: list[str | Exception]
    authenticated: bool = True
    start_error: Exception | None = None
    started: bool = False
    stopped: bool = False
    session: FakeSession | None = None
    session_kwargs: dict = field(default_factory=dict)

    async def start(self) -> None:
        if self.start_error:
            raise self.start_error
        self.started = True

    async def get_auth_status(self):
        return SimpleNamespace(isAuthenticated=self.authenticated, login="sara" if self.authenticated else None, statusMessage=None)

    async def create_session(self, **kwargs):
        self.session_kwargs = kwargs
        self.session = FakeSession(replies=self.replies, tools=list(kwargs.get("tools") or []))
        if kwargs.get("on_event"):
            self.session.on(kwargs["on_event"])
        return self.session

    async def stop(self) -> None:
        self.stopped = True


def good_narrative(facts: dict) -> str:
    members = [
        {
            "member_id": m["id"], "name": m["name"], "risk_level": "low",
            "summary": f"Demand {m['forecast'][0]['demand']} h against {m['forecast'][0]['capacity']} h capacity in the week of {m['forecast'][0]['week']}.",
            "patterns": [], "warnings": [],
        }
        for m in facts["members"]
    ]
    return json.dumps({"run_summary": "All members within capacity.", "members": members, "team_risks": [], "rebalancing": [], "suggested_adjustments": [], "model_notes": ""})


class FakeNarrator:
    """A Narrator that returns a prepared outcome; used by narrate/CLI/API tests."""

    def __init__(self, outcome) -> None:
        self.outcome = outcome
        self.calls: list[dict] = []

    def narrate_sync(self, facts: dict, progress=None):
        self.calls.append(facts)
        if progress:
            progress("fake narrator")
        return self.outcome
```

`service/tests/test_ai_session.py`:

```python
import pytest

from ai_fakes import FakeClient, good_narrative
from whf.ai.session import CopilotNarrator, NarratorConfig
from whf.pipeline import jsonable, run_forecast


@pytest.fixture()
def facts(db, generated) -> dict:
    return jsonable(run_forecast(db, team_id=1, as_of=generated.config.as_of).facts)


def _narrator(client: FakeClient, **cfg) -> CopilotNarrator:
    return CopilotNarrator(NarratorConfig(**cfg), client_factory=lambda: client)


def test_happy_path_returns_ok_and_cleans_up(facts) -> None:
    client = FakeClient(replies=[good_narrative(facts)])
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.status == "ok" and outcome.ai_status == "ok"
    assert outcome.narrative["members"][0]["member_id"] == facts["members"][0]["id"]
    assert outcome.model == "gpt-5" and outcome.usage == {"input_tokens": 100, "output_tokens": 50}
    assert outcome.tool_calls[:1] == ["get_run_overview"]
    assert client.started and client.stopped and client.session.disconnected
    kwargs = client.session_kwargs
    assert kwargs["system_message"]["mode"] == "replace"
    assert [t.name for t in kwargs["tools"]][0] == "get_run_overview"
    assert kwargs["available_tools"] is not None
    assert len(kwargs["skill_directories"]) == 5


def test_invalid_json_is_retried_once_then_accepted(facts) -> None:
    client = FakeClient(replies=["Sure! Here it is: {", good_narrative(facts)])
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.status == "ok" and outcome.attempts == 2
    assert "not valid JSON" in client.session.prompts[1]


def test_persistent_invalid_output_fails_with_reason(facts) -> None:
    client = FakeClient(replies=["nope", "still nope"])
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.status == "failed" and outcome.reason == "invalid_output" and outcome.ai_status == "failed:invalid_output"
    assert outcome.raw_text == "still nope" and client.stopped


def test_unverified_numbers_downgrade_status(facts) -> None:
    text = good_narrative(facts).replace("All members within capacity.", "Demand will hit 999.5 h.")
    outcome = _narrator(FakeClient(replies=[text])).narrate_sync(facts)
    assert outcome.status == "unverified" and outcome.ai_status == "unverified"
    assert any("999.5" in u for u in outcome.verification["unverified"])


def test_not_signed_in_fails_before_creating_a_session(facts) -> None:
    client = FakeClient(replies=[], authenticated=False)
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.status == "failed" and outcome.reason == "not_signed_in" and client.session is None and client.stopped


def test_cli_unavailable_when_client_cannot_start(facts) -> None:
    client = FakeClient(replies=[], start_error=RuntimeError("Copilot CLI not found"))
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.reason == "cli_unavailable" and "not found" in outcome.error


def test_timeout_is_reported(facts) -> None:
    client = FakeClient(replies=[TimeoutError()])
    outcome = _narrator(client, timeout_seconds=1).narrate_sync(facts)
    assert outcome.reason == "timeout" and client.stopped


def test_model_error_is_reported(facts) -> None:
    client = FakeClient(replies=[RuntimeError("model call failed: quota")])
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.reason == "model_error" and "quota" in outcome.error
```

`ai_fakes` is importable because Task 1 added `pythonpath = ["tests"]` to `[tool.pytest.ini_options]`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_ai_session.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.ai.session'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/ai/session.py`:

```python
"""One Copilot session per run: tools over the facts in, a validated narrative out."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

from whf.ai.facts_tools import FactsToolbox
from whf.ai.prompt import SYSTEM_PROMPT, build_retry_prompt, build_user_prompt, skill_directories
from whf.ai.schema import parse_narrative
from whf.ai.verify import verify_narrative

log = logging.getLogger(__name__)
Reason = Literal["not_signed_in", "cli_unavailable", "timeout", "invalid_output", "model_error", "other"]


@dataclass
class NarrativeOutcome:
    status: Literal["ok", "unverified", "failed"]
    narrative: dict | None = None
    error: str | None = None
    reason: Reason | None = None
    raw_text: str | None = None
    verification: dict | None = None
    model: str | None = None
    usage: dict = field(default_factory=dict)
    attempts: int = 0
    tool_calls: list[str] = field(default_factory=list)

    @property
    def ai_status(self) -> str:
        return self.status if self.status != "failed" else f"failed:{self.reason or 'other'}"


@dataclass
class NarratorConfig:
    model: str | None = None
    timeout_seconds: float = 240.0
    max_attempts: int = 2
    skill_directories: list[str] | None = None
    log_level: str = "error"


class Narrator(Protocol):
    def narrate_sync(self, facts: dict, progress: Callable[[str], None] | None = None) -> NarrativeOutcome: ...


def _default_client_factory(config: NarratorConfig) -> Callable[[], Any]:
    def factory() -> Any:
        from copilot import CopilotClient

        return CopilotClient(log_level=config.log_level)

    return factory


class CopilotNarrator:
    def __init__(self, config: NarratorConfig | None = None, client_factory: Callable[[], Any] | None = None) -> None:
        self.config = config or NarratorConfig()
        self._client_factory = client_factory or _default_client_factory(self.config)

    def narrate_sync(self, facts: dict, progress: Callable[[str], None] | None = None) -> NarrativeOutcome:
        return asyncio.run(self.narrate(facts, progress))

    async def narrate(self, facts: dict, progress: Callable[[str], None] | None = None) -> NarrativeOutcome:
        say = progress or (lambda _msg: None)
        outcome = NarrativeOutcome(status="failed", reason="other")
        client = self._client_factory()
        try:
            say("starting Copilot")
            try:
                await client.start()
            except Exception as exc:  # the SDK raises RuntimeError when the CLI is missing or cannot start
                return NarrativeOutcome(status="failed", reason="cli_unavailable", error=str(exc))
            auth = await client.get_auth_status()
            if not getattr(auth, "isAuthenticated", False):
                return NarrativeOutcome(status="failed", reason="not_signed_in", error=getattr(auth, "statusMessage", None) or "not signed in to GitHub Copilot")
            toolbox = FactsToolbox(facts)
            state: dict[str, Any] = {"messages": [], "model": None, "usage": {}, "tools": []}

            def on_event(event: Any) -> None:
                from copilot.generated.session_events import SessionEventType

                if event.type == SessionEventType.ASSISTANT_MESSAGE:
                    state["messages"].append(event.data.content)
                    state["model"] = getattr(event.data, "model", None) or state["model"]
                elif event.type == SessionEventType.TOOL_EXECUTION_START:
                    state["tools"].append(event.data.tool_name)
                    say(f"tool {event.data.tool_name}")
                elif event.type == SessionEventType.ASSISTANT_USAGE:
                    state["usage"] = {
                        "input_tokens": getattr(event.data, "input_tokens", None),
                        "output_tokens": getattr(event.data, "output_tokens", None),
                    }
                elif event.type == SessionEventType.SESSION_ERROR:
                    state["error"] = getattr(event.data, "message", "session error")

            from copilot import ToolSet

            say("creating session")
            session = await client.create_session(
                model=self.config.model,
                tools=toolbox.tools(),
                system_message={"mode": "replace", "content": SYSTEM_PROMPT},
                available_tools=ToolSet().add_custom("*"),
                skill_directories=self.config.skill_directories or skill_directories(),
                streaming=False,
                on_event=on_event,
            )
            try:
                prompt = build_user_prompt(facts)
                raw = ""
                for attempt in range(1, self.config.max_attempts + 1):
                    outcome.attempts = attempt
                    say(f"asking Copilot (attempt {attempt})")
                    try:
                        event = await session.send_and_wait(prompt, timeout=self.config.timeout_seconds)
                    except TimeoutError as exc:  # asyncio.TimeoutError is an alias since Python 3.11
                        return self._finish(outcome, state, status="failed", reason="timeout", error=f"no answer within {self.config.timeout_seconds:.0f} s ({exc})")
                    except Exception as exc:
                        return self._finish(outcome, state, status="failed", reason="model_error", error=str(exc))
                    raw = self._content_of(event, state)
                    problems: list[str] = []
                    try:
                        narrative = parse_narrative(raw)
                        problems = narrative.validate_against_facts(facts)
                    except ValueError as exc:
                        problems = [str(exc)]
                        narrative = None
                    if narrative is not None and not problems:
                        report = verify_narrative(narrative, facts)
                        outcome.narrative = narrative.model_dump(mode="json")
                        outcome.raw_text = raw
                        outcome.verification = {"checked": report.checked, "unverified": report.unverified, "fields": report.fields}
                        return self._finish(outcome, state, status="ok" if report.ok else "unverified")
                    log.info("narrative rejected on attempt %s: %s", attempt, problems)
                    prompt = build_retry_prompt(problems)
                return self._finish(outcome, state, status="failed", reason="invalid_output", error="; ".join(problems), raw_text=raw)
            finally:
                await session.disconnect()
        finally:
            try:
                await client.stop()
            except Exception as exc:  # stopping must never mask the real outcome
                log.warning("copilot client stop failed: %s", exc)

    @staticmethod
    def _content_of(event: Any, state: dict) -> str:
        from copilot.generated.session_events import SessionEventType

        if event is not None and getattr(event, "type", None) == SessionEventType.ASSISTANT_MESSAGE:
            return event.data.content
        return state["messages"][-1] if state["messages"] else ""

    @staticmethod
    def _finish(outcome: NarrativeOutcome, state: dict, *, status: str, reason: Reason | None = None, error: str | None = None, raw_text: str | None = None) -> NarrativeOutcome:
        outcome.status = status  # type: ignore[assignment]
        outcome.reason = reason
        outcome.error = error
        outcome.raw_text = raw_text if raw_text is not None else outcome.raw_text
        outcome.model = state.get("model")
        outcome.usage = state.get("usage", {})
        outcome.tool_calls = list(state.get("tools", []))
        return outcome
```

- [ ] **Step 4: Run tests, lint, commit**

Run: `uv run pytest tests/test_ai_session.py -v` → PASS (8 tests). The fake session calls the first two tools before replying, so `tool_calls[:1] == ["get_run_overview"]` holds. Then `uv run ruff check --fix . && uv run ruff format .`.

```bash
git add service/src/whf/ai/session.py service/tests/ai_fakes.py service/tests/test_ai_session.py
git commit -m "feat(ai): Copilot narrator with validated JSON, retry and number verification"
```

---

### Task 6: Copilot status and login helpers

**Files:**
- Create: `service/src/whf/ai/status.py`, `service/tests/test_ai_status.py`

**Interfaces:**
- Produces (in `whf.ai.status`): `CopilotStatus(cli_path: str | None, cli_source: Literal["environment", "path", "cache", "none"], authenticated: bool | None, login: str | None, message: str)` with `.ready` property (`cli_path is not None and authenticated is True`); `resolve_cli_path(env: Mapping[str, str] | None = None) -> tuple[str | None, str]` (order: `COPILOT_CLI_PATH` that exists > `shutil.which("copilot")` > `copilot._cli_download.get_cached_cli_path()`); `async copilot_status(client_factory=None) -> CopilotStatus`; `copilot_status_sync(client_factory=None) -> CopilotStatus`; `login_command(cli_path: str) -> list[str]` returning `[cli_path, "login"]`; `run_login(cli_path: str, runner=subprocess.call) -> int`.

- [ ] **Step 1: Write the failing tests**

`service/tests/test_ai_status.py`:

```python
from ai_fakes import FakeClient
from whf.ai.status import CopilotStatus, copilot_status_sync, login_command, resolve_cli_path, run_login


def test_resolve_prefers_env_then_path_then_cache(tmp_path, monkeypatch) -> None:
    exe = tmp_path / "copilot"
    exe.write_text("")
    assert resolve_cli_path({"COPILOT_CLI_PATH": str(exe)}) == (str(exe), "environment")
    monkeypatch.setattr("whf.ai.status.shutil.which", lambda name: "/usr/bin/copilot")
    monkeypatch.setattr("whf.ai.status.get_cached_cli_path", lambda: None)
    assert resolve_cli_path({}) == ("/usr/bin/copilot", "path")
    monkeypatch.setattr("whf.ai.status.shutil.which", lambda name: None)
    monkeypatch.setattr("whf.ai.status.get_cached_cli_path", lambda: str(tmp_path / "cached"))
    assert resolve_cli_path({}) == (str(tmp_path / "cached"), "cache")
    monkeypatch.setattr("whf.ai.status.get_cached_cli_path", lambda: None)
    assert resolve_cli_path({"COPILOT_CLI_PATH": str(tmp_path / "missing")}) == (None, "none")


def test_status_reports_signed_in_user(monkeypatch, tmp_path) -> None:
    exe = tmp_path / "copilot"
    exe.write_text("")
    monkeypatch.setenv("COPILOT_CLI_PATH", str(exe))
    status = copilot_status_sync(client_factory=lambda: FakeClient(replies=[]))
    assert status.ready and status.login == "sara" and status.cli_source == "environment"
    assert "signed in" in status.message


def test_status_when_not_signed_in_and_when_cli_missing(monkeypatch, tmp_path) -> None:
    monkeypatch.delenv("COPILOT_CLI_PATH", raising=False)
    monkeypatch.setattr("whf.ai.status.shutil.which", lambda name: None)
    monkeypatch.setattr("whf.ai.status.get_cached_cli_path", lambda: None)
    status = copilot_status_sync(client_factory=lambda: FakeClient(replies=[], authenticated=False))
    assert not status.ready and status.authenticated is False and "copilot login" in status.message
    broken = copilot_status_sync(client_factory=lambda: FakeClient(replies=[], start_error=RuntimeError("no cli")))
    assert broken.authenticated is None and "no cli" in broken.message and not broken.ready


def test_login_command_and_runner() -> None:
    assert login_command("C:/x/copilot.exe") == ["C:/x/copilot.exe", "login"]
    seen: list[list[str]] = []
    assert run_login("/bin/copilot", runner=lambda cmd: seen.append(cmd) or 0) == 0
    assert seen == [["/bin/copilot", "login"]]
    assert isinstance(CopilotStatus(None, "none", None, None, "x").ready, bool)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_ai_status.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.ai.status'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/ai/status.py`:

```python
"""Where the Copilot CLI is, whether the user is signed in, and how to sign in."""

from __future__ import annotations

import asyncio
import os
import shutil
import subprocess
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal

from copilot._cli_download import get_cached_cli_path

CliSource = Literal["environment", "path", "cache", "none"]


@dataclass
class CopilotStatus:
    cli_path: str | None
    cli_source: CliSource
    authenticated: bool | None
    login: str | None
    message: str

    @property
    def ready(self) -> bool:
        return self.cli_path is not None and self.authenticated is True


def resolve_cli_path(env: Mapping[str, str] | None = None) -> tuple[str | None, CliSource]:
    env = os.environ if env is None else env
    explicit = env.get("COPILOT_CLI_PATH")
    if explicit and Path(explicit).exists():
        return explicit, "environment"
    found = shutil.which("copilot")
    if found:
        return found, "path"
    cached = get_cached_cli_path()
    if cached:
        return cached, "cache"
    return None, "none"


async def copilot_status(client_factory: Callable[[], Any] | None = None) -> CopilotStatus:
    cli_path, source = resolve_cli_path()
    if client_factory is None:
        from copilot import CopilotClient

        def client_factory() -> Any:
            return CopilotClient(log_level="error")

    client = client_factory()
    try:
        try:
            await client.start()
        except Exception as exc:
            return CopilotStatus(cli_path, source, None, None, f"Copilot CLI could not start: {exc}")
        auth = await client.get_auth_status()
        if getattr(auth, "isAuthenticated", False):
            return CopilotStatus(cli_path, source, True, getattr(auth, "login", None), f"signed in as {getattr(auth, 'login', None) or 'unknown user'}")
        return CopilotStatus(cli_path, source, False, None, "not signed in: run `whf copilot login` (or `copilot login` in PowerShell)")
    finally:
        try:
            await client.stop()
        except Exception:
            pass


def copilot_status_sync(client_factory: Callable[[], Any] | None = None) -> CopilotStatus:
    return asyncio.run(copilot_status(client_factory))


def login_command(cli_path: str) -> list[str]:
    return [cli_path, "login"]


def run_login(cli_path: str, runner: Callable[[list[str]], int] = subprocess.call) -> int:
    """Run the interactive device-login flow of the CLI, inheriting the terminal."""
    return int(runner(login_command(cli_path)))
```

- [ ] **Step 4: Run tests, lint, commit**

Run: `uv run pytest tests/test_ai_status.py -v` → PASS (4 tests). Then `uv run ruff check --fix . && uv run ruff format .`.

```bash
git add service/src/whf/ai/status.py service/tests/test_ai_status.py
git commit -m "feat(ai): Copilot CLI resolution, sign-in status and login helper"
```

---

### Task 7: Persisting the narrative

**Files:**
- Create: `service/src/whf/narrate.py`, `service/tests/test_narrate.py`

**Interfaces:**
- Produces (in `whf.narrate`): `narrate_run(conn, run_id: int, narrator: Narrator | None = None, progress: Callable[[str], None] | None = None) -> NarrativeOutcome` (loads `run_facts`, calls `narrator.narrate_sync`, writes one JSON document to `run_narratives` with keys `status`, `narrative`, `verification`, `model`, `usage`, `attempts`, `tool_calls`, `error`, `reason`, `raw_text`, `generated_at`, and sets `runs.ai_status` to `outcome.ai_status`; `KeyError` when the run does not exist); `default_narrator() -> CopilotNarrator`.
- `load_run` (existing) already returns `narrative` from `run_narratives`.

- [ ] **Step 1: Write the failing tests**

`service/tests/test_narrate.py`:

```python
import json

import pytest

from ai_fakes import FakeNarrator
from whf.ai.session import NarrativeOutcome
from whf.db.repo import read_df
from whf.narrate import narrate_run
from whf.pipeline import load_run, run_forecast


def _ok_outcome(facts: dict) -> NarrativeOutcome:
    return NarrativeOutcome(
        status="ok", narrative={"run_summary": "fine", "members": []}, verification={"checked": 1, "unverified": [], "fields": {}},
        model="gpt-5", usage={"input_tokens": 1, "output_tokens": 1}, attempts=1, tool_calls=["get_run_overview"],
    )


def test_narrate_persists_document_and_status(db, generated) -> None:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of)
    narrator = FakeNarrator(_ok_outcome(result.facts))
    seen: list[str] = []
    outcome = narrate_run(db, result.run_id, narrator=narrator, progress=seen.append)
    assert outcome.status == "ok" and seen == ["fake narrator"]
    assert narrator.calls[0]["run"]["id"] == result.run_id  # the stored facts, id included
    row = read_df(db, "SELECT ai_status FROM runs WHERE id = ?", (result.run_id,))
    assert row["ai_status"][0] == "ok"
    doc = json.loads(read_df(db, "SELECT json FROM run_narratives WHERE run_id = ?", (result.run_id,))["json"][0])
    assert doc["status"] == "ok" and doc["narrative"]["run_summary"] == "fine" and doc["model"] == "gpt-5"
    assert doc["tool_calls"] == ["get_run_overview"] and "generated_at" in doc
    assert load_run(db, result.run_id)["narrative"]["status"] == "ok"


def test_failed_outcome_is_stored_with_reason(db, generated) -> None:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of)
    outcome = NarrativeOutcome(status="failed", reason="not_signed_in", error="not signed in")
    narrate_run(db, result.run_id, narrator=FakeNarrator(outcome))
    assert read_df(db, "SELECT ai_status FROM runs WHERE id = ?", (result.run_id,))["ai_status"][0] == "failed:not_signed_in"
    doc = load_run(db, result.run_id)["narrative"]
    assert doc["narrative"] is None and doc["reason"] == "not_signed_in"


def test_second_narration_replaces_the_first(db, generated) -> None:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of)
    narrate_run(db, result.run_id, narrator=FakeNarrator(NarrativeOutcome(status="failed", reason="timeout")))
    narrate_run(db, result.run_id, narrator=FakeNarrator(_ok_outcome(result.facts)))
    assert read_df(db, "SELECT COUNT(*) AS n FROM run_narratives WHERE run_id = ?", (result.run_id,))["n"][0] == 1
    assert read_df(db, "SELECT ai_status FROM runs WHERE id = ?", (result.run_id,))["ai_status"][0] == "ok"


def test_unknown_run_raises(db) -> None:
    with pytest.raises(KeyError):
        narrate_run(db, 999, narrator=FakeNarrator(NarrativeOutcome(status="ok")))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_narrate.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'whf.narrate'`

- [ ] **Step 3: Write the implementation**

`service/src/whf/narrate.py`:

```python
"""Attach a Copilot narrative to a stored run."""

from __future__ import annotations

import datetime as dt
import json
import sqlite3
from collections.abc import Callable
from dataclasses import asdict

from whf.ai.session import CopilotNarrator, Narrator, NarrativeOutcome
from whf.db.repo import read_df


def default_narrator() -> CopilotNarrator:
    return CopilotNarrator()


def narrate_run(
    conn: sqlite3.Connection,
    run_id: int,
    narrator: Narrator | None = None,
    progress: Callable[[str], None] | None = None,
) -> NarrativeOutcome:
    facts_rows = read_df(conn, "SELECT json FROM run_facts WHERE run_id = ?", (run_id,))
    if facts_rows.empty:
        raise KeyError(f"run {run_id} not found or has no facts")
    facts = json.loads(facts_rows["json"][0])
    outcome = (narrator or default_narrator()).narrate_sync(facts, progress)
    document = {**asdict(outcome), "generated_at": dt.datetime.now().isoformat(timespec="seconds")}
    try:
        conn.execute("INSERT OR REPLACE INTO run_narratives (run_id, json) VALUES (?, ?)", (run_id, json.dumps(document)))
        conn.execute("UPDATE runs SET ai_status = ? WHERE id = ?", (outcome.ai_status, run_id))
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    return outcome
```

- [ ] **Step 4: Run tests, lint, commit**

Run: `uv run pytest tests/test_narrate.py -v` → PASS (4 tests). Then `uv run ruff check --fix . && uv run ruff format .`.

```bash
git add service/src/whf/narrate.py service/tests/test_narrate.py
git commit -m "feat(ai): persist narratives with the run and track ai_status"
```

---

### Task 8: CLI and API surface

**Files:**
- Modify: `service/src/whf/cli.py`, `service/src/whf/api.py`
- Create: `service/tests/test_ai_cli_api.py`

**Interfaces:**
- CLI: `whf copilot status [--json]` (exit 0 when ready, 3 otherwise; prints cli path, source, sign-in and message); `whf copilot login` (runs the CLI's login flow, exit code passed through; exit 3 with a message when no CLI is found); `whf narrate RUN_ID [--db] [--json] [--model]` (exit 0 for `ok`/`unverified`, 4 for `failed`, 1 for unknown run); `whf run --ai` (after the forecast, narrates and prints the status line).
- API: `GET /copilot/status` → `{"cli_path", "cli_source", "authenticated", "login", "message", "ready"}`; `POST /runs/{run_id}/narrative` body `{"model": str | null}` → `{"run_id", "ai_status", "status", "reason", "error", "narrative", "verification", "model", "usage", "attempts", "tool_calls"}` (404 for unknown run). Both use module-level factories `whf.api.narrator_factory` and `whf.api.status_provider` that tests override (`create_app(db_path, token, narrator_factory=None, status_provider=None)`).
- Model override: `--model` and body `model` build `CopilotNarrator(NarratorConfig(model=...))`.

- [ ] **Step 1: Write the failing tests**

`service/tests/test_ai_cli_api.py`:

```python
import json

from fastapi.testclient import TestClient
from typer.testing import CliRunner

from ai_fakes import FakeNarrator
from whf.ai.session import NarrativeOutcome
from whf.ai.status import CopilotStatus
from whf.api import create_app
from whf.cli import app
from whf.data.generator import GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect

runner = CliRunner()
TOKEN = "t"


def _db(tmp_path):
    path = tmp_path / "ai.db"
    conn = connect(path)
    load_generated(conn, generate(GeneratorConfig(seed=5, months=6)))
    conn.close()
    return path


def _ready() -> CopilotStatus:
    return CopilotStatus("C:/copilot.exe", "environment", True, "sara", "signed in as sara")


def test_cli_copilot_status_and_login(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr("whf.cli.copilot_status_sync", lambda: _ready())
    out = runner.invoke(app, ["copilot", "status"])
    assert out.exit_code == 0 and "signed in as sara" in out.output
    monkeypatch.setattr("whf.cli.copilot_status_sync", lambda: CopilotStatus(None, "none", None, None, "no cli"))
    assert runner.invoke(app, ["copilot", "status"]).exit_code == 3
    assert json.loads(runner.invoke(app, ["copilot", "status", "--json"]).output)["ready"] is False
    monkeypatch.setattr("whf.cli.resolve_cli_path", lambda: ("/bin/copilot", "path"))
    monkeypatch.setattr("whf.cli.run_login", lambda path: 0 if path == "/bin/copilot" else 9)
    assert runner.invoke(app, ["copilot", "login"]).exit_code == 0
    monkeypatch.setattr("whf.cli.resolve_cli_path", lambda: (None, "none"))
    assert runner.invoke(app, ["copilot", "login"]).exit_code == 3


def test_cli_narrate_and_run_ai(monkeypatch, tmp_path) -> None:
    db = _db(tmp_path)
    run = runner.invoke(app, ["run", "--db", str(db), "--team", "1", "--as-of", "2026-09-03", "--json"])
    run_id = json.loads(run.output)["run_id"]
    ok = FakeNarrator(NarrativeOutcome(status="ok", narrative={"run_summary": "fine", "members": []}, verification={"checked": 0, "unverified": [], "fields": {}}))
    monkeypatch.setattr("whf.cli.default_narrator", lambda model=None: ok)
    out = runner.invoke(app, ["narrate", str(run_id), "--db", str(db)])
    assert out.exit_code == 0 and "ok" in out.output
    shown = json.loads(runner.invoke(app, ["narrate", str(run_id), "--db", str(db), "--json"]).output)
    assert shown["ai_status"] == "ok" and shown["narrative"]["run_summary"] == "fine"
    failed = FakeNarrator(NarrativeOutcome(status="failed", reason="not_signed_in", error="sign in first"))
    monkeypatch.setattr("whf.cli.default_narrator", lambda model=None: failed)
    out = runner.invoke(app, ["narrate", str(run_id), "--db", str(db)])
    assert out.exit_code == 4 and "not_signed_in" in out.output
    assert runner.invoke(app, ["narrate", "999", "--db", str(db)]).exit_code == 1
    monkeypatch.setattr("whf.cli.default_narrator", lambda model=None: ok)
    with_ai = runner.invoke(app, ["run", "--db", str(db), "--team", "1", "--as-of", "2026-09-03", "--ai"])
    assert with_ai.exit_code == 0 and "narrative: ok" in with_ai.output


def test_api_copilot_status_and_narrative(tmp_path) -> None:
    db = _db(tmp_path)
    ok = FakeNarrator(NarrativeOutcome(status="unverified", narrative={"run_summary": "x", "members": []}, verification={"checked": 1, "unverified": ["run_summary: 5.5 is not in the facts"], "fields": {}}))
    client = TestClient(create_app(db, TOKEN, narrator_factory=lambda model=None: ok, status_provider=_ready))
    h = {"X-WHF-Token": TOKEN}
    status = client.get("/copilot/status", headers=h).json()
    assert status["ready"] is True and status["login"] == "sara"
    assert client.get("/copilot/status").status_code == 401
    run_id = client.post("/runs", json={"team_id": 1, "as_of": "2026-09-03"}, headers=h).json()["run_id"]
    body = client.post(f"/runs/{run_id}/narrative", json={}, headers=h).json()
    assert body["ai_status"] == "unverified" and body["verification"]["unverified"]
    assert client.get(f"/runs/{run_id}", headers=h).json()["run"]["ai_status"] == "unverified"
    assert client.post("/runs/999/narrative", json={}, headers=h).status_code == 404
    seen_models: list = []
    client2 = TestClient(create_app(db, TOKEN, narrator_factory=lambda model=None: seen_models.append(model) or ok, status_provider=_ready))
    client2.post(f"/runs/{run_id}/narrative", json={"model": "gpt-5"}, headers=h)
    assert seen_models == ["gpt-5"]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_ai_cli_api.py -v`
Expected: FAIL (`No such command 'copilot'`, `TypeError: create_app() got an unexpected keyword argument`).

- [ ] **Step 3: Write the implementation**

In `service/src/whf/cli.py`:

Add imports at the top (module level, next to the existing ones):

```python
from whf.ai.session import CopilotNarrator, NarratorConfig
from whf.ai.status import copilot_status_sync, resolve_cli_path, run_login
from whf.narrate import narrate_run
```

Add a module-level factory and a sub-app:

```python
copilot_app = typer.Typer(help="GitHub Copilot sign-in and status", no_args_is_help=True)
app.add_typer(copilot_app, name="copilot")


def default_narrator(model: str | None = None) -> CopilotNarrator:
    return CopilotNarrator(NarratorConfig(model=model))
```

Add commands:

```python
@copilot_app.command("status")
def copilot_status_cmd(as_json: Annotated[bool, typer.Option("--json")] = False) -> None:
    """Show where the Copilot CLI is and whether the user is signed in."""
    status = copilot_status_sync()
    if as_json:
        typer.echo(json.dumps({**status.__dict__, "ready": status.ready}))
    else:
        typer.echo(f"cli: {status.cli_path or 'not found'} ({status.cli_source})")
        typer.echo(f"sign-in: {status.message}")
    raise typer.Exit(code=0 if status.ready else 3)


@copilot_app.command("login")
def copilot_login_cmd() -> None:
    """Sign in to GitHub Copilot with the CLI's device flow (interactive)."""
    cli_path, _source = resolve_cli_path()
    if cli_path is None:
        typer.echo("error: Copilot CLI not found; run `whf copilot status` after installing it or set COPILOT_CLI_PATH")
        raise typer.Exit(code=3)
    raise typer.Exit(code=run_login(cli_path))


@app.command()
def narrate(
    run_id: int,
    db: DbOption = None,
    model: Annotated[str | None, typer.Option("--model", help="Copilot model id; default is the account's default")] = None,
    as_json: Annotated[bool, typer.Option("--json")] = False,
) -> None:
    """Ask Copilot for the narrative of a stored run and save it with the run."""
    conn = _conn(db)
    try:
        outcome = narrate_run(conn, run_id, narrator=default_narrator(model), progress=None if as_json else lambda m: typer.echo(f"  {m}"))
    except KeyError as exc:
        typer.echo(f"error: {exc}")
        raise typer.Exit(code=1) from exc
    if as_json:
        typer.echo(json.dumps(jsonable({**outcome.__dict__, "ai_status": outcome.ai_status, "run_id": run_id})))
    else:
        typer.echo(f"narrative: {outcome.ai_status}" + (f" ({outcome.error})" if outcome.error else ""))
    raise typer.Exit(code=0 if outcome.status != "failed" else 4)
```

In the existing `run` command add the option `ai: Annotated[bool, typer.Option("--ai", help="Also ask Copilot for the narrative")] = False` and, after the forecast is printed (both in the `--json` branch and the text branch), when `ai` is true:

```python
    if ai:
        outcome = narrate_run(conn, result.run_id, narrator=default_narrator(), progress=None if as_json else lambda m: typer.echo(f"  {m}"))
        if as_json:
            typer.echo(json.dumps(jsonable({"run_id": result.run_id, "ai_status": outcome.ai_status})))
        else:
            typer.echo(f"narrative: {outcome.ai_status}" + (f" ({outcome.error})" if outcome.error else ""))
```

(In the `--json` branch print the run JSON first, then the narrative JSON on its own line.)

In `service/src/whf/api.py`:

Change the factory signature to `create_app(db_path, token, narrator_factory=None, status_provider=None)`, defaulting to:

```python
from whf.ai.session import CopilotNarrator, NarratorConfig
from whf.ai.status import copilot_status_sync
from whf.narrate import narrate_run


def _default_narrator_factory(model: str | None = None) -> CopilotNarrator:
    return CopilotNarrator(NarratorConfig(model=model))
```

with `narrator_factory = narrator_factory or _default_narrator_factory` and `status_provider = status_provider or copilot_status_sync` inside `create_app`. Add:

```python
class NarrativeRequest(BaseModel):
    model: str | None = None


    @app.get("/copilot/status", dependencies=guarded)
    def copilot_status_route() -> dict:
        status = status_provider()
        return {**status.__dict__, "ready": status.ready}

    @app.post("/runs/{run_id}/narrative", dependencies=guarded)
    def create_narrative(run_id: int, body: NarrativeRequest, conn: sqlite3.Connection = Depends(db)) -> dict:
        try:
            outcome = narrate_run(conn, run_id, narrator=narrator_factory(body.model))
        except KeyError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        return jsonable({**outcome.__dict__, "ai_status": outcome.ai_status, "run_id": run_id})
```

- [ ] **Step 4: Run tests, lint, commit**

Run: `uv run pytest tests/test_ai_cli_api.py tests/test_cli.py tests/test_api.py -q` → PASS. Then the full fast suite and lint.

```bash
git add service/src/whf/cli.py service/src/whf/api.py service/tests/test_ai_cli_api.py
git commit -m "feat(ai): copilot status/login, narrate command, run --ai and narrative API"
```

---

### Task 9: Live test, documentation and spec deviation

**Files:**
- Create: `service/tests/test_ai_live.py`
- Modify: `service/README.md`, `CLAUDE.md`, `docs/superpowers/specs/2026-09-03-workload-forecast-design.md`, `.claude/skills/README.md`

- [ ] **Step 1: Write the opt-in live test**

`service/tests/test_ai_live.py`:

```python
"""Opt-in end-to-end test against the real Copilot CLI. Run with WHF_COPILOT_LIVE=1 on a signed-in machine."""

import os

import pytest

from whf.ai.session import CopilotNarrator
from whf.narrate import narrate_run
from whf.pipeline import run_forecast

pytestmark = pytest.mark.copilot


@pytest.mark.skipif(os.environ.get("WHF_COPILOT_LIVE") != "1", reason="set WHF_COPILOT_LIVE=1 to run against Copilot")
def test_live_narrative_for_one_team(db, generated) -> None:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of)
    outcome = narrate_run(db, result.run_id, narrator=CopilotNarrator(), progress=print)
    assert outcome.status in {"ok", "unverified"}, outcome.error
    assert outcome.tool_calls and outcome.tool_calls[0] == "get_run_overview"
    assert len(outcome.narrative["members"]) == len(result.facts["members"])
```

Run: `uv run pytest tests/test_ai_live.py -v` → 1 skipped. Then, if you are on a machine with a signed-in Copilot CLI, run `WHF_COPILOT_LIVE=1 uv run pytest tests/test_ai_live.py -v -s` once and paste the outcome in the report; otherwise state that it was not run and why.

- [ ] **Step 2: Documentation**

Append to `service/README.md` under `## Run`:

```markdown
## Copilot narrative

```powershell
uv run whf copilot status          # where the CLI is and whether you are signed in (exit 3 if not ready)
uv run whf copilot login           # device-flow sign-in in this terminal
uv run whf run --team 1 --ai       # forecast, then ask Copilot for the narrative
uv run whf narrate 1               # narrate an existing run; --json prints the stored document
```

The narrative is stored with the run (`run_narratives`) and `runs.ai_status` records `ok`, `unverified` (a number in the text is not in the facts) or `failed:<reason>`. Set `WHF_COPILOT_LIVE=1` to run the one live test.
```

In `CLAUDE.md`, replace the sentence about product skills with: "Product skills `whf-*` live in `service/src/whf/ai/skills/` and are shipped inside the service; the Copilot session loads them through `skill_directories`. Edit them there; keep them factual, short and specific to this domain."

In `.claude/skills/README.md`, replace the "Product skills (to be written)" section with a pointer to `service/src/whf/ai/skills/`.

In the spec, section 6, "Skills" bullet: append "(Implementation note, 2026-09-03: the skills live in `service/src/whf/ai/skills/` and are loaded through the SDK's `skill_directories`; the `.claude/skills/whf-*` location was dropped to keep one source of truth.)" and in section 12 add the row: "The Copilot SDK downloads the CLI on first use into the user's cache | The packaging plan pre-downloads the pinned CLI version and sets `COPILOT_CLI_PATH`; `whf copilot status` reports where the CLI was found".

- [ ] **Step 3: Full verification and commit**

Run: `uv run pytest -q -m "not slow"` (all green, the live test skipped), `uv run ruff check . && uv run ruff format --check .`.

```bash
git add service/tests/test_ai_live.py service/README.md CLAUDE.md .claude/skills/README.md docs/superpowers/specs/2026-09-03-workload-forecast-design.md
git commit -m "docs(ai): live test, Copilot commands in README, skills location deviation"
```

---

## Self-review against the spec (section 6)

- **Session**: Task 5 creates one session per run with the SDK, model `None` (account default) unless configured; streaming is off because the narrative is one JSON document (spec's "streaming progress to the UI" is met through `progress` messages per tool call, which the API plan can forward).
- **Custom agent and restricted tools**: the system prompt (Task 4) states the rules; `available_tools=ToolSet().add_custom("*")` restricts the session to the eight read-only tools of Task 2, which cover every tool the spec lists (`get_run_overview`, `get_member_history`, `get_member_forecast`, `get_member_patterns`, `get_project_timelines`, `get_capacity` as `get_member_capacity`, `get_team_rebalancing_candidates` as `get_rebalancing_candidates`, plus `get_member_open_tasks`).
- **Skills**: Task 4 ships the five skills named in the spec; location deviation recorded in Task 9.
- **Output**: Task 1 is the strict JSON contract with per-member patterns, narrative, risk level, warnings, team risks, rebalancing moves with hours/week/reason/confidence, suggested adjustments; Task 3 marks unmatched numbers `unverified`; adjustments are stored in the narrative document, never merged into forecasts (revision trace).
- **Failure modes**: Task 5 maps not signed in, CLI unavailable, timeout, invalid output after one retry, and model errors to `failed:<reason>`; the numeric run is untouched (Task 7 only adds rows).
- **Sign-in**: Task 6 and Task 8 provide status and the device-login command; the interactive capture of the code is left to the desktop app plan as the spec allows.
- **Type consistency**: `NarrativeOutcome.ai_status` is what Task 7 stores and Task 8 returns; `FakeNarrator.narrate_sync(facts, progress)` matches the `Narrator` protocol; `create_app(db_path, token, narrator_factory=None, status_provider=None)` is used identically in Task 8's tests; `default_narrator(model=None)` in `cli.py` is patched by tests with that signature.
