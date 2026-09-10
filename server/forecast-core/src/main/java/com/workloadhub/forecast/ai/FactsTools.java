package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.data.ExportFiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** The nine tools Copilot may call, reading one run's stored facts and never the database. */
final class FactsTools {

    static final List<String> NAMES = List.of("get_run_overview", "get_member_history", "get_member_forecast", "get_member_patterns",
            "get_member_open_tasks", "get_member_capacity", "get_project_timelines", "get_rebalancing_candidates", "get_planned_work");

    private final JsonNode facts;
    private final Map<String, JsonNode> members = new TreeMap<>();

    FactsTools(JsonNode facts) {
        this.facts = facts;
        for (JsonNode m : facts.path("members")) {
            members.put(m.path("id").asText(), m);
        }
    }

    List<ToolSpec> specs() {
        return List.of(
                new ToolSpec("get_run_overview", "Run, team, member list, model quality, rebalancing candidates and data quality. Call this first.", false,
                        id -> runOverview()),
                new ToolSpec("get_member_history", "Last 13 weeks of task arrivals (hours and counts), logged hours of the last 4 weeks, unlogged and reopened tasks for one member.",
                        true, this::memberHistory),
                new ToolSpec("get_member_forecast", "Forecast rows per window (five weekdays each: demand, low, high, capacity, overload, open, new and planned hours, due hours) for one member.",
                        true, this::memberForecast),
                new ToolSpec("get_member_patterns", "Deterministic pattern statistics for one member (assignment style, weekday rhythm, trend, estimate bias, cycle time, lateness, cluster, backlog).",
                        true, this::memberPatterns),
                new ToolSpec("get_member_open_tasks", "Open tasks of one member with keys, estimates, due dates, overdue flags and project keys.", true,
                        this::memberOpenTasks),
                new ToolSpec("get_member_capacity", "Capacity, demand and overload per forecast window and per day for one member, with working days and absence hours.", true,
                        this::memberCapacity),
                new ToolSpec("get_project_timelines", "Projects of the team with status, open and backlog task counts and the first due date, plus the forecast windows.",
                        false, id -> projectTimelines()),
                new ToolSpec("get_rebalancing_candidates", "Members with overload and members with spare capacity over the two windows.", false,
                        id -> rebalancingCandidates()),
                new ToolSpec("get_planned_work", "The team's planned backlog per project and, per member, the planned tasks, project roles and recent mix (likely work).",
                        false, id -> plannedWork()));
    }

    private static Object plain(JsonNode node) {
        return node.isMissingNode() ? null : ExportFiles.mapper().treeToValue(node, Object.class);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> unknown(String id) {
        return map("error", "unknown member_id " + id, "known_member_ids", new ArrayList<>(members.keySet()));
    }

    Map<String, Object> runOverview() {
        List<Object> list = new ArrayList<>();
        for (JsonNode m : facts.path("members")) {
            list.add(map("id", m.path("id").asText(), "name", m.path("name").asText(), "role", m.path("role").asText(null)));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> team = (Map<String, Object>) plain(facts.path("team"));
        team.remove("planned_backlog");
        return map("run", plain(facts.path("run")), "team", team, "members", list, "model", plain(facts.path("model")),
                "rebalancing_candidates", plain(facts.path("rebalancing_candidates")), "data_quality", plain(facts.path("data_quality")),
                "pending_holidays", plain(facts.path("pending_holidays")),
                "how_to_proceed", "Call get_member_forecast, get_member_capacity, get_member_patterns, get_member_history and get_member_open_tasks"
                        + " for every member id listed here, then get_project_timelines, get_planned_work and get_rebalancing_candidates, then answer with the JSON document.");
    }

    Map<String, Object> memberHistory(String id) {
        JsonNode m = members.get(id);
        if (m == null) {
            return unknown(id);
        }
        return map("member_id", id, "name", m.path("name").asText(), "history_13w", plain(m.path("history_13w")), "logged_hours_4w", plain(m.path("logged_hours_4w")),
                "unlogged_tasks", plain(m.path("unlogged_tasks")), "reopened_tasks", plain(m.path("reopened_tasks")));
    }

    Map<String, Object> memberForecast(String id) {
        JsonNode m = members.get(id);
        return m == null ? unknown(id) : map("member_id", id, "name", m.path("name").asText(), "forecast", plain(m.path("forecast")));
    }

    Map<String, Object> memberPatterns(String id) {
        JsonNode m = members.get(id);
        return m == null ? unknown(id) : map("member_id", id, "name", m.path("name").asText(), "patterns", plain(m.path("patterns")));
    }

    Map<String, Object> memberOpenTasks(String id) {
        JsonNode m = members.get(id);
        return m == null ? unknown(id) : map("member_id", id, "name", m.path("name").asText(), "open_tasks", plain(m.path("open_tasks")));
    }

    Map<String, Object> memberCapacity(String id) {
        JsonNode m = members.get(id);
        if (m == null) {
            return unknown(id);
        }
        List<Object> windows = new ArrayList<>();
        for (JsonNode row : m.path("forecast")) {
            windows.add(map("window", plain(row.path("window")), "start", row.path("start").asText(), "end", row.path("end").asText(),
                    "capacity", plain(row.path("capacity")), "demand", plain(row.path("demand")), "overload", plain(row.path("overload")),
                    "working_days", plain(row.path("working_days")), "absence_hours", plain(row.path("absence_hours"))));
        }
        return map("member_id", id, "name", m.path("name").asText(), "windows", windows, "days", plain(m.path("days")));
    }

    Map<String, Object> projectTimelines() {
        return map("windows", plain(facts.path("run").path("windows")), "projects", plain(facts.path("projects")));
    }

    Map<String, Object> rebalancingCandidates() {
        return map("overloaded", plain(facts.path("rebalancing_candidates").path("overloaded")),
                "underloaded", plain(facts.path("rebalancing_candidates").path("underloaded")));
    }

    Map<String, Object> plannedWork() {
        List<Object> list = new ArrayList<>();
        for (JsonNode m : facts.path("members")) {
            list.add(map("id", m.path("id").asText(), "name", m.path("name").asText(), "likely_work", plain(m.path("likely_work"))));
        }
        return map("planned_backlog", plain(facts.path("team").path("planned_backlog")), "members", list);
    }
}
