package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.util.UUID;

/** A project and the window in which the generator creates its tasks. The window is not stored: the schema has no project dates. */
public record Project(UUID id, String key, String name, UUID teamId, UUID ownerId, String status,
        LocalDate windowStart, LocalDate windowEnd, boolean existing, WorkFamily family) {

    public boolean activeOn(LocalDate day) {
        return status.equals("ACTIVE") && !day.isBefore(windowStart) && day.isBefore(windowEnd);
    }
}
