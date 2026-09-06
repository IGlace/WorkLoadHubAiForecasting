# Forecast Evaluation Harness and Chronos-2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `whf eval` command that measures arrival-model accuracy and demand-forecast accuracy on any database and writes committable result files, plus Chronos-2 as a fourth arrival-model candidate with its own quantile bands, bundled offline in the installer.

**Architecture:** The harness lives in a new package `whf.eval` (metrics, truth, harness, report) and reuses the existing rolling backtest and pipeline through two small hooks: `run_forecast(..., force_model=, persist=)` and a `truncated_copy` of the database as of a past origin. The backtest learns to skip an unavailable model, time each model and collect native quantiles. Chronos-2 is one module under `whf.models` implementing the existing `ArrivalModel` protocol with the pipeline injectable for tests, a process-wide lazy loader, and offline weight resolution. Packaging pins torch to the CPU wheel index, downloads the pinned weights at build time next to the Copilot CLI, and extends the frozen smoke test.

**Tech Stack:** Python 3.11, pandas, numpy, scikit-learn, Typer, SQLite, Hypothesis; `chronos-forecasting` 2.3.x with `torch` 2.14 CPU; PyInstaller with `pyinstaller-hooks-contrib` (hooks for torch and transformers exist); electron-builder unchanged.

**Spec:** `docs/superpowers/specs/2026-09-06-forecast-evaluation-and-chronos2-design.md` (sections referenced as §n below). The earlier engine design is `docs/superpowers/specs/2026-09-03-workload-forecast-design.md` §5.

## Global Constraints

- Everything runs on Windows in PowerShell; CPU only, no GPU; nothing downloaded on a user's machine at install or run time (§2).
- The language model never produces a forecast number (unchanged). Demand is never capped by capacity.
- Test-driven development; property tests (Hypothesis) for arithmetic invariants; `uv run ruff check . && uv run ruff format --check .` clean; ruff line length 120; run Python commands from `service/`.
- The fast test suite must not import `torch` (adapter tests inject a stub pipeline); the real-weights test is marked `slow`.
- Frozen smoke test (`installer/pyinstaller/smoke_frozen.py`) stays standard-library only.
- English and French display names for every model shown in the app (§4.3).
- Chronos-2 weights: repository `amazon/chronos-2`, revision `29ec3766d36d6f73f0696f85560a422f50e8498c`, Apache 2.0; measured 478 MB; torch CPU wheel 728 MB unpacked (§5.1).
- Model names used everywhere: `seasonal_naive`, `tsb`, `gbm`, `chronos2`, and the harness-only `chronos2_ft`.
- Replay convention (§3.3): for a backtest origin `o` (the Monday of the last complete week), the replay date is `as_of = o + ONE_WEEK`, so `last_complete_week(as_of) == o` and `forecast_weeks(as_of) == (o + 1 week, o + 2 weeks)`, horizons 1 and 2 exactly as a live run.
- Commit messages: imperative subject, short body explaining why, footer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01CqJ57Eq8raMBmwPaDj5FVU`. Never `--no-verify`; the pre-commit hook may run the fast gate, so allow `git commit` a long timeout. Branch `dev`.

---

### Task 1: Evaluation metrics

**Files:**
- Create: `service/src/whf/eval/__init__.py` (empty docstring module: `"""Evaluation harness: metrics, truth, replay and reports."""`)
- Create: `service/src/whf/eval/metrics.py`
- Test: `service/tests/test_eval_metrics.py`

**Interfaces:**
- Consumes: numpy only.
- Produces: `mae(y_true, y_pred) -> float`, `bias(y_true, y_pred) -> float` (mean of pred minus true), `coverage(y_true, low, high) -> float` (share of `low <= y <= high`, `nan` on empty), `weighted_quantile_loss(y_true, quantile_preds: dict[float, np.ndarray]) -> float` (mean over quantiles of `2 * mean(max(q*(y-p), (q-1)*(y-p)))` divided by `mean(|y|)`; `nan` when `mean(|y|) == 0`), `overload_precision_recall(true_over, pred_over) -> tuple[float, float]` (`nan` precision when nothing predicted positive, `nan` recall when nothing truly positive). Tasks 5 and 6 call these by name.

- [ ] **Step 1: Write the failing tests**

```python
# service/tests/test_eval_metrics.py
import numpy as np
from hypothesis import given
from hypothesis import strategies as st
from hypothesis.extra.numpy import arrays

from whf.eval.metrics import bias, coverage, mae, overload_precision_recall, weighted_quantile_loss


def test_mae_and_bias_on_hand_made_values() -> None:
    y, p = np.array([10.0, 20.0, 30.0]), np.array([12.0, 18.0, 33.0])
    assert mae(y, p) == 7.0 / 3
    assert abs(bias(y, p) - 1.0) < 1e-12


def test_coverage_counts_inclusive_bounds() -> None:
    y = np.array([1.0, 2.0, 3.0, 4.0])
    assert coverage(y, np.array([1.0, 3.0, 0.0, 0.0]), np.array([1.0, 3.0, 2.0, 5.0])) == 0.5
    assert np.isnan(coverage(np.array([]), np.array([]), np.array([])))


def test_weighted_quantile_loss_is_zero_for_perfect_quantiles_and_nan_on_zero_truth() -> None:
    y = np.array([2.0, 4.0])
    perfect = {0.1: y, 0.5: y, 0.9: y}
    assert weighted_quantile_loss(y, perfect) == 0.0
    assert np.isnan(weighted_quantile_loss(np.zeros(2), perfect))
    worse = {0.5: y + 1.0}
    assert weighted_quantile_loss(y, worse) == 2 * 0.5 * 1.0 / 3.0


def test_overload_precision_recall_hand_made_and_degenerate() -> None:
    truth = np.array([True, True, False, False])
    pred = np.array([True, False, True, False])
    assert overload_precision_recall(truth, pred) == (0.5, 0.5)
    p, r = overload_precision_recall(np.array([False, False]), np.array([False, False]))
    assert np.isnan(p) and np.isnan(r)


finite = st.floats(min_value=0.0, max_value=1e4, allow_nan=False, allow_infinity=False)


@given(arrays(np.float64, st.integers(1, 30), elements=finite), st.floats(0.0, 10.0), st.floats(0.0, 10.0))
def test_coverage_in_unit_interval_and_wql_non_negative(y, a, b) -> None:
    low, high = y - a, y + b
    c = coverage(y, low, high)
    assert 0.0 <= c <= 1.0
    if np.mean(np.abs(y)) > 0:
        assert weighted_quantile_loss(y, {0.1: low, 0.5: y, 0.9: high}) >= 0.0


@given(arrays(np.bool_, st.integers(1, 30)), arrays(np.bool_, st.integers(1, 30)))
def test_precision_recall_within_unit_interval(t, p) -> None:
    n = min(len(t), len(p))
    prec, rec = overload_precision_recall(t[:n], p[:n])
    for v in (prec, rec):
        assert np.isnan(v) or 0.0 <= v <= 1.0
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run pytest tests/test_eval_metrics.py -q`
Expected: ImportError on `whf.eval.metrics`.

- [ ] **Step 3: Implement the metrics**

```python
# service/src/whf/eval/metrics.py
"""Pure metric functions for the evaluation harness. Every function accepts numpy arrays of equal length."""

from __future__ import annotations

import numpy as np


def _arr(*values: object) -> list[np.ndarray]:
    return [np.asarray(v, dtype=float) for v in values]


