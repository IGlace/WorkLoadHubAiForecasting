package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.util.UUID;

/** One row of `time_logs`. */
public record TimeLogRow(UUID taskId, UUID userId, LocalDate day, double hours) {
}
