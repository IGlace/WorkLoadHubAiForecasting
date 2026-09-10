package com.workloadhub.forecast.facts;

import com.workloadhub.forecast.api.MemberWeekForecast;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TeamCapacityRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.features.WeeklySeries;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.planned.PlannedWork;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** The document Copilot reads: every number the run computed, identified by names and task keys. */
public final class FactsBuilder {

    public static final int HISTORY_WEEKS = 13;
    public static final int LOGGED_WEEKS = 4;
    public static final int ROLE_WINDOW_WEEKS = 26;
    public static final double UNDERLOAD_RATIO = 0.7;
    public static final String PLANNED_BASIS = "share weights, 26-week window, shrink k=3";
    public static final String LIMITATIONS = "arrivals during the current partial week are not modelled; open tasks are placed from the first"
            + " forecast week; backlog work created and assigned inside the window is missed by both components";

    private FactsBuilder() {
    }

    public static String toJson(Map<String, Object> facts) {
        return ExportFiles.mapper().writeValueAsString(facts);
    }

    public static Map<String, Object> build(TeamOutcome out, UUID runId, LocalDateTime generatedAt) {
        Prepared p = out.prepared();
        ForecastData data = p.data();
        Lifecycle lc = p.lifecycle();
        LocalDate f1 = p.forecastWeeks()[0];
        LocalDate f2 = p.forecastWeeks()[1];
        Map<UUID, ProjectRow> projects = data.projectById();
        Map<UUID, List<MemberWeekForecast>> rowsByMember = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (MemberWeekForecast r : out.memberWeeks()) {
            rowsByMember.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);
        }
        List<MemberPattern> patterns = Patterns.table(out.members(), lc, data, p.asOf());
        Map<UUID, Integer> clusters = Clustering.assign(patterns);
        Map<UUID, MemberPattern> patternById = patterns.stream().collect(Collectors.toMap(MemberPattern::memberId, x -> x));
        List<LocalDate> historyWeeks = Weeks.between(p.origin().minusWeeks(HISTORY_WEEKS - 1), p.origin());
        WeeklySeries series = WeeklySeries.build(lc, out.members(), historyWeeks);
        Set<UUID> teamProjects = data.projectIdsOfTeamAndParent(out.teamId());
        Set<UUID> liveProjectIds = new java.util.HashSet<>();
        for (TaskFacts f : lc.all()) {
            if (f.task().projectId() != null && !f.done()) {
                liveProjectIds.add(f.task().projectId());
            }
        }
        LocalDate roleStart = p.asOf().minusWeeks(ROLE_WINDOW_WEEKS);
        Map<UUID, List<TaskFacts>> recentByProject = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (TaskFacts f : lc.all()) {
            if (f.isAssigned() && f.task().projectId() != null && teamProjects.contains(f.task().projectId()) && f.assignedDay().isAfter(roleStart)) {
                recentByProject.computeIfAbsent(f.task().projectId(), k -> new ArrayList<>()).add(f);
            }
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("run", map("id", str(runId), "as_of", str(p.asOf()), "weeks", List.of(str(f1), str(f2)), "generated_at", generatedAt.toString(),
                "origin", str(p.origin()), "horizons", List.of(p.horizons()[0], p.horizons()[1])));
        facts.put("team", team(out, data, f1, f2, projects));
        List<Object> members = new ArrayList<>();
        for (MemberRow m : out.members()) {
            members.add(member(m, out, rowsByMember.getOrDefault(m.id(), List.of()), patternById.get(m.id()), clusters.getOrDefault(m.id(), 0),
                    series, historyWeeks, projects, liveProjectIds, recentByProject));
        }
        facts.put("members", members);
        facts.put("projects", projectFacts(out, teamProjects, projects));
        facts.put("model", model(out));
        facts.put("rebalancing_candidates", rebalancing(out, rowsByMember));
        List<Object> pending = new ArrayList<>();
        for (HolidayRow h : data.holidays()) {
            if (h.active() && !h.confirmed()) {
                pending.add(map("title", h.title(), "start", str(h.start()), "end", str(h.end())));
            }
        }
        facts.put("pending_holidays", pending);
        Set<UUID> memberIds = out.members().stream().map(MemberRow::id).collect(Collectors.toSet());
        Set<String> teamKeys = lc.all().stream().filter(f -> f.assignee() != null && memberIds.contains(f.assignee())).map(f -> f.task().key())
                .collect(Collectors.toSet());
        facts.put("data_quality", map(
                "unresolved_assignments", lc.unresolvedAssignments().stream().filter(teamKeys::contains).sorted().toList(),
                "unlogged_tasks", lc.unloggedTasks().stream().filter(teamKeys::contains).sorted().toList(),
                "history_weeks", p.historyWeeks()));
        return facts;
    }

