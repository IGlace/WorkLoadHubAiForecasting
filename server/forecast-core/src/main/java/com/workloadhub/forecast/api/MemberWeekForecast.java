package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.UUID;

public record MemberWeekForecast(UUID userId, LocalDate weekStart, double openHrs, double newHrs, double plannedHrs, double demandHrs, double lowHrs, double highHrs, double capacityHrs, double overloadHrs, int workingDays, double absenceHrs) {
}
