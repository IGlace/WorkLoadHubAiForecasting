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
        assertTrue(all.contains("xgboost") && all.contains("seasonal_naive"));
        assertTrue(!all.contains("chronos2") && !all.contains("tsb") && !all.contains(" gbm"), "old model names are gone");
        assertTrue(all.contains("planned_hours") && all.contains("likely_work") && all.contains("due_hours"));
        assertTrue(all.contains("40 h"), "the Java module's default capacity");
        assertTrue(all.contains("window") && all.contains("expected_window") && all.contains("five weekdays"), "the rolling horizon vocabulary");
        assertTrue(!all.contains("expected_week") && !all.contains("two-week forecast") && !all.contains("per member and week"), "the weekly horizon is gone");
    }
}
