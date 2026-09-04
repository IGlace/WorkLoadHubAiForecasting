import copy
import os

os.environ.setdefault("OMP_NUM_THREADS", "2")  # keep scikit-learn's OpenMP pool small on shared CI runners

import pytest

from whf.data.generator import GeneratedData, GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect
from whf.pipeline import jsonable, run_forecast


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
