package com.workloadhub.forecast.eval;

import java.time.LocalDate;
import java.util.UUID;

/** One member-window demand forecast replayed from one origin, against what was actually logged on the window's days. */
public record DemandRow(String model, LocalDate origin, UUID teamId, UUID memberId, int windowIndex, LocalDate windowStart, LocalDate windowEnd,
        double forecast, double truth, double capacity, double openHours, double newHours, double plannedHours) {
}
