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


def load(env: Mapping[str, str] | None = None, *, shared: bool = True) -> Any:
    """A Chronos2Pipeline on CPU. Raises ModelUnavailable with the reason instead of failing the run.

    `shared` (the default) returns the process-wide instance, loaded on first use. `shared=False`
    builds a fresh one from the same weights and leaves the cached instance alone; fine-tuning uses
    it so that a LoRA fit can never reach the plain `chronos2` candidate running beside it.

    Everything the load touches sits inside one handler: a missing library raises ImportError, but a
    broken torch install raises OSError (`[WinError 126]` on Windows) and a corrupt checkpoint raises
    whatever the library likes. All of them mean the same thing here - the model cannot run - and none
    of them may reach the backtest, which only knows how to skip a ModelUnavailable candidate.
    """
    global _pipeline
    with _lock:
        if shared and _pipeline is not None:
            return _pipeline
        path = weights_path(env)
        if path is not None:
            # Bundled weights exist, so nothing may go to the network: an explicit HF_HUB_OFFLINE=0
            # in the user's environment is overridden on purpose, not merely defaulted.
            os.environ["HF_HUB_OFFLINE"] = "1"
        try:
            # The pipeline class first: importing it is what pulls torch in, so a caller that has
            # made the library unavailable (the test suite does) pays nothing for torch either.
            cls = _import_pipeline_class()
            import torch

            torch.set_num_threads(min(MAX_THREADS, os.cpu_count() or 1))
            if path is not None:
                pipeline = cls.from_pretrained(str(path), device_map="cpu")
            else:
                pipeline = cls.from_pretrained(WEIGHTS_REPO, revision=WEIGHTS_REVISION, device_map="cpu")
        except ModelUnavailable:
            raise
        except Exception as exc:
            raise ModelUnavailable(f"chronos2: cannot load ({type(exc).__name__}: {exc})") from exc
        if shared:
            _pipeline = pipeline
        return pipeline


def warm_up() -> threading.Thread:
    """Load the pipeline in the background so the first forecast does not pay for it."""

    def _run() -> None:
        try:
            load()
        except Exception as exc:  # a daemon thread must never dump a traceback into the service's stderr
            print(f"chronos2: warm-up skipped ({exc})", file=sys.stderr)

    thread = threading.Thread(target=_run, name="chronos2-warm-up", daemon=True)
    thread.start()
    return thread


class Chronos2Arrival:
    """Zero-shot Chronos-2 over the weekly arrival series, with the feature matrix's covariates.

    Fine-tuning is harness-only and always runs on a private pipeline: it never touches the
    process-wide one that the plain `chronos2` candidate shares.
    """

    name = "chronos2"

    def __init__(self, pipeline: ForecastPipeline | None = None, finetune: bool = False) -> None:
        self._pipeline = pipeline
        self._history: pd.DataFrame | None = None
        self._memo: tuple[Any, dict[float, np.ndarray]] | None = None
        self.finetune = finetune

    def _pipe(self) -> Any:
        return self._pipeline if self._pipeline is not None else load()

    def fit(self, train: pd.DataFrame, horizons: tuple[int, ...] = HORIZONS) -> Chronos2Arrival:
        keep = ["member_id", "week_start", "est_hours", *[f"{c}_h1" for c in PAST_COVARIATES]]
        hist = train[keep].copy()
        hist["member_id"] = hist["member_id"].astype(int)
        self._history = hist.sort_values(["member_id", "week_start"]).reset_index(drop=True)
        self._memo = None
        self._pipe()  # fail early with ModelUnavailable when the model cannot run here
        if self.finetune:
            self._finetune(max(horizons))
        return self

    def predict(self, rows: pd.DataFrame, horizon: int) -> np.ndarray:
        return self._quantiles(rows, horizon)[0.5].copy()  # a copy: the memo below hands out the same array

    def predict_quantiles(self, rows: pd.DataFrame, horizon: int) -> tuple[np.ndarray, np.ndarray]:
        q = self._quantiles(rows, horizon)
        return np.minimum(q[0.1], q[0.5]), np.maximum(q[0.9], q[0.5])

    def _quantiles(self, rows: pd.DataFrame, horizon: int) -> dict[float, np.ndarray]:
        # The callers ask for the point value and the band separately (the backtest and the pipeline
        # both do), which is the same inference twice; one memo of the last answer halves it.
        key = (
            tuple(sorted(set(rows["week_start"]))),
            tuple(int(m) for m in rows["member_id"].astype(int)),
            horizon,
        )
        if self._memo is not None and self._memo[0] == key:
            return self._memo[1]
        df, future = self._frames(rows, horizon)
        # Seed the library the pipeline actually runs on. `load()` imports torch before it builds a
        # real pipeline, so this finds it there; an injected stub never needs torch and must not
        # cause it to be imported (importing torch costs a test worker seconds and ~400 MB).
        torch = sys.modules.get("torch")
        if torch is not None:
            torch.manual_seed(0)
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
        # A NaN or infinity from the model would travel straight into the demand; it means "no
        # signal", so it becomes zero here rather than poisoning the arithmetic downstream.
        answer = {
            q: np.clip(
                np.nan_to_num(last.loc[ids, str(q)].to_numpy(dtype=float), nan=0.0, posinf=0.0, neginf=0.0),
                0.0,
                None,
            )
            for q in QUANTILES
        }
        self._memo = (key, answer)
        return answer

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
        """Harness-only LoRA fine-tune on the training window (targets only). Replaces this instance's pipeline.

        The base is an injected pipeline or a private copy of the weights, never the process-wide
        instance: `fit` may return `self` or attach adapters in place, which would silently turn every
        later `chronos2` fit in this process into the fine-tuned model.
        """
        assert self._history is not None
        base = self._pipeline if self._pipeline is not None else load(shared=False)
        inputs = [g["est_hours"].to_numpy(dtype=np.float32) for _, g in self._history.groupby("member_id", sort=True)]
        self._pipeline = base.fit(
            inputs,
            prediction_length=prediction_length,
            finetune_mode="lora",
            num_steps=FINETUNE_STEPS,
            learning_rate=FINETUNE_LR,
            remove_printer_callback=True,
        )
