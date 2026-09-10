package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.UUID;

/** One member on one weekday of the horizon. */
public record MemberDayForecast(UUID userId, LocalDate day, int windowIndex, double openHrs, double newHrs, double plannedHrs, double demandHrs,
        double capacityHrs, double overloadHrs, boolean workingDay) {
}
