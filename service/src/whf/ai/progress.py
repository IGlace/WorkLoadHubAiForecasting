"""What a Copilot narration is doing right now, for a caller that has to wait for it."""

from __future__ import annotations

import datetime as dt
import threading
from collections import OrderedDict, deque
from dataclasses import dataclass
from typing import Literal

# What the session is doing, independent of language. The desktop app phrases each of these in
# English or French itself; `message` is the English wording, for the CLI and the logs.
ProgressCode = Literal["starting", "session", "asking", "tool", "checking"]

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
        if self.code == "asking":
            return f"asking Copilot (attempt {self.detail})" if self.detail else "asking Copilot"
        return _MESSAGES[self.code]


class ProgressStore:
    """The steps of the narrations that are running or recently finished, in memory only.

    Bounded on both axes: the service can run for weeks, so it keeps the last few runs and the last
    few steps of each. Locked because the narration runs in FastAPI's threadpool while the polling
    request that reads it is served on another thread.
    """

    def __init__(self, keep_runs: int = 8, keep_steps: int = 50) -> None:
        self._keep_runs = keep_runs
        self._keep_steps = keep_steps
        self._runs: OrderedDict[int, deque[dict]] = OrderedDict()
        self._lock = threading.Lock()

    def begin(self, run_id: int) -> None:
        """Start a fresh list for this run, so a second narration does not show the first one's steps."""
        with self._lock:
            self._runs.pop(run_id, None)
            self._fresh(run_id)

    def record(self, run_id: int, event: ProgressEvent) -> None:
        with self._lock:
            steps = self._runs.get(run_id) or self._fresh(run_id)
            steps.append(
                {
                    "code": event.code,
                    "detail": event.detail,
                    "at": dt.datetime.now().isoformat(timespec="seconds"),
                }
            )

    def steps(self, run_id: int) -> list[dict]:
        with self._lock:
            return [dict(step) for step in self._runs.get(run_id, ())]

    def _fresh(self, run_id: int) -> deque[dict]:
        steps: deque[dict] = deque(maxlen=self._keep_steps)
        self._runs[run_id] = steps
        while len(self._runs) > self._keep_runs:
            self._runs.popitem(last=False)
        return steps
