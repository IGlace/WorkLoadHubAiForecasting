import copy
import os

os.environ.setdefault("OMP_NUM_THREADS", "2")  # keep scikit-learn's OpenMP pool small on shared CI runners

import pytest

from whf.data.generator import GeneratedData, GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect
from whf.pipeline import jsonable, run_forecast


def _refuse_chronos2() -> None:
    raise ImportError("chronos2 disabled in the fast suite")


def _install_chronos2_guard(mp: pytest.MonkeyPatch) -> None:
    from whf.models import chronos2

    mp.setattr(chronos2, "_pipeline", None)
    mp.setattr(chronos2, "_import_pipeline_class", _refuse_chronos2)


@pytest.fixture(scope="session", autouse=True)
def _chronos2_guard():
    """Keep the real Chronos-2 out of the fast suite.

    `torch` and `chronos-forecasting` are ordinary dependencies now, so on any machine with the
    weights in its Hugging Face cache every `run_forecast` and every `rolling_backtest` in this
    suite would load a 456 MB checkpoint into each of the six xdist workers - measured at 53
    minutes instead of 18, at over a gigabyte of resident memory per worker. On CI, where nothing
    is cached, the same code would instead have *downloaded* the weights. Refusing the pipeline
    import puts `chronos2` back where the fast tests already expect it: unavailable, and skipped.

    This is session-scoped on purpose. Session-scoped fixtures are built before any function-scoped
    fixture, so a function-scoped guard would arrive too late for `_team_one_facts` below, which
    runs a real forecast.
    """
    mp = pytest.MonkeyPatch()
    _install_chronos2_guard(mp)
    yield mp
    mp.undo()


@pytest.fixture(autouse=True)
def _chronos2_guard_exempts_chronos2_real_tests(request, _chronos2_guard: pytest.MonkeyPatch):
    """Only tests marked `chronos2_real` may touch the real model; every other test keeps the guard.

    `slow` is not enough. `test_accuracy_gate_against_hidden_effort_log` is slow and forecasts eight
    teams over the whole registry: exempting it would make CI's `-m slow` job download the weights,
    and would make the gate's thresholds mean different things on a machine that has them.
    """
    if request.node.get_closest_marker("chronos2_real") is None:
        yield
        return
    _chronos2_guard.undo()
    try:
        yield
    finally:
        _install_chronos2_guard(_chronos2_guard)


@pytest.fixture(scope="session")
def generated() -> GeneratedData:
    return generate(GeneratorConfig(seed=42))


@pytest.fixture()
def db(generated: GeneratedData):
    conn = connect(":memory:")
    load_generated(conn, generated)
    yield conn
    conn.close()


@pytest.fixture(scope="session")
def _team_one_facts(generated: GeneratedData) -> dict:
    """One real forecast for team 1, computed once.

    `run_forecast` costs about ten seconds, nearly all of it fitting the candidate models, while
    loading the generated data costs 0.06 s. Every test that only needs the resulting facts used to
    pay that ten seconds again, which was most of the suite's runtime.
    """
    conn = connect(":memory:")
    load_generated(conn, generated)
    try:
        return jsonable(run_forecast(conn, team_id=1, as_of=generated.config.as_of).facts)
    finally:
        conn.close()


@pytest.fixture()
def facts(_team_one_facts: dict) -> dict:
    """The facts of a real forecast, as a copy, so a test may mutate them without touching its neighbours."""
    return copy.deepcopy(_team_one_facts)
