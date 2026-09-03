"""One Copilot session per run: tools over the facts in, a validated narrative out."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

from whf.ai.facts_tools import FactsToolbox
from whf.ai.prompt import SYSTEM_PROMPT, build_retry_prompt, build_user_prompt, skill_directories
from whf.ai.schema import parse_narrative
from whf.ai.verify import verify_narrative

log = logging.getLogger(__name__)
Reason = Literal["not_signed_in", "cli_unavailable", "timeout", "invalid_output", "model_error", "other"]


@dataclass
class NarrativeOutcome:
    status: Literal["ok", "unverified", "failed"]
    narrative: dict | None = None
    error: str | None = None
    reason: Reason | None = None
    raw_text: str | None = None
    verification: dict | None = None
    model: str | None = None
    usage: dict = field(default_factory=dict)
    attempts: int = 0
    tool_calls: list[str] = field(default_factory=list)

    @property
    def ai_status(self) -> str:
        return self.status if self.status != "failed" else f"failed:{self.reason or 'other'}"


@dataclass
class NarratorConfig:
    model: str | None = None
    timeout_seconds: float = 240.0
    max_attempts: int = 2
    skill_directories: list[str] | None = None
    log_level: str = "error"


class Narrator(Protocol):
    def narrate_sync(self, facts: dict, progress: Callable[[str], None] | None = None) -> NarrativeOutcome: ...


def _default_client_factory(config: NarratorConfig) -> Callable[[], Any]:
    def factory() -> Any:
        from copilot import CopilotClient

        return CopilotClient(log_level=config.log_level)

    return factory


class CopilotNarrator:
    def __init__(self, config: NarratorConfig | None = None, client_factory: Callable[[], Any] | None = None) -> None:
        self.config = config or NarratorConfig()
        self._client_factory = client_factory or _default_client_factory(self.config)

    def narrate_sync(self, facts: dict, progress: Callable[[str], None] | None = None) -> NarrativeOutcome:
        return asyncio.run(self.narrate(facts, progress))

    async def narrate(self, facts: dict, progress: Callable[[str], None] | None = None) -> NarrativeOutcome:
        say = progress or (lambda _msg: None)
        outcome = NarrativeOutcome(status="failed", reason="other")
        client = self._client_factory()
        try:
            say("starting Copilot")
            try:
                await client.start()
            except Exception as exc:  # the SDK raises RuntimeError when the CLI is missing or cannot start
                return NarrativeOutcome(status="failed", reason="cli_unavailable", error=str(exc))
            try:
                auth = await client.get_auth_status()
            except Exception as exc:
                return NarrativeOutcome(
                    status="failed", reason="other", error=f"could not read Copilot sign-in status: {exc}"
                )
            if not getattr(auth, "isAuthenticated", False):
                return NarrativeOutcome(
                    status="failed",
                    reason="not_signed_in",
                    error=getattr(auth, "statusMessage", None) or "not signed in to GitHub Copilot",
                )
            toolbox = FactsToolbox(facts)
            state: dict[str, Any] = {"messages": [], "model": None, "usage": {}, "tools": []}

            def on_event(event: Any) -> None:
                from copilot.generated.session_events import SessionEventType

                if event.type == SessionEventType.ASSISTANT_MESSAGE:
                    state["messages"].append(event.data.content)
                    state["model"] = getattr(event.data, "model", None) or state["model"]
                elif event.type == SessionEventType.TOOL_EXECUTION_START:
                    state["tools"].append(event.data.tool_name)
                    say(f"tool {event.data.tool_name}")
                elif event.type == SessionEventType.ASSISTANT_USAGE:
                    state["usage"] = {
                        "input_tokens": getattr(event.data, "input_tokens", None),
                        "output_tokens": getattr(event.data, "output_tokens", None),
                    }
                elif event.type == SessionEventType.SESSION_ERROR:
                    state["error"] = getattr(event.data, "message", "session error")

            from copilot import ToolSet

            say("creating session")
            try:
                session = await client.create_session(
                    model=self.config.model,
                    tools=toolbox.tools(),
                    system_message={"mode": "replace", "content": SYSTEM_PROMPT},
                    available_tools=ToolSet().add_custom("*"),
                    skill_directories=self.config.skill_directories or skill_directories(),
                    streaming=False,
                    on_event=on_event,
                )
            except Exception as exc:
                return NarrativeOutcome(
                    status="failed", reason="other", error=f"could not create Copilot session: {exc}"
                )
            try:
                prompt = build_user_prompt(facts)
                raw = ""
                problems: list[str] = []
                for attempt in range(1, max(1, self.config.max_attempts) + 1):
                    outcome.attempts = attempt
                    say(f"asking Copilot (attempt {attempt})")
                    try:
                        event = await session.send_and_wait(prompt, timeout=self.config.timeout_seconds)
                    except TimeoutError as exc:  # asyncio.TimeoutError is an alias since Python 3.11
                        detail = f" ({exc})" if str(exc) else ""
                        return self._finish(
                            outcome,
                            state,
                            status="failed",
                            reason="timeout",
                            error=f"no answer within {self.config.timeout_seconds:.0f} s{detail}",
                        )
                    except Exception as exc:
                        error = str(exc)
                        if state.get("error"):
                            error = f"{error}; session error: {state['error']}"
                        return self._finish(outcome, state, status="failed", reason="model_error", error=error)
                    raw = self._content_of(event, state)
                    problems = []
                    try:
                        narrative = parse_narrative(raw)
                        problems = narrative.validate_against_facts(facts)
                    except ValueError as exc:
                        problems = [str(exc)]
                        narrative = None
                    if narrative is not None and not problems:
                        report = verify_narrative(narrative, facts)
                        outcome.narrative = narrative.model_dump(mode="json")
                        outcome.raw_text = raw
                        outcome.verification = {
                            "checked": report.checked,
                            "unverified": report.unverified,
                            "fields": report.fields,
                        }
                        return self._finish(outcome, state, status="ok" if report.ok else "unverified")
                    log.info("narrative rejected on attempt %s: %s", attempt, problems)
                    prompt = build_retry_prompt(problems)
                return self._finish(
                    outcome, state, status="failed", reason="invalid_output", error="; ".join(problems), raw_text=raw
                )
            finally:
                await session.disconnect()
        finally:
            try:
                await client.stop()
            except Exception as exc:  # stopping must never mask the real outcome
                log.warning("copilot client stop failed: %s", exc)

    @staticmethod
    def _content_of(event: Any, state: dict) -> str:
        from copilot.generated.session_events import SessionEventType

        if event is not None and getattr(event, "type", None) == SessionEventType.ASSISTANT_MESSAGE:
            return event.data.content
        return state["messages"][-1] if state["messages"] else ""

    @staticmethod
    def _finish(
        outcome: NarrativeOutcome,
        state: dict,
        *,
        status: str,
        reason: Reason | None = None,
        error: str | None = None,
        raw_text: str | None = None,
    ) -> NarrativeOutcome:
        outcome.status = status  # type: ignore[assignment]
        outcome.reason = reason
        outcome.error = error
        outcome.raw_text = raw_text if raw_text is not None else outcome.raw_text
        outcome.model = state.get("model")
        outcome.usage = state.get("usage", {})
        outcome.tool_calls = list(state.get("tools", []))
        return outcome
