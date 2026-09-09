package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;

/** One row of `holidays`; `confirmed` is `status = 'CONFIRMED'`. */
public record HolidayRow(LocalDate start, LocalDate end, boolean confirmed, boolean active, String title) {
}
