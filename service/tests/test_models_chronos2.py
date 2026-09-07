import datetime as dt
import sys
import types

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
                rows.append(
                    {
                        "id": tid,
                        "assignee_id": m,
                        "assigned_at": w + dt.timedelta(days=int(rng.integers(0, 5))),
                        "estimated_hours": float(rng.uniform(1, 6)),
                        "assignment_mode": "project",
                    }
                )
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

    def predict_df(
        self, df, future_df=None, *, id_column, timestamp_column, target, prediction_length, quantile_levels, freq, **kw
    ):
        self.calls.append(
            {
                "df": df,
                "future_df": future_df,
                "prediction_length": prediction_length,
                "freq": freq,
                "quantiles": quantile_levels,
            }
        )
        rows = []
        for member, g in df.groupby(id_column, sort=False):
            last = float(g.sort_values(timestamp_column)[target].iloc[-1])
            if last == 0.0:
                last = float("nan")  # a real model may answer NaN; the adapter must not pass it on
            for k in range(1, prediction_length + 1):
                ts = g[timestamp_column].max() + pd.Timedelta(weeks=k)
                rows.append(
                    {
                        id_column: member,
                        timestamp_column: ts,
                        "target_name": target,
                        "predictions": last,
                        **{str(q): last * (0.5 + q) for q in quantile_levels},
                    }
                )
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
    for _member, g in df.groupby("member_id"):
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
    assert g0[pd.Timestamp(origin - dt.timedelta(days=7))] == float(
        rows[rows.member_id.astype(int) == m0]["lag2"].iloc[0]
    )


def test_predict_quantiles_are_ordered_and_clipped() -> None:
    feat = _frame()
    origin = W0 + dt.timedelta(days=7 * 30)
    rows = feat[feat["week_start"] == origin]
    model = Chronos2Arrival(pipeline=StubPipeline()).fit(feat[feat["week_start"] < origin], (1,))
    low, high = model.predict_quantiles(rows, horizon=1)
    point = model.predict(rows, horizon=1)
    assert np.all(low <= point + 1e-9) and np.all(point <= high + 1e-9) and np.all(low >= 0)


def test_the_fast_suite_never_reaches_the_real_model() -> None:
    """The conftest guard, asserted here so that dropping it is a test failure and not a 53-minute
    test run: a Chronos2Arrival with no injected pipeline must refuse to load."""
    with pytest.raises(ModelUnavailable, match="disabled"):
        Chronos2Arrival().fit(_frame(), (1,))


@pytest.mark.slow
def test_slow_tests_are_guarded_too() -> None:
    """`slow` alone must not lift the guard: the accuracy gate is slow and would otherwise download
    456 MB of weights on CI, and would score a different set of models depending on the machine.
    Only `chronos2_real` opts in. Kept cheap so it costs nothing in the `-m slow` job."""
    with pytest.raises(ModelUnavailable, match="disabled"):
        Chronos2Arrival().fit(_frame(members=1, weeks=6), (1,))


def test_the_cache_fallback_never_downloads(monkeypatch) -> None:
    """With no bundled weights and no WHF_CHRONOS2_PATH, `load` reads a warm Hugging Face cache but
    must never fill one: `local_files_only=True` turns a missing cache into ModelUnavailable rather
    than an unannounced 456 MB download in a test run, a CI job or `whf serve` from a checkout."""
    import whf.models.chronos2 as mod

    seen: dict = {}

    class _Recording:
        @staticmethod
        def from_pretrained(name, **kwargs):
            seen["name"] = name
            seen.update(kwargs)
            return StubPipeline()

    monkeypatch.setattr(mod, "_pipeline", None)
    monkeypatch.setattr(mod, "weights_path", lambda env=None: None)
    monkeypatch.setattr(mod, "_import_pipeline_class", lambda: _Recording)
    mod.load(shared=False)
    assert seen["name"] == mod.WEIGHTS_REPO
    assert seen["revision"] == mod.WEIGHTS_REVISION
    assert seen["local_files_only"] is True


def test_registered_and_unavailable_without_torch(monkeypatch) -> None:
    assert MODEL_FACTORIES["chronos2"] is Chronos2Arrival
    import whf.models.chronos2 as mod

    monkeypatch.setattr(mod, "_pipeline", None)
    monkeypatch.setattr(mod, "_import_pipeline_class", lambda: (_ for _ in ()).throw(ImportError("no torch")))
    with pytest.raises(ModelUnavailable, match="chronos2"):
        Chronos2Arrival().fit(_frame(), (1,))


