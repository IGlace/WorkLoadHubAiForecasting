package com.workloadhub.forecast.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Spreads hours evenly over working days, per day or summed per Monday week. */
public final class HourPlacement {

    private HourPlacement() {
    }

    /** Hours per working day between start and end (evenly); all of them on {@code start} when no day in the range works. */
    public static SortedMap<LocalDate, Double> placeHoursByDay(double hours, LocalDate start, LocalDate end, WorkingCalendar cal, Set<LocalDate> off) {
        LocalDate last = end.isBefore(start) ? start : end;
        List<LocalDate> days = cal.workingDays(start, last, off);
        SortedMap<LocalDate, Double> out = new TreeMap<>();
        if (days.isEmpty()) {
            out.put(start, hours);
            return out;
        }
        double perDay = hours / days.size();
        for (LocalDate d : days) {
            out.put(d, perDay);
        }
        return out;
    }

    /** The day placement summed per Monday week. */
    public static SortedMap<LocalDate, Double> placeHours(double hours, LocalDate start, LocalDate end, WorkingCalendar cal, Set<LocalDate> off) {
        SortedMap<LocalDate, Double> out = new TreeMap<>();
        placeHoursByDay(hours, start, end, cal, off).forEach((d, h) -> out.merge(Weeks.mondayOf(d), h, Double::sum));
        return out;
    }
}
