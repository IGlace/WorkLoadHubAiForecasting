"""What a narration cost, read from the session metrics or from the usage events."""

from types import SimpleNamespace

from hypothesis import given
from hypothesis import strategies as st

from whf.ai.usage import empty_usage, usage_from_events, usage_from_metrics

KEYS = {
    "input_tokens",
    "output_tokens",
    "cache_read_tokens",
    "reasoning_tokens",
    "requests",
    "premium_requests",
    "ai_credits",
    "usd",
    "api_seconds",
    "models",
    "source",
}


def _model_metric(count: int, cost: float, **usage) -> SimpleNamespace:
    return SimpleNamespace(
        requests=SimpleNamespace(count=count, cost=cost),
        usage=SimpleNamespace(**{"cache_read_tokens": 0, "cache_write_tokens": 0, "reasoning_tokens": 0, **usage}),
    )


def _metrics(**overrides) -> SimpleNamespace:
    """A stand-in for the SDK's `UsageGetMetricsResult` (two models, credits and money)."""
    defaults = dict(
        model_metrics={
            "gpt-5": _model_metric(3, 1.5, input_tokens=1000, output_tokens=200, cache_read_tokens=800),
            "gpt-5-mini": _model_metric(1, 0.0, input_tokens=250, output_tokens=50, reasoning_tokens=None),
        },
        total_user_requests=4,
        total_premium_request_cost=1.5,
        total_api_duration_ms=12500,
        total_nano_aiu=3.2e9,
    )
    return SimpleNamespace(**{**defaults, **overrides})


def test_empty_usage_has_every_key_and_no_source() -> None:
    usage = empty_usage()
    assert set(usage) == KEYS
    assert usage["source"] == "none"
    assert usage["models"] == {}
    assert all(usage[key] is None for key in KEYS - {"models", "source"})


def test_metrics_carry_the_credits_the_money_and_the_tokens() -> None:
    usage = usage_from_metrics(_metrics())
    assert set(usage) == KEYS
    assert usage["source"] == "metrics"
    assert usage["input_tokens"] == 1250 and usage["output_tokens"] == 250
    assert usage["cache_read_tokens"] == 800
    assert usage["reasoning_tokens"] == 0  # a model that reports None reasoning counts as zero
    assert usage["requests"] == 4
    assert usage["premium_requests"] == 1.5
    assert usage["ai_credits"] == 3.2  # nano-AI units are credits × 1e9
    assert usage["usd"] == 0.032  # one AI credit is one cent
    assert usage["api_seconds"] == 12.5
    assert usage["models"] == {
        "gpt-5": {"requests": 3, "input_tokens": 1000, "output_tokens": 200},
        "gpt-5-mini": {"requests": 1, "input_tokens": 250, "output_tokens": 50},
    }


def test_metrics_without_credits_report_no_money() -> None:
    usage = usage_from_metrics(_metrics(total_nano_aiu=None))
    assert usage["ai_credits"] is None and usage["usd"] is None
    assert usage["premium_requests"] == 1.5 and usage["source"] == "metrics"


def test_events_sum_the_tokens_and_count_the_requests() -> None:
    usage = usage_from_events(
        [
            {"input_tokens": 100, "output_tokens": 50, "cache_read_tokens": 10, "model": "gpt-5"},
            {"input_tokens": 30, "output_tokens": 5, "cache_read_tokens": 0, "model": "gpt-5"},
        ]
    )
    assert set(usage) == KEYS
    assert usage["source"] == "events"
    assert usage["input_tokens"] == 130 and usage["output_tokens"] == 55
    assert usage["cache_read_tokens"] == 10
    assert usage["reasoning_tokens"] is None  # no event carried it: unknown, not zero
    assert usage["requests"] == 2
    assert usage["models"] == {"gpt-5": {"requests": 2, "input_tokens": 130, "output_tokens": 55}}
    # Events say nothing about what the account was billed.
    assert usage["premium_requests"] is None and usage["ai_credits"] is None
    assert usage["usd"] is None and usage["api_seconds"] is None


def test_events_with_nothing_at_all_are_a_zero_request_narration() -> None:
    usage = usage_from_events([])
    assert usage["source"] == "events" and usage["requests"] == 0
    assert usage["input_tokens"] is None and usage["models"] == {}


@given(
    st.lists(
        st.fixed_dictionaries(
            {
                "input_tokens": st.integers(min_value=0, max_value=10**6),
                "output_tokens": st.integers(min_value=0, max_value=10**6),
                "cache_read_tokens": st.integers(min_value=0, max_value=10**6),
                "reasoning_tokens": st.integers(min_value=0, max_value=10**6),
            }
        ),
        max_size=12,
    )
)
def test_events_total_exactly_what_the_events_carried(events: list[dict]) -> None:
    usage = usage_from_events(events)
    assert usage["requests"] == len(events)
    for field in ("input_tokens", "output_tokens", "cache_read_tokens", "reasoning_tokens"):
        expected = sum(event[field] for event in events) if events else None
        assert usage[field] == expected
