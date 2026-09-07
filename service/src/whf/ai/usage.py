"""What one Copilot narration cost, in one shape whatever the source.

Two sources answer the question, and they answer it differently. The session's own metrics
(`session.rpc.usage.get_metrics()`) are what the account is billed: AI credits, the legacy
premium-request cost, the time spent in the model API. The `assistant.usage` events the session
streams carry tokens per call and nothing about money. Both are folded into the same dict here, with
`source` saying which one it came from, so the CLI and the app can show what is known and stay quiet
about what is not: every value is `None` when it is unknown, never a plausible-looking zero.

Since 1 June 2026 Copilot bills AI credits; one credit is one US cent, and the SDK reports credits
as nano-AI units (credits × 1e9).
"""

from __future__ import annotations

from typing import Any

TOKEN_FIELDS = ("input_tokens", "output_tokens", "cache_read_tokens", "reasoning_tokens")
NANO_PER_CREDIT = 1e9
CREDITS_PER_USD = 100.0


def empty_usage() -> dict:
    """Nothing is known: no session was ever created, so no number can be honest.

    `models` is the one exception to "unknown is None": it is a mapping the app iterates over, so
    the empty mapping is both true and easier to consume than a null.
    """
    return {
        "input_tokens": None,
        "output_tokens": None,
        "cache_read_tokens": None,
        "reasoning_tokens": None,
        "requests": None,
        "premium_requests": None,
        "ai_credits": None,
        "usd": None,
        "api_seconds": None,
        "models": {},
        "source": "none",
    }


def usage_from_metrics(metrics: Any) -> dict:
    """The session's billed usage, from an SDK `UsageGetMetricsResult`."""
    model_metrics: dict[str, Any] = dict(getattr(metrics, "model_metrics", None) or {})
    nano_aiu = getattr(metrics, "total_nano_aiu", None)
    credits = None if nano_aiu is None else float(nano_aiu) / NANO_PER_CREDIT
    return {
        "input_tokens": sum(int(m.usage.input_tokens) for m in model_metrics.values()),
        "output_tokens": sum(int(m.usage.output_tokens) for m in model_metrics.values()),
        "cache_read_tokens": sum(int(m.usage.cache_read_tokens) for m in model_metrics.values()),
        # A model that does not reason reports no reasoning tokens; it did use zero of them.
        "reasoning_tokens": sum(int(m.usage.reasoning_tokens or 0) for m in model_metrics.values()),
        "requests": int(metrics.total_user_requests),
        "premium_requests": float(metrics.total_premium_request_cost),
        "ai_credits": credits,
        "usd": None if credits is None else credits / CREDITS_PER_USD,
        "api_seconds": float(metrics.total_api_duration_ms) / 1000.0,
        "models": {
            name: {
                "requests": int(m.requests.count),
                "input_tokens": int(m.usage.input_tokens),
                "output_tokens": int(m.usage.output_tokens),
            }
            for name, m in model_metrics.items()
        },
        "source": "metrics",
    }


def usage_from_events(events: list[dict]) -> dict:
    """The tokens the streamed `assistant.usage` events reported, one event per model call.

    A field no event carried stays `None`: the events simply did not say. Money is never known
    here, so it stays `None` too, which is what tells the app to show tokens only.
    """
    usage = empty_usage()
    for field in TOKEN_FIELDS:
        reported = [event[field] for event in events if event.get(field) is not None]
        usage[field] = sum(int(value) for value in reported) if reported else None
    usage["requests"] = len(events)
    models: dict[str, dict] = {}
    for event in events:
        name = event.get("model")
        if name is None:  # an event that does not name its model cannot be attributed to one
            continue
        entry = models.setdefault(name, {"requests": 0, "input_tokens": 0, "output_tokens": 0})
        entry["requests"] += 1
        entry["input_tokens"] += int(event.get("input_tokens") or 0)
        entry["output_tokens"] += int(event.get("output_tokens") or 0)
    usage["models"] = models
    usage["source"] = "events"
    return usage
