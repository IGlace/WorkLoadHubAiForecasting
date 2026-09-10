package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.data.ExportFiles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What one narration cost, in one shape whatever the source. The session metrics are the billed truth (credits,
 * premium requests, API time); the streamed events carry tokens only. Unknown is null, never a plausible zero;
 * {@code models} is the one exception (an empty map). One AI credit is one US cent; the SDK reports nano-AI units.
 */
final class Usage {

    static final double NANO_PER_CREDIT = 1e9;
    static final double CREDITS_PER_USD = 100.0;

    private Usage() {
    }

    static Map<String, Object> empty() {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("input_tokens", null);
        u.put("output_tokens", null);
        u.put("cache_read_tokens", null);
        u.put("reasoning_tokens", null);
        u.put("requests", null);
        u.put("premium_requests", null);
        u.put("ai_credits", null);
        u.put("usd", null);
        u.put("api_seconds", null);
        u.put("models", new LinkedHashMap<String, Object>());
        u.put("source", "none");
        return u;
    }

    static Map<String, Object> fromMetrics(UsageMetrics m) {
        Map<String, Object> u = empty();
        long input = 0;
        long output = 0;
        long cache = 0;
        long reasoning = 0;
        Map<String, Object> models = new LinkedHashMap<>();
        for (Map.Entry<String, UsageMetrics.ModelMetric> e : m.models().entrySet()) {
            UsageMetrics.ModelMetric mm = e.getValue();
            input += mm.inputTokens();
            output += mm.outputTokens();
            cache += mm.cacheReadTokens();
            reasoning += mm.reasoningTokens() == null ? 0 : mm.reasoningTokens();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("requests", mm.requests());
            entry.put("input_tokens", mm.inputTokens());
            entry.put("output_tokens", mm.outputTokens());
            models.put(e.getKey(), entry);
        }
        Double credits = m.totalNanoAiu() == null ? null : m.totalNanoAiu() / NANO_PER_CREDIT;
        u.put("input_tokens", input);
        u.put("output_tokens", output);
        u.put("cache_read_tokens", cache);
        u.put("reasoning_tokens", reasoning);
        u.put("requests", m.totalUserRequests());
        u.put("premium_requests", m.totalPremiumRequestCost());
        u.put("ai_credits", credits);
        u.put("usd", credits == null ? null : credits / CREDITS_PER_USD);
        u.put("api_seconds", m.totalApiDurationMs() == null ? null : m.totalApiDurationMs() / 1000.0);
        u.put("models", models);
        u.put("source", "metrics");
        return u;
    }

    static Map<String, Object> fromEvents(List<UsageEvent> events) {
        Map<String, Object> u = empty();
        u.put("input_tokens", sum(events, UsageEvent::inputTokens));
        u.put("output_tokens", sum(events, UsageEvent::outputTokens));
        u.put("cache_read_tokens", sum(events, UsageEvent::cacheReadTokens));
        u.put("reasoning_tokens", sum(events, UsageEvent::reasoningTokens));
        u.put("requests", (long) events.size());
        Map<String, Object> models = new LinkedHashMap<>();
        for (UsageEvent e : events) {
            if (e.model() == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) models.computeIfAbsent(e.model(), k -> {
                Map<String, Object> fresh = new LinkedHashMap<>();
                fresh.put("requests", 0L);
                fresh.put("input_tokens", 0L);
                fresh.put("output_tokens", 0L);
                return fresh;
            });
            entry.put("requests", (Long) entry.get("requests") + 1);
            entry.put("input_tokens", (Long) entry.get("input_tokens") + (e.inputTokens() == null ? 0 : e.inputTokens()));
            entry.put("output_tokens", (Long) entry.get("output_tokens") + (e.outputTokens() == null ? 0 : e.outputTokens()));
        }
        u.put("models", models);
        u.put("source", "events");
        return u;
    }

    /** The sum of the events that carried the field, or null when none did. */
    private static Long sum(List<UsageEvent> events, Function<UsageEvent, Long> field) {
        Long total = null;
        for (UsageEvent e : events) {
            Long v = field.apply(e);
            if (v != null) {
                total = (total == null ? 0 : total) + v;
            }
        }
        return total;
    }

    static String toJson(Map<String, Object> usage) {
        return ExportFiles.mapper().writeValueAsString(usage);
    }
}
