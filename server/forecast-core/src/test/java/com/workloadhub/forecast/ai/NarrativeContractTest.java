package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NarrativeContract.ContractException;
import com.workloadhub.forecast.ai.NarrativeContract.Narrative;
import com.workloadhub.forecast.data.ExportFiles;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class NarrativeContractTest {

    static final String A = "aaaaaaaa-0000-0000-0000-000000000004";
    static final String B = "aaaaaaaa-0000-0000-0000-000000000005";

    static final String FACTS = """
            {"run": {"id": "r", "as_of": "2026-09-03", "weeks": ["2026-09-07", "2026-09-14"]},
             "team": {"id": "t", "name": "Web Platform"},
             "members": [
               {"id": "%s", "name": "Sara Tazi", "open_tasks": [{"key": "WEB-1"}, {"key": "WEB-2"}],
                "forecast": [{"week": "2026-09-07", "demand": 52.0, "capacity": 40.0, "overload": 12.0},
                             {"week": "2026-09-14", "demand": 40.0, "capacity": 40.0, "overload": 0.0}]},
               {"id": "%s", "name": "Omar Benali", "open_tasks": [{"key": "WEB-9"}],
                "forecast": [{"week": "2026-09-07", "demand": 20.0, "capacity": 40.0, "overload": 0.0},
                             {"week": "2026-09-14", "demand": 35.0, "capacity": 40.0, "overload": 0.0}]}]}
            """.formatted(A, B);

    static JsonNode facts() {
        return ExportFiles.mapper().readTree(FACTS);
    }

    static ObjectNode good() {
        String json = """
                {"run_summary": "Two members, one overloaded in week one.",
                 "members": [
                   {"member_id": "%s", "name": "Sara Tazi", "risk_level": "high",
                    "summary": "Sara has 52.0 h of demand against 40.0 h of capacity in the week of 2026-09-07.",
                    "patterns": [{"kind": "assignment_style", "statement": "Mostly project-driven work.", "evidence": "share_project 0.6"}],
                    "warnings": ["Overload of 12.0 h in the week of 2026-09-07."],
                    "likely_work": [{"statement": "WEB-3 is likely to land.", "evidence": "planned WEB-3 share 0.7", "confidence": "high"}]},
                   {"member_id": "%s", "name": "Omar Benali", "risk_level": "low", "summary": "Spare capacity.", "patterns": [], "warnings": []}],
                 "team_risks": [{"title": "Week one overload", "detail": "One member above capacity.", "severity": "medium", "member_ids": ["%s"]}],
                 "rebalancing": [{"from_member_id": "%s", "to_member_id": "%s", "week": "2026-09-07", "hours": 8.0,
                                  "reason": "Omar has 20.0 h spare.", "confidence": "medium", "task_keys": ["WEB-1"]}],
                 "suggested_adjustments": [],
                 "model_notes": "Champion xgboost, MASE 0.9."}
                """.formatted(A, B, A, A, B);
        return (ObjectNode) ExportFiles.mapper().readTree(json);
    }

    static String text(JsonNode n) {
        return ExportFiles.mapper().writeValueAsString(n);
    }

    @Test
    void parsesPlainAndFencedJson() {
        Narrative n = NarrativeContract.parse(text(good()));
        assertEquals("high", n.members().get(0).riskLevel());
        assertEquals(LocalDate.of(2026, 9, 7), n.rebalancing().get(0).week());
        assertEquals(List.of("WEB-1"), n.rebalancing().get(0).taskKeys());
        assertEquals("high", n.members().get(0).likelyWork().get(0).confidence());
        assertEquals(List.of(), n.members().get(1).likelyWork(), "absent optional lists are empty");
        Narrative fenced = NarrativeContract.parse("```json\n" + text(good()) + "\n```");
        assertEquals(n.runSummary(), fenced.runSummary());
        Narrative wrapped = NarrativeContract.parse("Here it is: " + text(good()) + " Done.");
        assertEquals(n.modelNotes(), wrapped.modelNotes());
        assertTrue(n.toJson().startsWith("{") && n.toJson().contains("\"run_summary\""));
    }

    @Test
    void rejectsInvalidJsonUnknownFieldsAndBadValues() {
        ContractException notJson = assertThrows(ContractException.class, () -> NarrativeContract.parse("Here is my analysis: {"));
        assertTrue(notJson.problems().get(0).contains("not valid JSON"), notJson.problems().toString());
        ObjectNode extra = good();
        extra.put("extra_field", 1);
        assertTrue(problems(extra).stream().anyMatch(p -> p.contains("extra_field")));
        ObjectNode badLevel = good();
        ((ObjectNode) badLevel.path("members").get(0)).put("risk_level", "severe");
        assertTrue(problems(badLevel).stream().anyMatch(p -> p.startsWith("members[0].risk_level")));
        ObjectNode badKind = good();
        ((ObjectNode) badKind.path("members").get(0).path("patterns").get(0)).put("kind", "vibe");
        assertTrue(problems(badKind).stream().anyMatch(p -> p.startsWith("members[0].patterns[0].kind")));
        ObjectNode tooLong = good();
        ((ObjectNode) tooLong.path("members").get(0)).put("summary", "x".repeat(1201));
        assertTrue(problems(tooLong).stream().anyMatch(p -> p.startsWith("members[0].summary") && p.contains("1200")));
        ObjectNode zeroHours = good();
        ((ObjectNode) zeroHours.path("rebalancing").get(0)).put("hours", 0);
        assertTrue(problems(zeroHours).stream().anyMatch(p -> p.startsWith("rebalancing[0].hours")));
        ObjectNode badDate = good();
        ((ObjectNode) badDate.path("rebalancing").get(0)).put("week", "next monday");
        assertTrue(problems(badDate).stream().anyMatch(p -> p.startsWith("rebalancing[0].week")));
        ObjectNode fiveLikely = good();
        var list = ((ObjectNode) fiveLikely.path("members").get(0)).putArray("likely_work");
        for (int i = 0; i < 5; i++) {
            list.addObject().put("statement", "s").put("evidence", "e").put("confidence", "low");
        }
        assertTrue(problems(fiveLikely).stream().anyMatch(p -> p.startsWith("members[0].likely_work") && p.contains("4")));
        ObjectNode missing = good();
        ((ObjectNode) missing.path("members").get(1)).remove("summary");
        assertTrue(problems(missing).stream().anyMatch(p -> p.startsWith("members[1].summary")));
    }

    static List<String> problems(JsonNode n) {
        return assertThrows(ContractException.class, () -> NarrativeContract.parse(text(n))).problems();
    }

    @Test
    void crossChecksMembersWeeksAndMoves() {
        assertEquals(List.of(), NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(good())), facts()));

        ObjectNode unknown = good();
        ((ObjectNode) unknown.path("members").get(1)).put("member_id", "nobody");
        ((ObjectNode) unknown.path("rebalancing").get(0)).put("week", "2026-09-28");
        List<String> p1 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(unknown)), facts());
        assertTrue(p1.stream().anyMatch(s -> s.contains("nobody")) && p1.stream().anyMatch(s -> s.contains("2026-09-28")), p1.toString());
        assertTrue(p1.stream().anyMatch(s -> s.contains("missing") && s.contains(B)), "the replaced member is now missing");

        ObjectNode onlyOne = good();
        ((tools.jackson.databind.node.ArrayNode) onlyOne.path("members")).remove(1);
        List<String> p2 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(onlyOne)), facts());
        assertTrue(p2.stream().anyMatch(s -> s.contains("missing") && s.contains(B)), p2.toString());

        ObjectNode dup = good();
        ((tools.jackson.databind.node.ArrayNode) dup.path("members")).add(dup.path("members").get(0).deepCopy());
        List<String> p3 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(dup)), facts());
        assertTrue(p3.stream().anyMatch(s -> s.contains("more than once") && s.contains(A)), p3.toString());

        ObjectNode same = good();
        ((ObjectNode) same.path("rebalancing").get(0)).put("to_member_id", A);
        List<String> p4 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(same)), facts());
        assertTrue(p4.stream().anyMatch(s -> s.contains("same member")), p4.toString());

        ObjectNode beyond = good();
        ((ObjectNode) beyond.path("rebalancing").get(0)).put("hours", 20.0);
        List<String> p5 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(beyond)), facts());
        assertTrue(p5.stream().anyMatch(s -> s.contains("overload")), p5.toString());

        ObjectNode overfill = good();
        ((ObjectNode) overfill.path("rebalancing").get(0)).put("hours", 25.0);
        ObjectNode richFacts = (ObjectNode) facts();
        ((ObjectNode) richFacts.path("members").get(0).path("forecast").get(0)).put("overload", 30.0);
        List<String> p6 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(overfill)), richFacts);
        assertTrue(p6.stream().anyMatch(s -> s.contains("capacity")), p6.toString());

        ObjectNode wrongTask = good();
        ((ObjectNode) wrongTask.path("rebalancing").get(0)).putArray("task_keys").add("WEB-9").add("WEB-404");
        List<String> p7 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(wrongTask)), facts());
        assertTrue(p7.stream().anyMatch(s -> s.contains("WEB-9")) && p7.stream().anyMatch(s -> s.contains("WEB-404")), p7.toString());

        ObjectNode zeroDelta = good();
        zeroDelta.putArray("suggested_adjustments").addObject().put("member_id", A).put("week", "2026-09-07").put("delta_hours", 0).put("reason", "rest week");
        List<String> p8 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(zeroDelta)), facts());
        assertTrue(p8.stream().anyMatch(s -> s.contains("delta_hours")), p8.toString());

        ObjectNode riskUnknown = good();
        ((ObjectNode) riskUnknown.path("team_risks").get(0)).putArray("member_ids").add("ghost");
        List<String> p9 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(riskUnknown)), facts());
        assertTrue(p9.stream().anyMatch(s -> s.contains("ghost")), p9.toString());
    }
}
