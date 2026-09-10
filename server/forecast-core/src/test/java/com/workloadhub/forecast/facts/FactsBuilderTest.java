package com.workloadhub.forecast.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
        ForecastRunner runner = new ForecastRunner(new CapacityRule(40), true);
        Prepared prepared = runner.prepare(data, SeededData.asOf(), null, ForecastRunner.ProgressListener.NONE);
        UUID team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
        outcome = runner.forTeam(prepared, team, null);
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
        assertEquals(Map.of("index", 1, "start", "2026-09-07", "end", "2026-09-11", "working_days", ((Map<?, ?>) windows.get(0)).get("working_days")), windows.get(0));
        assertEquals("2026-09-14", ((Map<?, ?>) windows.get(1)).get("start"));
        assertEquals(List.of(2, 3), run.get("horizons"));
        Map<?, ?> team = (Map<?, ?>) facts.get("team");
        assertEquals(outcome.teamId().toString(), team.get("id"));
        assertEquals(2, ((List<?>) team.get("totals")).size());
        assertEquals(1, ((Map<?, ?>) ((List<?>) team.get("totals")).get(0)).get("window"));
        assertNotNull(team.get("planned_backlog"));
        Map<?, ?> model = (Map<?, ?>) facts.get("model");
        assertEquals(outcome.prepared().champion(), model.get("champion"));
        assertEquals("backtest residuals", ((Map<?, ?>) model.get("interval")).get("basis"));
        assertEquals("share weights, 26-week window, shrink k=3", model.get("planned_basis"));
        Map<?, ?> quality = (Map<?, ?>) facts.get("data_quality");
        assertEquals(outcome.prepared().historyWeeks(), quality.get("history_weeks"));
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
        assertTrue(window.containsKey("due_hours") && window.containsKey("planned_hours") && window.containsKey("working_days"));
        Map<?, ?> patterns = (Map<?, ?>) first.get("patterns");
        assertTrue(patterns.containsKey("cluster") && patterns.containsKey("hours_per_week_13w"));
        Map<?, ?> likely = (Map<?, ?>) first.get("likely_work");
        assertEquals(List.of("planned", "project_roles", "recent_mix"), List.copyOf(((Map<String, ?>) likely).keySet()));
        assertEquals(1, window.get("window"));
        assertEquals("2026-09-07", window.get("start"));
        List<?> days = (List<?>) first.get("days");
        assertEquals(10, days.size());
        Map<?, ?> day = (Map<?, ?>) days.get(0);
        assertEquals("2026-09-07", day.get("day"));
        assertTrue(day.containsKey("demand") && day.containsKey("capacity") && day.containsKey("working_day"));
        for (Object p : (List<?>) likely.get("planned")) {
            Object expected = ((Map<?, ?>) p).get("expected_window");
            assertTrue(expected.equals(1) || expected.equals(2) || expected.equals("after_window"), String.valueOf(expected));
        }
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
        ForecastRunner runner = new ForecastRunner(new CapacityRule(40), true);
        Prepared prepared = runner.prepare(data, asOf, null, ForecastRunner.ProgressListener.NONE);
        TeamOutcome outcome = runner.forTeam(prepared, TestData.TEAM, null);
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
}
