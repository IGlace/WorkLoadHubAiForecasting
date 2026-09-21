package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SkillTextsTest {

    @Test
    void sixSkillsLoadInPromptOrderWithTheirFrontMatter() {
        List<SkillTexts.Skill> skills = SkillTexts.load();
        assertEquals(List.of("whf-domain", "whf-forecast-interpretation", "whf-pattern-discovery", "whf-likely-work", "whf-rebalancing-advice",
                "whf-report-style"), skills.stream().map(SkillTexts.Skill::name).toList());
        assertEquals(SkillTexts.NAMES, skills.stream().map(SkillTexts.Skill::name).toList());
        for (SkillTexts.Skill s : skills) {
            assertTrue(s.text().startsWith("---\nname: " + s.name() + "\n"), s.name() + " front matter");
            assertTrue(s.text().contains("\ndescription: "), s.name());
            assertTrue(s.text().length() < 6000, s.name() + " should stay short");
        }
    }

    @Test
    void theSkillsSpeakTheJavaFactsVocabulary() {
        String all = String.join("\n", SkillTexts.load().stream().map(SkillTexts.Skill::text).toList());
        assertTrue(all.contains("xgboost"));
        assertTrue(!all.contains("seasonal_naive") && !all.contains("chronos2") && !all.contains("tsb") && !all.contains(" gbm"),
                "old and retired model names are gone");
        assertTrue(all.contains("mae") && all.contains("mean_actual_hours") && all.contains("thin_history"), "the model block's own vocabulary");
        assertTrue(all.contains("likely_work") && all.contains("due_hours"));
        assertTrue(all.contains("backlog_excess_hrs") && all.contains("due_excess_hrs") && all.contains("overdue_hrs"),
                "the pressure facts a forecast of logged hours cannot show on its own (design 2026-09-13, section 8)");
        assertTrue(all.contains("backlog_pressed") && all.contains("deadline_pressed"), "the two rebalancing_candidates lists the pressure facts feed");
        assertTrue(all.contains("logged_weekday_shares"), "the weekday shape a predicted week is split by");
        assertTrue(all.contains("44 h") && all.contains("8.8 h"), "the Java module's default capacity (ForecastProperties.defaultWeeklyHours)");
        assertTrue(!all.contains("40 h"), "the old 40 h default is gone from the skills");
        assertTrue(all.contains("window") && all.contains("five weekdays"), "the rolling horizon vocabulary");
        assertTrue(!all.contains("expected_week") && !all.contains("two-week forecast") && !all.contains("per member and week"), "the weekly horizon is gone");
        assertTrue(all.contains("pending_leaves") && all.contains("planned_hours"), "the 2026-09-17 facts");
        assertTrue(all.contains("assigned to them and not finished"), "open, defined for the leader (design 2026-09-17, section 8)");
        assertTrue(!all.contains("team_capacity") && !all.contains("share_manual") && !all.contains("share_project"), "removed facts");
        assertTrue(all.contains("approved leave"), "capacity is the calendar and the approved leaves");
        assertTrue(all.contains("report to them directly"), "a team is a leader and their direct reports (design 2026-09-21)");
        assertTrue(all.contains("team.parent_team_id"), "the skill team leader a team risk escalates to");
        assertTrue(!all.contains("a team without a manager"), "the old parentless-team definition of a department is gone");
    }

    @Test
    void noSkillNamesARemovedFactKey() {
        String all = String.join("\n", SkillTexts.load().stream().map(SkillTexts.Skill::text).toList());
        for (String key : List.of("open_hours", "new_hours", "planned_backlog", "planned_basis", "champion", "champion_mase",
                "forced_model", "mase_by_model", "unavailable", "expected_window", "hours_in_window", "fresh_hours")) {
            assertTrue(!all.contains(key), key + " is still named by a skill");
        }
    }
}
