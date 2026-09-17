package com.workloadhub.forecast.capacity;

import com.workloadhub.forecast.Numbers;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import java.time.LocalDate;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Hours a member can work in a week (design 2026-09-17, section 3): the default week, minus the member's leaves. */
public final class CapacityRule {

    public static final int WORKING_DAYS_PER_WEEK = 5;

    private final double defaultWeeklyHours;

    /**
     * One index per {@link ForecastData}, keyed by instance identity: the leaves are expanded once per data set
     * here instead of once per call, which FeatureBuilder makes on the order of member-weeks × horizons.
     */
    private final Map<ForecastData, Map<UUID, NavigableMap<LocalDate, Double>>> indexes = new IdentityHashMap<>();

    public CapacityRule(double defaultWeeklyHours) {
        this.defaultWeeklyHours = defaultWeeklyHours;
    }

    /** A working day's hours before any leave: the default week over five days (design 2026-09-17, section 3). */
    double fullDay() {
        return defaultWeeklyHours / WORKING_DAYS_PER_WEEK;
    }

    private Map<UUID, NavigableMap<LocalDate, Double>> indexFor(ForecastData data) {
        return indexes.computeIfAbsent(data, d -> LeaveDays.expand(d.leaves(), WorkingCalendar.fromHolidays(d.holidays()), fullDay()));
    }

    /** The member's leave hours on the week's working days. */
    public double absenceHours(UUID member, LocalDate monday, ForecastData data, WorkingCalendar cal) {
        NavigableMap<LocalDate, Double> byDay = indexFor(data).get(member);
        if (byDay == null || byDay.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (Map.Entry<LocalDate, Double> e : byDay.subMap(monday, true, monday.plusDays(6), true).entrySet()) {
            if (cal.isWorkingDay(e.getKey())) {
                sum += Math.min(fullDay(), e.getValue());
            }
        }
        return Numbers.round2(sum);
    }

    /** The default week over the week's working days, minus the member's leave hours in it, never below zero. */
    public double capacity(MemberRow member, LocalDate monday, ForecastData data, WorkingCalendar cal) {
        double hours = defaultWeeklyHours * cal.workingDaysInWeek(monday) / WORKING_DAYS_PER_WEEK - absenceHours(member.id(), monday, data, cal);
        return Numbers.round2(Math.max(0.0, hours));
    }

    /** Nothing on a weekend or holiday; else the default over five days minus that day's leave hours, never below zero. */
    public double dayCapacity(MemberRow member, LocalDate day, ForecastData data, WorkingCalendar cal) {
        if (!cal.isWorkingDay(day)) {
            return 0.0;
        }
        return Numbers.round2(Math.max(0.0, fullDay() - dayAbsenceHours(member.id(), day, data)));
    }

    /** The member's leave hours on that day, capped at a full day. */
    public double dayAbsenceHours(UUID member, LocalDate day, ForecastData data) {
        NavigableMap<LocalDate, Double> byDay = indexFor(data).get(member);
        return byDay == null ? 0.0 : Numbers.round2(Math.min(fullDay(), byDay.getOrDefault(day, 0.0)));
    }

    /**
     * The days a member is fully absent: their leave hours meet a full day. Its caller is the weekday split in
     * {@code ForecastRunner}: predicted hours must not land on a day the member is not there (ruling 18.4 of the
     * 2026-09-13 design). Judged against the full day, never against {@link #dayCapacity}, which has already
     * taken the leave off.
     */
    public Set<LocalDate> offDays(UUID member, ForecastData data, WorkingCalendar cal) {
        Set<LocalDate> out = new TreeSet<>();
        NavigableMap<LocalDate, Double> byDay = indexFor(data).get(member);
        if (byDay == null) {
            return out;
        }
        for (Map.Entry<LocalDate, Double> e : byDay.entrySet()) {
            if (cal.isWorkingDay(e.getKey()) && e.getValue() >= fullDay() - 1e-9) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
