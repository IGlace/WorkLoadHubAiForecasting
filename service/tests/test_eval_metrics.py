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
