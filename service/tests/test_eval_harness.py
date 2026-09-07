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
    config = EvalConfig(
        as_of=generated.config.as_of, origins=2, models=("seasonal_naive", "tsb"), teams=(1,), answer_key=key
    )
    result = evaluate(db, config)
    assert result.truth_source == "answer key" and result.skipped == {}
    assert len(result.origins) == 2 and result.elapsed_seconds > 0
    assert not result.scores.empty and not result.demand.empty


def test_evaluate_without_answer_key_uses_realised_hours(db, generated) -> None:
    config = EvalConfig(as_of=generated.config.as_of, origins=1, models=("seasonal_naive",), teams=(1,))
    result = evaluate(db, config)
    assert result.truth_source == "realised hours"


def test_evaluate_rejects_unknown_model_before_doing_any_work(db, generated) -> None:
    config = EvalConfig(as_of=generated.config.as_of, origins=1, models=("nope",), teams=(1,))
    with pytest.raises(ValueError, match="unknown model 'nope'"):
        evaluate(db, config)
