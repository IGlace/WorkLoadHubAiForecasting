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
