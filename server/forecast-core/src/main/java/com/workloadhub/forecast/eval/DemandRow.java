package com.workloadhub.forecast.eval;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One member-window demand forecast replayed from one origin, against what was actually logged on the
 * window's days. {@code backlogExcessHrs} and {@code dueExcessHrs} are the pressure figures of that same
 * replayed window (design 2026-09-13, section 8), carried through so {@code demand.csv} can be read against
 * the pressure the run found; {@code openHours}, {@code newHours} and {@code plannedHours} went with the
 * open/new/planned split task 5 retired.
 */
public record DemandRow(String model, LocalDate origin, UUID teamId, UUID memberId, int windowIndex, LocalDate windowStart, LocalDate windowEnd,
        double forecast, double truth, double capacity, double backlogExcessHrs, double dueExcessHrs) {
}
