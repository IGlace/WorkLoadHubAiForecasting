"""Fakes standing in for the Copilot SDK client and session in tests."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from types import SimpleNamespace
from typing import Any

from copilot.generated.session_events import SessionEventType

from whf.ai.progress import ProgressEvent
from whf.ai.session import NarrativeOutcome


def make_event(event_type: SessionEventType, **data: Any) -> SimpleNamespace:
    return SimpleNamespace(type=event_type, data=SimpleNamespace(**data))


@dataclass
class FakeSession:
    replies: list[str | Exception]
    tools: list[Any] = field(default_factory=list)
    # What a streaming turn sends before the final message: an intent line, reasoning as deltas
    # (id, chunk) or in one piece (id, content), and the answer in chunks.
    intents: list[str] = field(default_factory=list)
    reasoning_deltas: list[tuple[str, str]] = field(default_factory=list)
    reasoning_full: list[tuple[str, str]] = field(default_factory=list)
    message_deltas: list[str] = field(default_factory=list)
    unmatched_tool_complete_id: str | None = None
    prompts: list[str] = field(default_factory=list)
    handlers: list[Any] = field(default_factory=list)
    disconnected: bool = False
    call_tools_first: bool = True
    return_none: bool = False
    emit_session_error: str | None = None
    disconnect_raises: bool = False

    def on(self, handler):
        self.handlers.append(handler)
        return lambda: None

    async def send_and_wait(self, prompt: str, *, timeout: float = 60.0):
        self.prompts.append(prompt)
        if self.call_tools_first and self.tools:
            for index, tool in enumerate(self.tools[:2]):
                call_id = f"c{index}"
                self._emit(
                    make_event(
                        SessionEventType.TOOL_EXECUTION_START,
                        tool_name=tool.name,
                        tool_call_id=call_id,
                        arguments={},
                    )
                )
                self._emit(make_event(SessionEventType.TOOL_EXECUTION_COMPLETE, tool_call_id=call_id, success=True))
        if self.unmatched_tool_complete_id:
            self._emit(
                make_event(
                    SessionEventType.TOOL_EXECUTION_COMPLETE, tool_call_id=self.unmatched_tool_complete_id, success=True
                )
            )
        for intent in self.intents:
            self._emit(make_event(SessionEventType.ASSISTANT_INTENT, intent=intent))
        for reasoning_id, chunk in self.reasoning_deltas:
            self._emit(
                make_event(SessionEventType.ASSISTANT_REASONING_DELTA, delta_content=chunk, reasoning_id=reasoning_id)
            )
        for reasoning_id, content in self.reasoning_full:
            self._emit(make_event(SessionEventType.ASSISTANT_REASONING, content=content, reasoning_id=reasoning_id))
        for chunk in self.message_deltas:
            self._emit(make_event(SessionEventType.ASSISTANT_MESSAGE_DELTA, delta_content=chunk, message_id="m1"))
        if self.return_none:
            return None
        if self.emit_session_error:
            for h in self.handlers:
                h(make_event(SessionEventType.SESSION_ERROR, message=self.emit_session_error, error_type="model"))
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        for h in self.handlers:
            h(make_event(SessionEventType.ASSISTANT_USAGE, input_tokens=100, output_tokens=50))
        event = make_event(SessionEventType.ASSISTANT_MESSAGE, content=reply, message_id="m1", model="gpt-5")
        for h in self.handlers:
            h(event)
        return event

    def _emit(self, event) -> None:
        for handler in self.handlers:
            handler(event)

    async def disconnect(self) -> None:
        self.disconnected = True
        if self.disconnect_raises:
            raise RuntimeError("disconnect failed: connection already closed")


@dataclass
class FakeClient:
    replies: list[str | Exception]
    authenticated: bool = True
    start_error: Exception | None = None
    auth_error: Exception | None = None
    session_error: Exception | None = None
    reply_none: bool = False
    emit_session_error: str | None = None
    disconnect_raises: bool = False
    started: bool = False
    stopped: bool = False
    session: FakeSession | None = None
    session_kwargs: dict = field(default_factory=dict)
    intents: list[str] = field(default_factory=list)
    reasoning_deltas: list[tuple[str, str]] = field(default_factory=list)
    reasoning_full: list[tuple[str, str]] = field(default_factory=list)
    message_deltas: list[str] = field(default_factory=list)
    unmatched_tool_complete_id: str | None = None

    async def start(self) -> None:
        if self.start_error:
            raise self.start_error
        self.started = True

    async def get_auth_status(self):
        if self.auth_error:
            raise self.auth_error
        return SimpleNamespace(
            isAuthenticated=self.authenticated, login="sara" if self.authenticated else None, statusMessage=None
        )

    async def create_session(self, **kwargs):
        if self.session_error:
            raise self.session_error
        self.session_kwargs = kwargs
        self.session = FakeSession(
            replies=self.replies,
            tools=list(kwargs.get("tools") or []),
            return_none=self.reply_none,
            emit_session_error=self.emit_session_error,
            disconnect_raises=self.disconnect_raises,
            intents=list(self.intents),
            reasoning_deltas=list(self.reasoning_deltas),
            reasoning_full=list(self.reasoning_full),
            message_deltas=list(self.message_deltas),
            unmatched_tool_complete_id=self.unmatched_tool_complete_id,
        )
        if kwargs.get("on_event"):
            self.session.on(kwargs["on_event"])
        return self.session

    async def stop(self) -> None:
        self.stopped = True


def good_narrative(facts: dict) -> str:
    members = [
        {
            "member_id": m["id"],
            "name": m["name"],
            "risk_level": "low",
            "summary": f"Demand {m['forecast'][0]['demand']} h against {m['forecast'][0]['capacity']} h capacity in the week of {m['forecast'][0]['week']}.",
            "patterns": [],
            "warnings": [],
        }
        for m in facts["members"]
    ]
    return json.dumps(
        {
            "run_summary": "All members within capacity.",
            "members": members,
            "team_risks": [],
            "rebalancing": [],
            "suggested_adjustments": [],
            "model_notes": "",
        }
    )


class FakeNarrator:
    """A Narrator that returns a prepared outcome; used by narrate/CLI/API tests."""

    def __init__(self, outcome: NarrativeOutcome | None = None) -> None:
        self.outcome = outcome if outcome is not None else NarrativeOutcome(status="ok")
        self.calls: list[dict] = []

    def narrate_sync(self, facts: dict, progress=None, live=None):
        self.calls.append(facts)
        if progress:
            progress(ProgressEvent("asking", "1"))
        if live:
            live("thinking", "reading the facts")
            live("answer", "{")
        return self.outcome
