import datetime as dt
import json

import pandas as pd
import pytest

from whf.calendar import forecast_weeks
from whf.data.generator import GeneratorConfig, generate, truncate_to
from whf.data.loader import load_generated
from whf.db.connection import connect
from whf.db.repo import read_df
from whf.pipeline import jsonable, list_runs, load_run, run_forecast


def test_run_forecast_produces_two_weeks_per_counted_member(db, generated) -> None:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of, requested_by=None)
    counted = [m for m in generated.members if m["team_id"] == 1 and m["counted_in_workload"]]
    f1, f2 = forecast_weeks(generated.config.as_of)
    assert result.weeks == (f1, f2)
    assert len(result.forecasts) == 2 * len(counted)
    assert set(result.forecasts["week_start"]) == {f1, f2}
    f = result.forecasts
    assert (f["demand_hours"] >= 0).all() and (f["capacity_hours"] <= 40.0 + 1e-9).all()
    assert (f["overload_hours"] == (f["demand_hours"] - f["capacity_hours"]).clip(lower=0)).all()
    assert (f["demand_low"] <= f["demand_hours"] + 1e-9).all() and (f["demand_high"] >= f["demand_hours"] - 1e-9).all()
    assert (abs(f["demand_hours"] - (f["open_task_hours"] + f["new_task_hours"])) < 1e-6).all()
    assert result.champion in {"seasonal_naive", "tsb", "gbm"}


def test_run_is_persisted_with_facts(db, generated) -> None:
    result = run_forecast(db, team_id=2, as_of=generated.config.as_of)
    runs = read_df(db, "SELECT * FROM runs")
    assert runs["status"].tolist() == ["done"] and runs["champion_model"][0] == result.champion
    stored = read_df(db, "SELECT COUNT(*) AS n FROM forecasts WHERE run_id = ?", (result.run_id,))["n"][0]
    assert stored == len(result.forecasts)
    facts = json.loads(read_df(db, "SELECT json FROM run_facts WHERE run_id = ?", (result.run_id,))["json"][0])
    assert set(facts) >= {"run", "team", "members", "projects", "model", "rebalancing_candidates"}
    member = facts["members"][0]
    assert set(member) >= {"id", "name", "role", "history_13w", "forecast", "patterns", "open_tasks"}
    assert len(member["forecast"]) == 2 and len(member["history_13w"]) == 13
    assert facts["model"]["champion"] == result.champion
    loaded = load_run(db, result.run_id)
    assert loaded["run"]["id"] == result.run_id and loaded["narrative"] is None
    assert len(loaded["forecasts"]) == len(result.forecasts)
    assert list_runs(db, team_id=2)["id"].tolist() == [result.run_id]


def test_vacation_reduces_capacity(db, generated) -> None:
    as_of = generated.config.as_of
    f1, _ = forecast_weeks(as_of)
    member = next(m["id"] for m in generated.members if m["team_id"] == 1 and m["counted_in_workload"])
    db.execute(
        "INSERT INTO vacations (member_id, start_date, end_date, type) VALUES (?, ?, ?, 'vacation')",
        (member, f1.isoformat(), (f1 + dt.timedelta(days=6)).isoformat()),
    )
    db.commit()
    result = run_forecast(db, team_id=1, as_of=as_of)
    row = result.forecasts[(result.forecasts.member_id == member) & (result.forecasts.week_start == f1)].iloc[0]
    assert row["capacity_hours"] == 0.0


def test_capacity_override_applies(db, generated) -> None:
    as_of = generated.config.as_of
    f1, f2 = forecast_weeks(as_of)
    member = next(m["id"] for m in generated.members if m["team_id"] == 1 and m["counted_in_workload"])
    db.execute(
        "INSERT INTO capacity_overrides (member_id, week_start, weekly_hours, reason) VALUES (?, ?, 20.0, 'internal')",
        (member, f2.isoformat()),
    )
    db.commit()
    result = run_forecast(db, team_id=1, as_of=as_of)
    rows = result.forecasts[result.forecasts.member_id == member].set_index("week_start")
    assert rows.loc[f2, "capacity_hours"] <= 20.0
    assert rows.loc[f1, "capacity_hours"] > 20.0


def test_jsonable_converts_dates_and_numpy() -> None:
    import numpy as np

    value = jsonable({"d": dt.date(2026, 9, 7), "n": np.float64(1.5), "nan": float("nan"), "list": [np.int64(2)]})
    assert value == {"d": "2026-09-07", "n": 1.5, "nan": None, "list": [2]}


@pytest.mark.slow
def test_accuracy_gate_against_hidden_effort_log() -> None:
    full = generate(GeneratorConfig(seed=42, as_of=dt.date(2026, 9, 24)))
    as_of = dt.date(2026, 9, 3)
    conn = connect(":memory:")
    load_generated(conn, truncate_to(full, as_of))
    f1, f2 = forecast_weeks(as_of)
    truth = pd.DataFrame(full.answer_key["effort_by_member_week"])
    truth["week_start"] = [dt.date.fromisoformat(w) for w in truth["week_start"]]
    errors = []
    for team in [t["id"] for t in full.teams]:
        result = run_forecast(conn, team_id=team, as_of=as_of)
        members = set(result.forecasts["member_id"])
        actual = truth[(truth.member_id.isin(members)) & (truth.week_start.isin([f1, f2]))]["hours"].sum()
        predicted = result.forecasts["demand_hours"].sum()
        errors.append(abs(predicted - actual) / max(actual, 1.0))
        assert result.backtest_mase < 1.05
    assert sum(errors) / len(errors) < 0.45
