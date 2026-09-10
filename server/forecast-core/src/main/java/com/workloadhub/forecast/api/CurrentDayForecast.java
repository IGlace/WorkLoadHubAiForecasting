package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** The team's current forecast for one member and day: the latest run whose horizon covered the day before it arrived. */
public record CurrentDayForecast(UUID teamId, UUID userId, LocalDate day, UUID runId, double openHrs, double newHrs, double plannedHrs, double demandHrs,
        double capacityHrs, double overloadHrs, LocalDateTime forecastAt) {
}
