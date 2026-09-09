package com.workloadhub.forecast.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Spreads hours evenly over working days and sums them per Monday week. */
public final class HourPlacement {

    private HourPlacement() {
    }

    public static SortedMap<LocalDate, Double> placeHours(double hours, LocalDate start, LocalDate end, WorkingCalendar cal, Set<LocalDate> off) {
        LocalDate last = end.isBefore(start) ? start : end;
        List<LocalDate> days = cal.workingDays(start, last, off);
        SortedMap<LocalDate, Double> out = new TreeMap<>();
        if (days.isEmpty()) {
            out.put(Weeks.mondayOf(start), hours);
            return out;
        }
        double perDay = hours / days.size();
        for (LocalDate d : days) {
            out.merge(Weeks.mondayOf(d), perDay, Double::sum);
        }
        return out;
    }
}
