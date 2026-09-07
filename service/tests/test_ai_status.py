from types import SimpleNamespace

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
    assert status.code == "signed_in"


def test_status_when_not_signed_in_and_when_cli_missing(monkeypatch, tmp_path) -> None:
    monkeypatch.delenv("COPILOT_CLI_PATH", raising=False)
    monkeypatch.setattr("whf.ai.status.shutil.which", lambda name: None)
    monkeypatch.setattr("whf.ai.status.get_cached_cli_path", lambda: None)
    status = copilot_status_sync(client_factory=lambda: FakeClient(replies=[], authenticated=False))
    assert not status.ready and status.authenticated is False and "copilot login" in status.message
    assert status.code == "not_signed_in"
    broken = copilot_status_sync(client_factory=lambda: FakeClient(replies=[], start_error=RuntimeError("no cli")))
    assert broken.authenticated is None and "no cli" in broken.message and not broken.ready
    assert broken.code == "start_failed"


def test_the_status_code_lets_the_app_translate_the_message() -> None:
    """`message` is English prose from here and from the CLI; the app shows the user's own language."""
    for code in ("signed_in", "not_signed_in", "start_failed"):
        assert CopilotStatus(None, "none", None, None, "x", code).code == code


def test_login_command_and_runner() -> None:
    assert login_command("C:/x/copilot.exe") == ["C:/x/copilot.exe", "login"]
    seen: list[list[str]] = []
    assert run_login("/bin/copilot", runner=lambda cmd: seen.append(cmd) or 0) == 0
    assert seen == [["/bin/copilot", "login"]]
    assert isinstance(CopilotStatus(None, "none", None, None, "x", "start_failed").ready, bool)


def test_status_when_the_sdk_cannot_even_construct_a_client(monkeypatch) -> None:
    """With no CLI anywhere, CopilotClient() raises from its constructor, before start(): still exit 3, no traceback."""
    monkeypatch.delenv("COPILOT_CLI_PATH", raising=False)
    monkeypatch.setattr("whf.ai.status.shutil.which", lambda name: None)
    monkeypatch.setattr("whf.ai.status.get_cached_cli_path", lambda: None)

    def factory() -> FakeClient:
        raise RuntimeError("Copilot CLI not found. Install a published wheel ...")

    status = copilot_status_sync(client_factory=factory)
    assert status.cli_path is None and status.cli_source == "none"
    assert status.authenticated is None and not status.ready
    assert status.code == "start_failed"
    assert status.message.startswith("Copilot CLI could not start:") and "not found" in status.message


def test_status_stops_a_client_whose_start_failed(monkeypatch) -> None:
    """A constructed client that fails to start still gets stop(): the service calls this on every 'Check again'."""
    monkeypatch.delenv("COPILOT_CLI_PATH", raising=False)
    monkeypatch.setattr("whf.ai.status.shutil.which", lambda name: None)
    monkeypatch.setattr("whf.ai.status.get_cached_cli_path", lambda: None)
    client = FakeClient(replies=[], start_error=RuntimeError("no cli"))
    status = copilot_status_sync(client_factory=lambda: client)
    assert status.code == "start_failed" and not status.ready
    assert client.stopped


def _snapshot(**overrides):
    """One `AccountQuotaSnapshot` as the SDK shapes it."""
    fields = {
        "entitlement_requests": 300,
        "is_unlimited_entitlement": False,
        "overage": 0.0,
        "overage_allowed_with_exhausted_quota": False,
        "remaining_percentage": 62.5,
        "usage_allowed_with_exhausted_quota": True,
        "used_requests": 112,
        "reset_date": "2026-10-01",
    }
    return SimpleNamespace(**{**fields, **overrides})


def test_status_reports_the_remaining_quota_of_a_signed_in_account(monkeypatch, tmp_path) -> None:
    exe = tmp_path / "copilot"
    exe.write_text("")
    monkeypatch.setenv("COPILOT_CLI_PATH", str(exe))
    client = FakeClient(
        replies=[],
        quota_snapshots={
            "premium_interactions": _snapshot(),
            "chat": _snapshot(entitlement_requests=-1, is_unlimited_entitlement=True, reset_date=None),
        },
    )
    status = copilot_status_sync(client_factory=lambda: client)
    assert status.code == "signed_in"
    quota = status.quota
    assert quota is not None
    assert quota["premium_interactions"] == {
        "used": 112,
        "entitlement": 300,
        "unlimited": False,
        "remaining_percentage": 62.5,
        "overage": 0.0,
        "reset_date": "2026-10-01",
    }
    assert quota["chat"]["unlimited"] is True and quota["chat"]["reset_date"] is None
    assert client.quota_requests == [None]  # the signed-in user's own quota, no token of ours


def test_a_quota_lookup_that_fails_leaves_the_user_signed_in(monkeypatch, tmp_path) -> None:
    """The quota is a nicety; a Copilot version that cannot answer must not read as a broken sign-in."""
    exe = tmp_path / "copilot"
    exe.write_text("")
    monkeypatch.setenv("COPILOT_CLI_PATH", str(exe))
    client = FakeClient(replies=[], quota_error=RuntimeError("unknown method account.getQuota"))
    status = copilot_status_sync(client_factory=lambda: client)
    assert status.code == "signed_in" and status.ready and status.quota is None


def test_a_status_that_is_not_signed_in_has_no_quota() -> None:
    assert CopilotStatus(None, "none", None, None, "x", "not_signed_in").quota is None
