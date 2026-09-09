package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class WorkQueueTest {

    static final SeedConfig CFG = new SeedConfig(16, LocalDate.of(2026, 9, 6), 1, false, 0);

    record World(List<Person> people, Map<UUID, AbsencePlanner.Plan> plans, List<Team> teams, List<Project> projects,
            Rhythm rhythm, Reference ref, SeedCalendar cal) {
    }

    static World world(long seed) {
        SeedRandom rnd = new SeedRandom(seed);
        SeedCalendar cal = AbsencePlannerTest.cal();
        UUID lead = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID e1 = UUID.fromString("30000000-0000-0000-0000-000000000002");
        UUID e2 = UUID.fromString("30000000-0000-0000-0000-000000000003");
        List<Person> people = List.of(
                new Person(lead, "Lead One", "l@example.test", "Team Leader Calibration", "PTE / CT2", "CT2", null, "TEAM_LEADER", WorkFamily.CALIBRATION, CFG.firstMonday(), null),
                new Person(e1, "Eng Two", "a@example.test", "Calibration Engineer", "PTE / CT2", "CT2", lead, "MEMBER", WorkFamily.CALIBRATION, CFG.firstMonday(), null),
                new Person(e2, "Eng Three", "b@example.test", "Data Analyst & SW Developer", "PTE / CT2", "CT2", lead, "MEMBER", WorkFamily.DATA, CFG.firstMonday().plusWeeks(3), null));
        Map<UUID, Person> byId = new HashMap<>();
        Map<UUID, AbsencePlanner.Plan> plans = new HashMap<>();
        for (Person p : people) {
            byId.put(p.id(), p);
            plans.put(p.id(), AbsencePlanner.plan(p, cal, CFG, rnd));
        }
        Team dept = new Team(UUID.fromString("40000000-0000-0000-0000-000000000001"), "PTE / CT2", lead, null, List.of(lead), true, "CT2");
        Team team = new Team(UUID.fromString("40000000-0000-0000-0000-000000000002"), "CT2 · Lead One", lead, dept.id(), List.of(lead, e1, e2), false, "CT2");
        List<Team> teams = List.of(dept, team);
        List<Project> projects = ProjectPlanner.plan(teams, byId, List.of(), CFG, rnd);
        Rhythm rhythm = new Rhythm(CFG, cal, byId, plans, teams, rnd);
        return new World(people, plans, teams, projects, rhythm, reference(), cal);
    }

    static Reference reference() {
        Map<String, UUID> statuses = new HashMap<>();
        for (String s : List.of("Open", "To Do", "In Progress", "In Review", "Testing", "Done", "Closed", "Blocked", "On Hold")) {
            statuses.put(s, UUID.nameUUIDFromBytes(("status:" + s).getBytes()));
        }
        Map<String, UUID> types = new HashMap<>();
        for (String t : List.of("Story", "Bug", "Task", "Epic", "Improvement", "New Feature", "Change Request", "Incident", "Risk", "Spike", "Test", "Sub-task")) {
            types.put(t, UUID.nameUUIDFromBytes(("type:" + t).getBytes()));
        }
        return new Reference(statuses, types);
    }

    static WorkQueue.Result run(long seed, WorkQueue.Rates rates) {
        World w = world(seed);
        return WorkQueue.run(CFG, w.cal(), w.people(), w.plans(), w.teams(), w.projects(), w.rhythm(), w.ref(), rates, new SeedRandom(seed + 1));
    }

    static LocalDateTime ts(Object v) {
        return v == null ? null : LocalDateTime.parse((String) v);
    }

    @Property(tries = 15)
    boolean lifecycleInvariantsHold(@ForAll @LongRange(min = 1, max = 5000) long seed) {
        World w = world(seed);
        WorkQueue.Result r = WorkQueue.run(CFG, w.cal(), w.people(), w.plans(), w.teams(), w.projects(), w.rhythm(), w.ref(), WorkQueue.Rates.DEFAULT, new SeedRandom(seed + 1));
        Map<UUID, Person> byId = new HashMap<>();
        w.people().forEach(p -> byId.put(p.id(), p));
        Map<String, List<LinkedHashMap<String, Object>>> logsByTask = new HashMap<>();
        for (var log : r.timeLogRows()) {
            logsByTask.computeIfAbsent((String) log.get("task_id"), k -> new ArrayList<>()).add(log);
        }
        Map<String, List<LinkedHashMap<String, Object>>> historyByTask = new HashMap<>();
        for (var h : r.historyRows()) {
            historyByTask.computeIfAbsent((String) h.get("task_id"), k -> new ArrayList<>()).add(h);
        }
        UUID done = w.ref().statusIds().get("Done");
        for (var t : r.taskRows()) {
            String id = (String) t.get("id");
            LocalDateTime created = ts(t.get("created_date"));
            LocalDateTime started = ts(t.get("started_date"));
            LocalDateTime finished = ts(t.get("finished_date"));
            List<LinkedHashMap<String, Object>> logs = logsByTask.getOrDefault(id, List.of());
            List<LinkedHashMap<String, Object>> history = historyByTask.getOrDefault(id, List.of());
            String assignee = (String) t.get("assignee_id");
            // 2. order of events
            LocalDateTime assigned = history.stream().filter(h -> "assignee".equals(h.get("field_name")))
                    .map(h -> ts(h.get("changed_at"))).max(LocalDateTime::compareTo).orElse(created);
            if (created.isAfter(assigned)) return false;
            if (started != null && assigned.isAfter(started)) return false;
            // a task starts 0 to 3 present working days after its assignment
            if (started != null && assignee != null) {
                Person assigneePerson = byId.get(UUID.fromString(assignee));
                int presentBetween = 0;
                for (LocalDate d = assigned.toLocalDate().plusDays(1); d.isBefore(started.toLocalDate()); d = d.plusDays(1)) {
                    if (w.plans().get(assigneePerson.id()).hoursPresent(assigneePerson, d) > 0) {
                        presentBetween++;
                    }
                }
                if (presentBetween > 3) return false;
            }
            double logged = 0;
            for (var log : logs) {
                LocalDate day = LocalDate.parse((String) log.get("log_date"));
                if (started != null && day.isBefore(started.toLocalDate())) return false;
                if (finished != null && day.isAfter(finished.toLocalDate())) return false;
                // 3. no log on a day the assignee is absent, on a weekend or a holiday
                Person p = byId.get(UUID.fromString((String) log.get("user_id")));
                if (w.plans().get(p.id()).hoursPresent(p, day) == 0) return false;
                if (!log.get("user_id").equals(assignee)) return false;
                logged += (Double) log.get("hours");
            }
            // 4. remaining
            double estimate = t.get("original_estimate_hrs") == null ? 0 : (Double) t.get("original_estimate_hrs");
            Double remaining = (Double) t.get("remaining_estimate_hrs");
            boolean isDone = done.toString().equals(t.get("task_status_id"));
            if (isDone && (remaining == null || remaining != 0.0)) return false;
            if (!logs.isEmpty() && !isDone && Math.abs(remaining - Math.max(0, estimate - logged)) > 1e-6) return false;
            if (isDone && finished == null) return false;
            // 5. assignee transitions end at the current assignee, written as the display name
            for (var h : history) {
                if ("assignee".equals(h.get("field_name")) && h.get("new_value") != null) {
                    Person p = byId.get(UUID.fromString(assignee));
                    if (!p.fullName().equals(h.get("new_value"))) return false;
                }
                LocalDateTime changed = ts(h.get("changed_at"));
                if (changed.isBefore(created)) return false;
            }
            // status history is ordered
            List<LocalDateTime> statusTimes = history.stream().filter(h -> "status".equals(h.get("field_name"))).map(h -> ts(h.get("changed_at"))).toList();
            for (int i = 1; i < statusTimes.size(); i++) {
                if (statusTimes.get(i).isBefore(statusTimes.get(i - 1))) return false;
            }
        }
        // keys unique and numbered per project
        long keys = r.taskRows().stream().map(t -> t.get("key")).distinct().count();
        return keys == r.taskRows().size();
    }

    @Test
    void membersOfAnExportTeamAlsoSeeThatTeamsProjects() {
        // e1 belongs to both the ordinary manager team (via Rhythm.teamOf, its "primary" team) and an
        // export-style team (no department, no parent) that owns one project of its own; before the fix
        // planArrivals only ever considered the primary team's candidates, so e1's export-owned project
        // never received anything but its epics.
        long seed = 21;
        SeedRandom rnd = new SeedRandom(seed);
        SeedCalendar cal = AbsencePlannerTest.cal();
        UUID lead = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID e1 = UUID.fromString("30000000-0000-0000-0000-000000000002");
        List<Person> people = List.of(
                new Person(lead, "Lead One", "l@example.test", "Team Leader Calibration", "PTE / CT2", "CT2", null, "TEAM_LEADER", WorkFamily.CALIBRATION, CFG.firstMonday(), null),
                new Person(e1, "Eng Two", "a@example.test", "Calibration Engineer", "PTE / CT2", "CT2", lead, "MEMBER", WorkFamily.CALIBRATION, CFG.firstMonday(), null));
        Map<UUID, Person> byId = new HashMap<>();
        Map<UUID, AbsencePlanner.Plan> plans = new HashMap<>();
        for (Person p : people) {
            byId.put(p.id(), p);
            plans.put(p.id(), AbsencePlanner.plan(p, cal, CFG, rnd));
        }
        Team dept = new Team(UUID.fromString("40000000-0000-0000-0000-000000000001"), "PTE / CT2", lead, null, List.of(lead), true, "CT2");
        Team team = new Team(UUID.fromString("40000000-0000-0000-0000-000000000002"), "CT2 · Lead One", lead, dept.id(), List.of(lead, e1), false, "CT2");
        Team exportTeam = new Team(UUID.fromString("40000000-0000-0000-0000-000000000003"), "Legacy Squad", null, null, List.of(e1), false, null);
        List<Team> teams = List.of(dept, team, exportTeam);
        UUID exportProjectId = UUID.fromString("80000000-0000-0000-0000-000000000099");
        Project exportProject = new Project(exportProjectId, "LEG", "Legacy platform", exportTeam.id(), lead, "ACTIVE",
                CFG.firstMonday(), CFG.lastDay().plusWeeks(1), true, WorkFamily.UNKNOWN);
        List<Project> projects = new ArrayList<>();
        projects.add(exportProject);
        projects.addAll(ProjectPlanner.plan(teams, byId, List.of(), CFG, rnd));
        Rhythm rhythm = new Rhythm(CFG, cal, byId, plans, teams, rnd);
        WorkQueue.Result r = WorkQueue.run(CFG, cal, people, plans, teams, projects, rhythm, reference(),
                WorkQueue.Rates.DEFAULT, new SeedRandom(seed + 1));
        UUID epicType = reference().typeIds().get("Epic");
        boolean landed = r.taskRows().stream().anyMatch(t -> exportProjectId.toString().equals(t.get("project_id"))
                && !epicType.toString().equals(t.get("task_type_id")));
        assertTrue(landed, "at least one non-epic task of the export team's member lands in its own project");
    }

    @Test
    void backlogTasksCarryOneAssigneeTransitionAndOthersNone() {
        WorkQueue.Result r = run(3, new WorkQueue.Rates(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        Map<String, Long> assigneeRows = new HashMap<>();
        r.historyRows().stream().filter(h -> "assignee".equals(h.get("field_name")))
                .forEach(h -> assigneeRows.merge((String) h.get("task_id"), 1L, Long::sum));
        UUID epicType = reference().typeIds().get("Epic");
        long epics = r.taskRows().stream().filter(t -> epicType.toString().equals(t.get("task_type_id"))).count();
        long withRow = r.taskRows().stream().filter(t -> assigneeRows.containsKey(t.get("id"))).count();
        // every non-epic task of a member with selfPicked share is either self-picked (no row) or backlog (one row)
        assertTrue(withRow > 0);
        assertTrue(r.taskRows().stream().filter(t -> assigneeRows.containsKey(t.get("id")))
                .allMatch(t -> assigneeRows.get(t.get("id")) == 1L && ts(t.get("created_date")).isBefore(ts(historyOf(r, t).get("changed_at")))));
        assertTrue(epics >= 1, "epics created per project");
    }

    static LinkedHashMap<String, Object> historyOf(WorkQueue.Result r, LinkedHashMap<String, Object> task) {
        return r.historyRows().stream().filter(h -> "assignee".equals(h.get("field_name")) && task.get("id").equals(h.get("task_id"))).findFirst().orElseThrow();
    }

    @Test
    void unloggedTasksFinishWithoutLogsAndZeroRemaining() {
        WorkQueue.Result r = run(4, new WorkQueue.Rates(0.6, 0.25, 0.0, 0.0, 0.0, 0.0, 1.0));
        UUID done = reference().statusIds().get("Done");
        assertTrue(r.timeLogRows().isEmpty(), "no logs at all when every task is unlogged");
        long finished = r.taskRows().stream().filter(t -> done.toString().equals(t.get("task_status_id"))).count();
        assertTrue(finished > 0);
        r.taskRows().stream().filter(t -> done.toString().equals(t.get("task_status_id"))).forEach(t -> {
            assertEquals(0.0, t.get("remaining_estimate_hrs"));
            assertNotNull(t.get("finished_date"));
        });
    }

    @Test
    void reopenedTasksAreMarkedAndFinishTwice() {
        WorkQueue.Result r = run(5, new WorkQueue.Rates(0.6, 0.25, 0.0, 0.0, 0.0, 1.0, 0.0));
        List<LinkedHashMap<String, Object>> reopened = r.taskRows().stream().filter(t -> Boolean.TRUE.equals(t.get("reopened_from_done"))).toList();
        assertFalse(reopened.isEmpty());
        UUID done = reference().statusIds().get("Done");
        for (var t : reopened) {
            assertNotNull(t.get("last_reopened_at"));
            long doneTransitions = r.historyRows().stream().filter(h -> t.get("id").equals(h.get("task_id"))
                    && "status".equals(h.get("field_name")) && "Done".equals(h.get("new_value"))).count();
            assertTrue(doneTransitions >= 1);
            if (done.toString().equals(t.get("task_status_id"))) {
                assertEquals(2, doneTransitions, "finished twice");
            }
        }
    }

    @Test
    void openWorkRemainsAtTheEndAndAssignedHoursMatchEstimates() {
        WorkQueue.Result r = run(6, WorkQueue.Rates.DEFAULT);
        UUID done = reference().statusIds().get("Done");
        long open = r.taskRows().stream().filter(t -> !done.toString().equals(t.get("task_status_id")) && t.get("assignee_id") != null).count();
        assertTrue(open > 0, "tasks still open at the as-of date");
        assertTrue(r.taskRows().stream().anyMatch(t -> t.get("assignee_id") == null), "backlog left unassigned at the end");
        double fromRows = 0;
        for (var t : r.taskRows()) {
            if (t.get("assignee_id") != null && t.get("original_estimate_hrs") != null) {
                fromRows += (Double) t.get("original_estimate_hrs");
            }
        }
        double fromMap = r.assignedHours().values().stream().flatMap(m -> m.values().stream()).mapToDouble(Double::doubleValue).sum();
        assertEquals(fromRows, fromMap, 1e-6);
        assertNull(r.taskRows().stream().filter(t -> t.get("original_estimate_hrs") == null).findFirst().orElseThrow().get("remaining_estimate_hrs"), "epics carry no estimate");
    }

    /** The `changed_at` of the task's assignee history row (backlog), else its `created_date` (self/leader/epic). */
    static LocalDateTime assignedTimestamp(WorkQueue.Result r, LinkedHashMap<String, Object> t) {
        return r.historyRows().stream()
                .filter(h -> "assignee".equals(h.get("field_name")) && t.get("id").equals(h.get("task_id")))
                .map(h -> ts(h.get("changed_at"))).max(LocalDateTime::compareTo).orElse(ts(t.get("created_date")));
    }

    @Test
    void defaultRatesCoverAllModesSubTasksAndDataIntegrity() {
        long seed = 7;
        World w = world(seed);
        WorkQueue.Result r = WorkQueue.run(CFG, w.cal(), w.people(), w.plans(), w.teams(), w.projects(), w.rhythm(), w.ref(),
                WorkQueue.Rates.DEFAULT, new SeedRandom(seed + 1));
        Map<UUID, Person> byId = new HashMap<>();
        w.people().forEach(p -> byId.put(p.id(), p));

        // (a) all three creation modes occur
        Set<String> withAssigneeHistory = new HashSet<>();
        r.historyRows().stream().filter(h -> "assignee".equals(h.get("field_name")))
                .forEach(h -> withAssigneeHistory.add((String) h.get("task_id")));
        boolean backlogSeen = false;
        boolean selfSeen = false;
        boolean leaderSeen = false;
        for (var t : r.taskRows()) {
            Object assignee = t.get("assignee_id");
            if (assignee == null) {
                continue;
            }
            if (withAssigneeHistory.contains(t.get("id"))) {
                backlogSeen = true;
            } else if (assignee.equals(t.get("reporter_id"))) {
                selfSeen = true;
            } else {
                leaderSeen = true;
            }
        }
        assertTrue(backlogSeen, "backlog mode (assignee history row) occurs");
        assertTrue(selfSeen, "self mode (reporter == assignee, no history row) occurs");
        assertTrue(leaderSeen, "leader mode (reporter != assignee, no history row) occurs");

        // (b) the sub-task path: a Sub-task under an Epic in the same project
        UUID subTaskType = w.ref().type("Sub-task");
        UUID epicType = w.ref().type("Epic");
        Map<String, LinkedHashMap<String, Object>> byTaskId = new HashMap<>();
        for (var t : r.taskRows()) {
            byTaskId.put((String) t.get("id"), t);
        }
        boolean subTaskFound = r.taskRows().stream().anyMatch(t -> {
            Object parentId = t.get("parent_task_id");
            if (parentId == null || !subTaskType.toString().equals(t.get("task_type_id"))) {
                return false;
            }
            var parent = byTaskId.get(parentId);
            return parent != null && epicType.toString().equals(parent.get("task_type_id"))
                    && parent.get("project_id").equals(t.get("project_id"));
        });
        assertTrue(subTaskFound, "at least one sub-task under an epic of the same project");

        // (c) planned_week equals mondayOf(assignment day) when assigned, null when not
        for (var t : r.taskRows()) {
            Object plannedWeek = t.get("planned_week");
            if (t.get("assignee_id") == null) {
                assertNull(plannedWeek, "unassigned rows carry no planned week: " + t.get("id"));
            } else {
                LocalDateTime assigned = assignedTimestamp(r, t);
                assertEquals(SeedConfig.mondayOf(assigned.toLocalDate()).toString(), plannedWeek, "planned week for " + t.get("id"));
            }
        }

        // (d) nextTaskNumber per project equals the max task_number of that project's rows, plus one
        Map<String, Long> maxByProject = new HashMap<>();
        for (var t : r.taskRows()) {
            maxByProject.merge((String) t.get("project_id"), (Long) t.get("task_number"), Long::max);
        }
        for (var e : maxByProject.entrySet()) {
            UUID projectId = UUID.fromString(e.getKey());
            assertEquals(e.getValue() + 1, r.nextTaskNumber().get(projectId), "next task number for project " + e.getKey());
        }

        // (e) per member and day, logged hours never exceed presence
        Map<String, Double> perMemberDay = new HashMap<>();
        for (var log : r.timeLogRows()) {
            perMemberDay.merge(log.get("user_id") + "|" + log.get("log_date"), (Double) log.get("hours"), Double::sum);
        }
        for (var e : perMemberDay.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            Person p = byId.get(UUID.fromString(parts[0]));
            LocalDate day = LocalDate.parse(parts[1]);
            assertTrue(e.getValue() <= w.plans().get(p.id()).hoursPresent(p, day) + 1e-9,
                    "logged hours exceed presence for " + e.getKey());
        }

        // (f) determinism: an independently rebuilt world from the same seed (Rhythm owns a mutable
        // SeedRandom of its own, consumed by arrivals()/estimate() as a run proceeds, so reusing `w`'s
        // already-run rhythm would not be equal inputs) plus a fresh, equally-seeded SeedRandom gives
        // equal rows.
        World w2 = world(seed);
        WorkQueue.Result r2 = WorkQueue.run(CFG, w2.cal(), w2.people(), w2.plans(), w2.teams(), w2.projects(), w2.rhythm(), w2.ref(),
                WorkQueue.Rates.DEFAULT, new SeedRandom(seed + 1));
        assertEquals(r.taskRows(), r2.taskRows());
        assertEquals(r.historyRows(), r2.historyRows());
        assertEquals(r.timeLogRows(), r2.timeLogRows());

        // (g) every column of the export, in the export's order
        List<String> taskColumns = List.of("id", "key", "title", "version", "archived", "due_date", "priority",
                "created_at", "project_id", "updated_at", "archived_at", "assignee_id", "description", "reporter_id",
                "task_number", "created_date", "planned_week", "started_date", "task_type_id", "finished_date",
                "parent_task_id", "task_status_id", "last_reopened_at", "reopened_from_done", "original_estimate_hrs",
                "remaining_estimate_hrs");
        List<String> historyColumns = List.of("id", "task_id", "user_id", "new_value", "old_value", "changed_at",
                "created_at", "field_name", "updated_at");
        List<String> timeLogColumns = List.of("id", "note", "hours", "task_id", "user_id", "log_date", "created_at", "updated_at");
        assertEquals(taskColumns, new ArrayList<>(r.taskRows().get(0).keySet()));
        assertEquals(historyColumns, new ArrayList<>(r.historyRows().get(0).keySet()));
        assertEquals(timeLogColumns, new ArrayList<>(r.timeLogRows().get(0).keySet()));
    }
}
