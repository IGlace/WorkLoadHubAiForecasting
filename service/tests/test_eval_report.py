import datetime as dt

import pandas as pd

from whf.eval.harness import EvalConfig, EvalResult
from whf.eval.report import summary_tables, write_outputs

W = dt.date(2026, 8, 3)


def _result() -> EvalResult:
    scores = pd.DataFrame(
        [
            {"model": "tsb", "horizon": 1, "origin": W, "metric": "mase", "value": 0.8},
            {"model": "tsb", "horizon": 1, "origin": W - dt.timedelta(days=14), "metric": "mase", "value": 1.0},
        ]
    )
    demand = pd.DataFrame(
        [
            {
                "model": "tsb",
                "origin": W,
                "team_id": 1,
                "member_id": 1,
                "week_start": W,
                "forecast": 50.0,
                "truth": 40.0,
                "capacity": 44.0,
                "open_hours": 30.0,
                "new_hours": 20.0,
            },
            {
                "model": "tsb",
                "origin": W,
                "team_id": 1,
                "member_id": 2,
                "week_start": W,
                "forecast": 20.0,
                "truth": 46.0,
                "capacity": 44.0,
                "open_hours": 20.0,
                "new_hours": 0.0,
            },
        ]
    )
    return EvalResult(
        scores=scores,
        demand=demand,
        skipped={"chronos2": "no weights"},
        truth_source="answer key",
        elapsed_seconds=1.5,
        origins=[W],
    )


def test_summary_tables_aggregate_levels() -> None:
    a, b = summary_tables(_result())
    assert a.loc[(a.model == "tsb") & (a.horizon == 1), "mase"].item() == 0.9
    row = b[b.model == "tsb"].iloc[0]
    assert row["mae"] == 18.0 and row["bias"] == -8.0 and row["open_only_mae"] == 18.0
    assert row["overload_precision"] == 0.0 and row["overload_recall"] == 0.0 and row["rows"] == 2


def test_summary_tables_keeps_every_metric_column_even_when_all_nan() -> None:
    scores = pd.DataFrame(
        [
            {"model": "tsb", "horizon": 1, "origin": W, "metric": "mase", "value": 0.8},
        ]
    )
    result = EvalResult(
        scores=scores, demand=pd.DataFrame(columns=["model", "truth", "forecast", "capacity", "open_hours"])
    )
    a, _ = summary_tables(result)
    assert list(a.columns) == ["model", "horizon", "mae", "mase", "beats_naive", "coverage80", "wql", "seconds"]
    assert a["mase"].iloc[0] == 0.8
    assert pd.isna(a["wql"].iloc[0])


def test_write_outputs_creates_the_three_files_with_the_fingerprint(tmp_path) -> None:
    out = write_outputs(
        _result(),
        fingerprint={
            "members": 2,
            "teams": 1,
            "tasks": 9,
            "first_assigned": "2026-01-05",
            "last_assigned": "2026-08-01",
            "sha256": "ab" * 32,
        },
        config=EvalConfig(as_of=W, origins=1, models=("tsb",)),
        out_dir=tmp_path / "eval",
        versions={"whf": "0.1.0", "torch": "absent"},
    )
    assert {p.name for p in out.iterdir()} == {"scores.csv", "demand.csv", "summary.md"}
    text = (out / "summary.md").read_text(encoding="utf-8")
    assert (
        "answer key" in text and "chronos2" in text and "no weights" in text and "ab" * 32 in text and "torch" in text
    )
    assert "| tsb |" in text
