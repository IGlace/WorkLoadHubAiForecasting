package com.workloadhub.forecast.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;

/** Monday-based week arithmetic. */
public final class Weeks {

    private Weeks() {
    }

    public static LocalDate mondayOf(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }

    public static LocalDate lastCompleteWeek(LocalDate asOf) {
        return mondayOf(asOf).minusWeeks(1);
    }

    public static List<LocalDate> between(LocalDate first, LocalDate last) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate w = mondayOf(first); !w.isAfter(mondayOf(last)); w = w.plusWeeks(1)) {
            out.add(w);
        }
        return out;
    }

    public static long weeksBetween(LocalDate a, LocalDate b) {
        return ChronoUnit.WEEKS.between(mondayOf(a), mondayOf(b));
    }

    public static int isoWeek(LocalDate d) {
        return d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}
