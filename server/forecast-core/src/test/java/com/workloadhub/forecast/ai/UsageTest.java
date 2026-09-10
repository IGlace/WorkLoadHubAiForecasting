package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class UsageTest {

    static final Set<String> KEYS = Set.of("input_tokens", "output_tokens", "cache_read_tokens", "reasoning_tokens", "requests", "premium_requests",
            "ai_credits", "usd", "api_seconds", "models", "source");

    static UsageMetrics metrics(Double nanoAiu) {
        Map<String, UsageMetrics.ModelMetric> models = new LinkedHashMap<>();
        models.put("gpt-5", new UsageMetrics.ModelMetric(3, 1000, 200, 800, 0L));
        models.put("gpt-5-mini", new UsageMetrics.ModelMetric(1, 250, 50, 0, null));
        return new UsageMetrics(nanoAiu, 4L, 1.5, 12500L, models);
    }

    @Test
    void emptyHasEveryKeyAndNoSource() {
        Map<String, Object> u = Usage.empty();
        assertEquals(KEYS, u.keySet());
        assertEquals("none", u.get("source"));
        assertEquals(Map.of(), u.get("models"));
        for (String k : KEYS) {
            if (!k.equals("models") && !k.equals("source")) {
                assertNull(u.get(k), k);
            }
        }
    }

    @Test
    void metricsCarryTheCreditsTheMoneyAndTheTokens() {
        Map<String, Object> u = Usage.fromMetrics(metrics(3.2e9));
        assertEquals(KEYS, u.keySet());
        assertEquals("metrics", u.get("source"));
        assertEquals(1250L, u.get("input_tokens"));
        assertEquals(250L, u.get("output_tokens"));
        assertEquals(800L, u.get("cache_read_tokens"));
        assertEquals(0L, u.get("reasoning_tokens"), "a model that reports no reasoning tokens used zero");
        assertEquals(4L, u.get("requests"));
        assertEquals(1.5, u.get("premium_requests"));
        assertEquals(3.2, (Double) u.get("ai_credits"), 1e-9);
        assertEquals(0.032, (Double) u.get("usd"), 1e-9);
        assertEquals(12.5, u.get("api_seconds"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> models = (Map<String, Map<String, Object>>) u.get("models");
        assertEquals(Map.of("requests", 3L, "input_tokens", 1000L, "output_tokens", 200L), models.get("gpt-5"));
        assertEquals(Map.of("requests", 1L, "input_tokens", 250L, "output_tokens", 50L), models.get("gpt-5-mini"));
        assertTrue(Usage.toJson(u).contains("\"source\""));
    }

    @Test
    void metricsWithoutCreditsReportNoMoney() {
        Map<String, Object> u = Usage.fromMetrics(metrics(null));
        assertNull(u.get("ai_credits"));
        assertNull(u.get("usd"));
        assertEquals(1.5, u.get("premium_requests"));
        assertEquals("metrics", u.get("source"));
    }

    @Test
    void eventsSumTheTokensAndCountTheRequests() {
        Map<String, Object> u = Usage.fromEvents(List.of(new UsageEvent("gpt-5", 100L, 50L, 10L, null), new UsageEvent("gpt-5", 30L, 5L, 0L, null)));
        assertEquals(KEYS, u.keySet());
        assertEquals("events", u.get("source"));
        assertEquals(130L, u.get("input_tokens"));
        assertEquals(55L, u.get("output_tokens"));
        assertEquals(10L, u.get("cache_read_tokens"));
        assertNull(u.get("reasoning_tokens"), "no event carried it: unknown, not zero");
        assertEquals(2L, u.get("requests"));
        assertEquals(Map.of("gpt-5", Map.of("requests", 2L, "input_tokens", 130L, "output_tokens", 55L)), u.get("models"));
        assertNull(u.get("premium_requests"));
        assertNull(u.get("ai_credits"));
        assertNull(u.get("usd"));
        assertNull(u.get("api_seconds"));
    }

    @Test
    void noEventsIsAZeroRequestNarration() {
        Map<String, Object> u = Usage.fromEvents(List.of());
        assertEquals("events", u.get("source"));
        assertEquals(0L, u.get("requests"));
        assertNull(u.get("input_tokens"));
        assertEquals(Map.of(), u.get("models"));
    }

    @Provide
    Arbitrary<List<UsageEvent>> events() {
        Arbitrary<Long> count = Arbitraries.longs().between(0, 1_000_000).injectNull(0.3);
        return Combinators.combine(count, count, count, count).as((in, out, cache, reasoning) -> new UsageEvent(null, in, out, cache, reasoning))
                .list().ofMaxSize(12);
    }

    @Property
    void eventsTotalExactlyWhatTheEventsCarried(@ForAll("events") List<UsageEvent> events) {
        Map<String, Object> u = Usage.fromEvents(events);
        assertEquals((long) events.size(), u.get("requests"));
        check(u, "input_tokens", events.stream().map(UsageEvent::inputTokens).toList());
        check(u, "output_tokens", events.stream().map(UsageEvent::outputTokens).toList());
        check(u, "cache_read_tokens", events.stream().map(UsageEvent::cacheReadTokens).toList());
        check(u, "reasoning_tokens", events.stream().map(UsageEvent::reasoningTokens).toList());
    }

    static void check(Map<String, Object> u, String key, List<Long> reported) {
        List<Long> present = new ArrayList<>(reported.stream().filter(v -> v != null).toList());
        if (present.isEmpty()) {
            assertNull(u.get(key), key);
        } else {
            assertEquals(present.stream().mapToLong(Long::longValue).sum(), u.get(key), key);
        }
    }
}
