package com.workloadhub.forecast.planned;

import com.workloadhub.forecast.calendar.HourPlacement;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.Ids;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Family;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.model.EffortModel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Who will take the unassigned backlog and when, per the planned-work design, section 5. */
public final class PlannedWork {

    public static final int WINDOW_WEEKS = 26;
    public static final double SHRINK_K = 3.0;
    public static final int MIN_PROJECT_TASKS_FOR_LAG = 5;

    public record Piece(UUID taskId, String key, String title, UUID projectId, Family family, double estimate, UUID member, double share,
            LocalDate expectedDate, LocalDate expectedWeek, double hoursInWindow, double hoursAfterWindow) {
    }

    public record Allocation(SortedMap<MemberWeek, Double> hours, List<Piece> pieces, double hoursAfterWindow, int candidateCount,
            double candidateHours) {
        public static Allocation empty() {
            return new Allocation(new TreeMap<>(), List.of(), 0.0, 0, 0.0);
        }
    }

    public record Request(UUID teamId, List<MemberRow> members, LocalDate asOf, LocalDate[] forecastWeeks) {
    }

    private PlannedWork() {
    }

    public static List<TaskFacts> candidates(Lifecycle lc, ForecastData data, UUID teamId, LocalDate asOf) {
        Set<UUID> projects = data.projectIdsOfTeamAndParent(teamId);
        LocalDate latestCreation = asOf.minusDays(Lifecycle.BACKLOG_LAG_DAYS);
        return lc.all().stream()
                .filter(f -> f.assignee() == null && !f.done() && f.task().projectId() != null && projects.contains(f.task().projectId()))
                .filter(f -> !f.task().createdDate().toLocalDate().isAfter(latestCreation))
                .sorted(Comparator.comparing(TaskFacts::id, Ids.UUID_ORDER))
                .toList();
    }

