package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One member in one forecast window: the hours they are predicted to log, the band around it, the capacity it
 * is measured against, and the two pressures a forecast of logged hours cannot show on its own (design
 * 2026-09-13, section 8): {@code backlogExcessHrs}, the open work still left once the windows so far are
 * forecast, cumulative and non-increasing across a run's windows; {@code dueExcessHrs}, the hours due inside
 * this window beyond what it holds, measured against this window's own capacity.
 */
public record MemberWindowForecast(UUID userId, int windowIndex, LocalDate windowStart, LocalDate windowEnd, double demandHrs, double lowHrs,
        double highHrs, double capacityHrs, double overloadHrs, int workingDays, double absenceHrs, double backlogExcessHrs, double dueExcessHrs) {
}
