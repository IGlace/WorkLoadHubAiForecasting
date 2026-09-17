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
            "get_member_open_tasks", "get_member_capacity", "get_project_timelines", "get_rebalancing_candidates", "get_likely_work");

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
                new ToolSpec("get_member_forecast", "Forecast rows per window (five weekdays each: demand, low, high, capacity, overload, due hours) and the day-by-day rows for one member.",
                        true, this::memberForecast),
                new ToolSpec("get_member_patterns", "Deterministic pattern statistics for one member (assignment style, weekday rhythm, trend, estimate bias, cycle time, lateness, cluster, backlog).",
                        true, this::memberPatterns),
                new ToolSpec("get_member_open_tasks", "Open tasks of one member with keys, estimates, due dates, overdue flags and project keys.", true,
                        this::memberOpenTasks),
                new ToolSpec("get_member_capacity", "Capacity, demand, overload and the two pressure figures (backlog_excess_hrs, due_excess_hrs)"
                        + " per forecast window and per day for one member, with working days and absence hours.", true,
                        this::memberCapacity),
                new ToolSpec("get_project_timelines", "Projects of the team with status, open and backlog task counts and the first due date, plus the forecast windows.",
                        false, id -> projectTimelines()),
                new ToolSpec("get_rebalancing_candidates", "The four lists of members to act on, over the run's windows: overloaded, underloaded"
                        + " (spare capacity), backlog_pressed (open work the run does not absorb) and deadline_pressed (work due inside a window"
                        + " beyond what the window holds).", false,
                        id -> rebalancingCandidates()),
                new ToolSpec("get_likely_work", "Per member, the likely-work signals: project roles held on live projects and the recent task-type mix.",
                        false, id -> likelyWork()));
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
        return map("run", plain(facts.path("run")), "team", team, "members", list, "model", plain(facts.path("model")),
                "rebalancing_candidates", plain(facts.path("rebalancing_candidates")), "data_quality", plain(facts.path("data_quality")),
                "pending_holidays", plain(facts.path("pending_holidays")),
                "how_to_proceed", "Call get_member_forecast, get_member_capacity, get_member_patterns, get_member_history and get_member_open_tasks"
                        + " for every member id listed here, then get_project_timelines, get_likely_work and get_rebalancing_candidates, then answer with the JSON document.");
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
        return m == null ? unknown(id)
                : map("member_id", id, "name", m.path("name").asText(), "forecast", plain(m.path("forecast")), "days", plain(m.path("days")));
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
            // The two pressure figures travel with overload (design 2026-09-13, section 8.2): this is the tool
            // that serves overload per window, and whf-forecast-interpretation tells Copilot to read the three
            // together — a zero overload beside an above-zero backlog_excess_hrs is not a member who is fine.
            windows.add(map("window", plain(row.path("window")), "start", row.path("start").asText(), "end", row.path("end").asText(),
                    "capacity", plain(row.path("capacity")), "demand", plain(row.path("demand")), "overload", plain(row.path("overload")),
                    "working_days", plain(row.path("working_days")), "absence_hours", plain(row.path("absence_hours")),
                    "backlog_excess_hrs", plain(row.path("backlog_excess_hrs")), "due_excess_hrs", plain(row.path("due_excess_hrs")),
                    "planned_hours", plain(row.path("planned_hours"))));
        }
        return map("member_id", id, "name", m.path("name").asText(), "windows", windows, "days", plain(m.path("days")));
    }

    Map<String, Object> projectTimelines() {
        return map("windows", plain(facts.path("run").path("windows")), "projects", plain(facts.path("projects")));
    }

    /**
     * The whole {@code rebalancing_candidates} node, as {@code get_run_overview} already serves it: whitelisting
     * keys here dropped {@code backlog_pressed} and {@code deadline_pressed} at the tool boundary (design
     * 2026-09-13, ruling 18.5), while whf-rebalancing-advice tells Copilot that {@code deadline_pressed} is the
     * strongest case for moving work.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> rebalancingCandidates() {
        Map<String, Object> node = (Map<String, Object>) plain(facts.path("rebalancing_candidates"));
        return node == null ? map() : node;
    }

    Map<String, Object> likelyWork() {
        List<Object> list = new ArrayList<>();
        for (JsonNode m : facts.path("members")) {
            JsonNode lw = m.path("likely_work");
            list.add(map("id", m.path("id").asText(), "name", m.path("name").asText(),
                    "project_roles", plain(lw.path("project_roles")), "recent_mix", plain(lw.path("recent_mix"))));
        }
        return map("members", list);
    }
}