def mae(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    y, p = _arr(y_true, y_pred)
    return float(np.mean(np.abs(y - p))) if len(y) else float("nan")


def bias(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    """Mean of forecast minus truth: positive means the forecast runs high."""
    y, p = _arr(y_true, y_pred)
    return float(np.mean(p - y)) if len(y) else float("nan")


def coverage(y_true: np.ndarray, low: np.ndarray, high: np.ndarray) -> float:
    y, lo, hi = _arr(y_true, low, high)
    if len(y) == 0:
        return float("nan")
    return float(np.mean((lo <= y) & (y <= hi)))


def weighted_quantile_loss(y_true: np.ndarray, quantile_preds: dict[float, np.ndarray]) -> float:
    """Mean over quantiles of the scaled pinball loss, as used by the GIFT-Eval benchmark."""
    (y,) = _arr(y_true)
    scale = float(np.mean(np.abs(y))) if len(y) else 0.0
    if scale == 0.0 or not quantile_preds:
        return float("nan")
    losses = []
    for q, pred in quantile_preds.items():
        (p,) = _arr(pred)
        diff = y - p
        losses.append(2.0 * float(np.mean(np.maximum(q * diff, (q - 1.0) * diff))))
    return float(np.mean(losses)) / scale


def overload_precision_recall(true_over: np.ndarray, pred_over: np.ndarray) -> tuple[float, float]:
    t, p = np.asarray(true_over, dtype=bool), np.asarray(pred_over, dtype=bool)
    tp = float(np.sum(t & p))
    precision = tp / float(np.sum(p)) if np.sum(p) else float("nan")
    recall = tp / float(np.sum(t)) if np.sum(t) else float("nan")
    return precision, recall
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `uv run pytest tests/test_eval_metrics.py -q && uv run ruff check . && uv run ruff format --check .`
Expected: all pass, ruff clean.

- [ ] **Step 5: Commit**

```bash
git add service/src/whf/eval/__init__.py service/src/whf/eval/metrics.py service/tests/test_eval_metrics.py
git commit -m "feat(eval): metric functions for the evaluation harness

MAE, bias, interval coverage, weighted quantile loss and overload
precision/recall, as pure functions with property tests, so the harness
and its report share one definition of every number."
```

---

### Task 2: Truth series, data fingerprint and the truncated copy

**Files:**
- Create: `service/src/whf/eval/truth.py`
- Test: `service/tests/test_eval_truth.py`

**Interfaces:**
- Consumes: `whf.db.repo.read_df`, `with_dates`, `insert_rows`, `table_names`; `whf.db.connection.connect`; `whf.models.effort.place_hours(hours, start, end, off) -> dict[week_start, hours]`; `whf.calendar.week_start`.
- Produces: `truth_from_answer_key(path: Path) -> pd.DataFrame[member_id:int, week_start:date, hours:float]`; `realised_hours(conn) -> same frame` (§3.3, the single place the real-data truth is defined); `data_fingerprint(conn, db_file: Path | None) -> dict`; `truncated_copy(conn, as_of: dt.date) -> sqlite3.Connection` (in-memory database as it looked on `as_of`: tasks assigned after `as_of` dropped, tasks completed after `as_of` reopened with `completed_at`, `actual_hours` cleared and `status = 'in_progress'`, runs and forecasts not copied). Task 5 uses all four.

- [ ] **Step 1: Write the failing tests**

```python
# service/tests/test_eval_truth.py
import datetime as dt
import json

from whf.data.generator import GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect
from whf.db.repo import read_df
from whf.eval.truth import data_fingerprint, realised_hours, truncated_copy, truth_from_answer_key


def test_truth_from_answer_key_reads_member_week_hours(tmp_path) -> None:
    key = tmp_path / "answer_key.json"
    key.write_text(json.dumps({"effort_by_member_week": [{"member_id": 3, "week_start": "2026-08-03", "hours": 12.5}]}))
    truth = truth_from_answer_key(key)
    assert truth.to_dict(orient="records") == [{"member_id": 3, "week_start": dt.date(2026, 8, 3), "hours": 12.5}]


def test_realised_hours_spreads_actual_hours_over_working_days_of_the_task_window() -> None:
    conn = connect(":memory:")
    conn.execute("INSERT INTO departments (id, name) VALUES (1, 'D')")
    conn.execute("INSERT INTO teams (id, name, department_id) VALUES (1, 'T', 1)")
    conn.execute(
        "INSERT INTO members (id, name, team_id, role, counted_in_workload) VALUES (7, 'M', 1, 'member', 1)"
    )
    # Wed 2026-08-05 to Tue 2026-08-11: 5 working days, 3 in the first week, 2 in the second
    conn.execute(
        "INSERT INTO tasks (id, title, assignee_id, team_id, type, priority, status, created_at, assigned_at,"
        " completed_at, estimated_hours, actual_hours) VALUES (1, 't', 7, 1, 'dev', 'p2', 'done', '2026-08-05',"
        " '2026-08-05', '2026-08-11', 8.0, 10.0)"
    )
    conn.commit()
    out = realised_hours(conn).sort_values("week_start").to_dict(orient="records")
    assert out == [
        {"member_id": 7, "week_start": dt.date(2026, 8, 3), "hours": 6.0},
        {"member_id": 7, "week_start": dt.date(2026, 8, 10), "hours": 4.0},
    ]


def test_truncated_copy_hides_the_future(db, generated) -> None:
    as_of = generated.config.as_of - dt.timedelta(days=60)
    copy = truncated_copy(db, as_of)
    tasks = read_df(copy, "SELECT assigned_at, completed_at, actual_hours, status FROM tasks")
    assert (tasks["assigned_at"].str[:10] <= as_of.isoformat()).all()
    later = tasks["completed_at"].dropna().str[:10]
    assert (later <= as_of.isoformat()).all()
    reopened = tasks[tasks["completed_at"].isna()]
    assert reopened["actual_hours"].isna().all() and (reopened["status"] != "done").all()
    assert read_df(copy, "SELECT COUNT(*) AS n FROM runs")["n"][0] == 0
    assert read_df(copy, "SELECT COUNT(*) AS n FROM members")["n"][0] == len(generated.members)
    assert read_df(copy, "SELECT weekly_hours FROM capacity_defaults")["weekly_hours"][0] == 44.0


def test_data_fingerprint_names_counts_range_and_hash(tmp_path) -> None:
    db_file = tmp_path / "f.db"
    conn = connect(db_file)
    load_generated(conn, generate(GeneratorConfig(seed=1, months=3)))
    fp = data_fingerprint(conn, db_file)
    assert fp["members"] > 0 and fp["tasks"] > 0 and fp["teams"] > 0
    assert fp["first_assigned"] <= fp["last_assigned"]
    assert len(fp["sha256"]) == 64
    assert data_fingerprint(conn, None)["sha256"] is None
```

The `db` and `generated` fixtures already exist in `service/tests/conftest.py`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run pytest tests/test_eval_truth.py -q`
Expected: ImportError on `whf.eval.truth`.

- [ ] **Step 3: Implement**

```python
# service/src/whf/eval/truth.py
"""What the forecast is measured against, and how to look at the database as it was on a past date."""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import sqlite3
from pathlib import Path

import pandas as pd

from whf.db.connection import connect
from whf.db.repo import insert_rows, read_df, table_names, with_dates
from whf.models.effort import place_hours

TRUTH_COLUMNS = ["member_id", "week_start", "hours"]
COPIED_TABLES = (
    "departments",
    "teams",
    "members",
    "projects",
    "project_teams",
    "holidays",
    "vacations",
    "capacity_overrides",
    "profiles",
)


def truth_from_answer_key(path: Path) -> pd.DataFrame:
    """The generator's true hours per member and week (`effort_by_member_week`)."""
    rows = json.loads(Path(path).read_text(encoding="utf-8"))["effort_by_member_week"]
    out = pd.DataFrame(rows, columns=TRUTH_COLUMNS)
    out["member_id"] = out["member_id"].astype(int)
    out["week_start"] = [dt.date.fromisoformat(str(w)[:10]) for w in out["week_start"]]
    out["hours"] = out["hours"].astype(float)
    return out


def realised_hours(conn: sqlite3.Connection) -> pd.DataFrame:
    """Real-data truth: each completed task's actual hours spread evenly over the working days between
    assignment and completion (holidays from the holidays table), summed per member and week.

    This is the only place that definition lives; replace the body if the export carries logged hours.
    """
    tasks = with_dates(
        read_df(conn, "SELECT assignee_id, assigned_at, completed_at, actual_hours FROM tasks"),
        ["assigned_at", "completed_at"],
    )
    holidays = {d for d in with_dates(read_df(conn, "SELECT date FROM holidays"), ["date"])["date"] if d}
    done = tasks.dropna(subset=["completed_at", "actual_hours"])
    acc: dict[tuple[int, dt.date], float] = {}
    for t in done.itertuples(index=False):
        for ws, h in place_hours(float(t.actual_hours), t.assigned_at, t.completed_at, holidays).items():
            acc[(int(t.assignee_id), ws)] = acc.get((int(t.assignee_id), ws), 0.0) + h
    rows = [{"member_id": m, "week_start": w, "hours": round(h, 6)} for (m, w), h in sorted(acc.items())]
    return pd.DataFrame(rows, columns=TRUTH_COLUMNS)


def truncated_copy(conn: sqlite3.Connection, as_of: dt.date) -> sqlite3.Connection:
    """An in-memory database as it looked on `as_of`: no tasks assigned later, later completions reopened,
    no runs. Organisation, projects, holidays, vacations and capacity settings are copied unchanged."""
    copy = connect(":memory:")
    copy.execute("PRAGMA foreign_keys = OFF")
    cutoff = as_of.isoformat()
    for table in COPIED_TABLES:
        if table not in table_names(conn):
            continue
        rows = read_df(conn, f"SELECT * FROM {table}").to_dict(orient="records")
        if rows:
            insert_rows(copy, table, rows, commit=False)
    copy.execute("DELETE FROM capacity_defaults")
    insert_rows(copy, "capacity_defaults", read_df(conn, "SELECT * FROM capacity_defaults").to_dict(orient="records"), commit=False)
    tasks = read_df(conn, "SELECT * FROM tasks WHERE substr(assigned_at, 1, 10) <= ?", (cutoff,))
    later = tasks["completed_at"].notna() & (tasks["completed_at"].astype(str).str[:10] > cutoff)
    tasks.loc[later, ["completed_at", "actual_hours"]] = None
    tasks.loc[later, "status"] = "in_progress"
    if len(tasks):
        insert_rows(copy, "tasks", tasks.to_dict(orient="records"), commit=False)
    copy.commit()
    copy.execute("PRAGMA foreign_keys = ON")
    return copy


def data_fingerprint(conn: sqlite3.Connection, db_file: Path | None) -> dict:
    counts = {t: int(read_df(conn, f"SELECT COUNT(*) AS n FROM {t}")["n"][0]) for t in ("members", "teams", "tasks")}
    span = read_df(conn, "SELECT MIN(assigned_at) AS first, MAX(assigned_at) AS last FROM tasks")
    digest = hashlib.sha256(Path(db_file).read_bytes()).hexdigest() if db_file and Path(db_file).exists() else None
    return {
        **counts,
        "first_assigned": None if span["first"][0] is None else str(span["first"][0])[:10],
        "last_assigned": None if span["last"][0] is None else str(span["last"][0])[:10],
        "sha256": digest,
    }
```

Check `insert_rows` in `service/src/whf/db/repo.py:22`: it takes `rows: list[dict]` and `commit: bool`; NaN values from pandas must become NULL, which `_to_sql` in the same file already handles for `float('nan')` (verify; if it does not, map `pd.isna` values to `None` before calling). The `tasks` INSERT in the second test uses the column set from `service/src/whf/db/schema.sql:37-54`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `uv run pytest tests/test_eval_truth.py -q && uv run ruff check . && uv run ruff format --check .`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add service/src/whf/eval/truth.py service/tests/test_eval_truth.py
git commit -m "feat(eval): truth series, data fingerprint and a truncated database copy

The harness replays past origins without leakage by forecasting from an
in-memory copy of the database as it was on that date, and compares the
result with the generator's answer key or with realised hours derived
from completed tasks."
```

---

### Task 3: Backtest skips unavailable models, times them and keeps native quantiles

**Files:**
- Modify: `service/src/whf/models/base.py` (add `ModelUnavailable`, optional `predict_quantiles`)
- Modify: `service/src/whf/backtest.py:32-72` (`BacktestResult`, `rolling_backtest`)
- Test: `service/tests/test_backtest.py`

**Interfaces:**
- Consumes: the `ArrivalModel` protocol.
- Produces: `class ModelUnavailable(RuntimeError)` in `whf.models.base`; `BacktestResult` gains `residual_frames: dict[tuple[str, int], pd.DataFrame]` (columns `origin, residual`, the same residuals as `residuals` but with their origin), `quantiles: dict[tuple[str, int], pd.DataFrame]` (columns `origin, y, low, high`), `unavailable: dict[str, str]`, `timings: dict[str, float]` (seconds of fit plus predict, summed over origins). A factory raising `ModelUnavailable` is skipped after the first attempt and recorded. Models exposing `predict_quantiles(rows, horizon) -> tuple[np.ndarray, np.ndarray]` get their bands collected. Tasks 4, 5 and 7 rely on these names.

- [ ] **Step 1: Write the failing tests** (append to `service/tests/test_backtest.py`)

```python
from whf.models.base import ModelUnavailable
from whf.models.naive import SeasonalNaive


class _Broken:
    name = "broken"

    def __init__(self) -> None:
        raise ModelUnavailable("broken: no weights")


class _Banded(SeasonalNaive):
    name = "banded"

    def predict_quantiles(self, rows, horizon):
        point = self.predict(rows, horizon)
        return point * 0.5, point * 1.5


def test_backtest_skips_unavailable_models_and_records_the_reason() -> None:
    feat = _frame()
    origins = default_origins(W0 + dt.timedelta(days=7 * 76), count=2)
    result = rolling_backtest(feat, {"seasonal_naive": SeasonalNaive, "broken": _Broken}, origins, horizons=(1,))
    assert set(result.scores["model"]) == {"seasonal_naive"}
    assert result.unavailable == {"broken": "broken: no weights"}
    assert result.timings["seasonal_naive"] >= 0.0 and "broken" not in result.timings
    frame = result.residual_frames[("seasonal_naive", 1)]
    assert list(frame.columns) == ["origin", "residual"] and set(frame["origin"]) == set(origins)
    assert np.allclose(frame["residual"].to_numpy(), result.residuals[("seasonal_naive", 1)])


def test_backtest_collects_native_quantiles_when_a_model_offers_them() -> None:
    feat = _frame()
    origins = default_origins(W0 + dt.timedelta(days=7 * 76), count=2)
    result = rolling_backtest(feat, {"seasonal_naive": SeasonalNaive, "banded": _Banded}, origins, horizons=(1, 2))
    q = result.quantiles[("banded", 1)]
    assert list(q.columns) == ["origin", "y", "low", "high"] and len(q) == 2 * 8
    assert (q["low"] <= q["high"]).all()
    assert ("seasonal_naive", 1) not in result.quantiles
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run pytest tests/test_backtest.py -q`
Expected: ImportError on `ModelUnavailable`.

- [ ] **Step 3: Implement**

In `service/src/whf/models/base.py` add, after the imports:

```python
class ModelUnavailable(RuntimeError):
    """Raised by a model factory when the model cannot run here (missing library or weights).

    The backtest skips the candidate and records the reason; a run never fails because of it.
    """
```

and add to the `ArrivalModel` protocol docstring one sentence: "A model may also offer
`predict_quantiles(rows, horizon) -> (low, high)`, the 0.1 and 0.9 quantiles in the same units; the
pipeline uses them for the demand band when present."

Replace `BacktestResult` and `rolling_backtest` in `service/src/whf/backtest.py` with:

```python
@dataclass
class BacktestResult:
    scores: pd.DataFrame
    residuals: dict[tuple[str, int], np.ndarray] = field(default_factory=dict)
    residual_frames: dict[tuple[str, int], pd.DataFrame] = field(default_factory=dict)
    quantiles: dict[tuple[str, int], pd.DataFrame] = field(default_factory=dict)
    unavailable: dict[str, str] = field(default_factory=dict)
    timings: dict[str, float] = field(default_factory=dict)


def rolling_backtest(
    feat: pd.DataFrame,
    factories: dict[str, Callable[[], ArrivalModel]],
    origins: list[dt.date],
    horizons: tuple[int, ...],
) -> BacktestResult:
    rows: list[dict] = []
    residuals: dict[tuple[str, int], list[float]] = {}
    residual_rows: dict[tuple[str, int], list[dict]] = {}
    quantiles: dict[tuple[str, int], list[dict]] = {}
    unavailable: dict[str, str] = {}
    timings: dict[str, float] = {}
    max_h = max(horizons)
    for origin in origins:
        train = feat[feat["week_start"] <= origin - max_h * ONE_WEEK]
        test = feat[feat["week_start"] == origin]
        if train.empty or test.empty:
            continue
        fitted: dict[str, ArrivalModel] = {}
        for name, factory in factories.items():
            if name in unavailable:
                continue
            started = time.perf_counter()
            try:
                fitted[name] = factory().fit(train, horizons)
            except ModelUnavailable as exc:
                unavailable[name] = str(exc)
                continue
            timings[name] = timings.get(name, 0.0) + time.perf_counter() - started
        naive = SeasonalNaive().fit(train, horizons)
        for h in horizons:
            y = test[f"target_h{h}"].to_numpy(dtype=float)
            if np.isnan(y).any():
                continue
            y_naive = naive.predict(test, h)
            for name, model in fitted.items():
                started = time.perf_counter()
                y_hat = np.clip(model.predict(test, h), 0.0, None)
                band = model.predict_quantiles(test, h) if hasattr(model, "predict_quantiles") else None
                timings[name] += time.perf_counter() - started
                rows.append(
                    {
                        "model": name,
                        "origin": origin,
                        "horizon": h,
                        "mae": float(np.mean(np.abs(y - y_hat))),
                        "mase": mase(y, y_hat, y_naive),
                    }
                )
                residuals.setdefault((name, h), []).extend((y - y_hat).tolist())
                residual_rows.setdefault((name, h), []).extend({"origin": origin, "residual": float(v)} for v in (y - y_hat))
                if band is not None:
                    low, high = (np.clip(np.asarray(b, dtype=float), 0.0, None) for b in band)
                    quantiles.setdefault((name, h), []).extend(
                        {"origin": origin, "y": float(a), "low": float(b), "high": float(c)}
                        for a, b, c in zip(y, low, high, strict=True)
                    )
    scores = pd.DataFrame(rows, columns=["model", "origin", "horizon", "mae", "mase"])
    return BacktestResult(
        scores=scores,
        residuals={k: np.array(v) for k, v in residuals.items()},
        residual_frames={k: pd.DataFrame(v, columns=["origin", "residual"]) for k, v in residual_rows.items()},
        quantiles={k: pd.DataFrame(v, columns=["origin", "y", "low", "high"]) for k, v in quantiles.items()},
        unavailable=unavailable,
        timings=timings,
    )
```

Add `import time` and `from whf.models.base import ArrivalModel, ModelUnavailable` to the imports.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `uv run pytest tests/test_backtest.py tests/test_pipeline.py -q && uv run ruff check . && uv run ruff format --check .`
Expected: all pass (the pipeline tests prove the existing callers are unaffected).

- [ ] **Step 5: Commit**

```bash
git add service/src/whf/models/base.py service/src/whf/backtest.py service/tests/test_backtest.py
git commit -m "feat(backtest): skip unavailable models, time them, keep native quantiles

Prepares the backtest for a foundation-model candidate that may be
missing on a machine and that produces its own prediction bands."
```

---

### Task 4: Pipeline hooks: forced model, no persistence, model quantile bands, unavailable models in facts

**Files:**
- Modify: `service/src/whf/pipeline.py:109-263` (`run_forecast`), `:265-407` (`_build_facts` model section), `RunResult`
- Test: `service/tests/test_pipeline.py`

**Interfaces:**
- Consumes: Task 3's `BacktestResult` fields and `ModelUnavailable`.
- Produces: `run_forecast(conn, team_id, as_of=None, requested_by=None, *, force_model: str | None = None, persist: bool = True) -> RunResult`. `force_model` bypasses champion selection (backtest still runs for that model only, plus the floor for MASE); an unknown name raises `ValueError`, an unavailable one raises `ModelUnavailable`. `persist=False` writes nothing and returns `run_id == 0`. `RunResult` gains `unavailable: dict[str, str]`. `facts["model"]` gains `"unavailable": {name: reason}` and `facts["model"]["interval"]["basis"]` reads `"model quantiles"` or `"backtest residuals"`. Bands come from the champion's `predict_quantiles` per member when it has one. Tasks 5 and 6 call this.

- [ ] **Step 1: Write the failing tests** (append to `service/tests/test_pipeline.py`)

```python
from whf.backtest import FLOOR_MODEL
from whf.models import MODEL_FACTORIES
from whf.models.base import ModelUnavailable
from whf.models.naive import SeasonalNaive


def test_force_model_and_no_persist(db, generated) -> None:
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of, force_model="tsb", persist=False)
    assert result.champion == "tsb" and result.run_id == 0
    assert read_df(db, "SELECT COUNT(*) AS n FROM runs")["n"][0] == 0
    with pytest.raises(ValueError):
        run_forecast(db, team_id=1, as_of=generated.config.as_of, force_model="nope", persist=False)


class _Banded(SeasonalNaive):
    name = "banded"

    def predict_quantiles(self, rows, horizon):
        point = self.predict(rows, horizon)
        return point * 0.5, point * 2.0


class _Broken:
    name = "broken"

    def __init__(self) -> None:
        raise ModelUnavailable("broken: missing")


def test_model_quantiles_drive_the_band(db, generated, monkeypatch) -> None:
    monkeypatch.setitem(MODEL_FACTORIES, "banded", _Banded)
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of, force_model="banded", persist=False)
    f = result.forecasts
    assert (f["demand_low"] <= f["demand_hours"] + 1e-9).all() and (f["demand_high"] >= f["demand_hours"] - 1e-9).all()
    assert result.facts["model"]["interval"]["basis"] == "model quantiles"
    plain = run_forecast(db, team_id=1, as_of=generated.config.as_of, force_model=FLOOR_MODEL, persist=False)
    assert plain.facts["model"]["interval"]["basis"] == "backtest residuals"


def test_unavailable_models_are_recorded_or_raised_when_forced(db, generated, monkeypatch) -> None:
    monkeypatch.setitem(MODEL_FACTORIES, "broken", _Broken)
    full = run_forecast(db, team_id=1, as_of=generated.config.as_of, persist=False)
    assert full.unavailable == {"broken": "broken: missing"}
    assert full.facts["model"]["unavailable"] == {"broken": "broken: missing"}
    assert full.champion != "broken"
    with pytest.raises(ModelUnavailable):
        run_forecast(db, team_id=1, as_of=generated.config.as_of, force_model="broken", persist=False)
```

(With a forced model the backtest only runs that model and the floor, so the un-forced run is the one that exercises skipping.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run pytest tests/test_pipeline.py -q`
Expected: TypeError on the unexpected keyword `force_model`.

- [ ] **Step 3: Implement**

In `run_forecast`:

1. Signature: add `*, force_model: str | None = None, persist: bool = True`.
2. After computing `origins`, choose factories:
   ```python
   if force_model is not None and force_model not in MODEL_FACTORIES:
       raise ValueError(f"unknown model {force_model!r}; known: {sorted(MODEL_FACTORIES)}")
   factories = (
       MODEL_FACTORIES
       if force_model is None
       else {name: MODEL_FACTORIES[name] for name in {force_model, FLOOR_MODEL}}
   )
   backtest = rolling_backtest(feat, factories, origins, horizons)
   if force_model is not None:
       if force_model in backtest.unavailable:
           raise ModelUnavailable(backtest.unavailable[force_model])
       mine = backtest.scores[backtest.scores["model"] == force_model]["mase"].dropna()
       champion, champion_mase = force_model, (float(mine.mean()) if len(mine) else float("nan"))
   else:
       champion, champion_mase = select_champion(backtest.scores)
   model = MODEL_FACTORIES[champion]().fit(feat, horizons)
   ```
   (`FLOOR_MODEL` and `ModelUnavailable` imported from `whf.backtest` and `whf.models.base`.)
3. Bands: replace the pooled `bounds` block with per-member offsets:
   ```python
   offsets: dict[tuple[int, int], tuple[float, float]] = {}
   basis = "backtest residuals"
   if hasattr(model, "predict_quantiles"):
       basis = "model quantiles"
       for h in horizons:
           point = np.clip(model.predict(latest, h), 0.0, None)
           low, high = (np.clip(np.asarray(b, dtype=float), 0.0, None) for b in model.predict_quantiles(latest, h))
           for m, p, lo, hi in zip(latest["member_id"].astype(int), point, low, high, strict=True):
               offsets[(int(m), h)] = (min(0.0, float(lo - p)), max(0.0, float(hi - p)))
   else:
       for h in horizons:
           q10, q90 = interval_bounds(backtest.residuals.get((champion, h), np.array([])))
           for m in member_ids:
               offsets[(m, h)] = (min(0.0, q10), max(0.0, q90))
   ```
   and in the rows loop use `low, high = offsets[(m, h)]` instead of `bounds[h]`. Pass `offsets` and `basis` to `_build_facts` in place of `bounds`; in `_build_facts` the interval facts become `{"basis": basis, "horizons": {str(h): {"low_offset": ..., "high_offset": ...}}}` where for the model-quantile basis the per-horizon offsets are the mean over members (documented as such in the facts), and add `"unavailable": dict(backtest.unavailable)` to `facts["model"]`.
4. Persistence: wrap the whole `try:` transaction in `if persist:`; otherwise `run_id = 0` and `facts["run"]["id"] = 0`.
5. `RunResult`: add field `unavailable: dict[str, str] = field(default_factory=dict)` and return `backtest.unavailable`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `uv run pytest tests/test_pipeline.py tests/test_api.py tests/test_cli.py tests/test_ai_tools.py -q && uv run ruff check . && uv run ruff format --check .`
Expected: all pass. If a facts-shape test elsewhere pins `interval` keys, update it to the new shape.

- [ ] **Step 5: Commit**

```bash
git add service/src/whf/pipeline.py service/tests/test_pipeline.py
git commit -m "feat(pipeline): forced model, dry runs, model quantile bands, unavailable models in facts

The evaluation harness replays past origins with each candidate forced
and nothing persisted; a candidate with native quantiles now defines the
demand band itself; a candidate that cannot run is reported, not fatal."
```

---

### Task 5: The harness: arrival level and demand level

**Files:**
- Create: `service/src/whf/eval/harness.py`
- Modify: `service/src/whf/pipeline.py` (extract `arrival_feature_matrix`)
- Test: `service/tests/test_eval_harness.py`

**Interfaces:**
- Consumes: Tasks 1 to 4; `whf.pipeline._load_frames`, `default_origins`, `rolling_backtest`, `run_forecast(force_model=, persist=)`, `truncated_copy`, `truth_from_answer_key`, `realised_hours`.
- Produces: `arrival_feature_matrix(frames, origin) -> tuple[pd.DataFrame, pd.DataFrame, list[dt.date]]` returning `(arrivals, feat, weeks)` in `whf.pipeline` (the feature-building lines of `run_forecast`, now shared); in `whf.eval.harness`: `@dataclass EvalConfig(as_of: dt.date, origins: int = 6, models: tuple[str, ...] = (), teams: tuple[int, ...] = (), finetune: bool = False, answer_key: Path | None = None)`, `@dataclass EvalResult(scores: pd.DataFrame, demand: pd.DataFrame, skipped: dict[str, str], truth_source: str, elapsed_seconds: float, origins: list[dt.date])`, `arrival_level(feat, factories, origins, horizons) -> tuple[pd.DataFrame, dict[str, str]]` (long frame: `model, horizon, origin, metric, value` with metrics `mae`, `mase`, `beats_naive`, `coverage80`, `wql`, `seconds`), `demand_level(conn, factories, origins, teams, truth) -> tuple[pd.DataFrame, dict[str, str]]` (columns `model, origin, team_id, member_id, week_start, forecast, truth, capacity, open_hours, new_hours`), `evaluate(conn, config, db_file=None) -> EvalResult`. Task 6 consumes these.

- [ ] **Step 1: Extract the feature builder in the pipeline**

Move the block in `run_forecast` from `first_week = min(tasks["assigned_at"])` through `feat = build_feature_matrix(...)` into:

```python
def arrival_feature_matrix(frames: dict[str, pd.DataFrame], origin: dt.date) -> tuple[pd.DataFrame, pd.DataFrame, list[dt.date]]:
    """Weekly arrivals and the feature matrix for every counted member, up to `origin`."""
    members, tasks = frames["members"], frames["tasks"]
    counted = members[members["counted_in_workload"] == 1]
    holidays = {d for d in frames["holidays"]["date"] if d is not None}
    vacation_days: dict[int, set[dt.date]] = {}
    for m, s, e in zip(frames["vacations"]["member_id"], frames["vacations"]["start_date"], frames["vacations"]["end_date"], strict=True):
        vacation_days.setdefault(int(m), set()).update(days_in_ranges([(s, e)]))
    weeks = weeks_between(min(tasks["assigned_at"]), origin)
    arrivals = weekly_arrivals(tasks, [int(m) for m in counted["id"]], weeks)
    feat = build_feature_matrix(arrivals, tasks, frames["projects"], frames["project_teams"], members, holidays, vacation_days)
    return arrivals, feat, weeks
```

and call it from `run_forecast` (which still needs `holidays`, `vacation_days` and `off_by_member` for capacity; keep computing those there, or return them too: simplest is to compute `vacation_days` once in a tiny helper `_vacation_days(frames)` used by both). Existing pipeline tests must stay green.

- [ ] **Step 2: Write the failing harness tests**

```python
# service/tests/test_eval_harness.py
import datetime as dt

import pandas as pd
import pytest

from whf.calendar import ONE_WEEK, last_complete_week
from whf.eval.harness import EvalConfig, arrival_level, demand_level, evaluate
from whf.eval.truth import truth_from_answer_key
from whf.models import MODEL_FACTORIES
from whf.models.base import ModelUnavailable
from whf.pipeline import _load_frames, arrival_feature_matrix

FAST = {name: MODEL_FACTORIES[name] for name in ("seasonal_naive", "tsb")}


class _Broken:
    name = "broken"

    def __init__(self) -> None:
        raise ModelUnavailable("broken: missing")


def test_arrival_level_reports_every_metric_per_model_and_horizon(db, generated) -> None:
    frames = _load_frames(db)
    origin = last_complete_week(generated.config.as_of)
    _, feat, _ = arrival_feature_matrix(frames, origin)
    origins = [origin - 2 * ONE_WEEK, origin - 4 * ONE_WEEK]
    scores, skipped = arrival_level(feat, {**FAST, "broken": _Broken}, origins, (1, 2))
    assert skipped == {"broken": "broken: missing"}
    assert set(scores["model"]) == {"seasonal_naive", "tsb"}
    assert set(scores["metric"]) == {"mae", "mase", "beats_naive", "coverage80", "wql", "seconds"}
    cov = scores[(scores.metric == "coverage80") & (scores.model == "tsb")]["value"]
    assert ((cov >= 0) & (cov <= 1)).all()


def test_demand_level_replays_origins_without_leakage(db, generated, tmp_path) -> None:
    key = tmp_path / "k.json"
    key.write_text(__import__("json").dumps(generated.answer_key))
    truth = truth_from_answer_key(key)
    origin = last_complete_week(generated.config.as_of) - 2 * ONE_WEEK
    demand, skipped = demand_level(db, FAST, [origin], (1,), truth)
    assert skipped == {}
    assert set(demand["model"]) == {"seasonal_naive", "tsb"}
    assert set(demand["week_start"]) == {origin + ONE_WEEK, origin + 2 * ONE_WEEK}
    assert (demand["forecast"] >= 0).all() and (demand["capacity"] > 0).all()
    assert demand["truth"].notna().all()


def test_evaluate_end_to_end_on_generated_data(db, generated, tmp_path) -> None:
    key = tmp_path / "k.json"
    key.write_text(__import__("json").dumps(generated.answer_key))
    config = EvalConfig(as_of=generated.config.as_of, origins=2, models=("seasonal_naive", "tsb"), teams=(1,), answer_key=key)
    result = evaluate(db, config)
    assert result.truth_source == "answer key" and result.skipped == {}
    assert len(result.origins) == 2 and result.elapsed_seconds > 0
    assert not result.scores.empty and not result.demand.empty


def test_evaluate_without_answer_key_uses_realised_hours(db, generated) -> None:
    config = EvalConfig(as_of=generated.config.as_of, origins=1, models=("seasonal_naive",), teams=(1,))
    result = evaluate(db, config)
    assert result.truth_source == "realised hours"
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `uv run pytest tests/test_eval_harness.py -q`
Expected: ImportError.

- [ ] **Step 4: Implement the harness**

```python
# service/src/whf/eval/harness.py
"""Two-level evaluation: arrival accuracy per model, and demand accuracy of the whole pipeline per model."""

from __future__ import annotations

import datetime as dt
import sqlite3
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

from whf.backtest import default_origins, interval_bounds, rolling_backtest
from whf.calendar import ONE_WEEK, last_complete_week
from whf.eval.metrics import coverage, weighted_quantile_loss
from whf.eval.truth import realised_hours, truncated_copy, truth_from_answer_key
from whf.models import MODEL_FACTORIES
from whf.models.base import ArrivalModel, ModelUnavailable
from whf.pipeline import MIN_WEEKS_BEFORE_ORIGIN, _load_frames, arrival_feature_matrix, run_forecast

HORIZONS = (1, 2)
METRIC_COLUMNS = ["model", "horizon", "origin", "metric", "value"]
DEMAND_COLUMNS = ["model", "origin", "team_id", "member_id", "week_start", "forecast", "truth", "capacity", "open_hours", "new_hours"]


@dataclass
class EvalConfig:
    as_of: dt.date
    origins: int = 6
    models: tuple[str, ...] = ()
    teams: tuple[int, ...] = ()
    finetune: bool = False
    answer_key: Path | None = None


@dataclass
class EvalResult:
    scores: pd.DataFrame
    demand: pd.DataFrame
    skipped: dict[str, str] = field(default_factory=dict)
    truth_source: str = ""
    elapsed_seconds: float = 0.0
    origins: list[dt.date] = field(default_factory=list)


def _leave_one_out_band(residuals: pd.DataFrame, origin: dt.date) -> tuple[float, float]:
    others = residuals[residuals["origin"] != origin]["residual"].to_numpy(dtype=float)
    return interval_bounds(others) if len(others) else (0.0, 0.0)


def arrival_level(
    feat: pd.DataFrame,
    factories: dict[str, Callable[[], ArrivalModel]],
    origins: list[dt.date],
    horizons: tuple[int, ...],
) -> tuple[pd.DataFrame, dict[str, str]]:
    result = rolling_backtest(feat, factories, origins, horizons)
    rows: list[dict] = []
    per_origin_residuals = result.residual_frames
    for score in result.scores.itertuples(index=False):
        base = {"model": score.model, "horizon": score.horizon, "origin": score.origin}
        rows.append({**base, "metric": "mae", "value": score.mae})
        rows.append({**base, "metric": "mase", "value": score.mase})
        rows.append({**base, "metric": "beats_naive", "value": float(score.mase < 1.0) if not np.isnan(score.mase) else float("nan")})
        key = (score.model, score.horizon)
        res = per_origin_residuals[key]
        mine = res[res.origin == score.origin]["residual"].to_numpy(dtype=float)
        if key in result.quantiles:
            q = result.quantiles[key]
            q = q[q.origin == score.origin]
            y, low, high = (q[c].to_numpy(dtype=float) for c in ("y", "low", "high"))
            point = y - mine
            cov, wql = coverage(y, low, high), weighted_quantile_loss(y, {0.1: low, 0.5: point, 0.9: high})
        else:
            low_off, high_off = _leave_one_out_band(res, score.origin)
            cov = coverage(mine, np.full_like(mine, low_off), np.full_like(mine, high_off))
            wql = float("nan")
        rows.append({**base, "metric": "coverage80", "value": cov})
        rows.append({**base, "metric": "wql", "value": wql})
        rows.append({**base, "metric": "seconds", "value": result.timings.get(score.model, float("nan")) / max(len(origins), 1)})
    return pd.DataFrame(rows, columns=METRIC_COLUMNS), dict(result.unavailable)


def demand_level(
    conn: sqlite3.Connection,
    factories: dict[str, Callable[[], ArrivalModel]],
    origins: list[dt.date],
    teams: tuple[int, ...],
    truth: pd.DataFrame,
) -> tuple[pd.DataFrame, dict[str, str]]:
    skipped: dict[str, str] = {}
    rows: list[dict] = []
    truth_index = {(int(m), w): float(h) for m, w, h in zip(truth.member_id, truth.week_start, truth.hours, strict=True)}
    for origin in origins:
        as_of = origin + ONE_WEEK
        replay = truncated_copy(conn, as_of)
        team_ids = teams or tuple(int(t) for t in _load_frames(replay)["teams"]["id"])
        for name in factories:
            if name in skipped:
                continue
            for team_id in team_ids:
                try:
                    result = run_forecast(replay, team_id=team_id, as_of=as_of, force_model=name, persist=False)
                except ModelUnavailable as exc:
                    skipped[name] = str(exc)
                    break
                except ValueError:
                    continue  # team without counted members
                for r in result.forecasts.itertuples(index=False):
                    rows.append(
                        {
                            "model": name,
                            "origin": origin,
                            "team_id": team_id,
                            "member_id": int(r.member_id),
                            "week_start": r.week_start,
                            "forecast": float(r.demand_hours),
                            "truth": truth_index.get((int(r.member_id), r.week_start), 0.0),
                            "capacity": float(r.capacity_hours),
                            "open_hours": float(r.open_task_hours),
                            "new_hours": float(r.new_task_hours),
                        }
                    )
    return pd.DataFrame(rows, columns=DEMAND_COLUMNS), skipped


def evaluate(conn: sqlite3.Connection, config: EvalConfig, factories: dict[str, Callable[[], ArrivalModel]] | None = None) -> EvalResult:
    started = time.perf_counter()
    factories = factories or MODEL_FACTORIES
    chosen = {name: factories[name] for name in (config.models or tuple(factories))}
    if config.finetune:
        from whf.models.chronos2 import Chronos2Arrival

        chosen["chronos2_ft"] = lambda: Chronos2Arrival(finetune=True)
    frames = _load_frames(conn)
    origin = last_complete_week(config.as_of)
    _, feat, weeks = arrival_feature_matrix(frames, origin)
    origins = [o for o in default_origins(origin, config.origins) if o >= weeks[0] + MIN_WEEKS_BEFORE_ORIGIN * ONE_WEEK]
    scores, skipped_a = arrival_level(feat, chosen, origins, HORIZONS)
    if config.answer_key is not None:
        truth, source = truth_from_answer_key(config.answer_key), "answer key"
    else:
        truth, source = realised_hours(conn), "realised hours"
    demand, skipped_b = demand_level(conn, {n: f for n, f in chosen.items() if n not in skipped_a}, origins, config.teams, truth)
    return EvalResult(
        scores=scores,
        demand=demand,
        skipped={**skipped_a, **skipped_b},
        truth_source=source,
        elapsed_seconds=time.perf_counter() - started,
        origins=origins,
    )
```

The `chronos2_ft` factory name is registered only here (§3.5). In `evaluate`, an unknown name in `config.models` must raise `ValueError(f"unknown model {name!r}; known: {sorted(factories)}")` before any work starts; the CLI in Task 6 catches `ValueError`, prints `error: <message>` and exits with code 2 (add a CliRunner assertion for `--models nope` to the Task 6 CLI test).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `uv run pytest tests/test_eval_harness.py tests/test_pipeline.py tests/test_backtest.py -q && uv run ruff check . && uv run ruff format --check .`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add service/src/whf/eval/harness.py service/src/whf/pipeline.py service/tests/test_eval_harness.py
git commit -m "feat(eval): arrival-level and demand-level evaluation over rolling origins

Level A scores each arrival model on the backtest with leave-one-origin-out
interval coverage; level B replays the whole pipeline per origin and team
from a truncated database and compares demand with the truth."
```

---

### Task 6: `whf eval` command and the report files

**Files:**
- Create: `service/src/whf/eval/report.py`
- Modify: `service/src/whf/cli.py` (new `eval` command)
- Test: `service/tests/test_eval_report.py`, `service/tests/test_cli.py`

**Interfaces:**
- Consumes: Task 5's `EvalResult`, `EvalConfig`, `evaluate`; Task 2's `data_fingerprint`; `whf.eval.metrics`.
- Produces: `write_outputs(result, fingerprint, config, out_dir, versions) -> Path` writing `scores.csv`, `demand.csv`, `summary.md` (§3.4); `summary_tables(result) -> tuple[pd.DataFrame, pd.DataFrame]` (level A aggregated per model and horizon: mean of each metric; level B per model: `mae`, `bias`, `open_only_mae`, `overload_precision`, `overload_recall`, `rows`); CLI `whf eval [--db] [--as-of] [--origins] [--models] [--teams] [--finetune] [--out] [--answer-key]` with exit code 1 when any model was skipped.

- [ ] **Step 1: Write the failing tests**

```python
# service/tests/test_eval_report.py
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
            {"model": "tsb", "origin": W, "team_id": 1, "member_id": 1, "week_start": W, "forecast": 50.0, "truth": 40.0, "capacity": 44.0, "open_hours": 30.0, "new_hours": 20.0},
            {"model": "tsb", "origin": W, "team_id": 1, "member_id": 2, "week_start": W, "forecast": 20.0, "truth": 46.0, "capacity": 44.0, "open_hours": 20.0, "new_hours": 0.0},
        ]
    )
    return EvalResult(scores=scores, demand=demand, skipped={"chronos2": "no weights"}, truth_source="answer key", elapsed_seconds=1.5, origins=[W])


def test_summary_tables_aggregate_levels() -> None:
    a, b = summary_tables(_result())
    assert a.loc[(a.model == "tsb") & (a.horizon == 1), "mase"].item() == 0.9
    row = b[b.model == "tsb"].iloc[0]
    assert row["mae"] == 18.0 and row["bias"] == -8.0 and row["open_only_mae"] == 18.0
    assert row["overload_precision"] == 0.0 and row["overload_recall"] == 0.0 and row["rows"] == 2


def test_write_outputs_creates_the_three_files_with_the_fingerprint(tmp_path) -> None:
    out = write_outputs(
        _result(),
        fingerprint={"members": 2, "teams": 1, "tasks": 9, "first_assigned": "2026-01-05", "last_assigned": "2026-08-01", "sha256": "ab" * 32},
        config=EvalConfig(as_of=W, origins=1, models=("tsb",)),
        out_dir=tmp_path / "eval",
        versions={"whf": "0.1.0", "torch": "absent"},
    )
    assert {p.name for p in out.iterdir()} == {"scores.csv", "demand.csv", "summary.md"}
    text = (out / "summary.md").read_text(encoding="utf-8")
    assert "answer key" in text and "chronos2" in text and "no weights" in text and "ab" * 32 in text and "torch" in text
    assert "| tsb |" in text
```

And in `service/tests/test_cli.py`:

```python
def test_eval_command_writes_outputs_and_reports_skips(tmp_path, monkeypatch) -> None:
    db = tmp_path / "e.db"
    key = tmp_path / "k.json"
    result = runner.invoke(app, ["data", "generate", "--db", str(db), "--months", "6", "--answer-key", str(key)])
    assert result.exit_code == 0, result.output
    out = tmp_path / "out"
    ok = runner.invoke(
        app,
        ["eval", "--db", str(db), "--models", "seasonal_naive,tsb", "--origins", "2", "--teams", "1", "--out", str(out), "--answer-key", str(key)],
    )
    assert ok.exit_code == 0, ok.output
    assert (out / "summary.md").exists() and "tsb" in ok.output
    from whf.models import MODEL_FACTORIES
    from whf.models.base import ModelUnavailable

    class Broken:
        name = "broken"

        def __init__(self) -> None:
            raise ModelUnavailable("broken: missing")

    monkeypatch.setitem(MODEL_FACTORIES, "broken", Broken)
    skipped = runner.invoke(
        app, ["eval", "--db", str(db), "--models", "seasonal_naive,broken", "--origins", "1", "--teams", "1", "--out", str(out / "b"), "--answer-key", str(key)]
    )
    assert skipped.exit_code == 1 and "skipped" in skipped.output and "broken" in skipped.output
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run pytest tests/test_eval_report.py tests/test_cli.py -k "eval" -q`
Expected: ImportError / "No such command 'eval'".

- [ ] **Step 3: Implement the report**

```python
# service/src/whf/eval/report.py
"""Aggregate an EvalResult into tables and write scores.csv, demand.csv and summary.md."""

from __future__ import annotations

import datetime as dt
import os
import platform
from pathlib import Path

import numpy as np
import pandas as pd

from whf.eval.harness import EvalConfig, EvalResult
from whf.eval.metrics import bias, mae, overload_precision_recall


def summary_tables(result: EvalResult) -> tuple[pd.DataFrame, pd.DataFrame]:
    if result.scores.empty:
        level_a = pd.DataFrame(columns=["model", "horizon"])
    else:
        level_a = result.scores.pivot_table(index=["model", "horizon"], columns="metric", values="value", aggfunc="mean").reset_index()
    rows = []
    for name, g in result.demand.groupby("model"):
        y, p = g["truth"].to_numpy(dtype=float), g["forecast"].to_numpy(dtype=float)
        cap = g["capacity"].to_numpy(dtype=float)
        prec, rec = overload_precision_recall(y > cap, p > cap)
        rows.append(
            {
                "model": name,
                "mae": mae(y, p),
                "bias": bias(y, p),
                "open_only_mae": mae(y, g["open_hours"].to_numpy(dtype=float)),
                "overload_precision": prec,
                "overload_recall": rec,
                "rows": int(len(g)),
            }
        )
    level_b = pd.DataFrame(rows, columns=["model", "mae", "bias", "open_only_mae", "overload_precision", "overload_recall", "rows"])
    return level_a, level_b


def _markdown(df: pd.DataFrame) -> str:
    if df.empty:
        return "(no rows)\n"
    cols = list(df.columns)
    lines = ["| " + " | ".join(str(c) for c in cols) + " |", "|" + "---|" * len(cols)]
    for r in df.itertuples(index=False):
        cells = [f"{v:.3f}" if isinstance(v, float | np.floating) else str(v) for v in r]
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


def write_outputs(result: EvalResult, fingerprint: dict, config: EvalConfig, out_dir: Path, versions: dict[str, str]) -> Path:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    result.scores.to_csv(out_dir / "scores.csv", index=False)
    result.demand.to_csv(out_dir / "demand.csv", index=False)
    level_a, level_b = summary_tables(result)
    parts = [
        f"# Forecast evaluation, as of {config.as_of.isoformat()}",
        "",
        f"Truth: {result.truth_source}. Origins: {', '.join(o.isoformat() for o in result.origins) or 'none'}.",
        f"Models requested: {', '.join(config.models) or 'all'}. Teams: {', '.join(map(str, config.teams)) or 'all'}. Fine-tune: {config.finetune}.",
        f"Elapsed: {result.elapsed_seconds:.1f} s on {platform.processor() or platform.machine()} with {os.cpu_count()} logical CPUs.",
        "",
        "## Level A: arrival accuracy per model and horizon (means over origins)",
        "",
        _markdown(level_a),
        "## Level B: demand accuracy per model (all origins, teams, members, weeks)",
        "",
        _markdown(level_b),
        "## Skipped models",
        "",
        "\n".join(f"- {name}: {reason}" for name, reason in result.skipped.items()) or "none",
        "",
        "## Data fingerprint",
        "",
        "\n".join(f"- {k}: {v}" for k, v in fingerprint.items()),
        "",
        "## Versions",
        "",
        "\n".join(f"- {k}: {v}" for k, v in versions.items()),
        "",
        f"Generated {dt.datetime.now().isoformat(timespec='seconds')}.",
        "",
    ]
    (out_dir / "summary.md").write_text("\n".join(parts), encoding="utf-8")
    return out_dir
```

- [ ] **Step 4: Implement the CLI command** (in `service/src/whf/cli.py`, after `run`)

```python
@app.command("eval")
def eval_cmd(
    db: DbOption = None,
    as_of: Annotated[str | None, typer.Option("--as-of")] = None,
    origins: Annotated[int, typer.Option("--origins", help="Backtest origins, two weeks apart")] = 6,
    models: Annotated[str | None, typer.Option("--models", help="Comma-separated model names; default all")] = None,
    teams: Annotated[str | None, typer.Option("--teams", help="Comma-separated team ids; default all")] = None,
    finetune: Annotated[bool, typer.Option("--finetune", help="Also evaluate a LoRA-fine-tuned Chronos-2")] = False,
    out: Annotated[Path | None, typer.Option("--out", help="Output folder; default <data dir>/eval/<as-of>")] = None,
    answer_key: Annotated[Path | None, typer.Option("--answer-key", help="Generator answer key; omit for real data")] = None,
) -> None:
    """Measure every arrival model and the demand forecast on this database; write scores.csv, demand.csv, summary.md."""
    from whf.eval.harness import EvalConfig, evaluate
    from whf.eval.report import summary_tables, write_outputs
    from whf.eval.truth import data_fingerprint

    when = _date(as_of) or dt.date.today()
    config = EvalConfig(
        as_of=when,
        origins=origins,
        models=tuple(m.strip() for m in models.split(",")) if models else (),
        teams=tuple(int(t) for t in teams.split(",")) if teams else (),
        finetune=finetune,
        answer_key=answer_key,
    )
    conn = _conn(db)
    result = evaluate(conn, config)
    fingerprint = data_fingerprint(conn, db or db_path())
    target = write_outputs(result, fingerprint, config, out or (data_dir() / "eval" / when.isoformat()), _versions())
    level_a, level_b = summary_tables(result)
    typer.echo(level_a.to_string(index=False) if not level_a.empty else "no arrival scores")
    typer.echo(level_b.to_string(index=False) if not level_b.empty else "no demand rows")
    typer.echo(f"written to {target}")
    if result.skipped:
        for name, reason in result.skipped.items():
            typer.echo(f"skipped {name}: {reason}")
        raise typer.Exit(code=1)


def _versions() -> dict[str, str]:
    from importlib.metadata import PackageNotFoundError, version

    out = {"whf": __version__}
    for pkg in ("torch", "chronos-forecasting", "scikit-learn", "pandas"):
        try:
            out[pkg] = version(pkg)
        except PackageNotFoundError:
            out[pkg] = "absent"
    return out
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `uv run pytest tests/test_eval_report.py tests/test_cli.py -q && uv run ruff check . && uv run ruff format --check .`
Expected: all pass. The `eval` CLI test on a six-month database with two fast models and two origins should take well under a minute; if it is slower, reduce to `--origins 1`.

- [ ] **Step 6: Commit**

```bash
git add service/src/whf/eval/report.py service/src/whf/cli.py service/tests/test_eval_report.py service/tests/test_cli.py
git commit -m "feat(cli): whf eval writes scores.csv, demand.csv and summary.md

One command that works on the generated data today and on the real
export next week, so the model choice becomes a recorded measurement."
```

---

### Task 7: Chronos-2 arrival model

**Files:**
- Create: `service/src/whf/models/chronos2.py`
- Modify: `service/src/whf/models/__init__.py` (register `chronos2`)
- Modify: `service/src/whf/cli.py` (`serve` warm-up thread)
- Test: `service/tests/test_models_chronos2.py`, `service/tests/test_models_chronos2_real.py` (slow)

**Interfaces:**
- Consumes: `ArrivalModel`, `ModelUnavailable`, `HORIZONS`, `LAGS` from `whf.features`, `ONE_WEEK`.
- Produces: `Chronos2Arrival(pipeline=None, finetune=False)` with `name = "chronos2"`, `fit`, `predict`, `predict_quantiles`; module-level `load(env=None) -> pipeline`, `weights_path(env=None) -> Path | None`, `warm_up() -> threading.Thread`, constants `WEIGHTS_REPO = "amazon/chronos-2"`, `WEIGHTS_REVISION = "29ec3766d36d6f73f0696f85560a422f50e8498c"`, `PAST_COVARIATES`, `QUANTILES = (0.1, 0.5, 0.9)`, `MAX_THREADS = 4`, `FINETUNE_STEPS = 200`. Task 8's download script imports the two weight constants.

- [ ] **Step 1: Write the failing tests (stub pipeline, no torch)**

```python
# service/tests/test_models_chronos2.py
import datetime as dt

import numpy as np
import pandas as pd
import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from whf.features import build_feature_matrix, weekly_arrivals
from whf.models import MODEL_FACTORIES
from whf.models.base import ModelUnavailable
from whf.models.chronos2 import PAST_COVARIATES, Chronos2Arrival, weights_path

W0 = dt.date(2025, 1, 6)


def _frame(members: int = 4, weeks: int = 40, seed: int = 0) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    rows, tid = [], 0
    week_list = [W0 + dt.timedelta(days=7 * i) for i in range(weeks)]
    for m in range(1, members + 1):
        for w in week_list:
            for _ in range(rng.poisson(1.0 + m / 3)):
                tid += 1
                rows.append({"id": tid, "assignee_id": m, "assigned_at": w + dt.timedelta(days=int(rng.integers(0, 5))), "estimated_hours": float(rng.uniform(1, 6)), "assignment_mode": "project"})
    tasks = pd.DataFrame(rows)
    arr = weekly_arrivals(tasks, list(range(1, members + 1)), week_list)
    mem = pd.DataFrame([{"id": m, "team_id": 1} for m in range(1, members + 1)])
    projects = pd.DataFrame([{"id": 1, "start_date": W0, "deadline": W0 + dt.timedelta(days=7 * weeks)}])
    project_teams = pd.DataFrame([{"project_id": 1, "team_id": 1}])
    return build_feature_matrix(arr, tasks, projects, project_teams, mem, set(), {})


class StubPipeline:
    """Records the frames it receives and answers with the last context value as every quantile."""

    def __init__(self) -> None:
        self.calls: list[dict] = []

    def predict_df(self, df, future_df=None, *, id_column, timestamp_column, target, prediction_length, quantile_levels, freq, **kw):
        self.calls.append({"df": df, "future_df": future_df, "prediction_length": prediction_length, "freq": freq, "quantiles": quantile_levels})
        rows = []
        for member, g in df.groupby(id_column, sort=False):
            last = float(g.sort_values(timestamp_column)[target].iloc[-1])
            for k in range(1, prediction_length + 1):
                ts = g[timestamp_column].max() + pd.Timedelta(weeks=k)
                rows.append({id_column: member, timestamp_column: ts, "target_name": target, "predictions": last, **{str(q): last * (0.5 + q) for q in quantile_levels}})
        return pd.DataFrame(rows)


def test_predict_builds_contiguous_history_and_future_covariates() -> None:
    feat = _frame()
    origin = W0 + dt.timedelta(days=7 * 30)
    train = feat[feat["week_start"] <= origin - 2 * dt.timedelta(days=7)]
    rows = feat[feat["week_start"] == origin]
    stub = StubPipeline()
    model = Chronos2Arrival(pipeline=stub).fit(train, (1, 2))
    pred = model.predict(rows, horizon=2)
    assert pred.shape == (len(rows),) and np.all(pred >= 0)
    call = stub.calls[-1]
    assert call["prediction_length"] == 2 and call["freq"] == "W-MON" and call["quantiles"] == [0.1, 0.5, 0.9]
    df, future = call["df"], call["future_df"]
    for member, g in df.groupby("member_id"):
        weeks = sorted(g["timestamp"])
        assert weeks[-1] == pd.Timestamp(origin)
        assert all((b - a) == pd.Timedelta(weeks=1) for a, b in zip(weeks, weeks[1:], strict=False))
        assert set(PAST_COVARIATES) <= set(g.columns) and g[list(PAST_COVARIATES)].notna().all().all()
    assert set(future["member_id"]) == set(rows["member_id"].astype(int))
    assert len(future) == 2 * len(rows) and set(PAST_COVARIATES) <= set(future.columns)
    # the gap weeks between train and the origin row come from the row's lag columns
    m0 = int(rows["member_id"].astype(int).iloc[0])
    g0 = df[df.member_id == m0].set_index("timestamp")["est_hours"]
    assert g0[pd.Timestamp(origin)] == float(rows[rows.member_id.astype(int) == m0]["lag1"].iloc[0])
    assert g0[pd.Timestamp(origin - dt.timedelta(days=7))] == float(rows[rows.member_id.astype(int) == m0]["lag2"].iloc[0])


def test_predict_quantiles_are_ordered_and_clipped() -> None:
    feat = _frame()
    origin = W0 + dt.timedelta(days=7 * 30)
    rows = feat[feat["week_start"] == origin]
    model = Chronos2Arrival(pipeline=StubPipeline()).fit(feat[feat["week_start"] < origin], (1,))
    low, high = model.predict_quantiles(rows, horizon=1)
    point = model.predict(rows, horizon=1)
    assert np.all(low <= point + 1e-9) and np.all(point <= high + 1e-9) and np.all(low >= 0)


def test_registered_and_unavailable_without_torch(monkeypatch) -> None:
    assert MODEL_FACTORIES["chronos2"] is Chronos2Arrival
    import whf.models.chronos2 as mod

    monkeypatch.setattr(mod, "_pipeline", None)
    monkeypatch.setattr(mod, "_import_pipeline_class", lambda: (_ for _ in ()).throw(ImportError("no torch")))
    with pytest.raises(ModelUnavailable, match="chronos2"):
        Chronos2Arrival().fit(_frame(), (1,))


def test_weights_path_prefers_env_then_bundled(tmp_path) -> None:
    bundled = tmp_path / "models" / "chronos-2"
    bundled.mkdir(parents=True)
    assert weights_path(env={}, exe_dir=tmp_path) == bundled
    custom = tmp_path / "custom"
    custom.mkdir()
    assert weights_path(env={"WHF_CHRONOS2_PATH": str(custom)}, exe_dir=tmp_path) == custom
    assert weights_path(env={}, exe_dir=tmp_path / "nowhere") is None


@settings(max_examples=25, deadline=None)
@given(st.lists(st.floats(0, 50, allow_nan=False, allow_infinity=False), min_size=8, max_size=30))
def test_history_with_arbitrary_sparse_values_yields_finite_non_negative_predictions(values) -> None:
    weeks = [W0 + dt.timedelta(days=7 * i) for i in range(len(values))]
    tasks = pd.DataFrame([{"id": i + 1, "assignee_id": 1, "assigned_at": w, "estimated_hours": v if v > 0 else 0.0, "assignment_mode": "project"} for i, (w, v) in enumerate(zip(weeks, values, strict=True))])
    arr = weekly_arrivals(tasks, [1], weeks)
    feat = build_feature_matrix(arr, tasks, pd.DataFrame([{"id": 1, "start_date": W0, "deadline": weeks[-1]}]), pd.DataFrame([{"project_id": 1, "team_id": 1}]), pd.DataFrame([{"id": 1, "team_id": 1}]), set(), {})
    origin = weeks[-1]
    model = Chronos2Arrival(pipeline=StubPipeline()).fit(feat[feat.week_start < origin], (1,))
    pred = model.predict(feat[feat.week_start == origin], 1)
    assert np.isfinite(pred).all() and (pred >= 0).all()
```

(`weekly_arrivals` needs at least one task row; with all-zero values `estimated_hours` is 0 but the rows still exist, so the frame is valid.)

The slow real-weights test:

```python
# service/tests/test_models_chronos2_real.py
import os

import numpy as np
import pytest

from whf.models.chronos2 import Chronos2Arrival, load, weights_path

pytestmark = pytest.mark.slow


def _weights_available() -> bool:
    if weights_path() is not None:
        return True
    cache = os.path.expanduser("~/.cache/huggingface/hub/models--amazon--chronos-2")
    return os.path.isdir(cache)


@pytest.mark.skipif(not _weights_available(), reason="Chronos-2 weights not present")
def test_real_chronos2_forecasts_generated_data(db, generated) -> None:
    from whf.calendar import last_complete_week
    from whf.pipeline import _load_frames, arrival_feature_matrix, run_forecast

    pipe = load()
    assert pipe is load()  # loaded once per process
    frames = _load_frames(db)
    origin = last_complete_week(generated.config.as_of)
    _, feat, _ = arrival_feature_matrix(frames, origin)
    rows = feat[feat.week_start == origin]
    model = Chronos2Arrival().fit(feat[feat.week_start < origin], (1, 2))
    for h in (1, 2):
        low, high = model.predict_quantiles(rows, h)
        point = model.predict(rows, h)
        assert point.shape == (len(rows),) and np.isfinite(point).all() and (point >= 0).all()
        assert (low <= point + 1e-6).all() and (point <= high + 1e-6).all()
    result = run_forecast(db, team_id=1, as_of=generated.config.as_of, force_model="chronos2", persist=False)
    assert result.champion == "chronos2" and result.facts["model"]["interval"]["basis"] == "model quantiles"
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run pytest tests/test_models_chronos2.py -q`
Expected: ImportError.

- [ ] **Step 3: Implement the adapter**

```python
# service/src/whf/models/chronos2.py
"""Chronos-2 (Amazon, Apache 2.0) as a zero-shot arrival model. The pipeline is injectable for tests;
the real one is loaded once per process from bundled weights, never from the network on a user's machine."""

from __future__ import annotations

import datetime as dt
import os
import sys
import threading
from collections.abc import Mapping
from pathlib import Path
from typing import Any, Protocol

import numpy as np
import pandas as pd

from whf.calendar import ONE_WEEK
from whf.features import HORIZONS, LAGS
from whf.models.base import ModelUnavailable

WEIGHTS_REPO = "amazon/chronos-2"
WEIGHTS_REVISION = "29ec3766d36d6f73f0696f85560a422f50e8498c"
PAST_COVARIATES = ("working_days", "vacation_days", "proj_active", "proj_min_weeks_to_deadline", "proj_starting", "proj_ending")
QUANTILES = (0.1, 0.5, 0.9)
MAX_THREADS = 4
FINETUNE_STEPS = 200
FINETUNE_LR = 1e-5
_CONTIGUOUS_LAGS = tuple(k for k in LAGS if k <= 4)  # lag1..lag4 are consecutive weeks ending at the row's week


class ForecastPipeline(Protocol):
    def predict_df(self, df: pd.DataFrame, future_df: pd.DataFrame | None = None, **kwargs: Any) -> pd.DataFrame: ...


_lock = threading.Lock()
_pipeline: Any = None


def weights_path(env: Mapping[str, str] | None = None, exe_dir: Path | None = None) -> Path | None:
    """First existing of: WHF_CHRONOS2_PATH; <frozen exe dir>/models/chronos-2. None means: use the library's cache."""
    env = os.environ if env is None else env
    custom = env.get("WHF_CHRONOS2_PATH")
    if custom and Path(custom).is_dir():
        return Path(custom)
    if exe_dir is None and getattr(sys, "frozen", False):
        exe_dir = Path(sys.executable).parent
    if exe_dir is not None:
        bundled = exe_dir / "models" / "chronos-2"
        if bundled.is_dir():
            return bundled
    return None


def _import_pipeline_class() -> Any:
    from chronos import Chronos2Pipeline

    return Chronos2Pipeline


def load(env: Mapping[str, str] | None = None) -> Any:
    """The process-wide Chronos2Pipeline on CPU, loaded on first use. Raises ModelUnavailable with the reason."""
    global _pipeline
    with _lock:
        if _pipeline is not None:
            return _pipeline
        path = weights_path(env)
        if path is not None:
            os.environ.setdefault("HF_HUB_OFFLINE", "1")
        try:
            import torch

            cls = _import_pipeline_class()
        except ImportError as exc:
            raise ModelUnavailable(f"chronos2: library not installed ({exc})") from exc
        torch.set_num_threads(min(MAX_THREADS, os.cpu_count() or 1))
        try:
            if path is not None:
                _pipeline = cls.from_pretrained(str(path), device_map="cpu")
            else:
                _pipeline = cls.from_pretrained(WEIGHTS_REPO, revision=WEIGHTS_REVISION, device_map="cpu")
        except Exception as exc:  # noqa: BLE001 - any loading failure means "unavailable here"
            raise ModelUnavailable(f"chronos2: cannot load weights ({exc})") from exc
        return _pipeline


def warm_up() -> threading.Thread:
    """Load the pipeline in the background so the first forecast does not pay for it."""

    def _run() -> None:
        try:
            load()
        except ModelUnavailable:
            pass

    thread = threading.Thread(target=_run, name="chronos2-warm-up", daemon=True)
    thread.start()
    return thread


class Chronos2Arrival:
    name = "chronos2"

    def __init__(self, pipeline: ForecastPipeline | None = None, finetune: bool = False) -> None:
        self._pipeline = pipeline
        self._history: pd.DataFrame | None = None
        self.finetune = finetune

    def _pipe(self) -> Any:
        return self._pipeline if self._pipeline is not None else load()

    def fit(self, train: pd.DataFrame, horizons: tuple[int, ...] = HORIZONS) -> Chronos2Arrival:
        keep = ["member_id", "week_start", "est_hours", *[f"{c}_h1" for c in PAST_COVARIATES]]
        hist = train[keep].copy()
        hist["member_id"] = hist["member_id"].astype(int)
        self._history = hist.sort_values(["member_id", "week_start"]).reset_index(drop=True)
        self._pipe()  # fail early with ModelUnavailable when the model cannot run here
        if self.finetune:
            self._finetune(max(horizons))
        return self

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        return self._quantiles(rows, horizon)[0.5]

    def predict_quantiles(self, rows: pd.DataFrame, horizon: int) -> tuple[np.ndarray, np.ndarray]:
        q = self._quantiles(rows, horizon)
        return np.minimum(q[0.1], q[0.5]), np.maximum(q[0.9], q[0.5])

    def _quantiles(self, rows: pd.DataFrame, horizon: int) -> dict[float, np.ndarray]:
        df, future = self._frames(rows, horizon)
        try:
            import torch

            torch.manual_seed(0)
        except ImportError:
            pass
        out = self._pipe().predict_df(
            df,
            future_df=future,
            id_column="member_id",
            timestamp_column="timestamp",
            target="est_hours",
            prediction_length=horizon,
            quantile_levels=list(QUANTILES),
            freq="W-MON",
        )
        id_col = "member_id" if "member_id" in out.columns else "item_id"
        last = out.sort_values("timestamp").groupby(id_col).tail(1).set_index(id_col)
        ids = rows["member_id"].astype(int).to_numpy()
        return {q: np.clip(last.loc[ids, str(q)].to_numpy(dtype=float), 0.0, None) for q in QUANTILES}

    def _frames(self, rows: pd.DataFrame, horizon: int) -> tuple[pd.DataFrame, pd.DataFrame]:
        if self._history is None:
            raise RuntimeError("fit() before predict()")
        weeks_in_rows = set(rows["week_start"])
        if len(weeks_in_rows) != 1:
            raise ValueError("predict() expects rows from one week")
        (w0,) = weeks_in_rows
        past_frames, future_rows = [], []
        for r in rows.itertuples(index=False):
            m = int(r.member_id)
            hist = self._history[(self._history.member_id == m) & (self._history.week_start <= w0)]
            series: dict[dt.date, float] = dict(zip(hist.week_start, hist.est_hours.astype(float), strict=True))
            for k in _CONTIGUOUS_LAGS:  # the weeks between the training cut-off and w0 live in the row's lags
                value = getattr(r, f"lag{k}")
                if not pd.isna(value):
                    series[w0 - (k - 1) * ONE_WEEK] = float(value)
            first = min(series) if series else w0
            weeks = [first + i * ONE_WEEK for i in range((w0 - first).days // 7 + 1)]
            cov = {c: {} for c in PAST_COVARIATES}
            prev = None
            for h_row in hist.itertuples(index=False):
                if prev is not None:  # x_h1 on week w describes week w+1
                    for c in PAST_COVARIATES:
                        cov[c][h_row.week_start] = float(getattr(prev, f"{c}_h1"))
                prev = h_row
            if len(hist):
                head = hist.iloc[0]
                for c in PAST_COVARIATES:
                    cov[c].setdefault(head.week_start, float(head[f"{c}_h1"]))
            frame = pd.DataFrame({"member_id": m, "timestamp": [pd.Timestamp(w) for w in weeks], "est_hours": [series.get(w, 0.0) for w in weeks]})
            for c in PAST_COVARIATES:
                values = pd.Series([cov[c].get(w, np.nan) for w in weeks]).ffill().bfill().fillna(0.0)
                frame[c] = values.to_numpy(dtype=float)
            past_frames.append(frame)
            for k in range(1, horizon + 1):
                future_rows.append({"member_id": m, "timestamp": pd.Timestamp(w0 + k * ONE_WEEK), **{c: float(getattr(r, f"{c}_h{k}")) for c in PAST_COVARIATES}})
        return pd.concat(past_frames, ignore_index=True), pd.DataFrame(future_rows)

    def _finetune(self, prediction_length: int) -> None:
        """Harness-only LoRA fine-tune on the training window (targets only). Replaces this instance's pipeline."""
        assert self._history is not None
        inputs = [g["est_hours"].to_numpy(dtype=np.float32) for _, g in self._history.groupby("member_id", sort=True)]
        self._pipeline = self._pipe().fit(
            inputs,
            prediction_length=prediction_length,
            finetune_mode="lora",
            num_steps=FINETUNE_STEPS,
            learning_rate=FINETUNE_LR,
            remove_printer_callback=True,
        )
```

Register in `service/src/whf/models/__init__.py`:

```python
from whf.models.chronos2 import Chronos2Arrival
...
MODEL_FACTORIES: dict[str, Callable[[], ArrivalModel]] = {
    "seasonal_naive": SeasonalNaive,
    "tsb": TSB,
    "gbm": GradientBoostingArrival,
    "chronos2": Chronos2Arrival,
}
```

(`whf.models.chronos2` imports neither torch nor chronos at module level, so the fast suite and the CLI start-up stay unchanged.)

Warm-up in `serve` (`service/src/whf/cli.py`), just before `uvicorn.run(...)`:

```python
    from whf.models.chronos2 import warm_up

    warm_up()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `uv run pytest tests/test_models_chronos2.py tests/test_backtest.py tests/test_pipeline.py -q && uv run ruff check . && uv run ruff format --check .`
Expected: all pass. `test_pipeline.py::test_run_forecast_produces_two_weeks_per_counted_member` asserts `result.champion in {"seasonal_naive", "tsb", "gbm"}`; extend that set with `"chronos2"`. Without the library installed (until Task 8) the run must still succeed with `chronos2` in `result.unavailable`: add that assertion to the pipeline test guarded by `if "chronos2" in result.unavailable`.

- [ ] **Step 5: Commit**

```bash
git add service/src/whf/models/chronos2.py service/src/whf/models/__init__.py service/src/whf/cli.py service/tests/test_models_chronos2.py service/tests/test_models_chronos2_real.py service/tests/test_pipeline.py
git commit -m "feat(models): Chronos-2 zero-shot arrival model with native quantile bands

A fourth candidate for the backtest: the foundation model that leads
GIFT-Eval in 2026 and takes the holiday, vacation and project covariates
the feature matrix already computes. Loaded once per process from
bundled weights, skipped with a recorded reason when it cannot run."
```

---

### Task 8: Dependencies, weights at build time, freeze, smoke test, CI

**Files:**
- Modify: `service/pyproject.toml`, `service/uv.lock`
- Create: `installer/pyinstaller/download_weights.py`
- Modify: `installer/pyinstaller/whf.spec`, `installer/pyinstaller/smoke_frozen.py`
- Modify: `scripts/build-service.sh`, `scripts/build-service.ps1`
- Modify: `.github/workflows/ci.yml` (`freeze-linux` env)
- Modify: `installer/README.md` ("What it contains", size note)

**Interfaces:**
- Consumes: `WEIGHTS_REPO`, `WEIGHTS_REVISION` from `whf.models.chronos2`; `whf eval` from Task 6.
- Produces: a frozen service that runs Chronos-2 from `<dist>/whf/models/chronos-2` when present and degrades cleanly when absent; `WHF_SKIP_MODEL_DOWNLOAD=1` (bash) and `-SkipModelDownload` (PowerShell) to skip the download.

- [ ] **Step 1: Dependencies**

In `service/pyproject.toml`:

```toml
dependencies = [
    ...existing...,
    "chronos-forecasting>=2.3,<3",
    "torch>=2.6",
]

[tool.uv.sources]
torch = { index = "pytorch-cpu" }

[[tool.uv.index]]
name = "pytorch-cpu"
url = "https://download.pytorch.org/whl/cpu"
explicit = true
```

Run `uv lock && uv sync` in `service/`, then verify: `uv run python -c "import torch; print(torch.__version__)"` must print a version ending in `+cpu` on Linux (on Windows the CPU index serves plain version numbers without CUDA libraries; verify with `uv run python -c "import torch; print(torch.cuda.is_available(), torch.version.cuda)"` printing `False None`). Run the slow real-weights test: `WHF_CHRONOS2_PATH=$(ls -d ~/.cache/huggingface/hub/models--amazon--chronos-2/snapshots/*) uv run pytest tests/test_models_chronos2_real.py -m slow -q` (the snapshot is present in this sandbox from the spike; on a machine without it the test skips). Then the whole fast suite: `uv run pytest -q -m "not slow" -n 6` must stay green and must not get slower than before by more than a few seconds (torch is imported only by the real test).

- [ ] **Step 2: Weight download script**

```python
# installer/pyinstaller/download_weights.py
"""Download the pinned Chronos-2 weights into the frozen folder. Build-time only.

Usage: python installer/pyinstaller/download_weights.py <dist-dir>/whf
"""

from __future__ import annotations

import sys
from pathlib import Path

from huggingface_hub import snapshot_download

from whf.models.chronos2 import WEIGHTS_REPO, WEIGHTS_REVISION


def main(dist: str) -> int:
    target = Path(dist) / "models" / "chronos-2"
    target.mkdir(parents=True, exist_ok=True)
    snapshot_download(WEIGHTS_REPO, revision=WEIGHTS_REVISION, local_dir=str(target), allow_patterns=["*.json", "*.safetensors", "LICENSE*", "README.md"])
    files = sorted(p.name for p in target.iterdir())
    if "model.safetensors" not in files or "config.json" not in files:
        raise SystemExit(f"weights incomplete in {target}: {files}")
    print(f"chronos-2 weights at {target}: {files}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    raise SystemExit(main(sys.argv[1]))
```

- [ ] **Step 3: Build scripts**

`scripts/build-service.sh`, after the Copilot CLI block and before the smoke step:

```bash
if [ "${WHF_SKIP_MODEL_DOWNLOAD:-0}" != "1" ]; then
  # Chronos-2 weights: pinned revision, bundled next to the CLI so the service never downloads at run time.
  uv run python "$ROOT/installer/pyinstaller/download_weights.py" "$DIST/whf"
fi
```

`scripts/build-service.ps1`: add `[switch]$SkipModelDownload` to `param(...)` and, in the same position:

```powershell
    if (-not $SkipModelDownload) {
        # Chronos-2 weights: pinned revision, bundled next to the CLI so the service never downloads at run time.
        uv run python (Join-Path $root "installer\pyinstaller\download_weights.py") (Join-Path $dist "whf")
        if ($LASTEXITCODE -ne 0) { throw "Chronos-2 weights download failed" }
    }
```

Update the usage comment lines at the top of both scripts.

- [ ] **Step 4: PyInstaller spec**

In `installer/pyinstaller/whf.spec`:

```python
chronos_datas, chronos_binaries, chronos_hidden = collect_all("chronos")
```

add `chronos_binaries` to `binaries=`, `chronos_datas` to `datas=`, `*chronos_hidden` to `hiddenimports=`, and extend `excludes` with `"torch.utils.tensorboard", "torchvision", "torchaudio"`. The `torch` and `transformers` hooks come from `pyinstaller-hooks-contrib` (already a build dependency; verified present: `hook-torch.py`, `hook-transformers.py`).

- [ ] **Step 5: Smoke test**

In `installer/pyinstaller/smoke_frozen.py`, after the `run` step, add:

```python
        weights = dist / "models" / "chronos-2"
        eval_db = Path(tmp) / "eval.db"
        _run(exe, "data", "generate", "--db", str(eval_db), "--months", "6", "--answer-key", str(Path(tmp) / "eval_key.json"))
        eval_out = subprocess.run(
            [str(exe), "eval", "--db", str(eval_db), "--models", "chronos2", "--origins", "1", "--teams", "1",
             "--out", str(Path(tmp) / "eval_out"), "--answer-key", str(Path(tmp) / "eval_key.json")],
            capture_output=True, text=True, timeout=600,
        )
        if weights.is_dir():
            if eval_out.returncode != 0 or "skipped" in eval_out.stdout:
                raise SystemExit(f"chronos2 should run from bundled weights:\n{eval_out.stdout}\n{eval_out.stderr}")
            print("ok chronos2 eval from bundled weights")
        else:
            if eval_out.returncode != 1 or "skipped chronos2" not in eval_out.stdout:
                raise SystemExit(f"chronos2 should be skipped without weights:\n{eval_out.stdout}\n{eval_out.stderr}")
            print("ok chronos2 reported unavailable without weights")
```

Set `env={**os.environ, "HF_HUB_OFFLINE": "1"}` on that `subprocess.run` so the without-weights branch cannot silently download.

- [ ] **Step 6: CI and README**

`.github/workflows/ci.yml`, `freeze-linux` job: `env: { WHF_SKIP_CLI_DOWNLOAD: "1", WHF_SKIP_MODEL_DOWNLOAD: "1" }`. `package-windows` unchanged (downloads both). Add a `timeout-minutes` bump for `package-windows` if it is under 30.

`installer/README.md`, "What it contains": add a bullet "The Chronos-2 forecasting model weights (`resources/service/whf/models/chronos-2`, about 480 MB, Apache 2.0), downloaded at build time at a pinned revision; the service loads them from there and never contacts the network for them." Mention in "Building the installer" that the first build downloads about 1.2 GB (torch CPU wheel and weights) and that `-SkipModelDownload` produces an installer whose forecasts use the three classical models only.

- [ ] **Step 7: Verify the freeze on Linux**

Run from the repository root: `WHF_SKIP_CLI_DOWNLOAD=1 bash scripts/build-service.sh` (the weights come from the Hugging Face cache present in this sandbox, so the download is instant; give the command 900000 ms). Expected: PyInstaller succeeds, the folder contains `models/chronos-2/model.safetensors`, and the smoke test prints `ok chronos2 eval from bundled weights`. Then run it once more with `WHF_SKIP_MODEL_DOWNLOAD=1` after deleting `service/dist/whf/models` and confirm `ok chronos2 reported unavailable without weights`. Record `du -sh service/dist/whf` for both in the report. Then `uv run pytest tests/test_smoke_frozen_parsers.py -q` if that file tests helpers you touched.

- [ ] **Step 8: Commit**

```bash
git add service/pyproject.toml service/uv.lock installer/pyinstaller/download_weights.py installer/pyinstaller/whf.spec installer/pyinstaller/smoke_frozen.py scripts/build-service.sh scripts/build-service.ps1 .github/workflows/ci.yml installer/README.md
git commit -m "build: bundle CPU torch and the pinned Chronos-2 weights in the frozen service

torch comes from the CPU wheel index so no CUDA build is ever resolved;
the weights are downloaded at build time at a fixed revision next to the
Copilot CLI; the frozen smoke test proves both the bundled path and the
graceful degradation without weights."
```

---

### Task 9: Display names, product skill, documentation

**Files:**
- Modify: `app/src/renderer/src/i18n.ts` (English and French model names), the components that render `champion` / `champion_model` (`TeamResult` and `Runs` pages, find with `grep -rn "champion" app/src/renderer/src --include=*.tsx`)
- Modify: `app/src/renderer/src/__tests__/TeamResult.test.tsx`, `Runs.test.tsx`
- Modify: `service/src/whf/ai/skills/whf-forecast-interpretation/SKILL.md`
- Modify: `CLAUDE.md`, `docs/backlog.md`, `docs/requirements/requirements-v1.md` (non-functional note on installer size, if there is one)

**Interfaces:**
- Consumes: model names `seasonal_naive`, `tsb`, `gbm`, `chronos2`.
- Produces: i18n keys `model.seasonal_naive`, `model.tsb`, `model.gbm`, `model.chronos2` in both languages and a helper `modelName(t, name)` that falls back to the raw name; two factual lines for Copilot.

- [ ] **Step 1: Failing app tests**

In `TeamResult.test.tsx`, with the fixture champion `gbm`, assert the rendered text contains "Gradient boosting" in English and, after switching the language to French (the existing tests show how), "Boosting de gradient". In `Runs.test.tsx`, assert the row with `champion_model: 'tsb'` renders "TSB (intermittent demand)". Run `npm test` in `app/`: the new assertions fail.

- [ ] **Step 2: Implement**

`i18n.ts` English block:
```ts
'model.seasonal_naive': 'Seasonal naive', 'model.tsb': 'TSB (intermittent demand)', 'model.gbm': 'Gradient boosting', 'model.chronos2': 'Chronos-2 (foundation model)',
```
French block:
```ts
'model.seasonal_naive': 'Naïf saisonnier', 'model.tsb': 'TSB (demande intermittente)', 'model.gbm': 'Boosting de gradient', 'model.chronos2': 'Chronos-2 (modèle de fondation)',
```
Helper next to `t` in the same module:
```ts
export function modelName(t: (k: string) => string, name: string | null | undefined): string {
  if (!name) return ''
  const key = `model.${name}`
  const label = t(key)
  return label === key ? name : label
}
```
(adapt to how `t` reports a missing key in this code base; the `untranslatedKeys` assertion in the i18n tests must stay green, so add the keys to both languages). Use `modelName` wherever the champion is displayed.

- [ ] **Step 3: Skill and docs**

Append to `whf-forecast-interpretation/SKILL.md` under the model-quality bullet:
"- Model names: seasonal_naive repeats history; tsb is exponential smoothing for sparse demand; gbm is gradient boosting on engineered features; chronos2 is Chronos-2, a pretrained time-series foundation model used zero-shot with the holiday, vacation and project covariates. When `model.unavailable` lists chronos2, say the forecast used the classical models only and give the reason in plain words."

`CLAUDE.md`: in Toolchain commands add `uv run whf eval` (evaluation harness, writes `scores.csv`, `demand.csv`, `summary.md`); in Read-these-first add the new spec; in Hard rules add "The installer bundles CPU-only PyTorch and the pinned Chronos-2 weights; never resolve a CUDA build and never download weights at run time."

`docs/backlog.md`: under Landed, a new item summarising this plan (harness, Chronos-2 candidate, packaging) and the pending decision: model choice on the real data; under "Waiting on something specific": "Run `whf eval` on the real export and commit the result under `docs/eval/`; decide the default candidates from it".

- [ ] **Step 4: Verify and commit**

Run in `app/`: `npm run lint && npm run typecheck && npm test`. Then:

```bash
git add app/src/renderer/src/i18n.ts app/src/renderer/src/pages app/src/renderer/src/__tests__ service/src/whf/ai/skills/whf-forecast-interpretation/SKILL.md CLAUDE.md docs/backlog.md
git commit -m "feat(app): model display names in both languages; document the harness and Chronos-2"
```
(adjust the staged paths to the files actually touched; stage by path.)

---

### Task 10: First harness run on generated data

**Files:**
- Create: `docs/eval/2026-09-06-generated/summary.md`, `scores.csv`, `demand.csv`
- Modify: `docs/backlog.md` (one line pointing at the result)

- [ ] **Step 1: Run**

From `service/`, on a fresh twelve-month generated database:

```bash
uv run whf data generate --db /tmp/eval.db --answer-key /tmp/eval_key.json
WHF_CHRONOS2_PATH=$(ls -d ~/.cache/huggingface/hub/models--amazon--chronos-2/snapshots/*) uv run whf eval --db /tmp/eval.db --answer-key /tmp/eval_key.json --out ../docs/eval/2026-09-06-generated
```

Expected: exit 0, all four models scored, `summary.md` with both tables. If the run exceeds fifteen minutes, rerun with `--origins 4` and say so in the backlog line.

- [ ] **Step 2: Record and commit**

Add to `docs/backlog.md` under the item from Task 9: "First harness run on generated data (seed 42, twelve months): `docs/eval/2026-09-06-generated/summary.md`. Winner on generated data: <model>, MASE <x> at horizon 1; demand MAE <y> h per member-week; overload recall <z>. Not a decision: the generator wrote the truth. The real-data run next week decides."

```bash
git add docs/eval/2026-09-06-generated docs/backlog.md
git commit -m "docs(eval): first harness run on generated data

Recorded for comparison with next week's run on the real export; the
generated-data winner is not the decision."
```

---

## Verification before release

Fast gate in `service/` (`uv run ruff check . && uv run ruff format --check . && uv run pytest -q -m "not slow" -n 6`) and in `app/` (`npm run lint && npm run typecheck && npm test && npm run build`); the slow suite including the real-weights test; `bash scripts/build-service.sh` with and without `WHF_SKIP_MODEL_DOWNLOAD=1`; push `dev` and wait for CI, in particular `package-windows` (records the new installer size in its log: note it in `installer/README.md` if it differs materially from the estimate); fast-forward `main`. Owner's Windows check: install, run a forecast, confirm the run page names Chronos-2 or one of the classical models and that the first run after launch does not stall for more than a few seconds; run `whf.exe eval --db <path to whf.db>` once the real data is imported and send the `summary.md`.
