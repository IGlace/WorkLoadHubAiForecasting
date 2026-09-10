package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.UUID;

/** One member in one forecast window (five weekdays): the hours, the band, the capacity and why it is what it is. */
public record MemberWindowForecast(UUID userId, int windowIndex, LocalDate windowStart, LocalDate windowEnd, double openHrs, double newHrs,
        double plannedHrs, double demandHrs, double lowHrs, double highHrs, double capacityHrs, double overloadHrs, int workingDays, double absenceHrs) {
}
