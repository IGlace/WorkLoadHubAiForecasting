"""What a Copilot narration is doing right now, for a caller that has to wait for it."""

from __future__ import annotations

import datetime as dt
import threading
from collections import OrderedDict, deque
from dataclasses import dataclass
from typing import Literal

# What the session is doing, independent of language. The desktop app phrases each of these in
# English or French itself; `message` is the English wording, for the CLI and the logs.
ProgressCode = Literal["starting", "session", "asking", "tool", "tool_done", "checking"]
# The two live buffers: what Copilot is reasoning about, and the answer as it is written.
LiveKind = Literal["thinking", "answer"]

_MESSAGES: dict[str, str] = {
    "starting": "starting Copilot",
    "session": "creating session",
    "checking": "checking the answer against the facts",
}


@dataclass(frozen=True)
class ProgressEvent:
    code: ProgressCode
    detail: str | None = None

    @property
    def message(self) -> str:
        if self.code == "tool":
            return f"tool {self.detail}" if self.detail else "tool"
        if self.code == "tool_done":
            return f"tool {self.detail} done" if self.detail else "tool done"
        if self.code == "asking":
            return f"asking Copilot (attempt {self.detail})" if self.detail else "asking Copilot"
        return _MESSAGES[self.code]


@dataclass
class _RunProgress:
    """One narration: its coded steps, and the tail of the text Copilot has sent so far."""

    steps: deque[dict]
    thinking: str = ""
    answer: str = ""


class ProgressStore:
    """What the narrations that are running or recently finished are doing, in memory only.

    Bounded on every axis: the service can run for weeks, so it keeps the last few runs, the last
    few steps of each, and only the tail of the live text — Copilot can think for minutes. Locked
    because the narration runs in FastAPI's threadpool while the polling request that reads it is
    served on another thread.
    """

    def __init__(self, keep_runs: int = 8, keep_steps: int = 50, keep_chars: int = 16000) -> None:
        self._keep_runs = keep_runs
        self._keep_steps = keep_steps
        self._keep_chars = keep_chars
        self._runs: OrderedDict[int, _RunProgress] = OrderedDict()
        self._lock = threading.Lock()

    def begin(self, run_id: int) -> None:
        """Start a fresh entry for this run, so a second narration shows nothing of the first one."""
        with self._lock:
            self._runs.pop(run_id, None)
            self._fresh(run_id)

    def record(self, run_id: int, event: ProgressEvent) -> None:
        with self._lock:
            run = self._runs.get(run_id) or self._fresh(run_id)
            run.steps.append(
                {
                    "code": event.code,
                    "detail": event.detail,
                    "at": dt.datetime.now().isoformat(timespec="seconds"),
                }
            )

    def append(self, run_id: int, kind: LiveKind, text: str) -> None:
        """Add a chunk of live text, keeping only the last `keep_chars` characters of the buffer."""
        with self._lock:
            run = self._runs.get(run_id) or self._fresh(run_id)
            setattr(run, kind, (getattr(run, kind) + text)[-self._keep_chars :])

    def reset_answer(self, run_id: int) -> None:
        """Empty the answer buffer: a retry attempt writes a new answer, but keeps its thinking.

        A run the store has never seen is left alone rather than created: the app polls before the
        first step, and creating an entry here would push a real run out of the bounded history.
        """
        with self._lock:
            run = self._runs.get(run_id)
            if run is not None:
                run.answer = ""

    def steps(self, run_id: int) -> list[dict]:
        with self._lock:
            run = self._runs.get(run_id)
            return [dict(step) for step in run.steps] if run else []

    def live(self, run_id: int) -> dict:
        with self._lock:
            run = self._runs.get(run_id)
            return {"thinking": run.thinking, "answer": run.answer} if run else {"thinking": "", "answer": ""}

    def _fresh(self, run_id: int) -> _RunProgress:
        run = _RunProgress(steps=deque(maxlen=self._keep_steps))
        self._runs[run_id] = run
        while len(self._runs) > self._keep_runs:
            self._runs.popitem(last=False)
        return run
