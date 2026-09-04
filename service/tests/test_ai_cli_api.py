import json

import pytest
from ai_fakes import FakeNarrator
from fastapi.testclient import TestClient
from typer.testing import CliRunner

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
    return CopilotStatus("C:/copilot.exe", "environment", True, "sara", "signed in as sara", "signed_in")


def test_cli_copilot_status_and_login(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr("whf.cli.copilot_status_sync", lambda: _ready())
    out = runner.invoke(app, ["copilot", "status"])
    assert out.exit_code == 0 and "signed in as sara" in out.output
    monkeypatch.setattr(
        "whf.cli.copilot_status_sync", lambda: CopilotStatus(None, "none", None, None, "no cli", "start_failed")
    )
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
    ok = FakeNarrator(
        NarrativeOutcome(
            status="ok",
            narrative={"run_summary": "fine", "members": []},
            verification={"checked": 0, "unverified": [], "fields": {}},
        )
    )
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


def test_cli_run_json_ai_prints_a_single_json_document(monkeypatch, tmp_path) -> None:
    db = _db(tmp_path)
    ok = FakeNarrator(NarrativeOutcome(status="ok", narrative={"run_summary": "fine", "members": []}))
    monkeypatch.setattr("whf.cli.default_narrator", lambda model=None: ok)
    out = runner.invoke(app, ["run", "--db", str(db), "--team", "1", "--as-of", "2026-09-03", "--json", "--ai"])
    assert out.exit_code == 0
    payload = json.loads(out.output)  # a second JSON document on stdout would break this
    assert payload["ai_status"] == "ok" and payload["team_id"] == 1 and "forecasts" in payload


def test_cli_run_ai_exits_4_when_narration_fails_but_still_prints_the_forecast(monkeypatch, tmp_path) -> None:
    db = _db(tmp_path)
    failed = FakeNarrator(NarrativeOutcome(status="failed", reason="not_signed_in", error="sign in first"))
    monkeypatch.setattr("whf.cli.default_narrator", lambda model=None: failed)
    out = runner.invoke(app, ["run", "--db", str(db), "--team", "1", "--as-of", "2026-09-03", "--ai"])
    assert out.exit_code == 4
    assert "narrative: failed:not_signed_in" in out.output

    out_json = runner.invoke(app, ["run", "--db", str(db), "--team", "1", "--as-of", "2026-09-03", "--json", "--ai"])
    assert out_json.exit_code == 4
    payload = json.loads(out_json.output)  # the forecast must still be the single JSON document on stdout
    assert payload["team_id"] == 1 and "forecasts" in payload and payload["ai_status"].startswith("failed")


@pytest.fixture()
def client(tmp_path):
    """A client with the token already set, backed by a fake narrator that reports one step."""
    db = _db(tmp_path)
    narrator = FakeNarrator(NarrativeOutcome(status="ok", narrative={"run_summary": "fine", "members": []}))
    test_client = TestClient(
        create_app(db, TOKEN, narrator_factory=lambda model=None: narrator, status_provider=_ready)
    )
    test_client.headers.update({"X-WHF-Token": TOKEN})
    return test_client


@pytest.fixture()
def client_without_token(tmp_path):
    db = _db(tmp_path)
    return TestClient(create_app(db, TOKEN, status_provider=_ready))


def _run_id(client) -> int:
    return client.post("/runs", json={"team_id": 1, "as_of": "2026-09-03"}).json()["run_id"]


def test_narrating_records_the_steps_the_app_polls_for(client) -> None:
    """The POST blocks while Copilot works, so the app polls this route to show what is happening."""
    run_id = _run_id(client)
    assert client.get(f"/runs/{run_id}/narrative/progress").json()["steps"] == []
    client.post(f"/runs/{run_id}/narrative", json={"model": None})
    steps = client.get(f"/runs/{run_id}/narrative/progress").json()["steps"]
    assert [s["code"] for s in steps] == ["asking"]


def test_progress_of_an_unknown_run_is_empty_rather_than_an_error(client) -> None:
    """The app polls as soon as it sends the POST; a poll that arrives first must not be a 404."""
    assert client.get("/runs/999/narrative/progress").json() == {"run_id": 999, "steps": []}


def test_narrative_of_an_unknown_run_does_not_evict_a_real_run_being_narrated(client) -> None:
    """`begin` must run only after the run is known to exist, or a run of 404s can push a real
    run's steps out of the store's bounded eight-run history while it is still being narrated.
    """
    run_id = _run_id(client)
    client.post(f"/runs/{run_id}/narrative", json={"model": None})
    steps_before = client.get(f"/runs/{run_id}/narrative/progress").json()["steps"]
    assert steps_before  # sanity: the real run has recorded steps
    for unknown_id in range(9001, 9009):  # 8 unknown ids: the store keeps only the last 8 runs
        assert client.post(f"/runs/{unknown_id}/narrative", json={"model": None}).status_code == 404
        assert client.get(f"/runs/{unknown_id}/narrative/progress").json()["steps"] == []
    assert client.get(f"/runs/{run_id}/narrative/progress").json()["steps"] == steps_before


def test_the_progress_route_needs_the_token(client_without_token) -> None:
    assert client_without_token.get("/runs/1/narrative/progress").status_code == 401


def test_api_copilot_status_and_narrative(tmp_path) -> None:
    db = _db(tmp_path)
    ok = FakeNarrator(
        NarrativeOutcome(
            status="unverified",
            narrative={"run_summary": "x", "members": []},
            raw_text='{"run_summary": "x", "members": []}',
            verification={"checked": 1, "unverified": ["run_summary: 5.5 is not in the facts"], "fields": {}},
        )
    )
    client = TestClient(create_app(db, TOKEN, narrator_factory=lambda model=None: ok, status_provider=_ready))
    h = {"X-WHF-Token": TOKEN}
    status = client.get("/copilot/status", headers=h).json()
    assert status["ready"] is True and status["login"] == "sara"
    assert client.get("/copilot/status").status_code == 401
    run_id = client.post("/runs", json={"team_id": 1, "as_of": "2026-09-03"}, headers=h).json()["run_id"]
    body = client.post(f"/runs/{run_id}/narrative", json={}, headers=h).json()
    assert body["ai_status"] == "unverified" and body["verification"]["unverified"]
    assert "raw_text" not in body  # not sent over HTTP; kept in the stored document only
    assert client.get(f"/runs/{run_id}", headers=h).json()["run"]["ai_status"] == "unverified"
    assert client.post("/runs/999/narrative", json={}, headers=h).status_code == 404
    conn = connect(db)
    conn.execute(
        "INSERT INTO runs (id, team_id, as_of, status, started_at) VALUES (1000, 1, '2026-09-03', 'done', "
        "'2026-09-03T00:00:00')"
    )
    conn.commit()
    conn.close()
    assert client.post("/runs/1000/narrative", json={}, headers=h).status_code == 409
    seen_models: list = []
    client2 = TestClient(
        create_app(
            db, TOKEN, narrator_factory=lambda model=None: seen_models.append(model) or ok, status_provider=_ready
        )
    )
    client2.post(f"/runs/{run_id}/narrative", json={"model": "gpt-5"}, headers=h)
    assert seen_models == ["gpt-5"]
