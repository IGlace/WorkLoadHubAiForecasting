package com.workloadhub.forecast.capacity;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.AbsenceRow;
import com.workloadhub.forecast.data.rows.CapacityRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Hours a member can work in a week (design section 5): the application's own row first, then the rule. */
public final class CapacityRule {

    public static final int WORKING_DAYS_PER_WEEK = 5;
    public static final double FULL_DAY_HOURS = 8.0;

    private final double defaultWeeklyHours;

    public CapacityRule(double defaultWeeklyHours) {
        this.defaultWeeklyHours = defaultWeeklyHours;
    }

    static Optional<CapacityRow> rowFor(UUID member, LocalDate monday, ForecastData data) {
        return data.capacity().stream().filter(c -> c.userId().equals(member) && c.weekStart().equals(monday)).findFirst();
    }

    static Optional<CapacityRow> latestRowBefore(UUID member, LocalDate monday, ForecastData data) {
        return data.capacity().stream()
                .filter(c -> c.userId().equals(member) && !c.weekStart().isAfter(monday))
                .max((a, b) -> a.weekStart().compareTo(b.weekStart()));
    }

    public double absenceHours(UUID member, LocalDate monday, ForecastData data, WorkingCalendar cal) {
        Optional<CapacityRow> row = rowFor(member, monday, data);
        if (row.isPresent()) {
            return round2(row.get().absence());
        }
        LocalDate end = monday.plusDays(6);
        double sum = 0;
        for (AbsenceRow a : data.absences()) {
            if (a.userId().equals(member) && !a.day().isBefore(monday) && !a.day().isAfter(end) && cal.isWorkingDay(a.day())) {
                sum += a.hours();
            }
        }
        return round2(sum);
    }

    public double capacity(MemberRow member, LocalDate monday, ForecastData data, WorkingCalendar cal) {
        Optional<CapacityRow> row = rowFor(member.id(), monday, data);
        if (row.isPresent()) {
            return round2(row.get().available());
        }
        double base = latestRowBefore(member.id(), monday, data).map(CapacityRow::base).orElse(defaultWeeklyHours);
        double hours = base * cal.workingDaysInWeek(monday) / WORKING_DAYS_PER_WEEK - absenceHours(member.id(), monday, data, cal);
        return round2(Math.max(0.0, hours));
    }

    /** Days a member is away for the whole day: absences of at least a full day's hours. */
    public static Set<LocalDate> offDays(UUID member, ForecastData data) {
        Set<LocalDate> out = new TreeSet<>();
        for (AbsenceRow a : data.absences()) {
            if (a.userId().equals(member) && a.hours() >= FULL_DAY_HOURS) {
                out.add(a.day());
            }
        }
        return out;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
