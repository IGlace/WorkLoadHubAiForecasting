"""Chronos-2 (Amazon, Apache 2.0) as a zero-shot arrival model. The pipeline is injectable for tests;
the real one is loaded once per process from bundled weights, never from the network on a user's machine."""

from __future__ import annotations

import datetime as dt
import os
import sys
import threading
from collections.abc import Mapping
from pathlib import Path
from typing import Any, Protocol

import numpy as np
import pandas as pd

from whf.calendar import ONE_WEEK
from whf.features import HORIZONS, LAGS
from whf.models.base import ModelUnavailable

WEIGHTS_REPO = "amazon/chronos-2"
WEIGHTS_REVISION = "29ec3766d36d6f73f0696f85560a422f50e8498c"
PAST_COVARIATES = (
    "working_days",
    "vacation_days",
    "proj_active",
    "proj_min_weeks_to_deadline",
    "proj_starting",
    "proj_ending",
)
QUANTILES = (0.1, 0.5, 0.9)
MAX_THREADS = 4
FINETUNE_STEPS = 200
FINETUNE_LR = 1e-5
_CONTIGUOUS_LAGS = tuple(k for k in LAGS if k <= 4)  # lag1..lag4 are consecutive weeks ending at the row's week


class ForecastPipeline(Protocol):
    def predict_df(self, df: pd.DataFrame, future_df: pd.DataFrame | None = None, **kwargs: Any) -> pd.DataFrame: ...


_lock = threading.Lock()
_pipeline: Any = None


def weights_path(env: Mapping[str, str] | None = None, exe_dir: Path | None = None) -> Path | None:
    """First existing of: WHF_CHRONOS2_PATH; <frozen exe dir>/models/chronos-2. None means: use the library's cache."""
    env = os.environ if env is None else env
    custom = env.get("WHF_CHRONOS2_PATH")
    if custom and Path(custom).is_dir():
        return Path(custom)
    if exe_dir is None and getattr(sys, "frozen", False):
        exe_dir = Path(sys.executable).parent
    if exe_dir is not None:
        bundled = exe_dir / "models" / "chronos-2"
        if bundled.is_dir():
            return bundled
    return None


def _import_pipeline_class() -> Any:
    from chronos import Chronos2Pipeline

    return Chronos2Pipeline


def load(env: Mapping[str, str] | None = None) -> Any:
    """The process-wide Chronos2Pipeline on CPU, loaded on first use. Raises ModelUnavailable with the reason."""
    global _pipeline
    with _lock:
        if _pipeline is not None:
            return _pipeline
        path = weights_path(env)
        if path is not None:
            os.environ.setdefault("HF_HUB_OFFLINE", "1")
        try:
            import torch

            cls = _import_pipeline_class()
        except ImportError as exc:
            raise ModelUnavailable(f"chronos2: library not installed ({exc})") from exc
        torch.set_num_threads(min(MAX_THREADS, os.cpu_count() or 1))
        try:
            if path is not None:
                _pipeline = cls.from_pretrained(str(path), device_map="cpu")
            else:
                _pipeline = cls.from_pretrained(WEIGHTS_REPO, revision=WEIGHTS_REVISION, device_map="cpu")
        except Exception as exc:  # noqa: BLE001 - any loading failure means "unavailable here"
            raise ModelUnavailable(f"chronos2: cannot load weights ({exc})") from exc
        return _pipeline


def warm_up() -> threading.Thread:
    """Load the pipeline in the background so the first forecast does not pay for it."""

    def _run() -> None:
        try:
            load()
        except ModelUnavailable:
            pass

    thread = threading.Thread(target=_run, name="chronos2-warm-up", daemon=True)
    thread.start()
    return thread


