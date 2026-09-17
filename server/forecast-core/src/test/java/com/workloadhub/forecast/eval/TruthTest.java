package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.features.MemberDay;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TruthTest {

    @Test
    void sumsLogsPerUserAndDay() {
        MemberRow ana = TestData.member("ana", TestData.TEAM);
        TaskRow t = TestData.task("1", ana.id(), LocalDate.of(2026, 8, 3).atTime(9, 0), 8);
        ForecastData data = TestData.data(List.of(ana), List.of(t), List.of(), List.of(
                TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 5), 3), TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 5), 1.5)));
        assertEquals(4.5, Truth.realisedHoursByDay(data).get(new MemberDay(ana.id(), LocalDate.of(2026, 8, 5))), 1e-9);
        assertEquals(1, Truth.realisedHoursByDay(data).size());
    }
}
