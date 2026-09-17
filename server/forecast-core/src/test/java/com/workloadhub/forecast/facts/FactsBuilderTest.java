package com.workloadhub.forecast.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.LeaveRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Truncation;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FactsBuilderTest {

    static TeamOutcome outcome;
    static Map<String, Object> facts;
    static String json;

    @BeforeAll
    static void run() {
        ForecastData data = SeededData.data();
        ForecastRunner runner = new ForecastRunner(new CapacityRule(40), 2);
        Prepared prepared = runner.prepare(data, SeededData.asOf(), ForecastRunner.ProgressListener.NONE);
        UUID team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
        outcome = runner.forTeam(prepared, team);
        facts = FactsBuilder.build(outcome, UUID.fromString("00000000-0000-0000-0000-000000000001"), LocalDateTime.of(2026, 9, 6, 12, 0));
        json = FactsBuilder.toJson(facts);
    }

    @Test
    void topLevelShapeMatchesThePythonFactsPlusTheAdditions() {
        assertEquals(List.of("run", "team", "members", "projects", "model", "rebalancing_candidates", "pending_holidays", "data_quality"),
                List.copyOf(facts.keySet()));
        Map<?, ?> run = (Map<?, ?>) facts.get("run");
        assertEquals("00000000-0000-0000-0000-000000000001", run.get("id"));
        assertEquals("2026-09-06", run.get("as_of"));
        List<?> windows = (List<?>) run.get("windows");
        assertEquals(2, windows.size());
        assertEquals(Map.of("index", 1, "start", "2026-09-07", "end", "2026-09-11", "working_days", 5), windows.get(0));
        assertEquals("2026-09-14", ((Map<?, ?>) windows.get(1)).get("start"));
        assertEquals(List.of(2, 3), run.get("horizons"));
        Map<?, ?> team = (Map<?, ?>) facts.get("team");
        assertEquals(outcome.teamId().toString(), team.get("id"));
        assertEquals(2, ((List<?>) team.get("totals")).size());
        assertEquals(1, ((Map<?, ?>) ((List<?>) team.get("totals")).get(0)).get("window"));
        Map<?, ?> model = (Map<?, ?>) facts.get("model");
        assertEquals(outcome.prepared().mae(), model.get("mae"));
        assertEquals("xgboost", model.get("name"));
        assertEquals(2, model.get("windows"));
        assertEquals("backtest residuals", ((Map<?, ?>) model.get("interval")).get("basis"));
        Map<?, ?> quality = (Map<?, ?>) facts.get("data_quality");
        assertEquals(outcome.prepared().historyWeeks(), quality.get("history_weeks"));
    }

    @Test
    void theRemovedKeysAreGone() {
        for (String key : List.of("open_hours", "new_hours", "planned_backlog", "planned_basis", "champion", "champion_mase",
                "forced_model", "mase_by_model", "unavailable")) {
            assertFalse(json.contains("\"" + key + "\""), key + " is still in the facts");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void membersCarryTheirPendingLeavesInsideTheHorizonAndPlannedHoursPerWindow() {
        List<Map<String, Object>> members = (List<Map<String, Object>>) facts.get("members");
        LocalDate first = LocalDate.parse((String) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) facts.get("run")).get("windows")).get(0)).get("start"));
        LocalDate last = LocalDate.parse((String) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) facts.get("run")).get("windows")).get(1)).get("end"));
        for (Map<String, Object> m : members) {
            List<Map<String, Object>> pending = (List<Map<String, Object>>) m.get("pending_leaves");
            assertNotNull(pending);
            for (Map<String, Object> l : pending) {
                assertEquals(java.util.Set.of("start_date", "end_date", "leave_type", "absence_hours"), l.keySet());
                LocalDate s = LocalDate.parse((String) l.get("start_date"));
                LocalDate e = LocalDate.parse((String) l.get("end_date"));
                assertTrue(!e.isBefore(first) && !s.isAfter(last), "inside the horizon");
            }
            for (Map<String, Object> w : (List<Map<String, Object>>) m.get("forecast")) {
                assertTrue(w.containsKey("planned_hours"));
                assertTrue(((Number) w.get("planned_hours")).doubleValue() >= 0);
            }
        }
        // The seed gives one member in ten a pending leave inside the four weeks after the as-of date; a two-window
        // horizon of a 36-user seed catches at least one on some team, but not necessarily on the first team. The
        // shape is what this test pins; the count is asserted on the whole population below.
        long seededPending = SeededData.data().pendingLeaves().stream()
                .filter(l -> !l.end().isBefore(first) && !l.start().isAfter(last)).count();
        assertTrue(seededPending > 0, "the seed does produce pending leaves inside a horizon");
    }

    // Deferred task 6 of the plan, on a constructed fixture rather than the seed: the horizon filter is an
    // inclusive overlap test at both ends, and a leave whose `absence_hours` the application never recorded
    // must travel as JSON null rather than as a zero or an exception.
    @Test
    @SuppressWarnings("unchecked")
    void pendingLeavesAreFilteredInclusivelyAtBothEndsOfTheHorizonAndKeepANullTotal() {
        LocalDate asOf = LocalDate.of(2026, 9, 6);                 // Sunday: window 1 is 09-07..09-11, window 2 is 09-14..09-18
        MemberRow ana = TestData.member("leaves", TestData.TEAM);
        TaskRow open = TestData.task("open", ana.id(), asOf.minusWeeks(2).atTime(9, 0), 5.0);
        LeaveRow endsOnFirstDay = new LeaveRow(ana.id(), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 7), null, null, 8.8, "PENDING", "PAID_LEAVE");
        LeaveRow startsOnLastDay = new LeaveRow(ana.id(), LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 25), null, null, 17.6, "PENDING", "PAID_LEAVE");
        LeaveRow outside = new LeaveRow(ana.id(), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 25), null, null, 8.8, "PENDING", "PAID_LEAVE");
        LeaveRow noTotal = new LeaveRow(ana.id(), LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10), null, null, null, "PENDING", "SICK_LEAVE");
        ForecastData data = new ForecastData(List.of(ana),
                List.of(new TeamRow(TestData.PARENT_TEAM, "Dept", null, null), new TeamRow(TestData.TEAM, "Team", null, TestData.PARENT_TEAM)),
                List.of(), List.of(open), List.of(), List.of(), List.of(),
                List.of(endsOnFirstDay, startsOnLastDay, outside, noTotal), List.of(), List.of(TestData.user(ana)), TestData.STATUS_CATEGORIES);
        ForecastRunner runner = new ForecastRunner(new CapacityRule(44), 2);
        Prepared prepared = runner.prepare(data, asOf, ForecastRunner.ProgressListener.NONE);
        TeamOutcome out = runner.forTeam(prepared, TestData.TEAM);
        Map<String, Object> built = FactsBuilder.build(out, UUID.fromString("00000000-0000-0000-0000-000000000006"),
                LocalDateTime.of(2026, 9, 6, 12, 0));

        List<Map<String, Object>> members = (List<Map<String, Object>>) built.get("members");
        assertEquals(1, members.size());
        List<Map<String, Object>> pending = (List<Map<String, Object>>) members.get(0).get("pending_leaves");
        assertEquals(3, pending.size(), "the leave entirely after the horizon is dropped: " + pending);
        assertEquals("2026-09-02", pending.get(0).get("start_date"));
        assertEquals("2026-09-07", pending.get(0).get("end_date"), "a leave ending on the horizon's first day is inside it");
        assertEquals(8.8, pending.get(0).get("absence_hours"));
        assertEquals("2026-09-09", pending.get(1).get("start_date"));
        assertEquals("SICK_LEAVE", pending.get(1).get("leave_type"));
        assertNull(pending.get(1).get("absence_hours"), "a leave with no recorded total keeps it absent, it is not invented");
        assertTrue(pending.get(1).containsKey("absence_hours"), "the key is present and null, not missing");
        assertEquals("2026-09-18", pending.get(2).get("start_date"), "a leave starting on the horizon's last day is inside it");
        assertEquals("2026-09-25", pending.get(2).get("end_date"));
        assertFalse(pending.stream().anyMatch(l -> "2026-09-21".equals(l.get("start_date"))));
        assertTrue(FactsBuilder.toJson(built).contains("\"absence_hours\" : null"), "the null total reaches the JSON as null");
    }

    @Test
    void theTeamBlockHasNoCapacityPlan() {
        assertFalse(((Map<?, ?>) facts.get("team")).containsKey("team_capacity"));
        assertFalse(json.contains("team_capacity"));
    }

    @Test
    void theModelBlockNamesItsTargetAndItsScale() {
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) facts.get("model");
        assertEquals("xgboost", model.get("name"));
        assertEquals("logged hours per member-week", model.get("target"));
        assertTrue(model.containsKey("mae"));
        assertTrue(model.containsKey("mean_actual_hours"));
        assertEquals(2, model.get("windows"), "a narrative can say how far ahead it is reading");
        assertEquals("scored", model.get("confidence"));
    }

    @Test
    void aThinHistoryRunIsUnscoredAndSaysSo() {
        LocalDate youngAsOf = SeededData.asOf().minusWeeks(22);
        ForecastData young = Truncation.at(SeededData.data(), youngAsOf);
        ForecastRunner runner = new ForecastRunner(new CapacityRule(40), 2);
        Prepared prepared = runner.prepare(young, youngAsOf, ForecastRunner.ProgressListener.NONE);
        assertTrue(prepared.backtestOrigins().isEmpty(), "under 13 weeks before every origin");
        UUID team = young.teams().stream().filter(t -> !young.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
        TeamOutcome thin = runner.forTeam(prepared, team);
        Map<String, Object> thinFacts = FactsBuilder.build(thin, UUID.fromString("00000000-0000-0000-0000-000000000003"),
                LocalDateTime.of(2026, 9, 6, 12, 0));

        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) thinFacts.get("model");
        assertNull(model.get("mae"));
        assertNull(model.get("mean_actual_hours"));
        assertEquals("thin_history", model.get("confidence"));
        @SuppressWarnings("unchecked")
        Map<String, Object> interval = (Map<String, Object>) model.get("interval");
        assertEquals("none: no scored origins", interval.get("basis"));
        @SuppressWarnings("unchecked")
        Map<String, Object> horizonBand = (Map<String, Object>) ((Map<String, Object>) interval.get("horizons")).values().iterator().next();
        assertEquals(0.0, horizonBand.get("low_offset"));
        assertEquals(0.0, horizonBand.get("high_offset"), "a zero-width band, which must never read as certainty");
    }

    @Test
    void aNaNMaeReadsExactlyLikeThinHistoryNeverAsAScoredMeasurement() {
        // meanMae() returns NaN, not null, when scoring ran but every origin scored zero rows (Backtest.java).
        // A NaN reaching the facts would be quoted by the narrative as if it were a real measurement.
        Prepared p = outcome.prepared();
        Prepared nanScored = new Prepared(p.data(), p.lifecycle(), p.calendar(), p.asOf(), p.origin(), p.windows(), p.horizons(), p.features(),
                p.backtestOrigins(), p.backtest(), Double.NaN, Double.NaN, p.bandOffsets(), p.predictedHours(), p.historyWeeks(), p.secondsByPhase());
        TeamOutcome nanOutcome = new TeamOutcome(nanScored, outcome.teamId(), outcome.members(), outcome.memberWindows(), outcome.memberDays());
        Map<String, Object> nanFacts = FactsBuilder.build(nanOutcome, UUID.fromString("00000000-0000-0000-0000-000000000004"),
                LocalDateTime.of(2026, 9, 6, 12, 0));

        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) nanFacts.get("model");
        assertNull(model.get("mae"), "a NaN mae must read as absent, not as a number");
        assertNull(model.get("mean_actual_hours"));
        assertEquals("thin_history", model.get("confidence"));
        @SuppressWarnings("unchecked")
        Map<String, Object> interval = (Map<String, Object>) model.get("interval");
        assertEquals("none: no scored origins", interval.get("basis"));
        assertFalse(FactsBuilder.toJson(nanFacts).contains("NaN"), "no NaN literal ever reaches the JSON");
    }

    @Test
    void theHistoryBlockReportsWhatIsForecast() {
        List<?> members = (List<?>) facts.get("members");
        Map<?, ?> first = (Map<?, ?>) members.get(0);
        List<?> history = (List<?>) first.get("history_13w");
        Map<?, ?> row = (Map<?, ?>) history.get(0);
        assertTrue(row.containsKey("logged_hours"));
        assertTrue(row.containsKey("arrival_hours"));
        assertTrue(row.containsKey("tasks"));
        assertFalse(row.containsKey("fresh_hours"));
        assertFalse(row.containsKey("hours"));
    }

    @Test
    void everyMemberCarriesHistoryForecastPatternsAndLikelyWork() {
        List<?> members = (List<?>) facts.get("members");
        assertEquals(outcome.members().size(), members.size());
        Map<?, ?> first = (Map<?, ?>) members.get(0);
        assertEquals(outcome.members().get(0).id().toString(), first.get("id"));
        assertEquals(outcome.members().get(0).fullName(), first.get("name"));
        assertEquals(13, ((List<?>) first.get("history_13w")).size());
        List<?> forecast = (List<?>) first.get("forecast");
        assertEquals(2, forecast.size());
        Map<?, ?> window = (Map<?, ?>) forecast.get(0);
        assertEquals(outcome.memberWindows().get(0).demandHrs(), window.get("demand"));
        assertTrue(window.containsKey("due_hours") && window.containsKey("working_days"));
        Map<?, ?> patterns = (Map<?, ?>) first.get("patterns");
        assertTrue(patterns.containsKey("cluster") && patterns.containsKey("hours_per_week_13w"));
        Map<?, ?> likely = (Map<?, ?>) first.get("likely_work");
        assertEquals(List.of("project_roles", "recent_mix"), List.copyOf(((Map<String, ?>) likely).keySet()));
        assertEquals(1, window.get("window"));
        assertEquals("2026-09-07", window.get("start"));
        List<?> days = (List<?>) first.get("days");
        assertEquals(10, days.size());
        Map<?, ?> day = (Map<?, ?>) days.get(0);
        assertEquals("2026-09-07", day.get("day"));
        assertTrue(day.containsKey("demand") && day.containsKey("capacity") && day.containsKey("working_day"));
        assertEquals(4, ((List<?>) first.get("logged_hours_4w")).size());
        assertNotNull(first.get("open_tasks"));
        assertNotNull(first.get("reopened_tasks"));
        assertNotNull(first.get("unlogged_tasks"));
    }

    @Test
    void jsonIsStableHasNoNaNAndUsesKeysNotUuidsForTasks() {
        assertEquals(json, FactsBuilder.toJson(FactsBuilder.build(outcome, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                LocalDateTime.of(2026, 9, 6, 12, 0))));
        assertFalse(json.contains("NaN") || json.contains("Infinity"));
        JsonNode root = JsonMapper.builder().build().readTree(json);
        for (JsonNode task : root.at("/members/0/open_tasks")) {
            assertTrue(task.has("key") && !task.has("id"));
        }
        assertTrue(root.at("/run/generated_at").asText().startsWith("2026-09-06T12:00"));
    }

    @Test
    void projectRolesListsOnlyTheLiveProjectForASingleMemberTeam() {
        LocalDate asOf = LocalDate.of(2026, 9, 6);
        MemberRow ana = TestData.member("ana", TestData.TEAM);
        UUID liveProjectId = TestData.id("proj-live");
        UUID doneProjectId = TestData.id("proj-done");
        TaskRow openTask = TestData.task("open", ana.id(), asOf.minusWeeks(2).atTime(9, 0), 5.0).withProject(liveProjectId);
        TaskRow doneTask = TestData.task("done", ana.id(), asOf.minusWeeks(3).atTime(9, 0), 4.0).withProject(doneProjectId).withStatus("DONE");
        ProjectRow liveProject = new ProjectRow(liveProjectId, "LIVE", "Live Project", "ACTIVE", TestData.TEAM);
        ProjectRow doneProject = new ProjectRow(doneProjectId, "DONE", "Done Project", "ACTIVE", TestData.TEAM);
        ForecastData data = TestData.data(List.of(ana), List.of(openTask, doneTask), List.of(), List.of())
                .withProjects(List.of(liveProject, doneProject));
        ForecastRunner runner = new ForecastRunner(new CapacityRule(40), 2);
        Prepared prepared = runner.prepare(data, asOf, ForecastRunner.ProgressListener.NONE);
        TeamOutcome outcome = runner.forTeam(prepared, TestData.TEAM);
        Map<String, Object> facts = FactsBuilder.build(outcome, UUID.fromString("00000000-0000-0000-0000-000000000002"),
                LocalDateTime.of(2026, 9, 6, 12, 0));

        List<?> members = (List<?>) facts.get("members");
        assertEquals(1, members.size());
        Map<?, ?> likely = (Map<?, ?>) ((Map<?, ?>) members.get(0)).get("likely_work");
        List<?> roles = (List<?>) likely.get("project_roles");
        assertEquals(1, roles.size(), "only the live project should appear: " + roles);
        Map<?, ?> role = (Map<?, ?>) roles.get(0);
        assertEquals("LIVE", role.get("project_key"));
        assertEquals(1.0, role.get("share"));
        assertEquals("active", role.get("phase"));
        List<?> dominant = (List<?>) role.get("dominant_types");
        assertEquals(1, dominant.size());
        assertEquals(Map.of("type", "Task", "count", 1), dominant.get(0));
    }

    // Design 2026-09-13, section 8.1: two members whose pressure is real but different in kind, so the two
    // lists are proven independent rather than both trivially true off one shared row. "pressed" holds 200
    // hours of open work with no deadline and a demand forecast far under it, so the backlog outlasts the run;
    // "late" holds one 60-hour task due inside window 1 (capacity 44) but its whole backlog is absorbed by the
    // run's own demand, so it is deadline-pressed and not backlog-pressed. Demand is pinned by hand, as in
    // ForecastRunnerTest, so the two figures land where the fixture says rather than wherever a fitted model
    // would put them.
    @Test
    void theTwoListsNameTheMembersToActOn() {
        MemberRow pressed = TestData.member("pressed", TestData.TEAM);
        MemberRow late = TestData.member("late", TestData.TEAM);
        LocalDateTime created = LocalDate.of(2026, 8, 1).atTime(9, 0);
        TaskRow backlogA = TestData.task("backlog-a", pressed.id(), created, 100).withRemaining(100.0);
        TaskRow backlogB = TestData.task("backlog-b", pressed.id(), created, 100).withRemaining(100.0);
        TaskRow dueTask = TestData.task("due", late.id(), created, 60).withDue(LocalDate.of(2026, 9, 10)).withRemaining(60.0);
        LocalDate asOf = LocalDate.of(2026, 9, 6);                                    // Sunday: window 1 starts Monday 2026-09-07
        ForecastData data = TestData.data(List.of(pressed, late), List.of(backlogA, backlogB, dueTask), List.of(), List.of());
        ForecastRunner runner = new ForecastRunner(new CapacityRule(44), 2);
        Prepared base = runner.prepare(data, asOf, ForecastRunner.ProgressListener.NONE);
        Map<MemberWeek, Double> demand = new TreeMap<>();
        demand.put(new MemberWeek(pressed.id(), base.origin().plusWeeks(base.horizons()[0])), 10.0);
        demand.put(new MemberWeek(pressed.id(), base.origin().plusWeeks(base.horizons()[1])), 10.0);
        demand.put(new MemberWeek(late.id(), base.origin().plusWeeks(base.horizons()[0])), 30.0);
        demand.put(new MemberWeek(late.id(), base.origin().plusWeeks(base.horizons()[1])), 30.0);
        Prepared fixture = new Prepared(base.data(), base.lifecycle(), base.calendar(), base.asOf(), base.origin(), base.windows(), base.horizons(),
                base.features(), base.backtestOrigins(), base.backtest(), base.mae(), base.meanActualHours(), base.bandOffsets(), demand,
                base.historyWeeks(), base.secondsByPhase());
        TeamOutcome outcome = runner.forTeam(fixture, TestData.TEAM);
        Map<String, Object> facts = FactsBuilder.build(outcome, UUID.fromString("00000000-0000-0000-0000-000000000005"),
                LocalDateTime.of(2026, 9, 6, 12, 0));

        @SuppressWarnings("unchecked")
        Map<String, Object> candidates = (Map<String, Object>) facts.get("rebalancing_candidates");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> backlogPressed = (List<Map<String, Object>>) candidates.get("backlog_pressed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deadlinePressed = (List<Map<String, Object>>) candidates.get("deadline_pressed");
        assertTrue(backlogPressed.stream().anyMatch(e -> pressed.fullName().equals(e.get("name")) && pressed.id().toString().equals(e.get("member_id"))));
        assertFalse(backlogPressed.stream().anyMatch(e -> late.fullName().equals(e.get("name"))), "late's backlog is absorbed by the run's own demand");
        assertTrue(deadlinePressed.stream().anyMatch(e -> late.fullName().equals(e.get("name")) && late.id().toString().equals(e.get("member_id"))));
        assertFalse(deadlinePressed.stream().anyMatch(e -> pressed.fullName().equals(e.get("name"))), "pressed has no due date at all");
        assertTrue(backlogPressed.stream().anyMatch(e -> e.get("backlog_excess_hrs") instanceof Double d && d > 0));
        assertTrue(deadlinePressed.stream().anyMatch(e -> e.get("due_excess_hrs") instanceof Double d && d > 0));
    }
}
