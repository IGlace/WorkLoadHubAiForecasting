package com.workloadhub.forecast.ai;

import java.util.Map;

/** The session's billed usage as the SDK's usage RPC reports it, reduced to what the module keeps. */
record UsageMetrics(Double totalNanoAiu, Long totalUserRequests, Double totalPremiumRequestCost, Long totalApiDurationMs,
        Map<String, ModelMetric> models) {

    record ModelMetric(long requests, long inputTokens, long outputTokens, long cacheReadTokens, Long reasoningTokens) {
    }
}
