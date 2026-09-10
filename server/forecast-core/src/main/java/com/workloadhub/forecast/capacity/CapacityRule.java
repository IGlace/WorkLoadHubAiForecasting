package com.workloadhub.forecast.capacity;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.AbsenceRow;
import com.workloadhub.forecast.data.rows.CapacityRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Hours a member can work in a week (design section 5): the application's own row first, then the rule. */
public final class CapacityRule {

    public static final int WORKING_DAYS_PER_WEEK = 5;
    public static final double FULL_DAY_HOURS = 8.0;

    private final double defaultWeeklyHours;

    /**
     * One index per {@link ForecastData} this rule has seen, keyed by instance identity (not equals/hashCode,
     * which would force a full scan of the lists to hash or compare): {@code capacity()} and {@code absences()}
     * are scanned once per data set here instead of once per {@code capacity()}/{@code absenceHours()} call,
     * which FeatureBuilder makes on the order of member-weeks × horizons.
     */
    private final Map<ForecastData, Index> indexes = new IdentityHashMap<>();

    public CapacityRule(double defaultWeeklyHours) {
        this.defaultWeeklyHours = defaultWeeklyHours;
    }

    private record Index(Map<UUID, NavigableMap<LocalDate, CapacityRow>> capacityByMember,
            Map<UUID, NavigableMap<LocalDate, Double>> absenceHoursByMember) {

        static Index of(ForecastData data) {
            Map<UUID, NavigableMap<LocalDate, CapacityRow>> capacity = new HashMap<>();
            for (CapacityRow c : data.capacity()) {
                capacity.computeIfAbsent(c.userId(), k -> new TreeMap<>()).put(c.weekStart(), c);
            }
            Map<UUID, NavigableMap<LocalDate, Double>> absence = new HashMap<>();
            for (AbsenceRow a : data.absences()) {
                absence.computeIfAbsent(a.userId(), k -> new TreeMap<>()).merge(a.day(), a.hours(), Double::sum);
            }
            return new Index(capacity, absence);
        }
    }

    private Index indexFor(ForecastData data) {
        return indexes.computeIfAbsent(data, Index::of);
    }

    private Optional<CapacityRow> rowFor(UUID member, LocalDate monday, ForecastData data) {
        NavigableMap<LocalDate, CapacityRow> byWeek = indexFor(data).capacityByMember().get(member);
        return byWeek == null ? Optional.empty() : Optional.ofNullable(byWeek.get(monday));
    }

    private Optional<CapacityRow> latestRowBefore(UUID member, LocalDate monday, ForecastData data) {
        NavigableMap<LocalDate, CapacityRow> byWeek = indexFor(data).capacityByMember().get(member);
        if (byWeek == null) {
            return Optional.empty();
        }
        Map.Entry<LocalDate, CapacityRow> floor = byWeek.floorEntry(monday);
        return floor == null ? Optional.empty() : Optional.of(floor.getValue());
    }

    public double absenceHours(UUID member, LocalDate monday, ForecastData data, WorkingCalendar cal) {
        Optional<CapacityRow> row = rowFor(member, monday, data);
        if (row.isPresent()) {
            return round2(row.get().absence());
        }
        LocalDate end = monday.plusDays(6);
        NavigableMap<LocalDate, Double> byDay = indexFor(data).absenceHoursByMember().get(member);
        if (byDay == null || byDay.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (Map.Entry<LocalDate, Double> e : byDay.subMap(monday, true, end, true).entrySet()) {
            if (cal.isWorkingDay(e.getKey())) {
                sum += e.getValue();
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

    /**
     * Hours a member can work on one day (design 2026-09-10, section 5): nothing on a weekend or holiday; the
     * application's own week row spread evenly over that week's working days; else the latest base (or the
     * default) over five days minus that day's absence hours, never below zero.
     */
    public double dayCapacity(MemberRow member, LocalDate day, ForecastData data, WorkingCalendar cal) {
        if (!cal.isWorkingDay(day)) {
            return 0.0;
        }
        LocalDate monday = Weeks.mondayOf(day);
        Optional<CapacityRow> row = rowFor(member.id(), monday, data);
        if (row.isPresent()) {
            int working = cal.workingDaysInWeek(monday);
            return working == 0 ? 0.0 : round2(Math.max(0.0, row.get().available() / working));
        }
        double base = latestRowBefore(member.id(), monday, data).map(CapacityRow::base).orElse(defaultWeeklyHours);
        return round2(Math.max(0.0, base / WORKING_DAYS_PER_WEEK - dayAbsenceHours(member.id(), day, data)));
    }

    /** The member's absence hours recorded on that day. */
    public double dayAbsenceHours(UUID member, LocalDate day, ForecastData data) {
        NavigableMap<LocalDate, Double> byDay = indexFor(data).absenceHoursByMember().get(member);
        return byDay == null ? 0.0 : round2(byDay.getOrDefault(day, 0.0));
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
