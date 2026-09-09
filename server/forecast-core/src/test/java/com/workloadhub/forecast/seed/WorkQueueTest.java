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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    void backlogTasksCarryOneAssigneeTransitionAndOthersNone() {
        WorkQueue.Result r = run(3, new WorkQueue.Rates(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        Map<String, Long> assigneeRows = new HashMap<>();
        r.historyRows().stream().filter(h -> "assignee".equals(h.get("field_name")))
                .forEach(h -> assigneeRows.merge((String) h.get("task_id"), 1L, Long::sum));
        long epics = r.taskRows().stream().filter(t -> t.get("parent_task_id") == null && t.get("original_estimate_hrs") == null).count();
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
}
