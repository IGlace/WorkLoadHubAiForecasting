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
                "get_member_capacity", "get_project_timelines", "get_rebalancing_candidates", "get_likely_work"), FactsTools.NAMES);
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
        assertTrue(!team.containsKey("planned_backlog"), "planned_backlog is gone from the facts entirely");
        List<Map<String, Object>> members = (List<Map<String, Object>>) overview.get("members");
        assertEquals(Set.of("id", "name", "role"), members.get(0).keySet());
        assertEquals(FACTS.path("members").size(), members.size());

        assertEquals(Set.of("member_id", "name", "history_13w", "logged_hours_4w", "unlogged_tasks", "reopened_tasks"), TOOLS.memberHistory(FIRST).keySet());
        assertEquals(Set.of("member_id", "name", "forecast", "days"), TOOLS.memberForecast(FIRST).keySet());
        assertEquals(Set.of("member_id", "name", "patterns"), TOOLS.memberPatterns(FIRST).keySet());
        assertEquals(Set.of("member_id", "name", "open_tasks"), TOOLS.memberOpenTasks(FIRST).keySet());
        Map<String, Object> capacity = TOOLS.memberCapacity(FIRST);
        assertEquals(Set.of("member_id", "name", "windows", "days", "pending_leaves"), capacity.keySet(),
                "the tool that serves capacity serves the pending leaves three skills tell Copilot to read");
        List<Map<String, Object>> windows = (List<Map<String, Object>>) capacity.get("windows");
        assertEquals(2, windows.size());
        assertEquals(Set.of("window", "start", "end", "capacity", "demand", "overload", "working_days", "absence_hours",
                "backlog_excess_hrs", "due_excess_hrs", "planned_hours"), windows.get(0).keySet(),
                "the tool that serves overload per window serves the two pressure figures beside it (design 2026-09-13, section 8.2)");
        assertEquals(Set.of("windows", "projects"), TOOLS.projectTimelines().keySet());
        assertEquals(Set.of("overloaded", "underloaded", "backlog_pressed", "deadline_pressed"), TOOLS.rebalancingCandidates().keySet(),
                "all four lists reach Copilot, not the two the tool used to whitelist (ruling 18.5)");
        Map<String, Object> likely = TOOLS.likelyWork();
        assertEquals(Set.of("members"), likely.keySet());
        List<Map<String, Object>> likelyMembers = (List<Map<String, Object>>) likely.get("members");
        assertEquals(Set.of("id", "name", "project_roles", "recent_mix"), likelyMembers.get(0).keySet());
    }

    @Test
    void theRebalancingToolServesTheSameNodeTheOverviewDoes() {
        assertEquals(TOOLS.runOverview().get("rebalancing_candidates"), TOOLS.rebalancingCandidates(),
                "one node, served whole by both tools");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCapacityWindowsCarryTheFactsPressureFigures() {
        List<Map<String, Object>> windows = (List<Map<String, Object>>) TOOLS.memberCapacity(FIRST).get("windows");
        JsonNode factRows = FACTS.path("members").get(0).path("forecast");
        for (int i = 0; i < windows.size(); i++) {
            assertEquals(factRows.get(i).path("backlog_excess_hrs").asDouble(),
                    ((Number) windows.get(i).get("backlog_excess_hrs")).doubleValue(), 0.0);
            assertEquals(factRows.get(i).path("due_excess_hrs").asDouble(),
                    ((Number) windows.get(i).get("due_excess_hrs")).doubleValue(), 0.0);
        }
    }

    @Test
    void theRebalancingToolsDescriptionNamesTheFourListsAndNoFixedWindowCount() {
        String d = TOOLS.specs().stream().filter(s -> s.name().equals("get_rebalancing_candidates")).findFirst().orElseThrow().description();
        for (String list : List.of("overloaded", "underloaded", "backlog_pressed", "deadline_pressed")) {
            assertTrue(d.contains(list), list);
        }
        assertTrue(!d.contains("two windows"), "the window count is a setting (whf.forecast.windows), not two");
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
    @SuppressWarnings("unchecked")
    void theCapacityToolServesThePendingLeavesAndSaysSoInItsDescription() {
        // Three skills (whf-domain, whf-forecast-interpretation, whf-rebalancing-advice) tell Copilot to read
        // pending_leaves; without a tool that serves it the instruction could never be followed.
        for (JsonNode m : FACTS.path("members")) {
            String id = m.path("id").asText();
            List<Map<String, Object>> served = (List<Map<String, Object>>) TOOLS.memberCapacity(id).get("pending_leaves");
            assertEquals(m.path("pending_leaves").size(), served.size(), id);
            for (int i = 0; i < served.size(); i++) {
                assertEquals(m.path("pending_leaves").get(i).path("start_date").asText(), served.get(i).get("start_date"));
                assertEquals(m.path("pending_leaves").get(i).path("leave_type").asText(), served.get(i).get("leave_type"));
            }
        }
        String d = TOOLS.specs().stream().filter(s -> s.name().equals("get_member_capacity")).findFirst().orElseThrow().description();
        assertTrue(d.contains("pending leave"), d);
    }

    @Test
    void specsDispatchToTheLookups() {
        ToolSpec forecast = TOOLS.specs().stream().filter(s -> s.name().equals("get_member_forecast")).findFirst().orElseThrow();
        assertEquals(TOOLS.memberForecast(FIRST), forecast.handler().apply(FIRST));
        ToolSpec overview = TOOLS.specs().get(0);
        assertEquals(TOOLS.runOverview(), overview.handler().apply(null));
    }
}
