import importlib.util
import os

import numpy as np
import pytest

from whf.models.chronos2 import Chronos2Arrival, load, weights_path

pytestmark = [pytest.mark.slow, pytest.mark.chronos2_real]


def _can_run() -> bool:
    """Both halves are needed: the library (torch + chronos) and weights it may load offline.

    The cache half has to ask about the cache `load()` will actually read - which HF_HOME moves -
    and about a snapshot rather than the repo folder, because a failed offline lookup leaves an
    empty `models--amazon--chronos-2/` behind. `load()` never downloads, so getting this wrong
    turns a skip into a failure.
    """
    if importlib.util.find_spec("torch") is None or importlib.util.find_spec("chronos") is None:
        return False
    if weights_path() is not None:
        return True
    hf_home = os.environ.get("HF_HOME")
    hub = os.path.join(hf_home, "hub") if hf_home else os.path.expanduser("~/.cache/huggingface/hub")
    snapshots = os.path.join(hub, "models--amazon--chronos-2", "snapshots")
    return os.path.isdir(snapshots) and bool(os.listdir(snapshots))


@pytest.mark.skipif(not _can_run(), reason="Chronos-2 library or weights not present")
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
