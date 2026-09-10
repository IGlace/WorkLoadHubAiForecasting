package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportTest {

    @Test
    void writesTheThreeFilesWithThePythonColumns(@TempDir Path dir) throws Exception {
        LocalDate origin = LocalDate.of(2026, 8, 17);
        UUID team = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        EvalResult result = new EvalResult(
                List.of(new ScoreRow("xgboost", 1, origin, "mase", 0.8), new ScoreRow("xgboost", 1, origin, "seconds", 1.5),
                        new ScoreRow("xgboost", 2, origin, "mase", 0.9), new ScoreRow("xgboost", 2, origin, "seconds", Double.NaN),
                        new ScoreRow("seasonal_naive", 1, origin, "mase", 1.0)),
                List.of(new DemandRow("xgboost", origin, team, member, 1, origin.plusWeeks(1).plusDays(1), origin.plusWeeks(2), 30, 28, 40, 20, 8, 2),
                        new DemandRow("xgboost", origin, team, member, 2, origin.plusWeeks(2).plusDays(1), origin.plusWeeks(3), 45, 30, 40, 40, 5, 0)),
                Map.of(), Truth.SOURCE, 3.2, List.of(origin));
        Report.write(result, new EvalConfig(LocalDate.of(2026, 9, 6), 1, List.of(), List.of()), Map.of("tasks", "2"), Map.of("java", "21"), dir);
        List<String> scores = Files.readAllLines(dir.resolve("scores.csv"));
        assertEquals("model,horizon,origin,metric,value", scores.get(0));
        assertEquals("xgboost,1,2026-08-17,mase,0.8", scores.get(1));
        assertTrue(scores.stream().anyMatch(l -> l.equals("xgboost,2,2026-08-17,seconds,")), "NaN is an empty cell");
        List<String> demand = Files.readAllLines(dir.resolve("demand.csv"));
        assertEquals("model,origin,team_id,member_id,window,window_start,window_end,forecast,truth,capacity,open_hours,new_hours,planned_hours", demand.get(0));
        assertEquals(3, demand.size());
        String summary = Files.readString(dir.resolve("summary.md"));
        assertTrue(summary.startsWith("# Forecast evaluation, as of 2026-09-06"));
        assertTrue(summary.contains("| xgboost | 1 |") && summary.contains("| seasonal_naive | 1 |"));
        assertTrue(summary.contains("## Level B") && summary.contains("overload_precision"));
        assertTrue(summary.contains("- tasks: 2") && summary.contains("- java: 21"));
        assertTrue(summary.contains("| xgboost | 8.500 |"), "demand MAE (2 + 15) / 2 in Level B");
    }
}
