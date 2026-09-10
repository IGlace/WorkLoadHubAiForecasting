package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NarrativeContract.Narrative;
import com.workloadhub.forecast.ai.NumberVerifier.Report;
import com.workloadhub.forecast.data.ExportFiles;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NumberVerifierTest {

    static final String A = "aaaaaaaa-0000-0000-0000-000000000004";
    static final String B = "aaaaaaaa-0000-0000-0000-000000000005";

    static final JsonNode FACTS = ExportFiles.mapper().readTree("""
            {"run": {"id": "r", "weeks": ["2026-09-07", "2026-09-14"], "generated_at": "2026-09-03T10:00:00", "horizons": [1, 2]},
             "team": {"id": "t", "totals": [{"week": "2026-09-07", "demand": 72.5, "capacity": 88.0}]},
             "members": [
               {"id": "%s", "name": "A", "forecast": [{"week": "2026-09-07", "demand": 52.04, "capacity": 40.0, "overload": 12.04}]},
               {"id": "%s", "name": "B", "forecast": [{"week": "2026-09-07", "demand": 20.5, "capacity": 40.0, "overload": 0.0}]}],
             "model": {"champion": "xgboost", "champion_mase": 0.913},
             "rebalancing_candidates": {
               "overloaded": [{"member_id": "%s", "name": "A", "overload_hours": 12.0}],
               "underloaded": [{"member_id": "%s", "name": "B", "spare_hours": 19.5}]}}
            """.formatted(A, B, A, B));

    static Narrative narrative(String summary, List<String> warnings, String runSummary, String extraJson) {
        String warn = String.join(",", warnings.stream().map(w -> "\"" + w + "\"").toList());
        String json = "{\"run_summary\": \"" + runSummary + "\", \"members\": ["
                + "{\"member_id\": \"" + A + "\", \"name\": \"A\", \"risk_level\": \"high\", \"summary\": \"" + summary + "\", \"patterns\": [], \"warnings\": [" + warn + "]},"
                + "{\"member_id\": \"" + B + "\", \"name\": \"B\", \"risk_level\": \"low\", \"summary\": \"fine\", \"patterns\": [], \"warnings\": []}]"
                + (extraJson.isEmpty() ? "" : ", " + extraJson) + "}";
        return NarrativeContract.parse(json);
    }

    static Narrative narrative(String summary) {
        return narrative(summary, List.of(), "ok", "");
    }

    @Test
    void factNumbersRoundToOneDecimalAndToIntegers() {
        Set<Double> nums = NumberVerifier.factNumbers(FACTS);
        assertTrue(nums.containsAll(Set.of(52.0, 12.0, 40.0, 20.5, 0.9, 1.0, 2.0)), nums.toString());
    }

    @Test
    void numbersInTextSkipDatesTimesAndPercentages() {
        assertEquals(List.of(52.0, 40.0, 0.91), NumberVerifier.numbersInText("In the week of 2026-09-07 at 10:30, demand is 52.0 h (30% above 40 h), MASE 0.91."));
    }

    @Test
    void numbersGluedToAWordAreExtracted() {
        assertEquals(List.of(0.91), NumberVerifier.numbersInText("MASE0.91"));
        assertEquals(List.of(52.5), NumberVerifier.numbersInText("demand52.5h"));
    }

    @Test
    void thousandsSeparatorsDoNotBreakDecimalCommas() {
        assertEquals(List.of(1200.0), NumberVerifier.numbersInText("1,200 h"));
        assertEquals(List.of(1200.5), NumberVerifier.numbersInText("1 200,5 h"));
        assertEquals(List.of(52.5), NumberVerifier.numbersInText("52,5 h"));
        assertEquals(List.of(12.34), NumberVerifier.numbersInText("12,34"));
    }

    @Test
    void verifiedWhenEveryNumberMatchesTheFacts() {
        Report r = NumberVerifier.verify(narrative("Demand 52.0 h against 40 h, overload 12.0 h in week 2026-09-07."), FACTS);
        assertTrue(r.ok() && r.checked() == 3 && r.unverified().isEmpty(), r.toString());
        assertTrue(r.toJson().contains("\"checked\":3") || r.toJson().contains("\"checked\" : 3"));
    }

    @Test
    void anUnverifiedNumberIsReportedWithItsField() {
        Report r = NumberVerifier.verify(narrative("Demand will reach 63.5 h.", List.of("Expect 12 h overload."), "ok", ""), FACTS);
        assertFalse(r.ok());
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("63.5") && u.contains("members[0].summary")), r.unverified().toString());
        assertEquals(2, r.checked(), "12 h matches member A's own overload of 12.04, rounded");
    }

    @Test
    void smallIntegersWithoutAnHoursUnitAreNeverFlagged() {
        assertTrue(NumberVerifier.verify(narrative("Over " + NumberVerifier.SMALL_INTEGER_ALLOWANCE + " tasks in 2 weeks, 13 weeks of history."), FACTS).ok());
    }

    @Test
    void aSmallNumberWrittenAsHoursIsChecked() {
        Report r = NumberVerifier.verify(narrative("Overload of 8 h in week 2026-09-07."), FACTS);
        assertFalse(r.ok());
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("8") && u.contains("members[0].summary")));
    }

    @Test
    void theHoursUnitIsRecognisedInItsCommonSpellings() {
        for (String written : List.of("8 h", "8h", "8 hrs", "8 hours", "8 hour", "8 heures")) {
            assertFalse(NumberVerifier.verify(narrative("Overload of " + written + "."), FACTS).ok(), written);
        }
        assertTrue(NumberVerifier.verify(narrative("8 high-priority tasks arrive."), FACTS).ok(), "a word starting with h is not a unit");
    }

    @Test
    void aMembersTextMayNotCiteAnotherMembersNumber() {
        Report r = NumberVerifier.verify(narrative("Demand is 20.5 h."), FACTS);
        assertFalse(r.ok());
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("20.5") && u.contains("members[0].summary") && u.contains("not of this field")));
    }

    @Test
    void aMembersTextMayCiteRunTeamAndModelNumbers() {
        assertTrue(NumberVerifier.verify(narrative("Demand 52.0 h of the team's 72.5 h against 88.0 h, MASE 0.91."), FACTS).ok());
    }

    @Test
    void aMembersTextMayCiteTheirOwnRebalancingCandidacyOnly() {
        Report r = NumberVerifier.verify(narrative("Overload of 12.0 h; B has 19.5 h spare."), FACTS);
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("19.5")), "B's spare hours belong in B's section");
        assertTrue(NumberVerifier.verify(narrative("Overload of 12.0 h."), FACTS).ok());
    }

    @Test
    void teamLevelTextMayCiteAnyMembersNumber() {
        assertTrue(NumberVerifier.verify(narrative("fine", List.of(), "A is at 52.0 h, B at 20.5 h against 40.0 h.", ""), FACTS).ok());
    }

    @Test
    void aRebalancingReasonMayCiteTheMovesOwnHours() {
        String move = "\"rebalancing\": [{\"from_member_id\": \"" + A + "\", \"to_member_id\": \"" + B + "\", \"week\": \"2026-09-07\", \"hours\": 6.5,"
                + " \"reason\": \"Move 6.5 h of A's 12.0 h overload to B, who has 19.5 h spare.\", \"confidence\": \"high\"}]";
        assertTrue(NumberVerifier.verify(narrative("fine", List.of(), "ok", move), FACTS).ok());
    }

    @Test
    void aValueThatOnlyRoundsOntoAnUnrelatedFactIsFlagged() {
        Report r = NumberVerifier.verify(narrative("Overload of 3.5 h."), FACTS);
        assertFalse(r.ok());
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("3.5")));
    }

    @Test
    void anAdjustmentReasonMayCiteItsOwnDeltaHours() {
        String adj = "\"suggested_adjustments\": [{\"member_id\": \"" + A + "\", \"week\": \"2026-09-07\", \"delta_hours\": -2.5,"
                + " \"reason\": \"Trim 2.5 h: the audit day is already counted in capacity.\"}]";
        assertTrue(NumberVerifier.verify(narrative("fine", List.of(), "ok", adj), FACTS).ok());
    }

    @Test
    void likelyWorkTextIsScopedLikeTheMembersOtherText() {
        String likely = "\"likely_work\": [{\"statement\": \"WEB-3 probably lands.\", \"evidence\": \"spare 19.5 h\", \"confidence\": \"low\"}]";
        String json = "{\"run_summary\": \"ok\", \"members\": [{\"member_id\": \"" + A + "\", \"name\": \"A\", \"risk_level\": \"low\", \"summary\": \"fine\", " + likely + "},"
                + "{\"member_id\": \"" + B + "\", \"name\": \"B\", \"risk_level\": \"low\", \"summary\": \"fine\"}]}";
        Report r = NumberVerifier.verify(NarrativeContract.parse(json), FACTS);
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("members[0].likely_work[0].evidence") && u.contains("19.5")), r.unverified().toString());
    }
}
