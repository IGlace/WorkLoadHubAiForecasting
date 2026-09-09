package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.util.UUID;

/** One row of `absences`. */
public record AbsenceRow(UUID userId, LocalDate day, double hours) {
}
