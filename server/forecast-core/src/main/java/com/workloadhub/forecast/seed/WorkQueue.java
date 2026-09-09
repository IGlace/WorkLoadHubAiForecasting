package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Simulates each counted member day by day: tasks arrive per the rhythm, at most three are worked at
 * once, hours are logged on present days, and the rows are written the way WorkloadHub writes them.
 */
public final class WorkQueue {

    public record Rates(double backlog, double leaderAssigned, double subTask, double review, double blocked,
            double reopen, double unlogged) {
        public static final Rates DEFAULT = new Rates(0.60, 0.25, 0.15, 0.10, 0.03, 0.04, 0.05);
    }

    public record Result(List<LinkedHashMap<String, Object>> taskRows, List<LinkedHashMap<String, Object>> historyRows,
            List<LinkedHashMap<String, Object>> timeLogRows, Map<UUID, Long> nextTaskNumber,
            Map<UUID, Map<LocalDate, Double>> assignedHours) {
    }

    private static final int MAX_ACTIVE = 3;
    private static final int FUTURE_WEEKS = 3;
    private static final double HOURS_PER_DAY_PER_TASK = 8.0 / 2.5;

    /** One task moving through the queue. */
    private static final class Work {
        final LinkedHashMap<String, Object> row;
        final UUID id;
        final double estimate;
        final LocalDateTime assignedAt;
        double actual;
        double logged;
        boolean started;
        boolean unlogged;
        int unloggedDaysLeft;
        int blockedLeft;
        boolean wasBlocked;
        int reviewLeft;
        boolean inReview;
        LocalDate reopenOn;
        boolean reopened;
        String status;

        Work(LinkedHashMap<String, Object> row, double estimate, double actual, LocalDateTime assignedAt) {
            this.row = row;
            this.id = UUID.fromString((String) row.get("id"));
            this.estimate = estimate;
            this.actual = actual;
            this.assignedAt = assignedAt;
            this.status = "To Do";
        }

        boolean finished() {
            return logged >= actual - 1e-9;
        }
    }

    /** A task decided in advance for one week. */
    private record Arrival(LocalDate assignDay, LocalDateTime assignAt, double estimate, String typeName, String family,
            String priority, String mode, Project project) {
    }

    private final SeedConfig cfg;
    private final SeedCalendar cal;
    private final Map<UUID, AbsencePlanner.Plan> plans;
    private final Rhythm rhythm;
    private final Reference ref;
    private final Rates rates;
    private final SeedRandom rnd;
    private final Map<UUID, Person> people = new HashMap<>();
    private final Map<UUID, Long> nextNumber = new HashMap<>();
    private final Map<UUID, List<UUID>> epicsByProject = new HashMap<>();
    private final List<LinkedHashMap<String, Object>> taskRows = new ArrayList<>();
    private final List<LinkedHashMap<String, Object>> historyRows = new ArrayList<>();
    private final List<LinkedHashMap<String, Object>> timeLogRows = new ArrayList<>();
    private final Map<UUID, Map<LocalDate, Double>> assignedHours = new HashMap<>();
    private final List<Team> teams;
    private final List<Project> projects;

    private WorkQueue(SeedConfig cfg, SeedCalendar cal, List<Person> personList, Map<UUID, AbsencePlanner.Plan> plans,
            List<Team> teams, List<Project> projects, Rhythm rhythm, Reference ref, Rates rates, SeedRandom rnd) {
        this.cfg = cfg;
        this.cal = cal;
        this.plans = plans;
        this.teams = teams;
        this.projects = projects;
        this.rhythm = rhythm;
        this.ref = ref;
        this.rates = rates;
        this.rnd = rnd;
        for (Person p : personList) {
            people.put(p.id(), p);
        }
        for (Project p : projects) {
            nextNumber.put(p.id(), 1L);
        }
    }