    private static Map<String, Object> team(TeamOutcome out, ForecastData data, LocalDate f1, LocalDate f2, Map<UUID, ProjectRow> projects) {
        TeamRow team = data.teamById().get(out.teamId());
        List<Object> totals = new ArrayList<>();
        for (LocalDate w : List.of(f1, f2)) {
            double demand = 0;
            double capacity = 0;
            double planned = 0;
            for (MemberWeekForecast r : out.memberWeeks()) {
                if (r.weekStart().equals(w)) {
                    demand += r.demandHrs();
                    capacity += r.capacityHrs();
                    planned += r.plannedHrs();
                }
            }
            totals.add(map("week", str(w), "demand", round1(demand), "capacity", round1(capacity), "planned", round1(planned)));
        }
        List<Object> teamCapacity = new ArrayList<>();
        for (TeamCapacityRow r : data.teamCapacity()) {
            if (r.teamId().equals(out.teamId()) && (r.weekStart().equals(f1) || r.weekStart().equals(f2))) {
                teamCapacity.add(map("week", str(r.weekStart()), "total", round2(r.totalCapacity()), "allocated", round2(r.allocated())));
            }
        }
        Map<UUID, double[]> byProject = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        Map<UUID, Set<UUID>> tasksByProject = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (PlannedWork.Piece piece : out.planned().pieces()) {
            UUID pid = piece.projectId();
            double[] acc = byProject.computeIfAbsent(pid, k -> new double[3]);
            if (tasksByProject.computeIfAbsent(pid, k -> new java.util.HashSet<>()).add(piece.taskId())) {
                acc[0] += piece.estimate();
            }
            acc[1] += piece.hoursInWindow();
            acc[2] += piece.hoursAfterWindow();
        }
        List<Object> backlog = new ArrayList<>();
        byProject.forEach((pid, acc) -> backlog.add(map("project_id", str(pid), "project_key", projects.containsKey(pid) ? projects.get(pid).key() : null,
                "tasks", tasksByProject.get(pid).size(), "estimated_hours", round2(acc[0]), "hours_in_window", round2(acc[1]),
                "hours_after_window", round2(acc[2]))));
        return map("id", str(out.teamId()), "name", team == null ? null : team.name(), "parent_team_id", team == null ? null : str(team.parentId()),
                "manager_id", team == null ? null : str(team.managerId()), "totals", totals, "team_capacity", teamCapacity,
                "planned_backlog", map("candidates", out.planned().candidateCount(), "candidate_hours", round2(out.planned().candidateHours()),
                        "projects", backlog));
    }

