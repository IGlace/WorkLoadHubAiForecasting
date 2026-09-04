import datetime as dt

from whf.overview import department_overview, latest_ok_run_id, run_is_due
from whf.pipeline import run_forecast

NOW = dt.datetime(2026, 9, 21, 9, 0)


def test_run_is_due_without_a_run_or_after_14_days() -> None:
    assert run_is_due(None, NOW) is True
    assert run_is_due("2026-09-08T10:00:00", NOW) is False
    assert run_is_due("2026-09-07T08:59:00", NOW) is True
    assert run_is_due("2026-09-07T09:00:00", NOW) is False


def test_latest_ok_run_and_overview(db, generated) -> None:
    as_of = generated.config.as_of
    assert latest_ok_run_id(db, 1) is None
    first = run_forecast(db, team_id=1, as_of=as_of)
    second = run_forecast(db, team_id=1, as_of=as_of)
    assert latest_ok_run_id(db, 1) == second.run_id > first.run_id

    overview = department_overview(db, 1, dt.datetime.combine(as_of, dt.time(9)))
    assert overview["department_id"] == 1
    teams = {t["team_id"]: t for t in overview["teams"]}
    ran = teams[1]
    assert ran["run_id"] == second.run_id and ran["due"] is False and len(ran["weeks"]) == 2
    assert ran["weeks"][0]["week"] == second.weeks[0].isoformat()
    assert (
        abs(
            ran["weeks"][0]["demand"]
            - float(second.forecasts[second.forecasts.week_start == second.weeks[0]].demand_hours.sum())
        )
        < 0.11
    )
    assert all({"member_id", "name", "overload_hours"} <= set(o) for o in ran["overloaded"])
    not_run = next(t for tid, t in teams.items() if tid != 1)
    assert not_run["run_id"] is None and not_run["due"] is True and not_run["weeks"] == []
