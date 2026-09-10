package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ExportFiles;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PromptsTest {

    static final String FACTS = """
            {"run": {"id": "11111111-1111-1111-1111-111111111111", "as_of": "2026-09-06", "weeks": ["2026-09-07", "2026-09-14"]},
             "team": {"id": "44444444-4444-4444-4444-444444444444", "name": "Mobile Apps"},
             "members": [{"id": "aaaaaaaa-0000-0000-0000-000000000004", "name": "Sara Tazi", "role": "TEAM_LEADER"},
                         {"id": "aaaaaaaa-0000-0000-0000-000000000005", "name": "Omar Benali", "role": "MEMBER"}],
             "model": {"champion": "xgboost"}}
            """;

    static JsonNode facts() {
        return ExportFiles.mapper().readTree(FACTS);
    }

    @Test
    void theSystemMessageStatesTheRulesAndEmbedsEverySkillOnce() {
        Prompts p = Prompts.load();
        String lower = p.systemMessage().toLowerCase();
        assertTrue(lower.contains("never invent") && lower.contains("tools") && lower.contains("json") && lower.contains("one decimal"));
        for (String name : SkillTexts.NAMES) {
            String heading = "## Skill: " + name;
            assertEquals(p.systemMessage().indexOf(heading), p.systemMessage().lastIndexOf(heading), name + " appears once");
            assertTrue(p.systemMessage().contains(heading), name);
        }
        assertFalse(p.systemMessage().contains("\n---\nname:"), "front matter is stripped from the embedded skills");
    }

    @Test
    void theUserPromptNamesRunMembersWeeksLanguageAndContract() {
        Prompts p = Prompts.load();
        String en = p.userPrompt(facts(), "en");
        assertTrue(en.contains("Mobile Apps") && en.contains("2026-09-07") && en.contains("2026-09-14") && en.contains("2026-09-06"));
        assertTrue(en.contains("member_id aaaaaaaa-0000-0000-0000-000000000004 (Sara Tazi, TEAM_LEADER)"));
        assertTrue(en.contains("member_id aaaaaaaa-0000-0000-0000-000000000005 (Omar Benali, MEMBER)"));
        JsonNode roleless = ExportFiles.mapper().readTree(FACTS.replace("\"role\": \"MEMBER\"", "\"role\": null"));
        assertTrue(p.userPrompt(roleless, "en").contains("(Omar Benali, member)"), "a null role is the default, never the word null");
        assertTrue(en.contains("get_run_overview") && en.contains("get_planned_work") && en.contains("get_rebalancing_candidates"));
        assertTrue(en.contains(p.contractSchema()));
        assertTrue(en.contains("Language: en") && en.contains("in English"));
        String fr = p.userPrompt(facts(), "fr");
        assertTrue(fr.contains("Language: fr") && fr.contains("en français"));
        assertTrue(en.trim().endsWith("Return only the JSON document."));
    }

    @Test
    void theRetryPromptListsEveryProblem() {
        String text = Prompts.load().retryPrompt(List.of("members[1].member_id: unknown member", "the answer is not valid JSON"));
        assertTrue(text.contains("- members[1].member_id: unknown member") && text.contains("- the answer is not valid JSON"));
        assertTrue(text.contains("only the JSON"));
    }

    @Test
    void theContractSchemaIsJsonWithTheTopLevelFields() {
        JsonNode schema = ExportFiles.mapper().readTree(Prompts.load().contractSchema());
        assertEquals(false, schema.path("additionalProperties").asBoolean());
        for (String field : List.of("run_summary", "members", "team_risks", "rebalancing", "suggested_adjustments", "model_notes")) {
            assertTrue(schema.path("properties").has(field), field);
        }
        assertTrue(schema.path("$defs").path("member").path("properties").has("likely_work"));
        assertTrue(schema.path("$defs").path("move").path("properties").has("task_keys"));
    }
}