    private static Map<String, Object> member(MemberRow m, TeamOutcome out, List<MemberWeekForecast> rows, MemberPattern pattern, int cluster,
            WeeklySeries series, List<LocalDate> historyWeeks, Map<UUID, ProjectRow> projects, Set<UUID> liveProjectIds,
            Map<UUID, List<TaskFacts>> recentByProject) {
        Prepared p = out.prepared();
        Lifecycle lc = p.lifecycle();
        List<TaskFacts> mine = lc.assignedTo(m.id());
        List<Object> history = new ArrayList<>();
        for (LocalDate w : historyWeeks) {
            WeeklySeries.Cell c = series.cell(m.id(), w);
            history.add(map("week", str(w), "hours", round1(c.estHours()), "fresh_hours", round1(c.freshHours()), "tasks", c.nTasks()));
        }
        List<TaskFacts> open = mine.stream().filter(f -> !f.done()).toList();
        List<Object> forecast = new ArrayList<>();
        for (MemberWeekForecast r : rows) {
            LocalDate end = r.weekStart().plusDays(6);
            double due = 0;
            for (TaskFacts f : open) {
                LocalDate d = f.task().dueDate();
                if (d != null && !d.isBefore(r.weekStart()) && !d.isAfter(end)) {
                    due += f.remaining() != null ? f.remaining() : f.estimate();
                }
            }
            forecast.add(map("week", str(r.weekStart()), "demand", r.demandHrs(), "low", r.lowHrs(), "high", r.highHrs(), "capacity", r.capacityHrs(),
                    "overload", r.overloadHrs(), "open_hours", r.openHrs(), "new_hours", r.newHrs(), "planned_hours", r.plannedHrs(),
                    "working_days", r.workingDays(), "absence_hours", r.absenceHrs(), "due_hours", round2(due)));
        }
        Map<String, Object> patternMap = pattern.toMap();
        patternMap.put("cluster", cluster);
        List<Object> openTasks = new ArrayList<>();
        for (TaskFacts f : open) {
            openTasks.add(map("key", f.task().key(), "title", f.task().title(), "type", f.task().typeName(), "family", f.family().label(),
                    "priority", f.task().priority(), "estimated_hours", round2(f.estimate()),
                    "remaining_hours", f.remaining() == null ? null : round2(f.remaining()), "due_date", str(f.task().dueDate()),
                    "overdue", f.task().dueDate() != null && f.task().dueDate().isBefore(p.asOf()),
                    "project_key", key(projects, f.task().projectId()), "in_progress", f.inProgress()));
        }
        Map<LocalDate, Double> logged = new TreeMap<>();
        for (TimeLogRow l : p.data().timeLogs()) {
            if (l.userId().equals(m.id())) {
                logged.merge(Weeks.mondayOf(l.day()), l.hours(), Double::sum);
            }
        }
        List<Object> loggedWeeks = new ArrayList<>();
        for (LocalDate w : Weeks.between(p.origin().minusWeeks(LOGGED_WEEKS - 1), p.origin())) {
            loggedWeeks.add(map("week", str(w), "hours", round2(logged.getOrDefault(w, 0.0))));
        }
        List<Object> planned = new ArrayList<>();
        for (PlannedWork.Piece piece : out.planned().pieces()) {
            if (piece.member().equals(m.id())) {
                planned.add(map("key", piece.key(), "title", piece.title(), "project_key", key(projects, piece.projectId()), "type", piece.family().label(),
                        "estimated_hours", round2(piece.estimate()), "share", round2(piece.share()), "expected_date", str(piece.expectedDate()),
                        "expected_week", piece.expectedWeek() == null ? "after_window" : str(piece.expectedWeek()), "hours_in_window", round2(piece.hoursInWindow())));
            }
        }
        List<Object> roles = new ArrayList<>();
        for (Map.Entry<UUID, List<TaskFacts>> e : recentByProject.entrySet()) {
            if (!liveProjectIds.contains(e.getKey())) {
                continue;
            }
            List<TaskFacts> own = e.getValue().stream().filter(f -> m.id().equals(f.assignee())).toList();
            if (own.isEmpty()) {
                continue;
            }
            Map<String, Integer> types = new TreeMap<>();
            own.forEach(f -> types.merge(f.task().typeName(), 1, Integer::sum));
            List<Object> dominant = types.entrySet().stream().sorted((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()))
                    .limit(3).map(t -> (Object) map("type", t.getKey(), "count", t.getValue())).toList();
            roles.add(map("project_key", key(projects, e.getKey()), "share", round2((double) own.size() / e.getValue().size()), "dominant_types", dominant, "phase", "active"));
        }
        Map<String, Integer> recentMix = new TreeMap<>();
        LocalDate mixStart = Weeks.mondayOf(p.asOf()).minusWeeks(HISTORY_WEEKS);
        for (TaskFacts f : mine) {
            if (!f.assignedDay().isBefore(mixStart)) {
                recentMix.merge(f.task().typeName(), 1, Integer::sum);
            }
        }
        return map("id", str(m.id()), "name", m.fullName(), "role", m.role(), "job_title", m.jobTitle(), "history_13w", history, "forecast", forecast,
                "patterns", patternMap, "open_tasks", openTasks,
                "reopened_tasks", mine.stream().filter(f -> f.task().reopened()).map(f -> f.task().key()).sorted().toList(),
                "logged_hours_4w", loggedWeeks,
                "unlogged_tasks", mine.stream().filter(TaskFacts::unlogged).map(f -> f.task().key()).sorted().toList(),
                "likely_work", map("planned", planned, "project_roles", roles, "recent_mix", recentMix));
    }

