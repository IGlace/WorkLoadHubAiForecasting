package com.workloadhub.forecast.tools.seed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ids of the reference rows the tasks point at, by name. */
public record Reference(Map<String, UUID> statusIds, Map<String, UUID> typeIds) {

    public static final List<String> STATUSES = List.of("To Do", "In Progress", "In Review", "Blocked", "Done");
    public static final List<String> TYPES = List.of("Story", "Task", "New Feature", "Improvement", "Change Request",
            "Bug", "Incident", "Spike", "Test", "Risk", "Epic", "Sub-task");

    public static Reference from(List<LinkedHashMap<String, Object>> statusRows, List<LinkedHashMap<String, Object>> typeRows) {
        Map<String, UUID> statuses = new HashMap<>();
        for (LinkedHashMap<String, Object> r : statusRows) {
            statuses.put((String) r.get("name"), UUID.fromString((String) r.get("id")));
        }
        Map<String, UUID> types = new HashMap<>();
        for (LinkedHashMap<String, Object> r : typeRows) {
            types.put((String) r.get("name"), UUID.fromString((String) r.get("id")));
        }
        List<String> missingStatuses = STATUSES.stream().filter(s -> !statuses.containsKey(s)).toList();
        List<String> missingTypes = TYPES.stream().filter(t -> !types.containsKey(t)).toList();
        if (!missingStatuses.isEmpty() || !missingTypes.isEmpty()) {
            throw new IllegalArgumentException(describe(missingStatuses, missingTypes));
        }
        return new Reference(statuses, types);
    }

    private static String describe(List<String> missingStatuses, List<String> missingTypes) {
        List<String> parts = new ArrayList<>();
        if (!missingStatuses.isEmpty()) {
            parts.add("task_statuses lacks " + missingStatuses.stream().map(s -> "'" + s + "'").collect(java.util.stream.Collectors.joining(", ")));
        }
        if (!missingTypes.isEmpty()) {
            parts.add("task_types lacks " + missingTypes.stream().map(t -> "'" + t + "'").collect(java.util.stream.Collectors.joining(", ")));
        }
        return String.join("; ", parts);
    }

    /** What the rows lack, in the words of {@link #from}'s exception, or the empty string when they cover everything. */
    public static String missing(List<LinkedHashMap<String, Object>> statusRows, List<LinkedHashMap<String, Object>> typeRows) {
        try {
            from(statusRows, typeRows);
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** True when the rows name every status and type the generator writes. */
    public static boolean covers(List<LinkedHashMap<String, Object>> statusRows, List<LinkedHashMap<String, Object>> typeRows) {
        try {
            from(statusRows, typeRows);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public UUID status(String name) {
        return statusIds.get(name);
    }

    public UUID type(String name) {
        return typeIds.get(name);
    }
}
