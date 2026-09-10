package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.features.MemberDay;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.testing.SeededData;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.util.List;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;

class TruthTest {

    @Test
    void sumsLogsPerUserAndMondayWeek() {
        MemberRow ana = TestData.member("ana", TestData.TEAM);
        TaskRow t = TestData.task("1", ana.id(), LocalDate.of(2026, 8, 3).atTime(9, 0), 8);
        ForecastData data = TestData.data(List.of(ana), List.of(t), List.of(), List.of(
                TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 5), 3),
                TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 9), 1.5),
                TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 10), 2)));
        SortedMap<MemberWeek, Double> truth = Truth.realisedHours(data);
        assertEquals(4.5, truth.get(new MemberWeek(ana.id(), LocalDate.of(2026, 8, 3))), 1e-9, "Sunday belongs to the week that started Monday");
        assertEquals(2.0, truth.get(new MemberWeek(ana.id(), LocalDate.of(2026, 8, 10))), 1e-9);
        assertEquals(2, truth.size());
    }

    @Test
    void sumsLogsPerUserAndDay() {
        MemberRow ana = TestData.member("ana", TestData.TEAM);
        TaskRow t = TestData.task("1", ana.id(), LocalDate.of(2026, 8, 3).atTime(9, 0), 8);
        ForecastData data = TestData.data(List.of(ana), List.of(t), List.of(), List.of(
                TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 5), 3), TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 5), 1.5)));
        assertEquals(4.5, Truth.realisedHoursByDay(data).get(new MemberDay(ana.id(), LocalDate.of(2026, 8, 5))), 1e-9);
        assertEquals(1, Truth.realisedHoursByDay(data).size());
    }

    @Test
    void seededTruthCoversEveryLog() {
        ForecastData data = SeededData.data();
        double total = Truth.realisedHours(data).values().stream().mapToDouble(Double::doubleValue).sum();
        double logs = data.timeLogs().stream().mapToDouble(l -> l.hours()).sum();
        assertEquals(logs, total, 1e-3);
        assertTrue(total > 0);
    }
}
