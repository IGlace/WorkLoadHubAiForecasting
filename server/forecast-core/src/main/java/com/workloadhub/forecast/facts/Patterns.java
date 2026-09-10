package com.workloadhub.forecast.facts;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.Mode;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** The Python `member_patterns` on the WorkloadHub lifecycle facts. */
public final class Patterns {

    public static final int WINDOW_WEEKS = 13;
    static final List<String> WEEKDAYS = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday");

    private Patterns() {
    }

    public static List<MemberPattern> table(List<MemberRow> members, Lifecycle lc, ForecastData data, LocalDate asOf) {
        return members.stream().sorted((a, b) -> a.id().toString().compareTo(b.id().toString()))
                .map(m -> of(m.id(), lc, data, asOf)).toList();
    }

    public static MemberPattern of(UUID member, Lifecycle lc, ForecastData data, LocalDate asOf) {
        LocalDate windowEnd = Weeks.mondayOf(asOf);
        LocalDate windowStart = windowEnd.minusWeeks(WINDOW_WEEKS);
        List<TaskFacts> mine = lc.assignedTo(member);
        List<TaskFacts> recent = mine.stream()
                .filter(f -> !f.assignedDay().isBefore(windowStart) && f.assignedDay().isBefore(windowEnd)).toList();
        double[] weekly = new double[WINDOW_WEEKS];
        double[] weekdayCounts = new double[5];
        int self = 0;
        int manual = 0;
        int project = 0;
        int withProject = 0;
        Map<UUID, Double> byProject = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (TaskFacts f : recent) {
            weekly[(int) Weeks.weeksBetween(windowStart, f.assignedDay())] += f.estimate();
            DayOfWeek dow = f.assignedDay().getDayOfWeek();
            if (dow.getValue() <= 5) {
                weekdayCounts[dow.getValue() - 1]++;
            }
            if (f.mode() == Mode.SELF_PICKED) {
                self++;
            } else if (f.mode() == Mode.MANUAL) {
                manual++;
            } else {
                project++;
            }
            if (f.task().projectId() != null) {
                withProject++;
                byProject.merge(f.task().projectId(), f.estimate(), Double::sum);
            }
        }
        int n = recent.size();
        double hours13 = 0;
        for (double w : weekly) {
            hours13 += w;
        }
        double weekdayTotal = 0;
        for (double c : weekdayCounts) {
            weekdayTotal += c;
        }
        List<Double> weekdayShares = new ArrayList<>();
        int top = 0;
        for (int i = 0; i < 5; i++) {
            weekdayShares.add(weekdayTotal == 0 ? 0.0 : round3(weekdayCounts[i] / weekdayTotal));
            if (weekdayCounts[i] > weekdayCounts[top]) {
                top = i;
            }
        }
        Map<String, Double> hoursByProject = new TreeMap<>();
        double projectHours = byProject.values().stream().mapToDouble(Double::doubleValue).sum();
        if (projectHours > 0) {
            Map<UUID, ProjectRow> projects = data.projectById();
            byProject.forEach((id, h) -> hoursByProject.put(projects.containsKey(id) ? projects.get(id).key() : id.toString(), round3(h / projectHours)));
        }
        List<Double> ratios = new ArrayList<>();
        List<Double> cycles = new ArrayList<>();
        Map<String, List<Double>> cyclesByFamily = new TreeMap<>();
        List<Double> lateness = new ArrayList<>();
        for (TaskFacts f : mine) {
            if (f.finished() == null || f.actualHours() <= 0) {
                continue;
            }
            if (f.estimate() > 0) {
                ratios.add(f.actualHours() / f.estimate());
            }
            if (f.cycleDays() != null) {
                cycles.add((double) f.cycleDays());
                cyclesByFamily.computeIfAbsent(f.family().label(), k -> new ArrayList<>()).add((double) f.cycleDays());
            }
            if (f.latenessDays() != null) {
                lateness.add((double) f.latenessDays());
            }
        }
        Map<String, Double> cycleByFamily = new TreeMap<>();
        cyclesByFamily.forEach((k, v) -> cycleByFamily.put(k, median(v)));
        int open = 0;
        double openHours = 0;
        int overdue = 0;
        for (TaskFacts f : mine) {
            if (f.done()) {
                continue;
            }
            open++;
            openHours += f.remaining() != null ? f.remaining() : f.estimate();
            if (f.task().dueDate() != null && f.task().dueDate().isBefore(asOf)) {
                overdue++;
            }
        }
        return new MemberPattern(member, n, hours13, hours13 / WINDOW_WEEKS, round3(slope(weekly)),
                n == 0 ? null : (double) manual / n, n == 0 ? null : (double) self / n, n == 0 ? null : (double) project / n,
                weekdayTotal == 0 ? null : WEEKDAYS.get(top), weekdayShares,
                ratios.isEmpty() ? null : median(ratios), cycles.isEmpty() ? null : median(cycles), cycleByFamily,
                lateness.isEmpty() ? null : median(lateness), lateness.isEmpty() ? null : lateness.stream().filter(l -> l > 0).count() / (double) lateness.size(),
                n == 0 ? null : (double) withProject / n, hoursByProject, open, openHours, overdue);
    }

    /** Least-squares slope of values over 0..n−1. */
    static double slope(double[] values) {
        int n = values.length;
        double xMean = (n - 1) / 2.0;
        double yMean = 0;
        for (double v : values) {
            yMean += v / n;
        }
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (i - xMean) * (values[i] - yMean);
            den += (i - xMean) * (i - xMean);
        }
        return den == 0 ? 0.0 : num / den;
    }

    static double median(List<Double> values) {
        List<Double> s = values.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
