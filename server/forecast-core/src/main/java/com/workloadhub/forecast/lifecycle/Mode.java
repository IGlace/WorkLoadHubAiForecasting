package com.workloadhub.forecast.lifecycle;

import java.util.Locale;

/** How a task reached its assignee. */
public enum Mode {
    SELF_PICKED, PROJECT, MANUAL;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
