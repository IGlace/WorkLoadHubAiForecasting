package com.workloadhub.forecast.seed;

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
        for (String s : STATUSES) {
            if (!statuses.containsKey(s)) {
                throw new IllegalArgumentException("task_statuses lacks '" + s + "'");
            }
        }
        for (String t : TYPES) {
            if (!types.containsKey(t)) {
                throw new IllegalArgumentException("task_types lacks '" + t + "'");
            }
        }
        return new Reference(statuses, types);
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
