import os

os.environ.setdefault("OMP_NUM_THREADS", "2")  # keep scikit-learn's OpenMP pool small on shared CI runners

import pytest

from whf.data.generator import GeneratedData, GeneratorConfig, generate
from whf.data.loader import load_generated
from whf.db.connection import connect


@pytest.fixture(scope="session")
def generated() -> GeneratedData:
    return generate(GeneratorConfig(seed=42))


@pytest.fixture()
def db(generated: GeneratedData):
    conn = connect(":memory:")
    load_generated(conn, generated)
    yield conn
    conn.close()
