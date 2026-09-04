import json

import pytest
from ai_fakes import FakeClient, FakeNarrator, good_narrative

from whf.ai.progress import ProgressEvent
from whf.ai.session import CopilotNarrator, NarrativeOutcome, NarratorConfig
from whf.db.repo import read_df
from whf.narrate import RunHasNoFactsError, RunNotFoundError, narrate_run
from whf.pipeline import load_run, run_forecast


def _ok_outcome(facts: dict) -> NarrativeOutcome:
    """A real `ok` outcome, produced by `CopilotNarrator` against a fake Copilot client.

    Deliberately not a literal dict: `outcome.narrative` must be exactly what
    `whf.ai.schema.Narrative.model_dump(mode="json")` (`session.py`) emits, since that is the
    only place production code assigns it, so the contract test below binds the real producer
    rather than a hand-written fixture that merely happens to match it today.
    """
    client = FakeClient(replies=[good_narrative(facts)])
    narrator = CopilotNarrator(NarratorConfig(), client_factory=lambda: client)
    return narrator.narrate_sync(facts)


def test_narrate_persists_document_and_status(db, generated) -> None:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of)
    narrator = FakeNarrator(_ok_outcome(result.facts))
    seen: list[ProgressEvent] = []
    outcome = narrate_run(db, result.run_id, narrator=narrator, progress=seen.append)
    assert outcome.status == "ok" and [e.code for e in seen] == ["asking"]
    assert narrator.calls[0]["run"]["id"] == result.run_id  # the stored facts, id included
    row = read_df(db, "SELECT ai_status FROM runs WHERE id = ?", (result.run_id,))
    assert row["ai_status"][0] == "ok"
    # The stored row keeps the whole envelope (status, model, tool_calls, ...) for audit.
    doc = json.loads(read_df(db, "SELECT json FROM run_narratives WHERE run_id = ?", (result.run_id,))["json"][0])
    assert doc["status"] == "ok" and doc["model"] == "gpt-5"
    assert doc["narrative"]["run_summary"] == "All members within capacity."
    assert doc["tool_calls"][:1] == ["get_run_overview"] and "generated_at" in doc
    # `load_run` unwraps the envelope: the app only ever sees the bare narrative document.
    assert load_run(db, result.run_id)["narrative"]["run_summary"] == "All members within capacity."


def test_load_run_narrative_matches_the_apps_declared_shape(db, generated) -> None:
    """The contract test, and the reason this task exists.

    `load_run(...)["narrative"]` must have exactly the six keys the app's `Narrative` interface
    declares (`app/src/shared/types.ts`): `run_summary`, `members`, `team_risks`, `rebalancing`,
    `suggested_adjustments`, `model_notes` — no envelope fields like `status` or `raw_text` mixed
    in. The original bug was an extra-keys bug, so this asserts the exact set, not a subset.
    """
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of)
    narrate_run(db, result.run_id, narrator=FakeNarrator(_ok_outcome(result.facts)))
    narrative = load_run(db, result.run_id)["narrative"]
    assert set(narrative) == {
        "run_summary",
        "members",
        "team_risks",
        "rebalancing",
        "suggested_adjustments",
        "model_notes",
    }


def test_failed_outcome_is_stored_with_reason(db, generated) -> None:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of)
    outcome = NarrativeOutcome(status="failed", reason="not_signed_in", error="not signed in")
    narrate_run(db, result.run_id, narrator=FakeNarrator(outcome))
    assert (
        read_df(db, "SELECT ai_status FROM runs WHERE id = ?", (result.run_id,))["ai_status"][0]
        == "failed:not_signed_in"
    )
    # The stored row keeps the full envelope, reason included, for audit.
    stored = json.loads(read_df(db, "SELECT json FROM run_narratives WHERE run_id = ?", (result.run_id,))["json"][0])
    assert stored["narrative"] is None and stored["reason"] == "not_signed_in"
    # `load_run` unwraps to `None` for a failed narration: this is what restores the app's retry
    # button, since `{!narrative && <button>}` would otherwise stay hidden behind a truthy envelope.
    assert load_run(db, result.run_id)["narrative"] is None


def test_second_narration_replaces_the_first(db, generated) -> None:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of)
    narrate_run(db, result.run_id, narrator=FakeNarrator(NarrativeOutcome(status="failed", reason="timeout")))
    narrate_run(db, result.run_id, narrator=FakeNarrator(_ok_outcome(result.facts)))
    assert read_df(db, "SELECT COUNT(*) AS n FROM run_narratives WHERE run_id = ?", (result.run_id,))["n"][0] == 1
    assert read_df(db, "SELECT ai_status FROM runs WHERE id = ?", (result.run_id,))["ai_status"][0] == "ok"


def test_unknown_run_raises(db) -> None:
    with pytest.raises(KeyError):
        narrate_run(db, 999, narrator=FakeNarrator(NarrativeOutcome(status="ok")))


def test_unknown_run_raises_run_not_found_error(db) -> None:
    with pytest.raises(RunNotFoundError):
        narrate_run(db, 999, narrator=FakeNarrator(NarrativeOutcome(status="ok")))


def test_run_without_stored_facts_raises_run_has_no_facts_error(db) -> None:
    db.execute(
        "INSERT INTO runs (id, team_id, as_of, status, started_at) VALUES (?, ?, ?, ?, ?)",
        (1000, 1, "2026-09-03", "done", "2026-09-03T00:00:00"),
    )
    db.commit()
    with pytest.raises(RunHasNoFactsError):
        narrate_run(db, 1000, narrator=FakeNarrator(NarrativeOutcome(status="ok")))
