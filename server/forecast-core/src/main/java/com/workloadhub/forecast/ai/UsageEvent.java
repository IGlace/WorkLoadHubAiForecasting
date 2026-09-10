package com.workloadhub.forecast.ai;

/** One streamed usage event: tokens per model call, nothing about money; a field an event did not carry is null. */
public record UsageEvent(String model, Long inputTokens, Long outputTokens, Long cacheReadTokens, Long reasoningTokens) {
}
