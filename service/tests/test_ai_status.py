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
