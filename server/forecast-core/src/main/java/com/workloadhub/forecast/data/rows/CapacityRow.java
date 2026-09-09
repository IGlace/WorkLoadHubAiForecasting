package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.util.UUID;

/** One row of `user_capacity`. */
public record CapacityRow(UUID userId, LocalDate weekStart, double base, double absence, double available) {
}
