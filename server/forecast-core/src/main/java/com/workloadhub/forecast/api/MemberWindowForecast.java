package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.UUID;

/** One member in one forecast window: the hours they are predicted to log, the band around it, and the capacity it is measured against. */
public record MemberWindowForecast(UUID userId, int windowIndex, LocalDate windowStart, LocalDate windowEnd, double demandHrs, double lowHrs,
        double highHrs, double capacityHrs, double overloadHrs, int workingDays, double absenceHrs) {
}
