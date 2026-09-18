package com.workloadhub.forecast.tools.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import com.workloadhub.forecast.tools.export.ExportExporter;
import com.workloadhub.forecast.tools.export.ExportFiles;
import com.workloadhub.forecast.tools.export.ExportImporter;
import com.workloadhub.forecast.tools.testing.DatabaseTestSupport;
import com.workloadhub.forecast.tools.export.WorkloadHubSchema;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class SeedGeneratorTest {

    static final SeedConfig CFG = new SeedConfig(26, LocalDate.of(2026, 9, 6), 7, true, 40);

    static ExportEnvelope generated() {
        return SeedGenerator.generate(null, CFG);
    }

    static Set<String> ids(ExportEnvelope env, String table) {
        Set<String> out = new HashSet<>();
        for (var r : env.rows(table)) {
            out.add((String) r.get("id"));
        }
        return out;
    }

    static void assertResolves(ExportEnvelope env, String table, String column, Set<String> targets) {
        for (var r : env.rows(table)) {
            Object v = r.get(column);
            assertTrue(v == null || targets.contains(v), table + "." + column + " -> " + v);
        }
    }

    @Test
    void everyForeignKeyResolvesAndKeysAreUnique() {
        ExportEnvelope env = generated();
        Set<String> users = ids(env, "users");
        Set<String> teams = ids(env, "teams");
        Set<String> projects = ids(env, "projects");
        Set<String> tasks = ids(env, "tasks");
        Set<String> statuses = ids(env, "task_statuses");
        Set<String> types = ids(env, "task_types");
        assertResolves(env, "users", "manager_id", users);
        assertResolves(env, "teams", "manager_id", users);
        assertResolves(env, "teams", "parent_team_id", teams);
        assertResolves(env, "team_members", "team_id", teams);
        assertResolves(env, "team_members", "user_id", users);
        assertResolves(env, "projects", "owner_id", users);
        assertResolves(env, "projects", "team_id", teams);
        assertResolves(env, "tasks", "project_id", projects);
        assertResolves(env, "tasks", "assignee_id", users);
        assertResolves(env, "tasks", "reporter_id", users);
        assertResolves(env, "tasks", "parent_task_id", tasks);
        assertResolves(env, "tasks", "task_status_id", statuses);
        assertResolves(env, "tasks", "task_type_id", types);
        assertResolves(env, "task_history", "task_id", tasks);
        assertResolves(env, "task_history", "user_id", users);
        assertResolves(env, "time_logs", "task_id", tasks);
        assertResolves(env, "time_logs", "user_id", users);
        assertResolves(env, "personal_leaves", "employee_id", users);
        assertEquals(env.rows("tasks").size(), env.rows("tasks").stream().map(t -> t.get("key")).distinct().count());
        Map<String, Long> maxNumber = new HashMap<>();
        for (var t : env.rows("tasks")) {
            maxNumber.merge((String) t.get("project_id"), (Long) t.get("task_number"), Math::max);
        }
        for (var p : env.rows("projects")) {
            long expected = maxNumber.getOrDefault(p.get("id"), 0L) + 1;
            assertEquals(expected, p.get("next_task_number"), "next_task_number of " + p.get("key"));
        }
        assertEquals(WorkloadHubSchema.TABLE_ORDER.size(), env.data().size(), "every table present, empty ones included");
        assertEquals(List.of("refresh_tokens"), env.excludedTables());
    }

    @Test
    void realModeWritesTheFiveWorkTablesAndExcludesTheRest() throws Exception {
        ExportEnvelope input = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        assertEquals(SeedGenerator.REAL_MODE_TABLES, List.copyOf(env.data().keySet()));
        List<String> excluded = new java.util.ArrayList<>(WorkloadHubSchema.TABLE_ORDER);
        excluded.removeAll(SeedGenerator.REAL_MODE_TABLES);
        assertEquals(excluded, env.excludedTables());
        assertFalse(env.rows("tasks").isEmpty());
        Set<String> inputProjects = ids(input, "projects");
        assertTrue(ids(env, "projects").containsAll(inputProjects), "existing projects are re-emitted");
        assertEquals(input.database(), env.database());
    }

    @Test
    void syntheticModeStillWritesEveryTable() {
        ExportEnvelope env = generated();
        assertEquals(WorkloadHubSchema.TABLE_ORDER, List.copyOf(env.data().keySet()));
        assertEquals(List.of("refresh_tokens"), env.excludedTables());
    }

    @Test
    void historyCoversTheConfiguredWeeksWithRealisticVolume() {
        ExportEnvelope env = generated();
        assertEquals(40, env.rows("users").size());
        Set<String> weeks = new HashSet<>();
        for (var r : env.rows("time_logs")) {
            weeks.add(SeedConfig.mondayOf(LocalDate.parse((String) r.get("log_date"))).toString());
        }
        assertTrue(weeks.size() >= CFG.mondays().size() - 2, "logs in nearly every week: " + weeks.size());
        assertTrue(weeks.contains(CFG.firstMonday().toString()) || weeks.contains(CFG.firstMonday().plusWeeks(1).toString()));
        int tasks = env.rows("tasks").size();
        assertTrue(tasks > 40 * 26 * 0.8 && tasks < 40 * 26 * 6, "tasks " + tasks);
        assertTrue(env.rows("time_logs").size() > tasks, "several logs per task");
        long unassigned = env.rows("tasks").stream().filter(t -> t.get("assignee_id") == null).count();
        assertTrue(unassigned > 0, "backlog left at the as-of date");
        assertTrue(env.rows("holidays").size() >= 9);
        long counted = env.rows("users").stream().filter(u -> List.of("MEMBER", "TEAM_LEADER").contains(u.get("role"))).count();
        assertTrue(env.rows("personal_leaves").size() >= counted, "at least one vacation block per counted member");
    }

    @Test
    void loggedHoursTrackAssignedEstimatesAndNeverExceedPresence() {
        ExportEnvelope env = generated();
        Map<String, Double> logged = new HashMap<>();
        Map<String, Map<String, Double>> perDay = new HashMap<>();
        for (var l : env.rows("time_logs")) {
            String user = (String) l.get("user_id");
            logged.merge(user, (Double) l.get("hours"), Double::sum);
            perDay.computeIfAbsent(user, k -> new HashMap<>()).merge((String) l.get("log_date"), (Double) l.get("hours"), Double::sum);
        }
        Set<String> absentDays = new HashSet<>();
        for (var l : env.rows("personal_leaves")) {
            if (!"APPROVED".equals(l.get("status"))) {
                continue;
            }
            for (LocalDate d = LocalDate.parse((String) l.get("start_date")); !d.isAfter(LocalDate.parse((String) l.get("end_date"))); d = d.plusDays(1)) {
                absentDays.add(l.get("employee_id") + "|" + d);
            }
        }
        // A present day can now run long (WorkStyle.hoursOn = presence x weekdayWeight x overtimeFactor).
        // WorkStyle.draw caps every weekday weight at MAX_WEEKDAY_WEIGHT (clip-and-redistribute, after
        // renormalising), so this per-day bound is provably correct, not merely something this fixed seed
        // happens not to violate. It is also asserted per member-week, where weekdayWeights summing to
        // exactly 5 gives a second, independent bound on the same data.
        Map<String, Map<String, Double>> perMemberWeek = new HashMap<>();
        for (var e : perDay.entrySet()) {
            for (var d : e.getValue().entrySet()) {
                assertTrue(d.getValue() <= AbsencePlanner.HOURS_PER_DAY * WorkStyle.MAX_WEEKDAY_WEIGHT * WorkStyle.MAX_OVERTIME_FACTOR + 1e-9,
                        "more than a day's hours, even with the weekday-weight cap and overtime, on " + d.getKey());
                assertFalse(absentDays.contains(e.getKey() + "|" + d.getKey()), "log on an absence day");
                LocalDate day = LocalDate.parse(d.getKey());
                perMemberWeek.computeIfAbsent(e.getKey(), k -> new HashMap<>())
                        .merge(SeedConfig.mondayOf(day).toString(), d.getValue(), Double::sum);
            }
        }
        for (var e : perMemberWeek.entrySet()) {
            for (var wk : e.getValue().entrySet()) {
                // weekdayWeights sum to exactly 5, so a member's week can never exceed a full 44 h week
                // times the largest overtime factor, however the days within it were weighted.
                assertTrue(wk.getValue() <= AbsencePlanner.BASE_HOURS * WorkStyle.MAX_OVERTIME_FACTOR + 1e-9,
                        "more than a week's worth of hours, even with overtime, for " + e.getKey() + "@" + wk.getKey());
            }
        }
        Map<String, Double> estimatesDone = new HashMap<>();
        Set<String> doneStatuses = new HashSet<>();
        for (var s : env.rows("task_statuses")) {
            if ("DONE".equals(s.get("category"))) {
                doneStatuses.add((String) s.get("id"));
            }
        }
        for (var t : env.rows("tasks")) {
            if (t.get("assignee_id") != null && t.get("original_estimate_hrs") != null && doneStatuses.contains(t.get("task_status_id"))) {
                estimatesDone.merge((String) t.get("assignee_id"), (Double) t.get("original_estimate_hrs"), Double::sum);
            }
        }
        double ratios = 0;
        int n = 0;
        for (var e : estimatesDone.entrySet()) {
            if (e.getValue() > 40) {
                double r = logged.getOrDefault(e.getKey(), 0.0) / e.getValue();
                assertTrue(r > 0.4 && r < 2.5, "logged/estimated for " + e.getKey() + " = " + r);
                ratios += r;
                n++;
            }
        }
        assertTrue(n >= 20, "members with finished work: " + n);
        double mean = ratios / n;
        assertTrue(mean > 0.75 && mean < 1.3, "mean logged/estimated " + mean);
    }

    @Test
    void theSeedWritesLeavesNotAbsencesOrCapacityRows() {
        ExportEnvelope env = generated();
        assertTrue(env.rows("absences").isEmpty());
        assertTrue(env.rows("user_capacity").isEmpty());
        assertTrue(env.rows("team_capacity").isEmpty());
        List<LinkedHashMap<String, Object>> leaves = env.rows("personal_leaves");
        assertFalse(leaves.isEmpty());
        long halfDays = leaves.stream().filter(l -> l.get("end_time") != null).count();
        long pending = leaves.stream().filter(l -> "PENDING".equals(l.get("status"))).count();
        assertTrue(halfDays > 0, "some vacation blocks end on a half day");
        assertTrue(pending > 0, "some members have a pending leave after the as-of date");
        for (var l : leaves) {
            LocalDate start = LocalDate.parse((String) l.get("start_date"));
            LocalDate end = LocalDate.parse((String) l.get("end_date"));
            assertFalse(end.isBefore(start));
            assertTrue((Double) l.get("absence_hours") > 0);
            if ("PENDING".equals(l.get("status"))) {
                assertTrue(start.isAfter(CFG.lastDay()), "a pending leave lies in the future");
                assertTrue(!start.isAfter(CFG.lastDay().plusWeeks(4)), "inside the four weeks after the as-of date");
            } else {
                assertEquals("APPROVED", l.get("status"));
            }
            if (l.get("end_time") != null) {
                assertEquals("12:00", l.get("end_time"));
            }
        }
    }

    @Test
    void someOpenTasksArePlannedForAWeekAfterTheOneTheyWereAssignedIn() {
        // planned_hrs_h{h} needs planned_week == w + h with the task still open at the end of w. A seed that
        // plans every task for its own assignment week makes that column identically zero for every h >= 1.
        ExportEnvelope env = generated();
        Map<String, LocalDate> assignedWeek = new HashMap<>();
        for (var h : env.rows("task_history")) {
            if ("assignee".equals(h.get("field_name"))) {
                assignedWeek.put((String) h.get("task_id"),
                        SeedConfig.mondayOf(LocalDate.parse(((String) h.get("changed_at")).substring(0, 10))));
            }
        }
        int forward = 0;
        for (var t : env.rows("tasks")) {
            if (t.get("finished_date") != null || t.get("planned_week") == null) {
                continue;                                   // open at the as-of date only
            }
            LocalDate planned = LocalDate.parse((String) t.get("planned_week"));
            LocalDate assigned = assignedWeek.get((String) t.get("id"));
            if (assigned == null) {
                continue;                                   // an epic container: created straight onto its owner, never assigned
            }
            assertTrue(!planned.isBefore(assigned), "a plan is never earlier than the assignment week");
            if (planned.isAfter(assigned)) {
                forward++;
            }
        }
        assertTrue(forward > 0, "no open task is planned forward: planned_hrs_h1 would be zero on every matrix row");
    }

    @Test
    void everyAssigneeHistoryRowNamesItsAssignerAndSelfPickedTasksAreAssignedByTheirAssignee() {
        ExportEnvelope env = generated();
        Map<String, String> assigneeByTask = new HashMap<>();
        Map<String, String> reporterByTask = new HashMap<>();
        for (var t : env.rows("tasks")) {
            assigneeByTask.put((String) t.get("id"), (String) t.get("assignee_id"));
            reporterByTask.put((String) t.get("id"), (String) t.get("reporter_id"));
        }
        int self = 0;
        int assigned = 0;
        for (var h : env.rows("task_history")) {
            if (!"assignee".equals(h.get("field_name"))) {
                continue;
            }
            String actor = (String) h.get("user_id");
            assertTrue(actor != null && !actor.isBlank());
            String task = (String) h.get("task_id");
            if (actor.equals(assigneeByTask.get(task))) {
                self++;
                assertEquals(actor, reporterByTask.get(task), "a self-picked task is reported by its assignee");
            } else {
                assigned++;
            }
        }
        assertTrue(self > 0 && assigned > 0, "both modes present: self " + self + ", assigned " + assigned);
    }

    @Test
    void roundTripsThroughPostgresql() {
        ExportEnvelope env = generated();
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        new ExportImporter(ds).importAll(env, true);
        ExportEnvelope back = new ExportExporter(ds).exportAll();
        for (String table : WorkloadHubSchema.TABLE_ORDER) {
            assertEquals(env.rows(table).size(), back.rows(table).size(), table);
        }
        Function<LinkedHashMap<String, Object>, String> keyOf = t -> (String) t.get("key");
        assertEquals(env.rows("tasks").stream().map(keyOf).sorted().toList(), back.rows("tasks").stream().map(keyOf).sorted().toList());
    }

    @Test
    void isDeterministic() {
        assertEquals(ExportFiles.toJson(generated()), ExportFiles.toJson(SeedGenerator.generate(null, CFG)));
    }

    @Test
    void syntheticModeLeavesNoInputIdentityBehind() throws Exception {
        ExportEnvelope real = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        // users=1 shrinks the fixture's second user out of the users table entirely: the scrub must
        // still cover them, not just whoever ends up kept.
        SeedConfig cfg = new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, true, 1);
        String json = ExportFiles.toJson(SeedGenerator.generate(real, cfg));
        for (var u : real.rows("users")) {
            for (String col : List.of("full_name", "email", "username")) {
                assertFalse(json.contains((String) u.get(col)), col + " leaked: " + u.get(col));
            }
        }
        ExportEnvelope out = ExportFiles.parse(json);
        assertEquals(1, out.rows("users").size(), "the second user was shrunk out");
        assertTrue(out.rows("users").stream().allMatch(u -> ((String) u.get("username")).startsWith("user") && u.get("password") == null
                && u.get("object_id") == null && u.get("manager_object_id") == null));
    }

    @Test
    void syntheticModeScrubsAPrefixNameWithoutLeakingTheLongerOne() throws Exception {
        ExportEnvelope real = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        // "Zed" is a literal prefix of "Zedwards"; neither is in ReferenceData's name pool, so a newly
        // generated replacement name can never coincidentally contain either. A shortest-key-first scrub
        // would consume "Zed" out of "Zedwards" first and leave a corrupted "<replacement>wards" behind
        // instead of the whole, correct replacement for "Zedwards".
        real.rows("users").get(0).put("full_name", "Zed");
        real.rows("users").get(1).put("full_name", "Zedwards");
        real.rows("teams").get(0).put("name", "Zedwards");
        SeedConfig cfg = new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, true, 0);
        ExportEnvelope out = SeedGenerator.generate(real, cfg);
        String newLongName = out.rows("users").stream()
                .filter(u -> "30000000-0000-0000-0000-000000000002".equals(u.get("id")))
                .findFirst().orElseThrow().get("full_name").toString();
        String teamName = out.rows("teams").stream()
                .filter(t -> "40000000-0000-0000-0000-000000000001".equals(t.get("id")))
                .findFirst().orElseThrow().get("name").toString();
        assertEquals(newLongName, teamName);
    }

    @Test
    void scrubNeverCorruptsIdsOrDates() throws Exception {
        // "0001" as an account_name and "2026" as a username are both literal substrings of, respectively,
        // a UUID (every id in this fixture ends "-0000-0000-0000-00000000000N") and every "2026-..." ISO
        // timestamp in the export: an unscoped scrub replaces them there too, corrupting ids and dates.
        ExportEnvelope real = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        real.rows("users").get(0).put("account_name", "0001");
        real.rows("users").get(1).put("username", "2026");
        SeedConfig cfg = new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, true, 2);
        ExportEnvelope out = SeedGenerator.generate(real, cfg);
        for (String table : List.of("teams", "projects")) {
            for (var row : out.rows(table)) {
                for (var e : row.entrySet()) {
                    Object v = e.getValue();
                    if (v == null) {
                        continue;
                    }
                    if (e.getKey().equals("id") || e.getKey().endsWith("_id")) {
                        java.util.UUID.fromString(String.valueOf(v));
                    }
                    if (e.getKey().equals("created_at")) {
                        java.time.LocalDateTime.parse(String.valueOf(v));
                    }
                }
            }
        }
    }

    @Test
    void realModeOfTheFixtureImports() throws Exception {
        // the fixture's own manager team is named exactly the way Directory.derive would name a fresh
        // one ("CT2 · Lead One"), and its own project key ("CT2-CAL") is exactly the pattern
        // ProjectPlanner.plan mints for the CT2 department: real mode must disambiguate both so the
        // result still imports into teams.name/projects.key's UNIQUE constraints.
        ExportEnvelope real = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope out = SeedGenerator.generate(real, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        // Real mode writes only the five work tables and leaves the application's own tables alone (design
        // 2026-09-17, section 7.1): land the fixture's directory first, as the application's own database
        // already holds it, then the generated work tables on top, the way a real host would receive them.
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> directory = new LinkedHashMap<>();
        real.data().forEach((table, rows) -> {
            if (!SeedGenerator.REAL_MODE_TABLES.contains(table)) {
                directory.put(table, rows);
            }
        });
        new ExportImporter(ds).importAll(real.withData(directory), true);
        new ExportImporter(ds).importAll(out, false);
        Set<String> teamNames = new HashSet<>();
        for (var t : out.rows("teams")) {
            assertTrue(teamNames.add((String) t.get("name")), "duplicate team name: " + t.get("name"));
        }
        Set<String> projectKeys = new HashSet<>();
        for (var p : out.rows("projects")) {
            assertTrue(projectKeys.add((String) p.get("key")), "duplicate project key: " + p.get("key"));
        }
    }

    @Test
    void selfReferencingTablesAreParentsFirstOrdered() {
        ExportEnvelope env = generated();
        for (String table : WorkloadHubSchema.SELF_REFERENCES.keySet()) {
            String column = WorkloadHubSchema.SELF_REFERENCES.get(table);
            List<LinkedHashMap<String, Object>> ordered = WorkloadHubSchema.parentsFirst(table, env.rows(table));
            Map<Object, Integer> indexOf = new HashMap<>();
            for (int i = 0; i < ordered.size(); i++) {
                indexOf.put(ordered.get(i).get("id"), i);
            }
            for (int i = 0; i < ordered.size(); i++) {
                Object parentId = ordered.get(i).get(column);
                if (parentId != null && indexOf.containsKey(parentId)) {
                    assertTrue(indexOf.get(parentId) < i, table + ": parent " + parentId + " not before row at " + i);
                }
            }
        }
    }

    /** Real mode writes no teams, so every project it plans must belong to a team the export already has. */
    @Test
    void realModeProjectsBelongToTheExportsOwnTeams() throws Exception {
        ExportEnvelope input = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        Set<String> teams = ids(input, "teams");
        for (var p : env.rows("projects")) {
            assertTrue(p.get("team_id") == null || teams.contains(p.get("team_id")), "project " + p.get("key") + " points at an invented team");
        }
        assertFalse(env.rows("tasks").isEmpty(), "the export's team still gets work");
        for (var t : env.rows("tasks")) {
            assertTrue(ids(env, "projects").contains(t.get("project_id")));
        }
    }

    /** A real export carries the application's own statuses and types; one that lacks any is refused, never patched with invented ids. */
    @Test
    void realModeRefusesAnExportMissingAStatusOrType() throws Exception {
        ExportEnvelope real = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>(real.data());
        data.put("task_statuses", real.rows("task_statuses").stream().filter(s -> !"Done".equals(s.get("name"))).toList());
        data.put("task_types", real.rows("task_types").stream().filter(t -> !"Bug".equals(t.get("name"))).toList());
        ExportEnvelope partial = new ExportEnvelope(real.database(), real.schema(), real.exportedAt(), real.excludedTables(), data);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SeedGenerator.generate(partial, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0)));
        assertTrue(e.getMessage().contains("task_statuses lacks 'Done'") && e.getMessage().contains("task_types lacks 'Bug'"), e.getMessage());
    }

    /** Real mode writes no statuses or types, so every task must use the ids the export already has. */
    @Test
    void realModeTasksUseTheExportsOwnStatusesAndTypes() throws Exception {
        ExportEnvelope input = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        Set<String> statuses = ids(input, "task_statuses");
        Set<String> types = ids(input, "task_types");
        assertFalse(env.rows("tasks").isEmpty());
        for (var t : env.rows("tasks")) {
            assertTrue(statuses.contains(t.get("task_status_id")), "task " + t.get("key") + " uses an invented status");
            assertTrue(types.contains(t.get("task_type_id")), "task " + t.get("key") + " uses an invented type");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "seed.full", matches = "true")
    void fullPopulationRunsInUnderAMinute() {
        long start = System.nanoTime();
        ExportEnvelope env = SeedGenerator.generate(null, new SeedConfig(52, LocalDate.of(2026, 9, 6), 42, true, 264));
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        assertTrue(seconds < 60, "took " + seconds + " s");
        assertTrue(env.rows("tasks").size() > 15_000, "tasks " + env.rows("tasks").size());
    }
}
