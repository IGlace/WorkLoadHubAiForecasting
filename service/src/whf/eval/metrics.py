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