    /** Assigned tasks of the members inside the window (asOf − 26 weeks, asOf]. */
    public static List<TaskFacts> history(Lifecycle lc, List<MemberRow> members, LocalDate asOf) {
        LocalDate from = asOf.minusWeeks(WINDOW_WEEKS);
        List<TaskFacts> out = new ArrayList<>();
        for (MemberRow m : members) {
            for (TaskFacts f : lc.assignedTo(m.id())) {
                LocalDate day = f.assignedDay();
                if (day.isAfter(from) && !day.isAfter(asOf)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    public static Map<UUID, Double> weights(TaskFacts candidate, List<MemberRow> eligible, List<TaskFacts> history) {
        List<UUID> ids = eligible.stream().map(MemberRow::id).sorted(Ids.UUID_ORDER).toList();
        Map<UUID, Double> level = new LinkedHashMap<>();
        for (UUID id : ids) {
            level.put(id, 1.0 / ids.size());
        }
        UUID project = candidate.task().projectId();
        Family family = candidate.family();
        level = shrinkToward(level, history, f -> f.family() == family);
        if (project != null) {
            level = shrinkToward(level, history, f -> project.equals(f.task().projectId()));
            level = shrinkToward(level, history, f -> project.equals(f.task().projectId()) && f.family() == family);
        }
        return level;
    }

    private static Map<UUID, Double> shrinkToward(Map<UUID, Double> prior, List<TaskFacts> history, Predicate<TaskFacts> at) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (TaskFacts f : history) {
            if (at.test(f) && prior.containsKey(f.assignee())) {
                counts.merge(f.assignee(), 1, Integer::sum);
                total++;
            }
        }
        if (total == 0) {
            return prior;
        }
        Map<UUID, Double> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> e : prior.entrySet()) {
            out.put(e.getKey(), (counts.getOrDefault(e.getKey(), 0) + SHRINK_K * e.getValue()) / (total + SHRINK_K));
        }
        return out;
    }

    public static long lagDays(TaskFacts candidate, Lifecycle lc, ForecastData data, Set<UUID> teamMembers) {
        return lagDays(candidate, LagIndex.of(lc, teamMembers));
    }

    /** The assigned tasks' lag days, grouped once per {@code allocate} call instead of rescanned per candidate. */
    private record LagIndex(Map<UUID, List<Long>> byProject, List<Long> team, List<Long> all) {

        static LagIndex of(Lifecycle lc, Set<UUID> teamMembers) {
            Map<UUID, List<Long>> byProject = new HashMap<>();
            List<Long> team = new ArrayList<>();
            List<Long> all = new ArrayList<>();
            for (TaskFacts f : lc.all()) {
                if (!f.isAssigned()) {
                    continue;
                }
                long lag = f.lagDays();
                all.add(lag);
                UUID project = f.task().projectId();
                if (project != null) {
                    byProject.computeIfAbsent(project, k -> new ArrayList<>()).add(lag);
                }
                if (teamMembers.contains(f.assignee())) {
                    team.add(lag);
                }
            }
            return new LagIndex(byProject, team, all);
        }
    }

    private static long lagDays(TaskFacts candidate, LagIndex idx) {
        UUID project = candidate.task().projectId();
        List<Long> projectLags = project == null ? List.of() : idx.byProject().getOrDefault(project, List.of());
        if (projectLags.size() >= MIN_PROJECT_TASKS_FOR_LAG) {
            return median(projectLags);
        }
        if (!idx.team().isEmpty()) {
            return median(idx.team());
        }
        if (!idx.all().isEmpty()) {
            return median(idx.all());
        }
        return Lifecycle.BACKLOG_LAG_DAYS;
    }

    static long median(List<Long> values) {
        List<Long> s = values.stream().sorted().toList();
        int n = s.size();
        double m = n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
        return Math.round(m);
    }

    public static Allocation allocate(Request req, Lifecycle lc, ForecastData data, EffortModel effort, WorkingCalendar cal,
            Function<UUID, Set<LocalDate>> offDaysOf) {
        List<TaskFacts> candidates = candidates(lc, data, req.teamId(), req.asOf());
        int count = candidates.size();
        double candidateHours = candidates.stream().mapToDouble(TaskFacts::estimate).sum();
        LocalDate f1 = req.forecastWeeks()[0];
        LocalDate windowEnd = req.forecastWeeks()[req.forecastWeeks().length - 1].plusDays(6);
        // design section 5.2: a member present on no working day of the forecast window carries no share.
        List<MemberRow> eligible = req.members().stream()
                .filter(m -> m.employedOn(req.asOf()))
                .filter(m -> !cal.workingDays(f1, windowEnd, offDaysOf.apply(m.id())).isEmpty())
                .toList();
        if (eligible.isEmpty() || candidates.isEmpty()) {
            return new Allocation(new TreeMap<>(), List.of(), 0.0, count, candidateHours);
        }
        Set<UUID> teamMembers = req.members().stream().map(MemberRow::id).collect(Collectors.toSet());
        List<TaskFacts> history = history(lc, eligible, req.asOf());
        LagIndex lagIndex = LagIndex.of(lc, teamMembers);
        SortedMap<MemberWeek, Double> hours = new TreeMap<>();
        List<Piece> pieces = new ArrayList<>();
        double after = 0.0;
        for (TaskFacts c : candidates) {
            LocalDate expected = c.task().createdDate().toLocalDate().plusDays(lagDays(c, lagIndex));
            if (expected.isBefore(f1)) {
                expected = f1;
            }
            Map<UUID, Double> w = weights(c, eligible, history);
            for (Map.Entry<UUID, Double> e : w.entrySet()) {
                if (e.getValue() <= 0) {
                    continue;
                }
                UUID member = e.getKey();
                double allocated = c.estimate() * e.getValue();
                double scaled = allocated * effort.estimateRatio(member, c.family(), req.teamId());
                int span = (int) Math.round(effort.familyCycleDays(member, c.family(), req.teamId()));
                LocalDate end = expected.plusDays(Math.max(span - 1, 0));
                double in = 0.0;
                double out = 0.0;
                for (Map.Entry<LocalDate, Double> placed : HourPlacement.placeHours(scaled, expected, end, cal, offDaysOf.apply(member)).entrySet()) {
                    if (placed.getKey().isAfter(windowEnd)) {
                        out += placed.getValue();
                    } else {
                        in += placed.getValue();
                        hours.merge(new MemberWeek(member, placed.getKey()), placed.getValue(), Double::sum);
                    }
                }
                after += out;
                LocalDate expectedWeek = expected.isAfter(windowEnd) ? null : com.workloadhub.forecast.calendar.Weeks.mondayOf(expected);
                pieces.add(new Piece(c.id(), c.task().key(), c.task().title(), c.task().projectId(), c.family(), c.estimate(), member,
                        e.getValue(), expected, expectedWeek, in, out));
            }
        }
        return new Allocation(hours, List.copyOf(pieces), after, count, candidateHours);
    }
}
