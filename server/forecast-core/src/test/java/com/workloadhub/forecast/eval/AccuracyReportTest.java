package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccuracyReportTest {

    @Test
    void writesTheRowsAndTheScores(@TempDir Path dir) throws Exception {
        UUID team = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID user = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID run = UUID.fromString("50000000-0000-0000-0000-000000000001");
        AccuracyResult result = new AccuracyResult(team, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 6),
                List.of(new AccuracyRow(user, LocalDate.of(2026, 8, 20), run, 1, 8, 6.5, 8, false, false),
                        new AccuracyRow(user, LocalDate.of(2026, 8, 21), run, 2, 10, 9, 8, true, true)),
                List.of(new AccuracyScore("team", team.toString(), 2, 1.25, 1.25, 0.5, 2, 1.0, 1.0),
                        new AccuracyScore("member", user.toString(), 2, 1.25, 1.25, Double.NaN, 0, 1.0, 1.0),
                        new AccuracyScore("lead", "1", 1, 2.0, 2.0, 0.4, 1, Double.NaN, Double.NaN)), 3);
        AccuracyReport.write(result, dir);
        List<String> csv = Files.readAllLines(dir.resolve("accuracy.csv"));
        assertEquals("member_id,day,run_id,lead,forecast,truth,capacity,forecast_overload,actual_overload", csv.get(0));
        assertEquals(user + ",2026-08-20," + run + ",1,8,6.5,8,0,0", csv.get(1));
        assertEquals(user + ",2026-08-21," + run + ",2,10,9,8,1,1", csv.get(2));
        assertEquals(3, csv.size());
        String summary = Files.readString(dir.resolve("summary.md"));
        assertTrue(summary.startsWith("# Forecast accuracy, team " + team + ", 2026-08-20 to 2026-09-02, evaluated 2026-09-06"));
        assertTrue(summary.contains("Truth: " + Truth.SOURCE));
        assertTrue(summary.contains("| scope | key | n | mae | bias | mase | mase_n | overload_precision | overload_recall |"));
        assertTrue(summary.contains("| team | " + team + " | 2 | 1.25 | 1.25 | 0.5 | 2 | 1 | 1 |"));
        assertTrue(summary.contains("| member | " + user + " | 2 | 1.25 | 1.25 |  | 0 | 1 | 1 |"), "NaN is an empty cell");
        assertTrue(summary.contains("| lead | 1 | 1 | 2 | 2 | 0.4 | 1 |  |  |"));
        assertTrue(summary.contains("MASE compares each day with the same weekday one week earlier"), "the MASE caption");
        assertTrue(summary.contains("3 weekdays were holidays or full absences and are not scored"), "the non-working count");
    }
}
