import json

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


def test_api_copilot_status_and_narrative(tmp_path) -> None:
    db = _db(tmp_path)
    ok = FakeNarrator(
        NarrativeOutcome(
            status="unverified",
            narrative={"run_summary": "x", "members": []},
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