class Chronos2Arrival:
    name = "chronos2"

    def __init__(self, pipeline: ForecastPipeline | None = None, finetune: bool = False) -> None:
        self._pipeline = pipeline
        self._history: pd.DataFrame | None = None
        self.finetune = finetune

    def _pipe(self) -> Any:
        return self._pipeline if self._pipeline is not None else load()

    def fit(self, train: pd.DataFrame, horizons: tuple[int, ...] = HORIZONS) -> Chronos2Arrival:
        keep = ["member_id", "week_start", "est_hours", *[f"{c}_h1" for c in PAST_COVARIATES]]
        hist = train[keep].copy()
        hist["member_id"] = hist["member_id"].astype(int)
        self._history = hist.sort_values(["member_id", "week_start"]).reset_index(drop=True)
        self._pipe()  # fail early with ModelUnavailable when the model cannot run here
        if self.finetune:
            self._finetune(max(horizons))
        return self

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        return self._quantiles(rows, horizon)[0.5]

    def predict_quantiles(self, rows: pd.DataFrame, horizon: int) -> tuple[np.ndarray, np.ndarray]:
        q = self._quantiles(rows, horizon)
        return np.minimum(q[0.1], q[0.5]), np.maximum(q[0.9], q[0.5])

    def _quantiles(self, rows: pd.DataFrame, horizon: int) -> dict[float, np.ndarray]:
        df, future = self._frames(rows, horizon)
        try:
            import torch

            torch.manual_seed(0)
        except ImportError:
            pass
        out = self._pipe().predict_df(
            df,
            future_df=future,
            id_column="member_id",
            timestamp_column="timestamp",
            target="est_hours",
            prediction_length=horizon,
            quantile_levels=list(QUANTILES),
            freq="W-MON",
        )
        id_col = "member_id" if "member_id" in out.columns else "item_id"
        last = out.sort_values("timestamp").groupby(id_col).tail(1).set_index(id_col)
        ids = rows["member_id"].astype(int).to_numpy()
        return {q: np.clip(last.loc[ids, str(q)].to_numpy(dtype=float), 0.0, None) for q in QUANTILES}

    def _frames(self, rows: pd.DataFrame, horizon: int) -> tuple[pd.DataFrame, pd.DataFrame]:
        if self._history is None:
            raise RuntimeError("fit() before predict()")
        weeks_in_rows = set(rows["week_start"])
        if len(weeks_in_rows) != 1:
            raise ValueError("predict() expects rows from one week")
        (w0,) = weeks_in_rows
        past_frames, future_rows = [], []
        for r in rows.itertuples(index=False):
            m = int(r.member_id)
            hist = self._history[(self._history.member_id == m) & (self._history.week_start <= w0)]
            series: dict[dt.date, float] = dict(zip(hist.week_start, hist.est_hours.astype(float), strict=True))
            for k in _CONTIGUOUS_LAGS:  # the weeks between the training cut-off and w0 live in the row's lags
                value = getattr(r, f"lag{k}")
                if not pd.isna(value):
                    series[w0 - (k - 1) * ONE_WEEK] = float(value)
            first = min(series) if series else w0
            weeks = [first + i * ONE_WEEK for i in range((w0 - first).days // 7 + 1)]
            cov: dict[str, dict[dt.date, float]] = {c: {} for c in PAST_COVARIATES}
            prev = None
            for h_row in hist.itertuples(index=False):
                if prev is not None:  # x_h1 on week w describes week w+1
                    for c in PAST_COVARIATES:
                        cov[c][h_row.week_start] = float(getattr(prev, f"{c}_h1"))
                prev = h_row
            if len(hist):
                head = hist.iloc[0]
                for c in PAST_COVARIATES:
                    cov[c].setdefault(head.week_start, float(head[f"{c}_h1"]))
            frame = pd.DataFrame(
                {
                    "member_id": m,
                    "timestamp": [pd.Timestamp(w) for w in weeks],
                    "est_hours": [series.get(w, 0.0) for w in weeks],
                }
            )
            for c in PAST_COVARIATES:
                values = pd.Series([cov[c].get(w, np.nan) for w in weeks]).ffill().bfill().fillna(0.0)
                frame[c] = values.to_numpy(dtype=float)
            past_frames.append(frame)
            for k in range(1, horizon + 1):
                future_rows.append(
                    {
                        "member_id": m,
                        "timestamp": pd.Timestamp(w0 + k * ONE_WEEK),
                        **{c: float(getattr(r, f"{c}_h{k}")) for c in PAST_COVARIATES},
                    }
                )
        return pd.concat(past_frames, ignore_index=True), pd.DataFrame(future_rows)

    def _finetune(self, prediction_length: int) -> None:
        """Harness-only LoRA fine-tune on the training window (targets only). Replaces this instance's pipeline."""
        assert self._history is not None
        inputs = [g["est_hours"].to_numpy(dtype=np.float32) for _, g in self._history.groupby("member_id", sort=True)]
        self._pipeline = self._pipe().fit(
            inputs,
            prediction_length=prediction_length,
            finetune_mode="lora",
            num_steps=FINETUNE_STEPS,
            learning_rate=FINETUNE_LR,
            remove_printer_callback=True,
        )
