package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.UUID;

/** One member on one weekday that has passed: what was forecast for it before it arrived, and what was logged. */
public record AccuracyRow(UUID userId, LocalDate day, UUID runId, int lead, double forecastHrs, double loggedHrs, double capacityHrs,
        boolean forecastOverload, boolean actualOverload) {
}
