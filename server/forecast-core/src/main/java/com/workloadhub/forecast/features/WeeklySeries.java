package com.workloadhub.forecast.features;

import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Arrivals per member and week: all estimated hours, the fresh part, and the counts. */
public final class WeeklySeries {

    public record Cell(double estHours, double freshHours, int nTasks, int freshTasks) {
        public static final Cell ZERO = new Cell(0.0, 0.0, 0, 0);

        Cell plus(TaskFacts f) {
            boolean fresh = f.fresh();
            return new Cell(estHours + f.estimate(), freshHours + (fresh ? f.estimate() : 0.0), nTasks + 1, freshTasks + (fresh ? 1 : 0));
        }
    }

    private final List<MemberRow> members;
    private final List<LocalDate> weeks;
    private final Map<MemberWeek, Cell> cells;

    private WeeklySeries(List<MemberRow> members, List<LocalDate> weeks, Map<MemberWeek, Cell> cells) {
        this.members = List.copyOf(members);
        this.weeks = List.copyOf(weeks);
        this.cells = Map.copyOf(cells);
    }

    public static WeeklySeries build(Lifecycle lc, List<MemberRow> members, List<LocalDate> weeks) {
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
        return new WeeklySeries(members, weeks, cells);
    }

    public Cell cell(UUID member, LocalDate week) {
        return cells.getOrDefault(new MemberWeek(member, week), Cell.ZERO);
    }

    public List<LocalDate> weeks() {
        return weeks;
    }

    public List<MemberRow> members() {
        return members;
    }

    public double[] fresh(UUID member) {
        return weeks.stream().mapToDouble(w -> cell(member, w).freshHours()).toArray();
    }

    public double[] est(UUID member) {
        return weeks.stream().mapToDouble(w -> cell(member, w).estHours()).toArray();
    }
}
