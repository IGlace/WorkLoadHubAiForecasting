package com.workloadhub.forecast.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** The rolling horizon: two windows of five weekdays starting the first weekday after the run day (design 2026-09-10, section 3). */
public final class Horizon {

    public static final int WINDOWS = 2;
    public static final int WEEKDAYS_PER_WINDOW = 5;

    private Horizon() {
    }

    /** The first weekday after {@code asOf}: a Friday, Saturday or Sunday run starts on the next Monday. */
    public static LocalDate firstDay(LocalDate asOf) {
        LocalDate d = asOf.plusDays(1);
        while (isWeekend(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    public static List<ForecastWindow> windows(LocalDate asOf) {
        List<ForecastWindow> out = new ArrayList<>(WINDOWS);
        LocalDate d = firstDay(asOf);
        for (int i = 1; i <= WINDOWS; i++) {
            List<LocalDate> days = new ArrayList<>(WEEKDAYS_PER_WINDOW);
            while (days.size() < WEEKDAYS_PER_WINDOW) {
                if (!isWeekend(d)) {
                    days.add(d);
                }
                d = d.plusDays(1);
            }
            out.add(new ForecastWindow(i, days.get(0), days.get(days.size() - 1), days));
        }
        return List.copyOf(out);
    }

    /** Every weekday of the horizon, window 1 then window 2. */
    public static List<LocalDate> days(List<ForecastWindow> windows) {
        List<LocalDate> out = new ArrayList<>();
        for (ForecastWindow w : windows) {
            out.addAll(w.weekdays());
        }
        return List.copyOf(out);
    }

    /** The horizons (weeks after {@code origin}) the windows touch, ascending and distinct. */
    public static int[] horizons(LocalDate origin, List<ForecastWindow> windows) {
        TreeSet<Integer> hs = new TreeSet<>();
        for (LocalDate d : days(windows)) {
            hs.add((int) Weeks.weeksBetween(origin, d));
        }
        return hs.stream().mapToInt(Integer::intValue).toArray();
    }

    static boolean isWeekend(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
