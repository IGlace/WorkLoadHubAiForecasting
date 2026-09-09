package com.workloadhub.forecast.lifecycle;

import java.util.Locale;
import java.util.Map;

/** The four type families of the schema mapping, section 3. */
public enum Family {
    DELIVERY, DEFECT, CONTAINER, SUPPORT;

    private static final Map<String, Family> BY_TYPE = Map.ofEntries(
            Map.entry("story", DELIVERY), Map.entry("new feature", DELIVERY), Map.entry("task", DELIVERY),
            Map.entry("improvement", DELIVERY), Map.entry("change request", DELIVERY),
            Map.entry("bug", DEFECT), Map.entry("incident", DEFECT),
            Map.entry("epic", CONTAINER),
            Map.entry("spike", SUPPORT), Map.entry("test", SUPPORT), Map.entry("risk", SUPPORT));

    /** Null for Sub-task (the caller takes the parent's family); DELIVERY for unknown names. */
    public static Family ofType(String typeName) {
        if (typeName == null) {
            return DELIVERY;
        }
        String key = typeName.trim().toLowerCase(Locale.ROOT);
        if (key.equals("sub-task") || key.equals("subtask")) {
            return null;
        }
        return BY_TYPE.getOrDefault(key, DELIVERY);
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
