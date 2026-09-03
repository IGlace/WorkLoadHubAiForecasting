import datetime as dt

import numpy as np
import pandas as pd

from whf.backtest import default_origins, interval_bounds, mase, rolling_backtest, select_champion
from whf.features import build_feature_matrix, weekly_arrivals
from whf.models import MODEL_FACTORIES

W0 = dt.date(2025, 1, 6)


def _frame(members: int = 8, weeks: int = 80, seed: int = 0) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    rows, tid = [], 0
    week_list = [W0 + dt.timedelta(days=7 * i) for i in range(weeks)]
    for m in range(1, members + 1):
        for i, w in enumerate(week_list):
            burst = 2.0 if i % 8 == 7 else 1.0
            n = rng.poisson((1 + m / 2) * burst)
            for _ in range(n):
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


def test_mase_relative_to_naive() -> None:
    assert mase(np.array([1, 2, 3.0]), np.array([1, 2, 2.0]), np.array([0, 0, 0.0])) == 1 / 6
    assert np.isnan(mase(np.array([1.0]), np.array([1.0]), np.array([1.0])))


def test_default_origins_step_back_two_weeks() -> None:
    last = dt.date(2026, 8, 24)
    assert default_origins(last, count=3) == [dt.date(2026, 8, 10), dt.date(2026, 7, 27), dt.date(2026, 7, 13)]


def test_rolling_backtest_scores_every_model_origin_and_horizon() -> None:
    feat = _frame()
    origins = default_origins(W0 + dt.timedelta(days=7 * 76), count=3)
    result = rolling_backtest(feat, MODEL_FACTORIES, origins, horizons=(1, 2))
    assert set(result.scores["model"]) == set(MODEL_FACTORIES)
    assert len(result.scores) == len(MODEL_FACTORIES) * 3 * 2
    assert ("gbm", 1) in result.residuals and len(result.residuals[("gbm", 1)]) == 3 * 8
    naive = result.scores[result.scores.model == "seasonal_naive"]
    assert np.allclose(naive["mase"], 1.0)


def test_champion_beats_naive_on_bursty_data() -> None:
    feat = _frame()
    origins = default_origins(W0 + dt.timedelta(days=7 * 76), count=4)
    result = rolling_backtest(feat, MODEL_FACTORIES, origins, horizons=(1, 2))
    name, score = select_champion(result.scores)
    assert name != "seasonal_naive"  # gbm or tsb, whichever wins on this synthetic series
    assert score < 1.0


def test_select_champion_falls_back_to_floor() -> None:
    scores = pd.DataFrame(
        [
            {"model": "seasonal_naive", "origin": W0, "horizon": 1, "mae": 1.0, "mase": 1.0},
            {"model": "tsb", "origin": W0, "horizon": 1, "mae": 1.2, "mase": 1.2},
            {"model": "gbm", "origin": W0, "horizon": 1, "mae": 1.1, "mase": 1.1},
        ]
    )
    assert select_champion(scores) == ("seasonal_naive", 1.0)


def test_interval_bounds_are_ordered_quantiles() -> None:
    low, high = interval_bounds(np.array([-4.0, -2.0, 0.0, 2.0, 4.0]))
    assert low < 0 < high
    assert interval_bounds(np.array([])) == (0.0, 0.0)
