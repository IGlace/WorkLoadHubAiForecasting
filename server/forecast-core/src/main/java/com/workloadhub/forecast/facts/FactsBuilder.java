package com.workloadhub.forecast.facts;

import com.workloadhub.forecast.Numbers;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.calendar.ForecastWindow;
import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.Json;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.LeaveRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.UserRef;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.features.WeeklySeries;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.OpenWork;
import com.workloadhub.forecast.lifecycle.TaskFacts;
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
    public static final String LIMITATIONS = "predicted hours are spread over a week's working days by the member's logged-hours weekday shares;"
            + " days already past are not re-forecast; the forecast is of hours logged, so a member who logs less than they work is forecast to work"
            + " less; and it is a forecast of what someone will get through rather than of what is waiting for them";

    private FactsBuilder() {
    }

    public static String toJson(Map<String, Object> facts) {
        return Json.mapper().writeValueAsString(facts);
    }

    public static Map<String, Object> build(TeamOutcome out, UUID runId, LocalDateTime generatedAt) {
        Prepared p = out.prepared();
        ForecastData data = p.data();
        Lifecycle lc = p.lifecycle();
        Map<UUID, ProjectRow> projects = data.projectById();
        Map<UUID, List<MemberWindowForecast>> rowsByMember = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (MemberWindowForecast r : out.memberWindows()) {
            rowsByMember.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);
        }
        Map<UUID, List<MemberDayForecast>> daysByMember = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (MemberDayForecast r : out.memberDays()) {
            daysByMember.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);
        }
        List<MemberPattern> patterns = Patterns.table(out.members(), lc, data, p.asOf());
        Map<UUID, Integer> clusters = Clustering.assign(patterns);
        Map<UUID, MemberPattern> patternById = patterns.stream().collect(Collectors.toMap(MemberPattern::memberId, x -> x));
        List<LocalDate> historyWeeks = Weeks.between(p.origin().minusWeeks(HISTORY_WEEKS - 1), p.origin());
        WeeklySeries series = WeeklySeries.build(lc, data, out.members(), historyWeeks);
        Set<UUID> teamProjects = data.projectIdsOfTeam(out.teamId());
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
        List<Object> windows = new ArrayList<>();
        for (ForecastWindow w : p.windows()) {
            int working = 0;
            for (LocalDate d : w.weekdays()) {
                if (p.calendar().isWorkingDay(d)) {
                    working++;
                }
            }
            windows.add(map("index", w.index(), "start", str(w.start()), "end", str(w.end()), "working_days", working));
        }
        List<Object> horizonList = new ArrayList<>();
        for (int h : p.horizons()) {
            horizonList.add(h);
        }
        facts.put("run", map("id", str(runId), "as_of", str(p.asOf()), "windows", windows, "generated_at", generatedAt.toString(),
                "origin", str(p.origin()), "horizons", horizonList));
        facts.put("team", team(out, data, projects));
        List<Object> members = new ArrayList<>();
        for (MemberRow m : out.members()) {
            members.add(member(m, out, rowsByMember.getOrDefault(m.id(), List.of()), daysByMember.getOrDefault(m.id(), List.of()),
                    patternById.get(m.id()), clusters.getOrDefault(m.id(), 0),
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

    private static Map<String, Object> team(TeamOutcome out, ForecastData data, Map<UUID, ProjectRow> projects) {
        UserRef lead = data.userById().get(out.teamId());
        List<Object> totals = new ArrayList<>();
        for (ForecastWindow w : out.prepared().windows()) {
            double demand = 0;
            double capacity = 0;
            for (MemberWindowForecast r : out.memberWindows()) {
                if (r.windowIndex() == w.index()) {
                    demand += r.demandHrs();
                    capacity += r.capacityHrs();
                }
            }
            totals.add(map("window", w.index(), "start", str(w.start()), "end", str(w.end()), "demand", round1(demand), "capacity", round1(capacity)));
        }
        // A team is keyed by its leader, so `name` is the leader's own name, `manager_id` is the key itself,
        // and `parent_team_id` is the leader's own manager: the skill team leader a risk is escalated to.
        return map("id", str(out.teamId()), "name", lead == null ? null : lead.fullName(),
                "parent_team_id", lead == null || lead.managerId() == null ? null : str(lead.managerId()),
                "manager_id", lead == null ? null : str(out.teamId()), "totals", totals);
    }

    /** Estimated hours of the member's open tasks whose planned week overlaps the window. */
    static double plannedHours(List<TaskFacts> open, LocalDate start, LocalDate end) {
        double sum = 0;
        for (TaskFacts f : open) {
            LocalDate pw = f.task().plannedWeek();
            if (pw == null) {
                continue;
            }
            pw = Weeks.mondayOf(pw);
            if (!pw.plusDays(6).isBefore(start) && !pw.isAfter(end)) {
                sum += f.estimate();
            }
        }
        return sum;
    }

    private static Map<String, Object> member(MemberRow m, TeamOutcome out, List<MemberWindowForecast> rows, List<MemberDayForecast> days,
            MemberPattern pattern, int cluster,
            WeeklySeries series, List<LocalDate> historyWeeks, Map<UUID, ProjectRow> projects, Set<UUID> liveProjectIds,
            Map<UUID, List<TaskFacts>> recentByProject) {
        Prepared p = out.prepared();
        Lifecycle lc = p.lifecycle();
        List<TaskFacts> mine = lc.assignedTo(m.id());
        List<Object> history = new ArrayList<>();
        double[] loggedByWeek = series.logged(m.id());
        for (int i = 0; i < historyWeeks.size(); i++) {
            LocalDate w = historyWeeks.get(i);
            WeeklySeries.Cell c = series.cell(m.id(), w);
            history.add(map("week", str(w), "logged_hours", round1(loggedByWeek[i]), "arrival_hours", round1(c.estHours()), "tasks", c.nTasks()));
        }
        List<TaskFacts> open = mine.stream().filter(f -> !f.done()).toList();
        List<Object> forecast = new ArrayList<>();
        for (MemberWindowForecast r : rows) {
            // The same helper ForecastRunner reads back for due_excess_hrs (design 2026-09-13, section 8.2): one
            // definition of the sum, and one rounding of it, so due_hours here and due_excess_hrs on the row can
            // never contradict each other.
            double due = OpenWork.dueHours(open, r.windowStart(), r.windowEnd());
            forecast.add(map("window", r.windowIndex(), "start", str(r.windowStart()), "end", str(r.windowEnd()), "demand", r.demandHrs(), "low", r.lowHrs(),
                    "high", r.highHrs(), "capacity", r.capacityHrs(), "overload", r.overloadHrs(),
                    "working_days", r.workingDays(), "absence_hours", r.absenceHrs(), "due_hours", Numbers.round2(due),
                    "backlog_excess_hrs", r.backlogExcessHrs(), "due_excess_hrs", r.dueExcessHrs(),
                    "planned_hours", Numbers.round2(plannedHours(open, r.windowStart(), r.windowEnd()))));
        }
        List<Object> dayList = new ArrayList<>();
        for (MemberDayForecast d : days) {
            dayList.add(map("day", str(d.day()), "window", d.windowIndex(), "demand", d.demandHrs(), "capacity", d.capacityHrs(), "overload", d.overloadHrs(),
                    "working_day", d.workingDay()));
        }
        LocalDate first = p.windows().get(0).start();
        LocalDate last = p.windows().get(p.windows().size() - 1).end();
        List<Object> pendingLeaves = new ArrayList<>();
        for (LeaveRow l : p.data().pendingLeaves()) {
            if (l.employeeId().equals(m.id()) && !l.end().isBefore(first) && !l.start().isAfter(last)) {
                pendingLeaves.add(map("start_date", str(l.start()), "end_date", str(l.end()), "leave_type", l.leaveType(),
                        "absence_hours", l.absenceHours() == null ? null : Numbers.round2(l.absenceHours())));
            }
        }
        Map<String, Object> patternMap = pattern.toMap();
        patternMap.put("cluster", cluster);
        List<Object> openTasks = new ArrayList<>();
        for (TaskFacts f : open) {
            openTasks.add(map("key", f.task().key(), "title", f.task().title(), "type", f.task().typeName(), "family", f.family().label(),
                    "priority", f.task().priority(), "estimated_hours", Numbers.round2(f.estimate()),
                    "remaining_hours", f.remaining() == null ? null : Numbers.round2(f.remaining()), "due_date", str(f.task().dueDate()),
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
            loggedWeeks.add(map("week", str(w), "hours", Numbers.round2(logged.getOrDefault(w, 0.0))));
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
            roles.add(map("project_key", key(projects, e.getKey()), "share", Numbers.round2((double) own.size() / e.getValue().size()), "dominant_types", dominant, "phase", "active"));
        }
        Map<String, Integer> recentMix = new TreeMap<>();
        LocalDate mixStart = Weeks.mondayOf(p.asOf()).minusWeeks(HISTORY_WEEKS);
        for (TaskFacts f : mine) {
            if (!f.assignedDay().isBefore(mixStart)) {
                recentMix.merge(f.task().typeName(), 1, Integer::sum);
            }
        }
        return map("id", str(m.id()), "name", m.fullName(), "role", m.role(), "job_title", m.jobTitle(), "history_13w", history, "forecast", forecast, "days", dayList,
                "pending_leaves", pendingLeaves,
                "patterns", patternMap, "open_tasks", openTasks,
                "reopened_tasks", mine.stream().filter(f -> f.task().reopened()).map(f -> f.task().key()).sorted().toList(),
                "logged_hours_4w", loggedWeeks,
                "unlogged_tasks", mine.stream().filter(TaskFacts::unlogged).map(f -> f.task().key()).sorted().toList(),
                "likely_work", map("project_roles", roles, "recent_mix", recentMix));
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
            horizons.put(String.valueOf(h), map("low_offset", Numbers.round2(q[0]), "high_offset", Numbers.round2(q[1])));
        }
        // A NaN mae (every backtest origin scored zero rows) must read exactly like a null one: never "scored",
        // never a number a narrative could quote as a measurement.
        Double mae = finite(p.mae() == null ? Double.NaN : p.mae());
        boolean scored = mae != null;
        Double meanActualHours = scored ? finite(p.meanActualHours() == null ? Double.NaN : p.meanActualHours()) : null;
        return map("name", "xgboost", "target", "logged hours per member-week", "mae", mae,
                "mean_actual_hours", meanActualHours,
                "confidence", scored ? "scored" : "thin_history",
                "backtest_origins", p.backtestOrigins().stream().map(FactsBuilder::str).toList(), "horizons", java.util.Arrays.stream(p.horizons()).boxed().toList(),
                "windows", p.windows().size(),
                "interval", map("basis", scored ? "backtest residuals" : "none: no scored origins", "horizons", horizons),
                "limitations", LIMITATIONS,
                "seconds_by_phase", new LinkedHashMap<>(p.secondsByPhase()));
    }

    private static Map<String, Object> rebalancing(TeamOutcome out, Map<UUID, List<MemberWindowForecast>> rowsByMember) {
        List<Object> overloaded = new ArrayList<>();
        List<Object> underloaded = new ArrayList<>();
        List<Object> backlogPressed = new ArrayList<>();
        List<Object> deadlinePressed = new ArrayList<>();
        for (MemberRow m : out.members()) {
            double demand = 0;
            double capacity = 0;
            double overload = 0;
            List<MemberWindowForecast> rows = rowsByMember.getOrDefault(m.id(), List.of());
            for (MemberWindowForecast r : rows) {
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
            // backlog_excess_hrs is non-increasing across a run's windows (design 2026-09-13, section 8.1), so
            // the last window is the strictest test: it is above zero there exactly when the backlog does not
            // fit inside the whole run. due_excess_hrs is per window, so any window above zero is enough to act on.
            if (!rows.isEmpty()) {
                double lastBacklogExcess = rows.get(rows.size() - 1).backlogExcessHrs();
                if (lastBacklogExcess > 0) {
                    backlogPressed.add(map("member_id", str(m.id()), "name", m.fullName(),
                            "backlog_excess_hrs", round1(lastBacklogExcess)));
                }
            }
            double maxDueExcess = rows.stream().mapToDouble(MemberWindowForecast::dueExcessHrs).max().orElse(0.0);
            if (maxDueExcess > 0) {
                deadlinePressed.add(map("member_id", str(m.id()), "name", m.fullName(), "due_excess_hrs", round1(maxDueExcess)));
            }
        }
        return map("overloaded", overloaded, "underloaded", underloaded, "backlog_pressed", backlogPressed, "deadline_pressed", deadlinePressed);
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
}