    public static Result run(SeedConfig cfg, SeedCalendar cal, List<Person> people, Map<UUID, AbsencePlanner.Plan> plans,
            List<Team> teams, List<Project> projects, Rhythm rhythm, Reference ref, Rates rates, SeedRandom rnd) {
        WorkQueue q = new WorkQueue(cfg, cal, people, plans, teams, projects, rhythm, ref, rates, rnd);
        q.createEpics();
        List<Person> counted = new ArrayList<>(people);
        counted.removeIf(p -> !p.counted());
        counted.sort(Comparator.comparing(p -> p.id().toString()));
        for (Person p : counted) {
            q.simulate(p);
        }
        return new Result(q.taskRows, q.historyRows, q.timeLogRows, q.nextNumber, q.assignedHours);
    }

    private void createEpics() {
        List<Project> sorted = new ArrayList<>(projects);
        sorted.sort(Comparator.comparing(Project::key));
        for (Project project : sorted) {
            if (!project.status().equals("ACTIVE") || project.ownerId() == null || !people.containsKey(project.ownerId())) {
                continue;
            }
            int n = rnd.between(1, 3);
            List<UUID> epics = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                LocalDate day = nextWorkingDay(project.windowStart());
                LocalDateTime at = rnd.at(day, 9, 11);
                long number = nextNumber.merge(project.id(), 1L, Long::sum) - 1;
                UUID id = rnd.uuid();
                LinkedHashMap<String, Object> row = Rows.task(id, project.key() + "-" + number, project.name() + " epic " + i,
                        "Container for the work of " + project.name(), null, "MEDIUM", project.id(), project.ownerId(),
                        project.ownerId(), number, at, SeedConfig.mondayOf(day), ref.type("Epic"), null, ref.status("In Progress"), null);
                row.put("started_date", at.toString());
                taskRows.add(row);
                epics.add(id);
            }
            epicsByProject.put(project.id(), epics);
        }
    }

    private LocalDate nextWorkingDay(LocalDate d) {
        LocalDate x = d;
        while (!cal.isWorkingDay(x)) {
            x = x.plusDays(1);
        }
        return x;
    }

    private List<Arrival> planArrivals(Person p, Team team) {
        List<Project> candidates = ProjectPlanner.projectsFor(team, teams, projects);
        List<Arrival> out = new ArrayList<>();
        AbsencePlanner.Plan plan = plans.get(p.id());
        List<LocalDate> mondays = new ArrayList<>(cfg.mondays());
        for (int i = 1; i <= FUTURE_WEEKS; i++) {
            mondays.add(SeedConfig.mondayOf(cfg.lastDay()).plusWeeks(i));
        }
        for (LocalDate monday : mondays) {
            boolean future = monday.isAfter(cfg.lastDay());
            int n = rhythm.arrivals(p, monday);
            if (n == 0) {
                continue;
            }
            List<LocalDate> present = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                LocalDate d = monday.plusDays(i);
                boolean ok = future ? cal.isWorkingDay(d) : plan.hoursPresent(p, d) > 0 && !d.isAfter(cfg.lastDay());
                if (ok) {
                    present.add(d);
                }
            }
            if (present.isEmpty()) {
                continue;
            }
            List<Project> active = candidates.stream().filter(pr -> pr.activeOn(monday)).toList();
            if (active.isEmpty()) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                LocalDate day = rnd.pick(present);
                WorkFamily f = p.family();
                String family = rnd.pick(List.of("delivery", "defect", "support"), new double[] {f.delivery + f.container, f.defect, f.support});
                String type = switch (family) {
                    case "defect" -> rnd.pick(List.of("Bug", "Incident"), new double[] {0.8, 0.2});
                    case "support" -> rnd.pick(List.of("Spike", "Test", "Risk"), new double[] {0.4, 0.4, 0.2});
                    default -> rnd.pick(List.of("Story", "Task", "New Feature", "Improvement", "Change Request"), new double[] {0.35, 0.35, 0.1, 0.1, 0.1});
                };
                String priority = family.equals("defect")
                        ? rnd.pick(List.of("HIGHEST", "HIGH", "MEDIUM", "LOW"), new double[] {0.2, 0.4, 0.3, 0.1})
                        : rnd.pick(List.of("HIGHEST", "HIGH", "MEDIUM", "LOW", "LOWEST"), new double[] {0.05, 0.2, 0.5, 0.2, 0.05});
                double nonSelf = 1.0 - f.selfPicked;
                double share = rates.backlog + rates.leaderAssigned;
                double backlog = share == 0 ? 0 : nonSelf * rates.backlog / share;
                double leader = share == 0 ? 0 : nonSelf * rates.leaderAssigned / share;
                String mode = rnd.pick(List.of("backlog", "leader", "self"), new double[] {backlog, leader, f.selfPicked});
                if (future && !mode.equals("backlog")) {
                    continue; // only backlog tasks exist before their assignment week
                }
                out.add(new Arrival(day, rnd.at(day, 9, 11), rhythm.estimate(p), type, family, priority, mode, rnd.pick(active)));
            }
        }
        out.sort(Comparator.comparing(Arrival::assignAt));
        return out;
    }

    private void simulate(Person p) {
        Team team = rhythm.teamOf(p);
        if (team == null) {
            return;
        }
        UUID leader = team.managerId() != null && people.containsKey(team.managerId()) && !team.managerId().equals(p.id())
                ? team.managerId() : p.id();
        AbsencePlanner.Plan plan = plans.get(p.id());
        List<Arrival> arrivals = planArrivals(p, team);
        double ratio = rnd.lognormal(1.0, 0.25); // the member's estimation bias
        Deque<Work> queue = new ArrayDeque<>();
        List<Work> sleeping = new ArrayList<>(); // done, waiting for a possible reopen
        int next = 0;
        for (LocalDate day = cfg.firstMonday(); !day.isAfter(cfg.lastDay()); day = day.plusDays(1)) {
            while (next < arrivals.size() && arrivals.get(next).assignDay().equals(day)) {
                queue.addLast(assign(p, leader, arrivals.get(next), ratio));
                next++;
            }
            boolean working = cal.isWorkingDay(day);
            double hours = plan.hoursPresent(p, day);
            // reopen scheduled tasks
            for (Work w : new ArrayList<>(sleeping)) {
                if (w.reopenOn != null && !day.isBefore(w.reopenOn) && working) {
                    sleeping.remove(w);
                    reopen(p, w, day);
                    queue.addFirst(w);
                }
            }
            if (working) {
                for (Work w : queue) {
                    if (w.blockedLeft > 0) {
                        w.blockedLeft--;
                        if (w.blockedLeft == 0) {
                            transition(p, w, "In Progress", day.atTime(9, 0));
                        }
                    } else if (w.inReview) {
                        w.reviewLeft--;
                        if (w.reviewLeft <= 0) {
                            finish(p, w, day.atTime(16, 0));
                        }
                    } else if (w.unlogged && w.started) {
                        w.unloggedDaysLeft--;
                        if (w.unloggedDaysLeft <= 0) {
                            finish(p, w, day.atTime(16, 30));
                        }
                    }
                }
            }
            if (hours > 0) {
                // unlogged tasks start on the first present day after assignment
                for (Work w : queue) {
                    if (w.unlogged && !w.started) {
                        start(p, w, day.atTime(9, 15));
                    }
                }
                logDay(p, queue, day, hours);
            }
            // move finished tasks out of the queue
            for (Work w : new ArrayList<>(queue)) {
                if ("Done".equals(w.status)) {
                    queue.remove(w);
                    sleeping.add(w);
                }
            }
        }
        // tasks still queued keep their state; remaining reflects the logs
        for (Work w : queue) {
            if (w.row.get("original_estimate_hrs") != null) {
                w.row.put("remaining_estimate_hrs", Math.max(0.0, w.estimate - w.logged));
            }
        }
        // backlog tasks whose assignment week lies beyond the as-of date: created, not yet assigned
        for (; next < arrivals.size(); next++) {
            Arrival a = arrivals.get(next);
            LocalDate created = backlogCreationDay(a.assignDay());
            if (a.mode().equals("backlog") && !created.isAfter(cfg.lastDay())) {
                unassigned(leader, a, rnd.at(created, 9, 17));
            }
        }
    }

    private LocalDate backlogCreationDay(LocalDate assignDay) {
        LocalDate created = assignDay.minusDays(rnd.between(7, 21));
        while (!cal.isWorkingDay(created)) {
            created = created.minusDays(1);
        }
        return created;
    }

    /** A backlog row the leader created and nobody has picked up yet. */
    private void unassigned(UUID leader, Arrival a, LocalDateTime createdAt) {
        Project project = a.project();
        long number = nextNumber.merge(project.id(), 1L, Long::sum) - 1;
        LinkedHashMap<String, Object> row = Rows.task(rnd.uuid(), project.key() + "-" + number, title(a, project),
                "Generated for " + project.name(), null, a.priority(), project.id(), null, leader, number, createdAt,
                null, ref.type(a.typeName()), null, ref.status("To Do"), a.estimate());
        taskRows.add(row);
    }

    private Work assign(Person p, UUID leader, Arrival a, double ratio) {
        Project project = a.project();
        long number = nextNumber.merge(project.id(), 1L, Long::sum) - 1;
        UUID id = rnd.uuid();
        LocalDateTime createdAt = a.assignAt();
        UUID reporter = a.mode().equals("self") ? p.id() : leader;
        boolean backlog = a.mode().equals("backlog");
        if (backlog) {
            createdAt = rnd.at(backlogCreationDay(a.assignDay()), 9, 17);
        }
        String typeName = a.typeName();
        UUID parent = null;
        List<UUID> epics = epicsByProject.get(project.id());
        if (a.family().equals("delivery") && epics != null && !epics.isEmpty() && rnd.chance(rates.subTask())) {
            typeName = "Sub-task";
            parent = rnd.pick(epics);
        }
        double actual = Math.max(0.25, Math.round(a.estimate() * ratio * rnd.lognormal(1.0, 0.2) * 4) / 4.0);
        int cycleDays = (int) Math.ceil(actual / HOURS_PER_DAY_PER_TASK);
        LocalDate due = addWorkingDays(a.assignDay(), (int) Math.ceil(cycleDays * Math.max(0.6, 1.1 + 0.2 * rnd.gaussian())));
        String title = title(a, project);
        LinkedHashMap<String, Object> row = Rows.task(id, project.key() + "-" + number, title, "Generated for " + project.name(),
                due, a.priority(), project.id(), p.id(), reporter, number, createdAt, SeedConfig.mondayOf(a.assignDay()),
                ref.type(typeName), parent, ref.status("To Do"), a.estimate());
        row.put("updated_at", a.assignAt().toString());
        taskRows.add(row);
        if (backlog) {
            historyRows.add(Rows.history(rnd.uuid(), id, leader, "assignee", null, p.fullName(), a.assignAt()));
        }
        assignedHours.computeIfAbsent(p.id(), k -> new TreeMap<>()).merge(SeedConfig.mondayOf(a.assignDay()), a.estimate(), Double::sum);
        Work w = new Work(row, a.estimate(), actual, a.assignAt());
        w.unlogged = rnd.chance(rates.unlogged());
        w.unloggedDaysLeft = Math.max(1, cycleDays);
        if (rnd.chance(rates.reopen())) {
            w.reopened = false;
            w.reopenOn = LocalDate.MIN; // marker: reopen once after the first finish
        }
        return w;
    }

    private String title(Arrival a, Project project) {
        String verb = switch (a.family()) {
            case "defect" -> rnd.pick(List.of("Fix", "Investigate", "Resolve"));
            case "support" -> rnd.pick(List.of("Assess", "Review", "Prepare"));
            default -> rnd.pick(List.of("Implement", "Update", "Deliver", "Analyse"));
        };
        String object = rnd.pick(List.of("dataset", "test bench setup", "signal mapping", "report", "configuration",
                "model variant", "measurement plan", "release candidate", "checklist", "interface spec"));
        return verb + " " + object + " for " + project.name();
    }

    private LocalDate addWorkingDays(LocalDate from, int days) {
        LocalDate d = from;
        int n = 0;
        while (n < days) {
            d = d.plusDays(1);
            if (cal.isWorkingDay(d)) {
                n++;
            }
        }
        return d;
    }

    private void logDay(Person p, Deque<Work> queue, LocalDate day, double hours) {
        List<Work> active = new ArrayList<>();
        for (Work w : queue) {
            if (!w.unlogged && w.blockedLeft == 0 && !w.inReview && !w.finished()) {
                active.add(w);
                if (active.size() == MAX_ACTIVE) {
                    break;
                }
            }
        }
        double left = hours;
        int i = 0;
        while (left >= 0.25 && i < active.size()) {
            Work w = active.get(i);
            double share = Math.round(left / (active.size() - i) * 4) / 4.0;
            double give = Math.min(Math.max(0.25, share), Math.max(0.25, w.actual - w.logged));
            give = Math.min(give, left);
            if (give < 0.25) {
                break;
            }
            if (!w.started) {
                start(p, w, day.atTime(9, 0));
            }
            timeLogRows.add(Rows.timeLog(rnd.uuid(), w.id, p.id(), give, day, "Work on " + w.row.get("key")));
            w.logged += give;
            left -= give;
            w.row.put("remaining_estimate_hrs", Math.max(0.0, w.estimate - w.logged));
            w.row.put("updated_at", day.atTime(17, 30).toString());
            if (w.finished()) {
                if (rnd.chance(rates.review())) {
                    w.inReview = true;
                    w.reviewLeft = rnd.between(1, 2);
                    transition(p, w, "In Review", day.atTime(17, 0));
                } else {
                    finish(p, w, day.atTime(17, 0));
                }
            } else if (!w.wasBlocked && w.logged >= 0.3 * w.actual && rnd.chance(rates.blocked())) {
                w.wasBlocked = true;
                w.blockedLeft = rnd.between(2, 5);
                transition(p, w, "Blocked", day.atTime(17, 0));
            }
            i++;
        }
    }

    /** Starts a task; never before its assignment, which can be later the same morning. */
    private void start(Person p, Work w, LocalDateTime at) {
        LocalDateTime when = at.isBefore(w.assignedAt) ? w.assignedAt.plusMinutes(5) : at;
        w.started = true;
        w.row.put("started_date", when.toString());
        transition(p, w, "In Progress", when);
    }

    private void finish(Person p, Work w, LocalDateTime at) {
        w.inReview = false;
        w.row.put("finished_date", at.toString());
        if (w.row.get("original_estimate_hrs") != null) {
            w.row.put("remaining_estimate_hrs", 0.0);
        }
        transition(p, w, "Done", at);
        if (w.reopenOn != null && !w.reopened) {
            LocalDate on = at.toLocalDate().plusDays(rnd.between(7, 21));
            w.reopenOn = on.isAfter(cfg.lastDay()) ? null : on;
        } else {
            w.reopenOn = null;
        }
    }

    private void reopen(Person p, Work w, LocalDate day) {
        w.reopened = true;
        w.reopenOn = null;
        w.row.put("reopened_from_done", true);
        w.row.put("last_reopened_at", day.atTime(9, 0).toString());
        w.row.put("finished_date", null);
        w.actual += Math.max(0.25, Math.round(w.estimate * rnd.uniform(0.2, 0.4) * 4) / 4.0);
        w.unloggedDaysLeft = Math.max(1, (int) Math.ceil((w.actual - w.logged) / HOURS_PER_DAY_PER_TASK));
        transition(p, w, "In Progress", day.atTime(9, 0));
        w.row.put("remaining_estimate_hrs", Math.max(0.0, w.estimate - w.logged));
    }

    private void transition(Person p, Work w, String to, LocalDateTime at) {
        historyRows.add(Rows.history(rnd.uuid(), w.id, p.id(), "status", w.status, to, at));
        w.status = to;
        w.row.put("task_status_id", ref.status(to).toString());
        w.row.put("updated_at", at.toString());
    }
}
