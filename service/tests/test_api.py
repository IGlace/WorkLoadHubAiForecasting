import pytest
from fastapi.testclient import TestClient

from whf.api import create_app
from whf.data.generator import GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect

TOKEN = "secret-token"


@pytest.fixture()
def client(tmp_path):
    db = tmp_path / "api.db"
    conn = connect(db)
    load_generated(conn, generate(GeneratorConfig(seed=5, months=6)))
    conn.close()
    return TestClient(create_app(db, TOKEN))


def _h() -> dict[str, str]:
    return {"X-WHF-Token": TOKEN}


def test_health_is_public_and_others_need_token(client) -> None:
    assert client.get("/health").json()["status"] == "ok"
    assert client.get("/meta").status_code == 401
    assert client.get("/meta", headers={"X-WHF-Token": "wrong"}).status_code == 401
    assert (
        client.post(
            "/vacations", json={"member_id": 1, "start_date": "2026-09-21", "end_date": "2026-09-23"}
        ).status_code
        == 401
    )
    meta = client.get("/meta", headers=_h()).json()
    assert len(meta["departments"]) == 3 and meta["capacity_default"] == 40.0
    assert {"id", "name", "team_id", "role"} <= set(meta["members"][0])


def test_non_ascii_token_header_is_rejected_not_500(client) -> None:
    # raw bytes bypass httpx's client-side ascii check; the server decodes header
    # bytes as latin-1 per the HTTP spec, so this arrives as a non-ASCII str.
    response = client.get("/meta", headers={"X-WHF-Token": "wrong-token-café".encode()})
    assert response.status_code == 401


def test_run_and_fetch(client) -> None:
    created = client.post("/runs", json={"team_id": 1, "as_of": "2026-09-03"}, headers=_h())
    assert created.status_code == 200, created.text
    body = created.json()
    assert body["run_id"] == 1 and len(body["forecasts"]) >= 8 and body["weeks"] == ["2026-09-07", "2026-09-14"]
    listed = client.get("/runs", params={"team_id": 1}, headers=_h()).json()
    assert [r["id"] for r in listed] == [1]
    one = client.get("/runs/1", headers=_h()).json()
    assert one["run"]["id"] == 1 and one["facts"]["team"]["id"] == 1 and one["narrative"] is None
    assert client.get("/runs/99", headers=_h()).status_code == 404
    assert client.post("/runs", json={"team_id": 999}, headers=_h()).status_code == 404


def test_projects_capacity_and_vacations(client) -> None:
    bad = client.post(
        "/projects",
        json={"name": "X", "department_id": 1, "start_date": "2026-10-01", "deadline": "2026-09-01", "team_ids": [1]},
        headers=_h(),
    )
    assert bad.status_code == 422
    ok = client.post(
        "/projects",
        json={
            "name": "X",
            "department_id": 1,
            "start_date": "2026-09-10",
            "deadline": "2026-11-01",
            "team_ids": [1, 2],
        },
        headers=_h(),
    )
    assert ok.status_code == 200 and ok.json()["id"] > 0
    projects = client.get("/projects", headers=_h()).json()
    assert any(p["name"] == "X" and p["team_ids"] == [1, 2] for p in projects)
    assert client.put("/capacity/default", json={"weekly_hours": 36}, headers=_h()).status_code == 200
    assert (
        client.put(
            "/capacity/overrides", json={"member_id": 2, "week_start": "2026-09-14", "weekly_hours": 16}, headers=_h()
        ).status_code
        == 200
    )
    cap = client.get("/capacity", headers=_h()).json()
    assert cap["default_weekly_hours"] == 36.0 and cap["overrides"][0]["member_id"] == 2
    client.put("/capacity/overrides", json={"member_id": 3, "week_start": None, "weekly_hours": 20}, headers=_h())
    client.put("/capacity/overrides", json={"member_id": 3, "week_start": None, "weekly_hours": 24}, headers=_h())
    cap2 = client.get("/capacity", headers=_h()).json()
    permanent_for_3 = [o for o in cap2["overrides"] if o["member_id"] == 3 and o["week_start"] is None]
    assert len(permanent_for_3) == 1 and permanent_for_3[0]["weekly_hours"] == 24.0
    vac = client.post(
        "/vacations", json={"member_id": 2, "start_date": "2026-09-21", "end_date": "2026-09-23"}, headers=_h()
    )
    assert vac.status_code == 200
    mine = client.get("/vacations", params={"member_id": 2}, headers=_h()).json()
    assert any(v["start_date"] == "2026-09-21" for v in mine)


def test_profile_round_trip(client) -> None:
    assert client.get("/profile", headers=_h()).json() == {"member_id": None, "role": None}
    meta = client.get("/meta", headers=_h()).json()
    leader = next(m for m in meta["members"] if m["role"] == "skill_team_leader")
    put = client.put("/profile", json={"member_id": leader["id"]}, headers=_h())
    assert put.status_code == 200 and put.json() == {"member_id": leader["id"], "role": "skill_team_leader"}
    assert client.get("/profile", headers=_h()).json()["role"] == "skill_team_leader"
    assert client.put("/profile", json={"member_id": 999999}, headers=_h()).status_code == 404
    assert client.put("/profile", json={"member_id": None}, headers=_h()).json() == {"member_id": None, "role": None}


def test_holidays_are_listed_and_filtered_by_year(client) -> None:
    rows = client.get("/holidays", headers=_h()).json()
    assert rows and {"date", "name", "country"} <= set(rows[0])
    year = int(rows[0]["date"][:4])
    filtered = client.get(f"/holidays?year={year}", headers=_h()).json()
    assert filtered and all(r["date"].startswith(str(year)) for r in filtered)
    assert client.get("/holidays?year=1900", headers=_h()).json() == []


def test_project_update_and_deletes(client) -> None:
    created = client.post(
        "/projects",
        json={
            "name": "Gamma",
            "department_id": 1,
            "start_date": "2026-10-05",
            "deadline": "2026-11-27",
            "team_ids": [1],
        },
        headers=_h(),
    ).json()
    body = {
        "name": "Gamma 2",
        "start_date": "2026-10-12",
        "deadline": "2026-12-04",
        "team_ids": [1, 2],
        "type": "delivery",
        "status": "active",
    }
    updated = client.put(f"/projects/{created['id']}", json=body, headers=_h())
    assert updated.status_code == 200 and updated.json()["team_ids"] == [1, 2] and updated.json()["status"] == "active"
    assert client.put("/projects/999999", json=body, headers=_h()).status_code == 404
    bad = {**body, "deadline": "2026-10-12"}
    assert client.put(f"/projects/{created['id']}", json=bad, headers=_h()).status_code == 422

    client.put(
        "/capacity/overrides", json={"member_id": 1, "weekly_hours": 30, "week_start": "2026-10-05"}, headers=_h()
    )
    overrides = client.get("/capacity", headers=_h()).json()["overrides"]
    oid = next(o["id"] for o in overrides if o["member_id"] == 1 and o["week_start"] == "2026-10-05")
    assert client.delete(f"/capacity/overrides/{oid}", headers=_h()).json() == {"deleted": True}
    assert client.delete(f"/capacity/overrides/{oid}", headers=_h()).status_code == 404

    vid = client.post(
        "/vacations", json={"member_id": 1, "start_date": "2026-10-05", "end_date": "2026-10-06"}, headers=_h()
    ).json()["id"]
    assert client.delete(f"/vacations/{vid}", headers=_h()).json() == {"deleted": True}
    assert client.delete(f"/vacations/{vid}", headers=_h()).status_code == 404
