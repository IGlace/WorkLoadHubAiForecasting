package com.workloadhub.forecast.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import com.workloadhub.forecast.testing.SeededData;
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
        assertEquals(List.of("2026-09-07", "2026-09-14"), run.get("weeks"));
        Map<?, ?> team = (Map<?, ?>) facts.get("team");
        assertEquals(outcome.teamId().toString(), team.get("id"));
        assertEquals(2, ((List<?>) team.get("totals")).size());
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
        Map<?, ?> week = (Map<?, ?>) forecast.get(0);
        assertEquals(outcome.memberWeeks().get(0).demandHrs(), week.get("demand"));
        assertTrue(week.containsKey("due_hours") && week.containsKey("planned_hours") && week.containsKey("working_days"));
        Map<?, ?> patterns = (Map<?, ?>) first.get("patterns");
        assertTrue(patterns.containsKey("cluster") && patterns.containsKey("hours_per_week_13w"));
        Map<?, ?> likely = (Map<?, ?>) first.get("likely_work");
        assertEquals(List.of("planned", "project_roles", "recent_mix"), List.copyOf(((Map<String, ?>) likely).keySet()));
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
}
