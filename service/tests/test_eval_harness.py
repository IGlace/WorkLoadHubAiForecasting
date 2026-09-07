import pytest

from whf.calendar import ONE_WEEK, last_complete_week
from whf.eval.harness import EvalConfig, arrival_level, demand_level, evaluate
from whf.eval.truth import truth_from_answer_key
from whf.models import MODEL_FACTORIES
from whf.models.base import ModelUnavailable
from whf.models.naive import SeasonalNaive
from whf.pipeline import _load_frames, arrival_feature_matrix

FAST = {name: MODEL_FACTORIES[name] for name in ("seasonal_naive", "tsb")}


class _Broken:
    name = "broken"

    def __init__(self) -> None:
        raise ModelUnavailable("broken: missing")


class _Banded(SeasonalNaive):
    """A model with native quantiles, the way chronos2 has them."""

    name = "banded"

    def predict_quantiles(self, rows, horizon):
        point = self.predict(rows, horizon)
        return point * 0.5, point * 1.5


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


def test_arrival_level_scores_the_interval_of_native_quantile_and_residual_models_alike(db, generated) -> None:
    """Every model must be measured on the band a run would actually show for it: its own quantiles
    when it has them, the leave-one-origin-out residual band otherwise. A NaN `wql` for the residual
    models would mean the interval column compares nothing."""
    frames = _load_frames(db)
    origin = last_complete_week(generated.config.as_of)
    _, feat, _ = arrival_feature_matrix(frames, origin)
    origins = [origin - 2 * ONE_WEEK, origin - 4 * ONE_WEEK]
    scores, skipped = arrival_level(feat, {"banded": _Banded, "tsb": MODEL_FACTORIES["tsb"]}, origins, (1, 2))
    assert skipped == {}
    for model in ("banded", "tsb"):
        for metric in ("coverage80", "wql"):
            values = scores[(scores.model == model) & (scores.metric == metric)]["value"]
            assert len(values) == len(origins) * 2
            assert values.notna().all(), f"{model} has no {metric}"
            assert (values >= 0).all()
        cov = scores[(scores.model == model) & (scores.metric == "coverage80")]["value"]
        assert (cov <= 1).all()


def test_arrival_level_reports_seconds_once_per_model_over_the_origins_it_scored(db, generated) -> None:
    """The timing is per model over the whole backtest, so it belongs on one row: the first horizon,
    divided by the origins that model actually scored, and NaN elsewhere rather than repeated."""
    frames = _load_frames(db)
    origin = last_complete_week(generated.config.as_of)
    _, feat, _ = arrival_feature_matrix(frames, origin)
    origins = [origin - 2 * ONE_WEEK, origin - 4 * ONE_WEEK]
    scores, _ = arrival_level(feat, {"tsb": MODEL_FACTORIES["tsb"]}, origins, (1, 2))
    seconds = scores[scores.metric == "seconds"]
    assert seconds[seconds.horizon == 1]["value"].notna().all()
    assert seconds[seconds.horizon == 2]["value"].isna().all()
    assert (seconds[seconds.horizon == 1]["value"] > 0).all()


def test_arrival_level_single_origin_yields_nan_coverage_for_a_residual_model(db, generated) -> None:
    """`tsb` has no `predict_quantiles`, so coverage80 must fall back to a leave-one-origin-out band.

    With a single origin there is no other origin to draw that band from, and reporting `0.0`
    would silently claim a (fake) perfect miss rate rather than admitting no band exists.
    """
    frames = _load_frames(db)
    origin = last_complete_week(generated.config.as_of)
    _, feat, _ = arrival_feature_matrix(frames, origin)
    scores, skipped = arrival_level(feat, {"tsb": MODEL_FACTORIES["tsb"]}, [origin - 2 * ONE_WEEK], (1,))
    assert skipped == {}
    cov = scores[scores.metric == "coverage80"]["value"]
    assert len(cov) == 1
    assert cov.isna().all()
    assert scores[scores.metric == "wql"]["value"].isna().all()  # the same band, so the same silence


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
    assert (demand["truth"] > 0).any()  # the truth join actually matched some rows


def test_demand_level_supports_a_harness_local_factory_not_in_the_registry(db, generated, tmp_path) -> None:
    """`chronos2_ft` (registered only inside `evaluate`) must also work here: demand_level must
    pass its own `factories` through to `run_forecast` rather than relying on the global registry.
    """
    key = tmp_path / "k.json"
    key.write_text(__import__("json").dumps(generated.answer_key))
    truth = truth_from_answer_key(key)
    origin = last_complete_week(generated.config.as_of) - 2 * ONE_WEEK
    demand, skipped = demand_level(db, {"local_naive": SeasonalNaive}, [origin], (1,), truth)
    assert skipped == {}
    assert set(demand["model"]) == {"local_naive"}
    assert not demand.empty


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


def test_evaluate_adds_the_fine_tuned_chronos2_candidate(db, generated, monkeypatch) -> None:
    seen: dict[str, bool] = {}

    class _Stub:
        name = "chronos2"

        def __init__(self, pipeline=None, finetune: bool = False) -> None:
            seen["finetune"] = finetune
            raise ModelUnavailable("ft: stub")

    monkeypatch.setattr("whf.models.chronos2.Chronos2Arrival", _Stub)
    config = EvalConfig(as_of=generated.config.as_of, origins=1, models=("seasonal_naive",), teams=(1,), finetune=True)
    result = evaluate(db, config)
    assert result.skipped == {"chronos2_ft": "ft: stub"}
    assert seen == {"finetune": True}


def test_evaluate_rejects_unknown_model_before_doing_any_work(db, generated) -> None:
    config = EvalConfig(as_of=generated.config.as_of, origins=1, models=("nope",), teams=(1,))
    with pytest.raises(ValueError, match="unknown model 'nope'"):
        evaluate(db, config)
