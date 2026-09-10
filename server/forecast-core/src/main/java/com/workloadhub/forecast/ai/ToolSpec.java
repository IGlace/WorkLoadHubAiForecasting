package com.workloadhub.forecast.ai;

import java.util.function.Function;

/**
 * One read-only tool over a run's facts. A member-scoped tool takes {@code member_id} (a string) and the handler
 * receives it; a plain tool's handler receives null. The result is a JSON-shaped map or list.
 */
public record ToolSpec(String name, String description, boolean memberScoped, Function<String, Object> handler) {
}