    private static List<Object> projectFacts(TeamOutcome out, Set<UUID> teamProjects, Map<UUID, ProjectRow> projects) {
        Lifecycle lc = out.prepared().lifecycle();
        List<Object> outList = new ArrayList<>();
        for (UUID pid : teamProjects) {
            ProjectRow pr = projects.get(pid);
            int open = 0;
            int backlog = 0;
            LocalDate firstDue = null;
            for (TaskFacts f : lc.all()) {
                if (!pid.equals(f.task().projectId()) || f.done()) {
                    continue;
                }
                if (f.isAssigned()) {
                    open++;
                } else {
                    backlog++;
                }
                LocalDate d = f.task().dueDate();
                if (d != null && (firstDue == null || d.isBefore(firstDue))) {
                    firstDue = d;
                }
            }
            outList.add(map("id", str(pid), "key", pr.key(), "name", pr.name(), "status", pr.status(), "open_tasks", open, "backlog_tasks", backlog,
                    "first_due", str(firstDue)));
        }
        return outList;
    }

    private static Map<String, Object> model(TeamOutcome out) {
        Prepared p = out.prepared();
        Map<String, Object> horizons = new LinkedHashMap<>();
        for (int h : p.horizons()) {
            double[] q = p.bandOffsets().get(h);
            horizons.put(String.valueOf(h), map("low_offset", round2(q[0]), "high_offset", round2(q[1])));
        }
        Map<String, Object> mase = new TreeMap<>();
        p.backtest().meanMaseByModel().forEach((k, v) -> mase.put(k, finite(v)));
        return map("champion", p.champion(), "champion_mase", finite(p.championMase()), "forced_model", p.forcedModel(), "mase_by_model", mase,
                "backtest_origins", p.backtestOrigins().stream().map(FactsBuilder::str).toList(), "horizons", List.of(p.horizons()[0], p.horizons()[1]),
                "interval", map("basis", "backtest residuals", "horizons", horizons), "unavailable", new TreeMap<>(p.backtest().unavailable()),
                "planned_basis", out.plannedWorkEnabled() ? PLANNED_BASIS : "disabled", "limitations", LIMITATIONS,
                "seconds_by_phase", new LinkedHashMap<>(p.secondsByPhase()));
    }

    private static Map<String, Object> rebalancing(TeamOutcome out, Map<UUID, List<MemberWeekForecast>> rowsByMember) {
        List<Object> overloaded = new ArrayList<>();
        List<Object> underloaded = new ArrayList<>();
        for (MemberRow m : out.members()) {
            double demand = 0;
            double capacity = 0;
            double overload = 0;
            for (MemberWeekForecast r : rowsByMember.getOrDefault(m.id(), List.of())) {
                demand += r.demandHrs();
                capacity += r.capacityHrs();
                overload += r.overloadHrs();
            }
            if (overload > 0) {
                overloaded.add(map("member_id", str(m.id()), "name", m.fullName(), "overload_hours", round1(overload)));
            }
            if (capacity > 0 && demand < UNDERLOAD_RATIO * capacity) {
                underloaded.add(map("member_id", str(m.id()), "name", m.fullName(), "spare_hours", round1(capacity - demand)));
            }
        }
        return map("overloaded", overloaded, "underloaded", underloaded);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static String str(Object v) {
        return v == null ? null : v.toString();
    }

    static String key(Map<UUID, ProjectRow> projects, UUID id) {
        return id == null ? null : projects.containsKey(id) ? projects.get(id).key() : id.toString();
    }

    static Double finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static double round2(double v) {
        return ForecastRunner.round2(v);
    }
}
