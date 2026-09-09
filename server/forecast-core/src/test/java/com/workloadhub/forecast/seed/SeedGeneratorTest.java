package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ExportEnvelope;
import com.workloadhub.forecast.data.ExportExporter;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
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
        assertResolves(env, "absences", "user_id", users);
        assertResolves(env, "personal_leaves", "employee_id", users);
        assertResolves(env, "user_capacity", "user_id", users);
        assertResolves(env, "team_capacity", "team_id", teams);
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
    void historyCoversTheConfiguredWeeksWithRealisticVolume() {
        ExportEnvelope env = generated();
        assertEquals(40, env.rows("users").size());
        Set<String> weeks = new HashSet<>();
        for (var r : env.rows("user_capacity")) {
            weeks.add((String) r.get("week_start"));
        }
        assertEquals(CFG.mondays().size(), weeks.size());
        assertTrue(weeks.contains(CFG.firstMonday().toString()) && weeks.contains(SeedConfig.mondayOf(CFG.lastDay()).toString()));
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
        for (var a : env.rows("absences")) {
            absentDays.add(a.get("user_id") + "|" + a.get("date"));
        }
        for (var e : perDay.entrySet()) {
            for (var d : e.getValue().entrySet()) {
                assertTrue(d.getValue() <= 8.0 + 1e-9, "more than 8 h on " + d.getKey());
                assertFalse(absentDays.contains(e.getKey() + "|" + d.getKey()), "log on an absence day");
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
    void capacityRowsAreConsistent() {
        ExportEnvelope env = generated();
        Map<String, Double> available = new HashMap<>();
        for (var r : env.rows("user_capacity")) {
            double base = (Double) r.get("base_capacity_hrs");
            double abs = (Double) r.get("absence_hrs");
            double avail = (Double) r.get("available_hrs");
            assertTrue(avail <= base - abs + 1e-9 && avail >= 0);
            available.put(r.get("user_id") + "|" + r.get("week_start"), avail);
        }
        Map<String, List<String>> members = new HashMap<>();
        for (var m : env.rows("team_members")) {
            members.computeIfAbsent((String) m.get("team_id"), k -> new java.util.ArrayList<>()).add((String) m.get("user_id"));
        }
        for (var r : env.rows("team_capacity")) {
            double sum = 0;
            for (String u : members.getOrDefault((String) r.get("team_id"), List.of())) {
                sum += available.getOrDefault(u + "|" + r.get("week_start"), 0.0);
            }
            assertEquals(sum, (Double) r.get("total_capacity_hrs"), 1e-6);
        }
    }

    @Test
    void roundTripsThroughSqlite() {
        ExportEnvelope env = generated();
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
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
        SeedConfig cfg = new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, true, 0);
        String json = ExportFiles.toJson(SeedGenerator.generate(real, cfg));
        for (var u : real.rows("users")) {
            for (String col : List.of("full_name", "email")) {
                assertFalse(json.contains((String) u.get(col)), col + " leaked: " + u.get(col));
            }
        }
        ExportEnvelope out = ExportFiles.parse(json);
        assertTrue(out.rows("users").stream().allMatch(u -> ((String) u.get("username")).startsWith("user") && u.get("password") == null
                && u.get("object_id") == null && u.get("manager_object_id") == null));
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
