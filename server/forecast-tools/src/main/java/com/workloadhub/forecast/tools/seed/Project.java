package com.workloadhub.forecast.tools.seed;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A project and the window in which the generator creates its tasks. The window is not stored: the schema has
 * no project dates.
 *
 * <p>{@code teamId} is the project's own team — the ad-hoc group who work on it, which is what the
 * application's `teams` table means — and {@code deptCode} is the department whose people that group is drawn
 * from, or null for a project carried over from a real export whose owner has no department.
 */
public record Project(UUID id, String key, String name, UUID teamId, UUID ownerId, String status,
        String deptCode, LocalDate windowStart, LocalDate windowEnd) {

    public boolean activeOn(LocalDate day) {
        return status.equals("ACTIVE") && !day.isBefore(windowStart) && day.isBefore(windowEnd);
    }
}
