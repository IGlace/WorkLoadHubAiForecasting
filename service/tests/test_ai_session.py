import pytest
from ai_fakes import FakeClient, good_narrative

from whf.ai.session import CopilotNarrator, NarratorConfig, _default_client_factory
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
