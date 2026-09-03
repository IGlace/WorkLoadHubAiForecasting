"""Fakes standing in for the Copilot SDK client and session in tests."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from types import SimpleNamespace
from typing import Any

from copilot.generated.session_events import SessionEventType


def make_event(event_type: SessionEventType, **data: Any) -> SimpleNamespace:
    return SimpleNamespace(type=event_type, data=SimpleNamespace(**data))


@dataclass
class FakeSession:
    replies: list[str | Exception]
    tools: list[Any] = field(default_factory=list)
    prompts: list[str] = field(default_factory=list)
    handlers: list[Any] = field(default_factory=list)
    disconnected: bool = False
    call_tools_first: bool = True

    def on(self, handler):
        self.handlers.append(handler)
        return lambda: None

    async def send_and_wait(self, prompt: str, *, timeout: float = 60.0):
        self.prompts.append(prompt)
        if self.call_tools_first and self.tools:
            for tool in self.tools[:2]:
                for h in self.handlers:
                    h(
                        make_event(
                            SessionEventType.TOOL_EXECUTION_START, tool_name=tool.name, tool_call_id="c1", arguments={}
                        )
                    )
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        for h in self.handlers:
            h(make_event(SessionEventType.ASSISTANT_USAGE, input_tokens=100, output_tokens=50))
        event = make_event(SessionEventType.ASSISTANT_MESSAGE, content=reply, message_id="m1", model="gpt-5")
        for h in self.handlers:
            h(event)
        return event

    async def disconnect(self) -> None:
        self.disconnected = True


@dataclass
class FakeClient:
    replies: list[str | Exception]
    authenticated: bool = True
    start_error: Exception | None = None
    started: bool = False
    stopped: bool = False
    session: FakeSession | None = None
    session_kwargs: dict = field(default_factory=dict)

    async def start(self) -> None:
        if self.start_error:
            raise self.start_error
        self.started = True

    async def get_auth_status(self):
        return SimpleNamespace(
            isAuthenticated=self.authenticated, login="sara" if self.authenticated else None, statusMessage=None
        )

    async def create_session(self, **kwargs):
        self.session_kwargs = kwargs
        self.session = FakeSession(replies=self.replies, tools=list(kwargs.get("tools") or []))
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

    def __init__(self, outcome) -> None:
        self.outcome = outcome
        self.calls: list[dict] = []

    def narrate_sync(self, facts: dict, progress=None):
        self.calls.append(facts)
        if progress:
            progress("fake narrator")
        return self.outcome
