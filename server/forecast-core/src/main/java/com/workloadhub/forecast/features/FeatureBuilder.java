package com.workloadhub.forecast.features;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.Ids;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.lifecycle.Family;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.Mode;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Builds the feature matrix of spec section 6 for a set of members up to an origin week. */
public final class FeatureBuilder {

    static final String NO_TITLE = "(none)";

    private final ForecastData data;
    private final Lifecycle lc;
    private final WorkingCalendar cal;
    private final CapacityRule rule;
    private final int windows;

    public FeatureBuilder(ForecastData data, Lifecycle lc, WorkingCalendar cal, CapacityRule rule, int windows) {
        this.data = data;
        this.lc = lc;
        this.cal = cal;
        this.rule = rule;
        this.windows = windows;
    }

    public FeatureMatrix build(List<MemberRow> membersIn, LocalDate origin) {
        List<MemberRow> members = membersIn.stream().sorted(Comparator.comparing(MemberRow::id, Ids.UUID_ORDER)).toList();
        LocalDate firstWeek = origin.minusWeeks(Features.HISTORY_WEEKS - 1);
        List<LocalDate> weeks = Weeks.between(firstWeek, origin);
        WeeklySeries series = WeeklySeries.build(lc, data, members, weeks);
        Set<UUID> leaders = teamKeys();
        Map<String, List<String>> books = codebooks(members, leaders);
        List<String> columns = Features.allColumns(windows);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            col.put(columns.get(i), i);
        }
        TeamContext teams = new TeamContext(data, lc);                 // Task 6
        Map<MemberWeek, double[]> availability = new HashMap<>();
        List<MemberWeek> keys = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        int originIndex = weeks.size() - 1;
        for (MemberRow m : members) {
            List<TaskFacts> tasks = lc.assignedTo(m.id());
            MemberContext mc = new MemberContext(m, tasks, data);       // Task 6 uses the log index
            double[] fresh = series.fresh(m.id());
            double[] logged = series.logged(m.id());
            int start = startIndex(m, tasks, weeks);
            for (int i = start; i <= originIndex; i++) {
                LocalDate w = weeks.get(i);
                if (m.left() != null && !w.isBefore(m.left())) {
                    break;
                }
                double[] r = new double[columns.size()];
                Arrays.fill(r, Double.NaN);
                ownHistory(r, col, logged, start, i);
                weeksSinceLastArrival(r, col, fresh, start, i);
                windowStats(r, col, mc, w);
                throughput(r, col, mc, w, fresh, start, i);              // Task 6
                UUID team = teamOf(m, leaders);                          // Task 6
                if (team != null) {
                    teams.fill(r, col, team, w);
                }
                for (int h : Features.horizons(windows)) {
                    LocalDate target = w.plusWeeks(h);
                    double[] avail = availability.computeIfAbsent(new MemberWeek(m.id(), target), k -> new double[] {
                            cal.workingDaysInWeek(target), rule.absenceHours(m.id(), target, data, cal), rule.capacity(m, target, data, cal)});
                    r[col.get("working_days_h" + h)] = avail[0];
                    r[col.get("absence_hrs_h" + h)] = avail[1];
                    r[col.get("available_hrs_h" + h)] = avail[2];
                    r[col.get("due_hrs_h" + h)] = mc.dueHours(w, target);   // Task 6
                    r[col.get("planned_hrs_h" + h)] = mc.plannedHours(w, target);
                    r[col.get(Features.target(h))] = i + h <= originIndex ? logged[i + h] : Double.NaN;
                }
                r[col.get("member_id")] = books.get("member_id").indexOf(m.id().toString());
                if (team != null) {
                    r[col.get("team_id")] = books.get("team_id").indexOf(team.toString());
                }
                r[col.get("role")] = books.get("role").indexOf(m.role());
                r[col.get("job_title")] = books.get("job_title").indexOf(title(m));
                if (m.joined() != null) {
                    r[col.get("tenure_weeks")] = Weeks.weeksBetween(m.joined(), w);
                }
                r[col.get("week_of_year")] = Weeks.isoWeek(w);
                keys.add(new MemberWeek(m.id(), w));
                rows.add(r);
            }
        }
        return FeatureMatrix.of(columns, keys, rows.toArray(double[][]::new), books);
    }

    /**
     * The team a member's features describe: <b>the one they do their work in</b>. For a leader that is the
     * team they lead, not their own manager's — otherwise a leader's team columns would describe the group of
     * leaders above them, a different and much larger quantity than every other row of the same run carries.
     * For everyone else it is their manager's team.
     *
     * <p>Null when a member neither leads a team nor reports to anyone. Per the owner's 2026-09-16 ruling
     * that is left blank, not given a sentinel; such a member is in no team and appears in no run.
     */
    static UUID teamOf(MemberRow m, Set<UUID> leaders) {
        return leaders.contains(m.id()) ? m.id() : m.managerId();
    }

    /**
     * The members who key a team: the effective TEAM_LEADERs (design 2026-09-21, ruling 1). The effective
     * role already carries the "somebody reports to them" half of the rule, because a leader nobody counted
     * reports to is demoted to MEMBER by {@link com.workloadhub.forecast.data.EffectiveRole}. Read from every
     * counted member, not from the subset being built, so one member's features never depend on who else was
     * asked for.
     */
    private Set<UUID> teamKeys() {
        Set<UUID> out = new HashSet<>();
        for (MemberRow m : data.members()) {
            if ("TEAM_LEADER".equals(m.role())) {
                out.add(m.id());
            }
        }
        return out;
    }

    private static String title(MemberRow m) {
        return m.jobTitle() == null || m.jobTitle().isBlank() ? NO_TITLE : m.jobTitle();
    }

    private static Map<String, List<String>> codebooks(List<MemberRow> members, Set<UUID> leaders) {
        Map<String, List<String>> books = new LinkedHashMap<>();
        books.put("member_id", members.stream().map(m -> m.id().toString()).sorted().toList());
        books.put("team_id", members.stream().map(m -> teamOf(m, leaders)).filter(Objects::nonNull)
                .map(UUID::toString).distinct().sorted().toList());
        books.put("role", members.stream().map(MemberRow::role).distinct().sorted().toList());
        books.put("job_title", members.stream().map(FeatureBuilder::title).distinct().sorted().toList());
        return books;
    }

    /**
     * The earlier of the first-activity week and the first assignment week, never before the first loaded
     * week. A member with no activity at all has no first-activity week, so their first assignment decides,
     * and failing that they start at the first loaded week.
     */
    private static int startIndex(MemberRow m, List<TaskFacts> tasks, List<LocalDate> weeks) {
        LocalDate start = m.joined() == null ? null : Weeks.mondayOf(m.joined());
        if (!tasks.isEmpty() && (start == null || tasks.get(0).assignedWeek().isBefore(start))) {
            start = tasks.get(0).assignedWeek();
        }
        if (start == null) {
            return 0;
        }
        int idx = weeks.indexOf(start);
        if (idx >= 0) {
            return idx;
        }
        return start.isBefore(weeks.get(0)) ? 0 : weeks.size();
    }

    /** The target's own history: lags and rolling windows of the logged series. */
    private static void ownHistory(double[] r, Map<String, Integer> col, double[] series, int start, int i) {
        for (int lag : Features.LAGS) {
            int j = i - (lag - 1);
            r[col.get("lag" + lag)] = j >= start ? series[j] : Double.NaN;
        }
        for (int w : Features.ROLL_WINDOWS) {
            int from = Math.max(start, i - w + 1);
            int n = i - from + 1;
            double sum = 0;
            for (int j = from; j <= i; j++) {
                sum += series[j];
            }
            double mean = sum / n;
            double sq = 0;
            for (int j = from; j <= i; j++) {
                sq += (series[j] - mean) * (series[j] - mean);
            }
            r[col.get("roll_mean_" + w)] = mean;
            r[col.get("roll_std_" + w)] = n >= 2 ? Math.sqrt(sq / (n - 1)) : 0.0;
        }
    }

    /** Weeks since the member's last fresh arrival: about arrivals, so it reads the arrival series, not the target's. */
    private static void weeksSinceLastArrival(double[] r, Map<String, Integer> col, double[] fresh, int start, int i) {
        double since = Double.NaN;
        for (int j = i; j >= start; j--) {
            if (fresh[j] > 0) {
                since = i - j;
                break;
            }
        }
        r[col.get("weeks_since_last_arrival")] = since;
    }

    /** Thirteen-week arrival mix and finish statistics; hours are those logged by the week's end. */
    private static void windowStats(double[] r, Map<String, Integer> col, MemberContext mc, LocalDate w) {
        LocalDate from = w.minusWeeks(Features.WINDOW_13 - 1);
        LocalDate end = w.plusDays(6);
        int n = 0;
        int defect = 0;
        int delivery = 0;
        int support = 0;
        int high = 0;
        int self = 0;
        int assigned = 0;
        int known = 0;
        int finished = 0;
        int reopened = 0;
        double actual = 0;
        double estimate = 0;
        List<Integer> cycles = new ArrayList<>();
        for (TaskFacts t : mc.tasks) {
            LocalDate aw = t.assignedWeek();
            if (!aw.isBefore(from) && !aw.isAfter(w)) {
                n++;
                if (t.family() == Family.DEFECT) {
                    defect++;
                } else if (t.family() == Family.DELIVERY) {
                    delivery++;
                } else if (t.family() == Family.SUPPORT) {
                    support++;
                }
                String p = t.task().priority();
                if ("HIGHEST".equals(p) || "HIGH".equals(p)) {
                    high++;
                }
                if (t.mode() == Mode.SELF_PICKED) {
                    self++;
                    known++;
                } else if (t.mode() == Mode.ASSIGNED) {
                    assigned++;
                    known++;
                }
            }
            if (t.finished() != null) {
                LocalDate fd = t.finished().toLocalDate();
                if (!fd.isBefore(from) && !fd.isAfter(end)) {
                    finished++;
                    if (t.task().reopened()) {
                        reopened++;
                    }
                    double hours = t.unlogged() ? t.actualHours() : mc.loggedOnTaskBy(t, end);
                    if (t.estimate() > 0 && hours > 0) {
                        actual += hours;
                        estimate += t.estimate();
                    }
                    Integer c = t.cycleDays();
                    if (c != null) {
                        cycles.add(c);
                    }
                }
            }
        }
        r[col.get("arrivals_13w")] = n;
        r[col.get("share_defect_13w")] = n == 0 ? Double.NaN : (double) defect / n;
        r[col.get("share_delivery_13w")] = n == 0 ? Double.NaN : (double) delivery / n;
        r[col.get("share_support_13w")] = n == 0 ? Double.NaN : (double) support / n;
        r[col.get("share_high_priority_13w")] = n == 0 ? Double.NaN : (double) high / n;
        r[col.get("share_self_picked_13w")] = known == 0 ? Double.NaN : (double) self / known;
        r[col.get("share_assigned_13w")] = known == 0 ? Double.NaN : (double) assigned / known;
        r[col.get("reopen_rate_13w")] = finished == 0 ? Double.NaN : (double) reopened / finished;
        r[col.get("estimate_ratio_13w")] = estimate == 0 ? Double.NaN : actual / estimate;
        r[col.get("cycle_days_13w")] = cycles.isEmpty() ? Double.NaN : median(cycles);
    }

    static double median(List<Integer> values) {
        List<Integer> s = values.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    /** Per-member indexes: the member's tasks and their time logs, by week and by task. */
    static final class MemberContext {
        final List<TaskFacts> tasks;
        private final Map<UUID, List<TimeLogRow>> logsByTask = new HashMap<>();

        MemberContext(MemberRow m, List<TaskFacts> tasks, ForecastData data) {
            this.tasks = tasks;
            for (TimeLogRow l : data.timeLogs()) {
                if (!l.userId().equals(m.id())) {
                    continue;
                }
                logsByTask.computeIfAbsent(l.taskId(), k -> new ArrayList<>()).add(l);
            }
        }

        /** The member's hours on the task logged on or before the day. */
        double loggedOnTaskBy(TaskFacts t, LocalDate day) {
            double logged = 0;
            for (TimeLogRow l : logsByTask.getOrDefault(t.id(), List.of())) {
                if (!l.day().isAfter(day)) {
                    logged += l.hours();
                }
            }
            return logged;
        }

        /** Estimate minus the hours logged on or before the day, never negative. */
        double remainingAsOf(TaskFacts t, LocalDate day) {
            return Math.max(0.0, t.estimate() - loggedOnTaskBy(t, day));
        }

        double dueHours(LocalDate w, LocalDate target) {
            LocalDate end = w.plusDays(6);
            LocalDate targetEnd = target.plusDays(6);
            double sum = 0;
            for (TaskFacts t : tasks) {
                LocalDate due = t.task().dueDate();
                if (due != null && t.openAtEndOf(end) && !due.isBefore(target) && !due.isAfter(targetEnd)) {
                    sum += remainingAsOf(t, end);
                }
            }
            return sum;
        }

        /**
         * Estimated hours of the member's tasks open at the end of week {@code w} and planned for the week of
         * {@code target}. {@code planned_week} is one of the five fields read as they stand on the run day
         * (ruling G of 2026-09-16, {@link Features}): {@code Truncation} does not rewind it, so this column
         * carries the hindsight of a plan a leader may have set after week {@code w} ended.
         */
        double plannedHours(LocalDate w, LocalDate target) {
            LocalDate end = w.plusDays(6);
            double sum = 0;
            for (TaskFacts t : tasks) {
                LocalDate pw = t.task().plannedWeek();
                if (pw != null && Weeks.mondayOf(pw).equals(target) && t.openAtEndOf(end)) {
                    sum += t.estimate();
                }
            }
            return sum;
        }
    }

    private void throughput(double[] r, Map<String, Integer> col, MemberContext mc, LocalDate w, double[] f, int start, int i) {
        for (int k = 1; k <= 4; k++) {
            int j = i - (k - 1);
            r[col.get("arrival_hrs_lag" + k)] = j >= start ? f[j] : Double.NaN;
        }
        LocalDate end = w.plusDays(6);
        int open = 0;
        int overdue = 0;
        int running = 0;
        double remaining = 0;
        for (TaskFacts t : mc.tasks) {
            if (!t.openAtEndOf(end)) {
                continue;
            }
            open++;
            remaining += mc.remainingAsOf(t, end);
            if (t.task().dueDate() != null && !t.task().dueDate().isAfter(end)) {
                overdue++;
            }
            if (t.inProgressAtEndOf(end)) {
                running++;
            }
        }
        r[col.get("open_tasks")] = open;
        r[col.get("open_remaining_hrs")] = remaining;
        r[col.get("overdue_open")] = overdue;
        r[col.get("in_progress_tasks")] = running;
    }

    /** Per-team, per-week values shared by every member of the team. */
    static final class TeamContext {
        private final ForecastData data;
        private final Map<UUID, Map<LocalDate, double[]>> cache = new HashMap<>();
        private final Map<UUID, List<TaskFacts>> tasksByProject = new HashMap<>();
        private final Map<UUID, Map<UUID, LocalDate>> heldFromByTeam = new HashMap<>();

        TeamContext(ForecastData data, Lifecycle lc) {
            this.data = data;
            for (TaskFacts f : lc.all()) {
                if (f.task().projectId() != null) {
                    tasksByProject.computeIfAbsent(f.task().projectId(), k -> new ArrayList<>()).add(f);
                }
            }
        }

        /**
         * When each project became the team's: the earliest day one of its members was assigned a task in it.
         * Computed once per team — walking every task once per (team, week) instead costs a full scan of the
         * task table for every week of the history, which a real export cannot afford.
         */
        private Map<UUID, LocalDate> heldFrom(UUID team) {
            return heldFromByTeam.computeIfAbsent(team, k -> {
                Set<UUID> memberIds = new HashSet<>();
                data.membersOfTeam(k).forEach(m -> memberIds.add(m.id()));
                Map<UUID, LocalDate> out = new HashMap<>();
                for (Map.Entry<UUID, List<TaskFacts>> e : tasksByProject.entrySet()) {
                    for (TaskFacts t : e.getValue()) {
                        if (t.assignee() != null && memberIds.contains(t.assignee()) && t.isAssigned()) {
                            out.merge(e.getKey(), t.assignedDay(), (a, b) -> a.isBefore(b) ? a : b);
                        }
                    }
                }
                return out;
            });
        }

        /**
         * The projects the team holds by the end of {@code end}: those one of its members had been assigned a
         * task in on or before that day. Bounded by the week under construction, not by the whole history,
         * because a project the team only picks up later must not reach a row built before it did.
         */
        private Set<UUID> projectsOf(UUID team, LocalDate end) {
            Set<UUID> out = new TreeSet<>(Ids.UUID_ORDER);
            heldFrom(team).forEach((project, since) -> {
                if (!since.isAfter(end)) {
                    out.add(project);
                }
            });
            return out;
        }

        void fill(double[] r, Map<String, Integer> col, UUID team, LocalDate w) {
            double[] v = cache.computeIfAbsent(team, k -> new HashMap<>()).computeIfAbsent(w, k -> compute(team, w));
            r[col.get("team_backlog_unassigned_hrs")] = v[0];
            r[col.get("proj_active")] = v[1];
            r[col.get("proj_planning")] = v[2];
        }

        private double[] compute(UUID team, LocalDate w) {
            LocalDate end = w.plusDays(6);
            double backlog = 0;
            int active = 0;
            int planning = 0;
            Map<UUID, ProjectRow> projects = data.projectById();
            for (UUID pid : projectsOf(team, end)) {
                ProjectRow p = projects.get(pid);
                boolean hasWork = false;
                for (TaskFacts t : tasksByProject.getOrDefault(pid, List.of())) {
                    if (t.task().createdDate().toLocalDate().isAfter(end)) {
                        continue;
                    }
                    boolean finishedByEnd = t.finished() != null && !t.finished().toLocalDate().isAfter(end);
                    boolean assignedByEnd = t.isAssigned() && !t.assigned().toLocalDate().isAfter(end);
                    if (!finishedByEnd && !assignedByEnd) {
                        backlog += t.estimate();
                        hasWork = true;
                    }
                    if (t.openAtEndOf(end)) {
                        hasWork = true;
                    }
                }
                if ("ACTIVE".equals(p.status()) && hasWork) {
                    active++;
                }
                if ("PLANNING".equals(p.status())) {
                    planning++;
                }
            }
            return new double[] {backlog, active, planning};
        }
    }
}
