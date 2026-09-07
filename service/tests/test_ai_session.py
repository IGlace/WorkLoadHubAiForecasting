from ai_fakes import FakeClient, good_narrative, make_metrics

from whf.ai.progress import ProgressEvent
from whf.ai.session import CopilotNarrator, NarratorConfig, _default_client_factory
from whf.ai.usage import empty_usage

# `facts` comes from tests/conftest.py: one real forecast for team 1, shared and copied per test.


def _narrator(client: FakeClient, **cfg) -> CopilotNarrator:
    return CopilotNarrator(NarratorConfig(**cfg), client_factory=lambda: client)


def test_happy_path_returns_ok_and_cleans_up(facts) -> None:
    client = FakeClient(replies=[good_narrative(facts)])
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.status == "ok" and outcome.ai_status == "ok"
    assert outcome.narrative["members"][0]["member_id"] == facts["members"][0]["id"]
    assert outcome.model == "gpt-5"
    assert outcome.usage["source"] == "metrics" and outcome.usage["input_tokens"] == 100
    assert outcome.tool_calls[:1] == ["get_run_overview"]
    assert client.started and client.stopped and client.session.disconnected
    kwargs = client.session_kwargs
    assert kwargs["system_message"]["mode"] == "replace"
    assert [t.name for t in kwargs["tools"]][0] == "get_run_overview"
    assert kwargs["available_tools"].to_list() == ["custom:*", "builtin:skill"]
    assert kwargs["enable_skills"] is True
    assert len(kwargs["skill_directories"]) == 5


def test_invalid_json_is_retried_once_then_accepted(facts) -> None:
    client = FakeClient(replies=["Sure! Here it is: {", good_narrative(facts)])
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.status == "ok" and outcome.attempts == 2
    assert "not valid JSON" in client.session.prompts[1]


def test_persistent_invalid_output_fails_with_reason(facts) -> None:
    client = FakeClient(replies=["nope", "still nope"])
    outcome = _narrator(client).narrate_sync(facts)
    assert (
        outcome.status == "failed"
        and outcome.reason == "invalid_output"
        and outcome.ai_status == "failed:invalid_output"
    )
    assert outcome.raw_text == "still nope" and client.stopped


def test_unverified_numbers_downgrade_status(facts) -> None:
    text = good_narrative(facts).replace("All members within capacity.", "Demand will hit 999.5 h.")
    outcome = _narrator(FakeClient(replies=[text])).narrate_sync(facts)
    assert outcome.status == "unverified" and outcome.ai_status == "unverified"
    assert any("999.5" in u for u in outcome.verification["unverified"])


def test_not_signed_in_fails_before_creating_a_session(facts) -> None:
    client = FakeClient(replies=[], authenticated=False)
    outcome = _narrator(client).narrate_sync(facts)
    assert (
        outcome.status == "failed" and outcome.reason == "not_signed_in" and client.session is None and client.stopped
    )


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


def test_auth_status_failure_maps_to_other(facts) -> None:
    client = FakeClient(replies=[], auth_error=RuntimeError("auth server unreachable"))
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.status == "failed" and outcome.reason == "other"
    assert "auth server unreachable" in outcome.error and client.stopped


def test_create_session_failure_maps_to_other(facts) -> None:
    client = FakeClient(replies=[], session_error=RuntimeError("bad skill dir"))
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.status == "failed" and outcome.reason == "other"
    assert "bad skill dir" in outcome.error and client.stopped


def test_none_reply_is_treated_as_invalid_output(facts) -> None:
    client = FakeClient(replies=[], reply_none=True)
    outcome = _narrator(client, max_attempts=1).narrate_sync(facts)
    assert outcome.reason == "invalid_output" and outcome.raw_text == ""


def test_session_error_event_enriches_model_error(facts) -> None:
    client = FakeClient(replies=[RuntimeError("model call failed")], emit_session_error="rate limited")
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.reason == "model_error" and "rate limited" in outcome.error


def test_disconnect_failure_does_not_mask_a_successful_narrative(facts) -> None:
    client = FakeClient(replies=[good_narrative(facts)], disconnect_raises=True)
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.status == "ok" and outcome.ai_status == "ok"
    assert client.session.disconnected and client.stopped


def test_default_client_factory_uses_only_log_level_and_the_logged_in_user(monkeypatch) -> None:
    """Pins the hard rule: no github_token, no provider, no use_logged_in_user override."""
    calls: list[dict] = []

    class RecordingCopilotClient:
        def __init__(self, **kwargs):
            calls.append(kwargs)

    import copilot

    monkeypatch.setattr(copilot, "CopilotClient", RecordingCopilotClient)
    factory = _default_client_factory(NarratorConfig(log_level="warn"))
    factory()
    assert calls == [{"log_level": "warn"}]


def _record_live(client: FakeClient, facts: dict, **cfg) -> list[tuple[str, str]]:
    """Narrate and return every live chunk the narrator sent, as (kind, text) in order."""
    chunks: list[tuple[str, str]] = []
    _narrator(client, **cfg).narrate_sync(facts, live=lambda kind, text: chunks.append((kind, text)))
    return chunks


def _joined(chunks: list[tuple[str, str]], kind: str) -> str:
    return "".join(text for chunk_kind, text in chunks if chunk_kind == kind)


def test_the_session_streams_so_the_app_can_show_the_answer_being_written(facts) -> None:
    client = FakeClient(replies=[good_narrative(facts)])
    _narrator(client).narrate_sync(facts)
    assert client.session_kwargs["streaming"] is True


