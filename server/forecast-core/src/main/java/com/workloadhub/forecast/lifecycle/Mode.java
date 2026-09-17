package com.workloadhub.forecast.lifecycle;

import java.util.Locale;

/** How a task reached its assignee, from the task_history row that assigned it (design 2026-09-17, section 6). */
public enum Mode {
    SELF_PICKED, ASSIGNED, UNKNOWN;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
