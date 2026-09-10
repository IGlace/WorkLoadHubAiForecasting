package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.testing.SeededFacts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class FactsToolsTest {

    static final JsonNode FACTS = SeededFacts.facts();
    static final FactsTools TOOLS = new FactsTools(FACTS);
    static final String FIRST = FACTS.path("members").get(0).path("id").asText();

    @Test
    void nineSpecsInTheDocumentedOrderWithTheirScope() {
        List<ToolSpec> specs = TOOLS.specs();
        assertEquals(FactsTools.NAMES, specs.stream().map(ToolSpec::name).toList());
        assertEquals(List.of("get_run_overview", "get_member_history", "get_member_forecast", "get_member_patterns", "get_member_open_tasks",
                "get_member_capacity", "get_project_timelines", "get_rebalancing_candidates", "get_planned_work"), FactsTools.NAMES);
        for (ToolSpec s : specs) {
            assertEquals(s.name().startsWith("get_member_"), s.memberScoped(), s.name());
            assertTrue(s.description().length() > 20, s.name());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolReturnsItsDocumentedKeys() {
        Map<String, Object> overview = TOOLS.runOverview();
        assertEquals(Set.of("run", "team", "members", "model", "rebalancing_candidates", "data_quality", "pending_holidays", "how_to_proceed"), overview.keySet());
        Map<String, Object> team = (Map<String, Object>) overview.get("team");
        assertTrue(!team.containsKey("planned_backlog"), "the backlog is get_planned_work's");
        List<Map<String, Object>> members = (List<Map<String, Object>>) overview.get("members");
        assertEquals(Set.of("id", "name", "role"), members.get(0).keySet());
        assertEquals(FACTS.path("members").size(), members.size());

        assertEquals(Set.of("member_id", "name", "history_13w", "logged_hours_4w", "unlogged_tasks", "reopened_tasks"), TOOLS.memberHistory(FIRST).keySet());
        assertEquals(Set.of("member_id", "name", "forecast"), TOOLS.memberForecast(FIRST).keySet());
        assertEquals(Set.of("member_id", "name", "patterns"), TOOLS.memberPatterns(FIRST).keySet());
        assertEquals(Set.of("member_id", "name", "open_tasks"), TOOLS.memberOpenTasks(FIRST).keySet());
        Map<String, Object> capacity = TOOLS.memberCapacity(FIRST);
        assertEquals(Set.of("member_id", "name", "windows", "days"), capacity.keySet());
        List<Map<String, Object>> windows = (List<Map<String, Object>>) capacity.get("windows");
        assertEquals(2, windows.size());
        assertEquals(Set.of("window", "start", "end", "capacity", "demand", "overload", "working_days", "absence_hours"), windows.get(0).keySet());
        assertEquals(Set.of("windows", "projects"), TOOLS.projectTimelines().keySet());
        assertEquals(Set.of("overloaded", "underloaded"), TOOLS.rebalancingCandidates().keySet());
        Map<String, Object> planned = TOOLS.plannedWork();
        assertEquals(Set.of("planned_backlog", "members"), planned.keySet());
        List<Map<String, Object>> plannedMembers = (List<Map<String, Object>>) planned.get("members");
        assertEquals(Set.of("id", "name", "likely_work"), plannedMembers.get(0).keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void forecastNumbersAreTheFactsNumbers() {
        Map<String, Object> forecast = TOOLS.memberForecast(FIRST);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) forecast.get("forecast");
        JsonNode factRows = FACTS.path("members").get(0).path("forecast");
        assertEquals(factRows.size(), rows.size());
        assertEquals(factRows.get(0).path("demand").asDouble(), ((Number) rows.get(0).get("demand")).doubleValue(), 0.0);
        assertEquals(factRows.get(0).path("start").asText(), rows.get(0).get("start"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnknownMemberIsAnErrorNamingTheKnownIds() {
        Map<String, Object> r = TOOLS.memberForecast("nobody");
        assertEquals("unknown member_id nobody", r.get("error"));
        List<String> known = (List<String>) r.get("known_member_ids");
        assertTrue(known.contains(FIRST));
        assertEquals(known.stream().sorted().toList(), known, "sorted");
    }

    @Test
    void specsDispatchToTheLookups() {
        ToolSpec forecast = TOOLS.specs().stream().filter(s -> s.name().equals("get_member_forecast")).findFirst().orElseThrow();
        assertEquals(TOOLS.memberForecast(FIRST), forecast.handler().apply(FIRST));
        ToolSpec overview = TOOLS.specs().get(0);
        assertEquals(TOOLS.runOverview(), overview.handler().apply(null));
    }
}