def test_a_broken_torch_install_is_reported_as_unavailable_not_raised(monkeypatch) -> None:
    """The classic Windows failure is OSError [WinError 126], not ImportError; the run must survive it."""
    import whf.models.chronos2 as mod

    monkeypatch.setitem(sys.modules, "torch", types.SimpleNamespace(set_num_threads=lambda n: None))
    monkeypatch.setattr(mod, "_pipeline", None)
    monkeypatch.setattr(
        mod,
        "_import_pipeline_class",
        lambda: (_ for _ in ()).throw(OSError("[WinError 126] The specified module could not be found")),
    )
    with pytest.raises(ModelUnavailable, match="chronos2"):
        Chronos2Arrival().fit(_frame(), (1,))


def test_fine_tuning_never_replaces_the_process_wide_pipeline(monkeypatch) -> None:
    import whf.models.chronos2 as mod

    class _Tuned(StubPipeline):
        pass

    class _Base(StubPipeline):
        def fit(self, inputs, **kw):
            return _Tuned()

    sentinel = object()
    monkeypatch.setattr(mod, "_pipeline", sentinel)
    monkeypatch.setattr(mod, "load", lambda *a, **kw: pytest.fail("an injected pipeline must be used as the base"))
    model = Chronos2Arrival(pipeline=_Base(), finetune=True).fit(_frame(), (1,))
    assert mod._pipeline is sentinel
    assert isinstance(model._pipeline, _Tuned)


def test_fine_tuning_loads_a_private_copy_when_no_pipeline_is_injected(monkeypatch) -> None:
    import whf.models.chronos2 as mod

    class _Tuned(StubPipeline):
        pass

    class _Base(StubPipeline):
        def fit(self, inputs, **kw):
            return _Tuned()

    shared_flags = []

    def _load(env=None, *, shared=True):
        shared_flags.append(shared)
        return _Base()

    sentinel = object()
    monkeypatch.setattr(mod, "_pipeline", sentinel)
    monkeypatch.setattr(mod, "load", _load)
    model = Chronos2Arrival(finetune=True).fit(_frame(), (1,))
    assert False in shared_flags  # the fine-tune base is a private pipeline
    assert mod._pipeline is sentinel and isinstance(model._pipeline, _Tuned)


def test_point_and_band_for_the_same_rows_cost_one_pipeline_call() -> None:
    feat = _frame()
    origin = W0 + dt.timedelta(days=7 * 30)
    rows = feat[feat["week_start"] == origin]
    stub = StubPipeline()
    model = Chronos2Arrival(pipeline=stub).fit(feat[feat["week_start"] < origin], (1, 2))
    model.predict(rows, horizon=1)
    model.predict_quantiles(rows, horizon=1)
    assert len(stub.calls) == 1
    model.predict(rows, horizon=2)  # a different horizon is a different question
    assert len(stub.calls) == 2
    model.fit(feat[feat["week_start"] < origin], (1, 2))  # fit invalidates the memo
    model.predict(rows, horizon=2)
    assert len(stub.calls) == 3


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
    tasks = pd.DataFrame(
        [
            {
                "id": i + 1,
                "assignee_id": 1,
                "assigned_at": w,
                "estimated_hours": v if v > 0 else 0.0,
                "assignment_mode": "project",
            }
            for i, (w, v) in enumerate(zip(weeks, values, strict=True))
        ]
    )
    arr = weekly_arrivals(tasks, [1], weeks)
    feat = build_feature_matrix(
        arr,
        tasks,
        pd.DataFrame([{"id": 1, "start_date": W0, "deadline": weeks[-1]}]),
        pd.DataFrame([{"project_id": 1, "team_id": 1}]),
        pd.DataFrame([{"id": 1, "team_id": 1}]),
        set(),
        {},
    )
    origin = weeks[-1]
    model = Chronos2Arrival(pipeline=StubPipeline()).fit(feat[feat.week_start < origin], (1,))
    pred = model.predict(feat[feat.week_start == origin], 1)
    assert np.isfinite(pred).all() and (pred >= 0).all()
