package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One row of `personal_leaves`. {@code absenceHours} is the total over the whole leave as the application
 * records it, or null when it did not; {@code beginTime} and {@code endTime} mark a leave that starts or ends
 * on a partial day (design 2026-09-17, section 2).
 */
public record LeaveRow(UUID employeeId, LocalDate start, LocalDate end, LocalTime beginTime, LocalTime endTime,
		Double absenceHours, String status, String leaveType) {
}
