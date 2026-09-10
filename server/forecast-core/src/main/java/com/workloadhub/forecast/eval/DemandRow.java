package com.workloadhub.forecast.eval;

import java.time.LocalDate;
import java.util.UUID;

/** One member-week demand forecast replayed from one origin, against what was actually logged. */
public record DemandRow(String model, LocalDate origin, UUID teamId, UUID memberId, LocalDate weekStart, double forecast, double truth,
        double capacity, double openHours, double newHours, double plannedHours) {
}
