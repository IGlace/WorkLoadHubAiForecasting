package com.workloadhub.forecast.ai;

import java.util.Map;

/** The session's billed usage as the SDK's usage RPC reports it, reduced to what the module keeps. */
public record UsageMetrics(Double totalNanoAiu, Long totalUserRequests, Double totalPremiumRequestCost, Long totalApiDurationMs,
        Map<String, ModelMetric> models) {

    public record ModelMetric(long requests, long inputTokens, long outputTokens, long cacheReadTokens, Long reasoningTokens) {
    }
}