def test_intent_and_reasoning_deltas_are_forwarded_as_thinking(facts) -> None:
    client = FakeClient(
        replies=[good_narrative(facts)],
        intents=["Reading the capacity of each member"],
        reasoning_deltas=[("r1", "Yara is "), ("r1", "over capacity")],
    )
    chunks = _record_live(client, facts)
    assert _joined(chunks, "thinking") == "Reading the capacity of each member\nYara is over capacity"


def test_a_full_reasoning_that_was_already_streamed_is_not_repeated(facts) -> None:
    """Some models send the deltas and then the whole block again; showing it twice reads as a loop."""
    client = FakeClient(
        replies=[good_narrative(facts)],
        reasoning_deltas=[("r1", "Yara is over capacity")],
        reasoning_full=[("r1", "Yara is over capacity")],
    )
    assert _joined(_record_live(client, facts), "thinking") == "Yara is over capacity"


def test_a_full_reasoning_that_was_never_streamed_is_shown(facts) -> None:
    """Models that send their thinking in one piece rather than in deltas must still show up."""
    client = FakeClient(replies=[good_narrative(facts)], reasoning_full=[("r2", "Checking the weeks")])
    assert _joined(_record_live(client, facts), "thinking") == "Checking the weeks\n"


def test_message_deltas_are_forwarded_as_the_answer(facts) -> None:
    client = FakeClient(replies=[good_narrative(facts)], message_deltas=['{"run_summary"', ': "fine"}'])
    assert _joined(_record_live(client, facts), "answer") == '{"run_summary": "fine"}'


def test_every_attempt_starts_a_new_answer(facts) -> None:
    """An empty answer chunk means "a new answer starts here": the rejected one must not be prepended."""
    client = FakeClient(replies=["not JSON", good_narrative(facts)], message_deltas=["{"])
    answers = [text for kind, text in _record_live(client, facts) if kind == "answer"]
    assert answers == ["", "{", "", "{"]


def test_a_finished_tool_call_is_a_step_naming_the_tool(facts) -> None:
    client = FakeClient(replies=[good_narrative(facts)])
    steps: list[ProgressEvent] = []
    _narrator(client).narrate_sync(facts, steps.append)
    pairs = [(event.code, event.detail) for event in steps]
    assert ("tool", "get_run_overview") in pairs
    assert pairs.index(("tool_done", "get_run_overview")) > pairs.index(("tool", "get_run_overview"))


def test_a_finished_tool_call_with_an_unknown_id_is_still_reported(facts) -> None:
    """A completion whose start was never seen must not be dropped, and must not name a wrong tool."""
    client = FakeClient(replies=[good_narrative(facts)], unmatched_tool_complete_id="never-started")
    steps: list[ProgressEvent] = []
    _narrator(client).narrate_sync(facts, steps.append)
    assert ("tool_done", None) in [(event.code, event.detail) for event in steps]


def test_the_session_metrics_say_what_the_narration_cost(facts) -> None:
    """The billed numbers come from the session's own metrics, not from the streamed events."""
    client = FakeClient(replies=[good_narrative(facts)], metrics=make_metrics(total_nano_aiu=2.5e9))
    outcome = _narrator(client).narrate_sync(facts)
    usage = outcome.usage
    assert usage["source"] == "metrics"
    assert usage["ai_credits"] == 2.5 and usage["usd"] == 0.025
    assert usage["input_tokens"] == 100 and usage["output_tokens"] == 50
    assert usage["requests"] == 1 and usage["premium_requests"] == 1.0
    assert usage["api_seconds"] == 2.5
    assert usage["models"] == {"gpt-5": {"requests": 1, "input_tokens": 100, "output_tokens": 50}}


def test_the_metrics_are_read_while_the_session_is_still_connected(facts) -> None:
    """A disconnected session answers nothing, so the cost must be read before disconnecting."""
    client = FakeClient(replies=[good_narrative(facts)])
    _narrator(client).narrate_sync(facts)
    assert client.session.calls == ["get_metrics", "disconnect"]
    assert client.session.metrics_timeout == 10.0


def test_a_failed_narration_still_reports_what_it_cost(facts) -> None:
    client = FakeClient(replies=["nope", "still nope"])
    outcome = _narrator(client).narrate_sync(facts)
    assert outcome.status == "failed" and outcome.usage["source"] == "metrics"
    assert outcome.usage["ai_credits"] == 1.5


def test_unavailable_metrics_fall_back_to_the_streamed_usage_events(facts) -> None:
    """The RPC is experimental; when it fails the tokens the events carried are still worth showing."""
    client = FakeClient(replies=[good_narrative(facts)], metrics_error=RuntimeError("usage rpc unavailable"))
    usage = _narrator(client).narrate_sync(facts).usage
    assert usage["source"] == "events"
    assert usage["input_tokens"] == 100 and usage["output_tokens"] == 50 and usage["cache_read_tokens"] == 20
    assert usage["requests"] == 1
    assert usage["models"] == {"gpt-5": {"requests": 1, "input_tokens": 100, "output_tokens": 50}}
    # Events say nothing about money; showing a zero would be a lie.
    assert usage["ai_credits"] is None and usage["usd"] is None and usage["premium_requests"] is None


def test_a_narration_that_never_opened_a_session_costs_nothing_known(facts) -> None:
    outcome = _narrator(FakeClient(replies=[], authenticated=False)).narrate_sync(facts)
    assert outcome.usage == empty_usage() and outcome.usage["source"] == "none"
    outcome = _narrator(FakeClient(replies=[], session_error=RuntimeError("bad skill dir"))).narrate_sync(facts)
    assert outcome.usage["source"] == "none"
