package com.workloadhub.forecast.features;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Arrivals per member and week (all estimated hours, the fresh part, and the counts), and the target: logged hours. */
public final class WeeklySeries {

    public record Cell(double estHours, double freshHours, int nTasks) {
        public static final Cell ZERO = new Cell(0.0, 0.0, 0);

        Cell plus(TaskFacts f) {
            return new Cell(estHours + f.estimate(), freshHours + (f.fresh() ? f.estimate() : 0.0), nTasks + 1);
        }
    }

    private final List<LocalDate> weeks;
    private final Map<MemberWeek, Cell> cells;
    private final Map<MemberWeek, Double> logged;

    private WeeklySeries(List<MemberRow> members, List<LocalDate> weeks, Map<MemberWeek, Cell> cells, Map<MemberWeek, Double> logged) {
        this.weeks = List.copyOf(weeks);
        this.cells = Map.copyOf(cells);
        this.logged = Map.copyOf(logged);
    }

    public static WeeklySeries build(Lifecycle lc, ForecastData data, List<MemberRow> members, List<LocalDate> weeks) {
        Set<UUID> ids = members.stream().map(MemberRow::id).collect(Collectors.toSet());
        Set<LocalDate> inRange = Set.copyOf(weeks);
        Map<MemberWeek, Cell> cells = new HashMap<>();
        for (TaskFacts f : lc.all()) {
            if (!f.isAssigned() || !ids.contains(f.assignee())) {
                continue;
            }
            LocalDate week = f.assignedWeek();
            if (!inRange.contains(week)) {
                continue;
            }
            cells.merge(new MemberWeek(f.assignee(), week), Cell.ZERO.plus(f), (a, b) -> a.plus(f));
        }
        // No hour is ever logged on a weekend, public holiday or absence day (ruling 18.4), so no day filter here.
        Map<MemberWeek, Double> logged = new HashMap<>();
        for (TimeLogRow l : data.timeLogs()) {
            if (!ids.contains(l.userId())) {
                continue;
            }
            LocalDate week = Weeks.mondayOf(l.day());
            if (!inRange.contains(week)) {
                continue;
            }
            logged.merge(new MemberWeek(l.userId(), week), l.hours(), Double::sum);
        }
        // Rounded to six decimals, the same rounding as eval.Truth.realisedHoursByDay.
        logged.replaceAll((k, v) -> Math.round(v * 1e6) / 1e6);
        return new WeeklySeries(members, weeks, cells, logged);
    }

    public Cell cell(UUID member, LocalDate week) {
        return cells.getOrDefault(new MemberWeek(member, week), Cell.ZERO);
    }

    public List<LocalDate> weeks() {
        return weeks;
    }

    public double[] fresh(UUID member) {
        return weeks.stream().mapToDouble(w -> cell(member, w).freshHours()).toArray();
    }

    /** Hours this member logged in each week, 0.0 where they logged none: the forecast's target. */
    public double[] logged(UUID member) {
        return weeks.stream().mapToDouble(w -> logged.getOrDefault(new MemberWeek(member, w), 0.0)).toArray();
    }
}
