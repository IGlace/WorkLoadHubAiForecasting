package com.workloadhub.forecast.tools.seed;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A project and the window in which the generator creates its tasks. The window is not stored: the schema has
 * no project dates.
 *
 * <p>{@code teamId} is the project's own team — the ad-hoc group who work on it, which is what the
 * application's `teams` table means — and is null in real mode, which writes no team rows.
 *
 * <p>{@code deptCode} is the department whose people work on it, as {@link Directory#deptCode} spells one, so
 * <b>null means the department nobody named</b>, not "any department": a person with no department at all and
 * this project's people are the same people. A project carried over from a real export whose owner is outside
 * the directory belongs to no department of ours and is {@code openToAll} instead.
 */
public record Project(UUID id, String key, String name, UUID teamId, UUID ownerId, String status,
        String deptCode, boolean openToAll, LocalDate windowStart, LocalDate windowEnd) {

    public Project {
        // Directory.deptCode answers null for a blank department, and Department.code() spells the same thing
        // as "". Left unnormalised, "".equals(null) is false and the people with no department are matched to
        // nothing at all: they get no candidate project, so no task, no log and no history.
        deptCode = deptCode == null || deptCode.isBlank() ? null : deptCode;
    }

    public boolean activeOn(LocalDate day) {
        return status.equals("ACTIVE") && !day.isBefore(windowStart) && day.isBefore(windowEnd);
    }

    /** Whether {@code person}'s department is this project's. Both sides are already normalised. */
    public boolean belongsTo(String personDeptCode) {
        return Objects.equals(deptCode, personDeptCode == null || personDeptCode.isBlank() ? null : personDeptCode);
    }
}
