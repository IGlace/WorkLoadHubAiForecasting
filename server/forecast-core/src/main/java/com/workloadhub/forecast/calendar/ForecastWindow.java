package com.workloadhub.forecast.calendar;

import java.time.LocalDate;
import java.util.List;

/** Five weekdays of the horizon: its index (1 or 2), its first and last weekday, and its weekdays in order (holidays included, weekends never). */
public record ForecastWindow(int index, LocalDate start, LocalDate end, List<LocalDate> weekdays) {

    public ForecastWindow {
        weekdays = List.copyOf(weekdays);
        if (weekdays.isEmpty() || !weekdays.get(0).equals(start) || !weekdays.get(weekdays.size() - 1).equals(end)) {
            throw new IllegalArgumentException("a window's start and end are its first and last weekday");
        }
    }

    public boolean contains(LocalDate day) {
        return weekdays.contains(day);
    }
}
