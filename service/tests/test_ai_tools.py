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
    assert len(fc["forecast"]) == 2 and {"week", "demand", "capacity", "overload", "low", "high"} <= set(
        fc["forecast"][0]
    )
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
