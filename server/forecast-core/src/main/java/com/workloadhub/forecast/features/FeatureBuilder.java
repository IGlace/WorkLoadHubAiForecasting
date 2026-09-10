package com.workloadhub.forecast.features;

import com.workloadhub.forecast.calendar.Weeks;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.lifecycle.Family;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.Mode;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds the feature matrix of spec section 6 for a set of members up to an origin week. */
public final class FeatureBuilder {

    static final double NEVER_WEEKS = 52.0;
    static final String NO_TITLE = "(none)";

    private final ForecastData data;
    private final Lifecycle lc;
    private final WorkingCalendar cal;
    private final CapacityRule rule;

    public FeatureBuilder(ForecastData data, Lifecycle lc, WorkingCalendar cal, CapacityRule rule) {
        this.data = data;
        this.lc = lc;
        this.cal = cal;
        this.rule = rule;
    }

    public FeatureMatrix build(List<MemberRow> membersIn, LocalDate origin) {
        List<MemberRow> members = membersIn.stream().sorted((a, b) -> a.id().toString().compareTo(b.id().toString())).toList();
        LocalDate firstWeek = origin.minusWeeks(Features.HISTORY_WEEKS - 1);
        List<LocalDate> weeks = Weeks.between(firstWeek, origin);
        WeeklySeries series = WeeklySeries.build(lc, members, weeks);
        Map<String, List<String>> books = codebooks(members);
        List<String> columns = Features.allColumns();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            col.put(columns.get(i), i);
        }
        TeamContext teams = new TeamContext(data, lc, weeks);          // Task 6
        Map<MemberWeek, double[]> availability = new HashMap<>();
        List<MemberWeek> keys = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        int originIndex = weeks.size() - 1;
        for (MemberRow m : members) {
            List<TaskFacts> tasks = lc.assignedTo(m.id());
            MemberContext mc = new MemberContext(m, tasks, data);       // Task 6 uses the log index
            double[] fresh = series.fresh(m.id());
            double[] est = series.est(m.id());
            int start = startIndex(m, tasks, weeks);
            for (int i = start; i <= originIndex; i++) {
                LocalDate w = weeks.get(i);
                if (m.left() != null && !w.isBefore(m.left())) {
                    break;
                }
                double[] r = new double[columns.size()];
                Arrays.fill(r, Double.NaN);
                ownHistory(r, col, fresh, start, i);
                windowStats(r, col, mc, w);
                throughput(r, col, mc, w);                               // Task 6
                teams.fill(r, col, m.primaryTeamId(), w);                // Task 6
                for (int h : Features.HORIZONS) {
                    LocalDate target = w.plusWeeks(h);
                    double[] avail = availability.computeIfAbsent(new MemberWeek(m.id(), target), k -> new double[] {
                            cal.workingDaysInWeek(target), rule.absenceHours(m.id(), target, data, cal), rule.capacity(m, target, data, cal)});
                    r[col.get("working_days_h" + h)] = avail[0];
                    r[col.get("absence_hrs_h" + h)] = avail[1];
                    r[col.get("available_hrs_h" + h)] = avail[2];
                    r[col.get("due_hrs_h" + h)] = mc.dueHours(w, target);   // Task 6
                    r[col.get(Features.target(h))] = i + h <= originIndex ? fresh[i + h] : Double.NaN;
                }
                r[col.get("member_id")] = books.get("member_id").indexOf(m.id().toString());
                r[col.get("team_id")] = books.get("team_id").indexOf(m.primaryTeamId().toString());
                r[col.get("role")] = books.get("role").indexOf(m.role());
                r[col.get("job_title")] = books.get("job_title").indexOf(title(m));
                r[col.get("tenure_weeks")] = Weeks.weeksBetween(m.joined(), w);
                r[col.get("week_of_year")] = Weeks.isoWeek(w);
                r[col.get(Features.FRESH)] = fresh[i];
                r[col.get(Features.EST)] = est[i];
                keys.add(new MemberWeek(m.id(), w));
                rows.add(r);
            }
        }
        return FeatureMatrix.of(columns, keys, rows.toArray(double[][]::new), books);
    }

    private static String title(MemberRow m) {
        return m.jobTitle() == null || m.jobTitle().isBlank() ? NO_TITLE : m.jobTitle();
    }

    private static Map<String, List<String>> codebooks(List<MemberRow> members) {
        Map<String, List<String>> books = new LinkedHashMap<>();
        books.put("member_id", members.stream().map(m -> m.id().toString()).sorted().toList());
        books.put("team_id", members.stream().map(m -> m.primaryTeamId().toString()).distinct().sorted().toList());
        books.put("role", members.stream().map(MemberRow::role).distinct().sorted().toList());
        books.put("job_title", members.stream().map(FeatureBuilder::title).distinct().sorted().toList());
        return books;
    }

    /** The earlier of the join week and the first assignment week, never before the first loaded week. */
    private static int startIndex(MemberRow m, List<TaskFacts> tasks, List<LocalDate> weeks) {
        LocalDate start = Weeks.mondayOf(m.joined());
        if (!tasks.isEmpty() && tasks.get(0).assignedWeek().isBefore(start)) {
            start = tasks.get(0).assignedWeek();
        }
        int idx = weeks.indexOf(start);
        if (idx >= 0) {
            return idx;
        }
        return start.isBefore(weeks.get(0)) ? 0 : weeks.size();
    }

    private static void ownHistory(double[] r, Map<String, Integer> col, double[] f, int start, int i) {
        for (int lag : Features.LAGS) {
            int j = i - (lag - 1);
            r[col.get("lag" + lag)] = j >= start ? f[j] : Double.NaN;
        }
        for (int w : Features.ROLL_WINDOWS) {
            int from = Math.max(start, i - w + 1);
            int n = i - from + 1;
            double sum = 0;
            for (int j = from; j <= i; j++) {
                sum += f[j];
            }
            double mean = sum / n;
            double sq = 0;
            for (int j = from; j <= i; j++) {
                sq += (f[j] - mean) * (f[j] - mean);
            }
            r[col.get("roll_mean_" + w)] = mean;
            r[col.get("roll_std_" + w)] = n >= 2 ? Math.sqrt(sq / (n - 1)) : 0.0;
        }
        double since = NEVER_WEEKS;
        for (int j = i; j >= start; j--) {
            if (f[j] > 0) {
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
        int manual = 0;
        int project = 0;
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
                } else if (t.mode() == Mode.MANUAL) {
                    manual++;
                } else {
                    project++;
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
        r[col.get("share_defect_13w")] = n == 0 ? 0.0 : (double) defect / n;
        r[col.get("share_delivery_13w")] = n == 0 ? 0.0 : (double) delivery / n;
        r[col.get("share_support_13w")] = n == 0 ? 0.0 : (double) support / n;
        r[col.get("share_high_priority_13w")] = n == 0 ? 0.0 : (double) high / n;
        r[col.get("share_self_picked_13w")] = n == 0 ? 1.0 / 3 : (double) self / n;
        r[col.get("share_manual_13w")] = n == 0 ? 1.0 / 3 : (double) manual / n;
        r[col.get("share_project_13w")] = n == 0 ? 1.0 / 3 : (double) project / n;
        r[col.get("reopen_rate_13w")] = finished == 0 ? 0.0 : (double) reopened / finished;
        r[col.get("estimate_ratio_13w")] = estimate == 0 ? 1.0 : actual / estimate;
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
        private final Map<LocalDate, Double> loggedByWeek = new HashMap<>();
        private final Map<UUID, List<TimeLogRow>> logsByTask = new HashMap<>();

        MemberContext(MemberRow m, List<TaskFacts> tasks, ForecastData data) {
            this.tasks = tasks;
            for (TimeLogRow l : data.timeLogs()) {
                if (!l.userId().equals(m.id())) {
                    continue;
                }
                loggedByWeek.merge(Weeks.mondayOf(l.day()), l.hours(), Double::sum);
                logsByTask.computeIfAbsent(l.taskId(), k -> new ArrayList<>()).add(l);
            }
        }

        double loggedInWeek(LocalDate monday) {
            return loggedByWeek.getOrDefault(monday, 0.0);
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
            return Double.NaN;                                                   // Task 6
        }
    }

    // ---- Task 6 fills these in; in Task 5 they leave their columns NaN ----

    private void throughput(double[] r, Map<String, Integer> col, MemberContext mc, LocalDate w) {
    }

    /** Per-team, per-week values shared by every member of the team. */
    static final class TeamContext {
        TeamContext(ForecastData data, Lifecycle lc, List<LocalDate> weeks) {
        }

        void fill(double[] r, Map<String, Integer> col, UUID team, LocalDate w) {
        }
    }
}
