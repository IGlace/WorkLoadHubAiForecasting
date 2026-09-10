package com.workloadhub.forecast.ai;

import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import tools.jackson.databind.JsonNode;

/** What Copilot is told: the rules and the skills (system), the task and the contract (user), the problems (retry). */
public final class Prompts {

    public static final Set<String> SUPPORTED_LANGUAGES = Set.of("en", "fr");

    static final String RULES = """
            You are the workload analyst inside WorkloadHub AI Forecasting. A deterministic engine has
            already computed every number: demand, capacity, overload, intervals, pattern statistics, planned work and model quality.
            Your job is to read those facts through the tools and explain them to a team leader.

            Hard rules:
            1. Never invent, estimate or recompute a number. Every figure you write must come from a tool result,
               copied exactly as given (hours with one decimal, for example 12.5). If a number is not in the tools, do not write it.
            2. Use the tools. Start with get_run_overview, then query every member listed there, then the project timelines,
               the planned work and the rebalancing candidates. Do not answer before you have looked at every member.
            3. Answer with one JSON document that matches the contract in the user message. No prose before or after it,
               no Markdown fences. Field names and enumerations must match exactly. Member ids are the id strings from
               get_run_overview, copied exactly; task keys are copied exactly.
            4. Patterns need evidence: quote the statistic (name and value) that supports each statement.
            5. Rebalancing moves go from a member with overload to a member with spare capacity in the same week,
               respect the target's capacity, name the tasks moved, and give the hours moved and the reason.
            6. Write in the language given in the user message, plainly, for a busy team leader.

            The product skills below are the rules of this domain. Follow them.
            """;

    private final String systemMessage;
    private final String contractSchema;

    private Prompts(String systemMessage, String contractSchema) {
        this.systemMessage = systemMessage;
        this.contractSchema = contractSchema;
    }

    public static Prompts load() {
        StringBuilder sb = new StringBuilder(RULES);
        for (SkillTexts.Skill s : SkillTexts.load()) {
            sb.append("\n## Skill: ").append(s.name()).append("\n\n").append(s.body()).append('\n');
        }
        return new Prompts(sb.toString(), SkillTexts.resource("ai/contract.schema.json").strip());
    }

    public String systemMessage() {
        return systemMessage;
    }

    public String contractSchema() {
        return contractSchema;
    }

    public String userPrompt(JsonNode facts, String language) {
        JsonNode run = facts.path("run");
        JsonNode team = facts.path("team");
        StringJoiner members = new StringJoiner(", ");
        for (JsonNode m : facts.path("members")) {
            members.add("member_id " + m.path("id").asText() + " (" + m.path("name").asText() + ", " + m.path("role").asText("member") + ")");
        }
        StringJoiner windows = new StringJoiner(" and ");
        for (JsonNode w : run.path("windows")) {
            windows.add(w.path("start").asText() + ".." + w.path("end").asText());
        }
        String languageLine = language.equals("fr")
                ? "Language: fr. Rédigez chaque champ narratif en français; gardez les noms des membres et les clés des tâches tels quels."
                : "Language: en. Write every narrative field in English; keep member names and task keys as given.";
        return "Analyse forecast run " + run.path("id").asText() + " for team '" + team.path("name").asText() + "' (team id " + team.path("id").asText()
                + "), run date " + run.path("as_of").asText() + ", forecast windows " + windows + ".\n" + languageLine + "\n"
                + "Members to cover, each exactly once: " + members + ".\n\n"
                + "Procedure: 1) get_run_overview; 2) for each member: get_member_forecast, get_member_capacity, get_member_patterns, "
                + "get_member_history, get_member_open_tasks; 3) get_project_timelines; 4) get_planned_work; 5) get_rebalancing_candidates; "
                + "6) write the JSON document.\n\n"
                + "Contract (JSON Schema):\n" + contractSchema + "\n\n"
                + "Return only the JSON document.";
    }

    public String retryPrompt(List<String> problems) {
        StringBuilder sb = new StringBuilder("Your previous answer was rejected for these reasons:\n");
        for (String p : problems) {
            sb.append("- ").append(p).append('\n');
        }
        sb.append("\nFix every point and return only the JSON document, with no text around it.");
        return sb.toString();
    }
}
