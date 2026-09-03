import json

import pytest
from ai_fakes import FakeNarrator

from whf.ai.session import NarrativeOutcome
from whf.db.repo import read_df
from whf.narrate import narrate_run
from whf.pipeline import load_run, run_forecast


def _ok_outcome(facts: dict) -> NarrativeOutcome:
    return NarrativeOutcome(
        status="ok",
        narrative={"run_summary": "fine", "members": []},
        verification={"checked": 1, "unverified": [], "fields": {}},
        model="gpt-5",
        usage={"input_tokens": 1, "output_tokens": 1},
        attempts=1,
        tool_calls=["get_run_overview"],
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
    assert (
        read_df(db, "SELECT ai_status FROM runs WHERE id = ?", (result.run_id,))["ai_status"][0]
        == "failed:not_signed_in"
    )
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
