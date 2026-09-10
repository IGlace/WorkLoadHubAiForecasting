package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklySeriesTest {

    static final MemberRow ANA = TestData.member("ana", TestData.TEAM);
    static final MemberRow BEN = TestData.member("ben", TestData.TEAM);
    static final LocalDate W1 = LocalDate.of(2026, 8, 3);

    @Test
    void sumsEstimatesPerAssigneeAndWeekAndSplitsFreshFromBacklog() {
        LocalDateTime created = W1.atTime(9, 0);
        TaskRow fresh = TestData.task("1", ANA.id(), created, 8);
        TaskRow lagged = TestData.task("2", ANA.id(), created, 5);
        TaskRow nextWeek = TestData.task("3", ANA.id(), created.plusWeeks(1), 3);
        TaskRow bens = TestData.task("4", BEN.id(), created, 2);
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(fresh, lagged, nextWeek, bens),
                List.of(TestData.assignee(lagged.id(), ANA.fullName(), created.plusDays(3))), List.of());
        WeeklySeries s = WeeklySeries.build(Lifecycle.derive(data), List.of(ANA, BEN), List.of(W1, W1.plusWeeks(1), W1.plusWeeks(2)));
        assertEquals(new WeeklySeries.Cell(13.0, 8.0, 2, 1), s.cell(ANA.id(), W1));
        assertEquals(new WeeklySeries.Cell(3.0, 3.0, 1, 1), s.cell(ANA.id(), W1.plusWeeks(1)));
        assertEquals(WeeklySeries.Cell.ZERO, s.cell(ANA.id(), W1.plusWeeks(2)));
        assertEquals(new WeeklySeries.Cell(2.0, 2.0, 1, 1), s.cell(BEN.id(), W1));
        assertEquals(List.of(8.0, 3.0, 0.0), java.util.Arrays.stream(s.fresh(ANA.id())).boxed().toList());
        assertEquals(List.of(13.0, 3.0, 0.0), java.util.Arrays.stream(s.est(ANA.id())).boxed().toList());
    }

    @Test
    void arrivalsOutsideTheWeeksOrToOtherPeopleAreIgnored() {
        LocalDateTime created = W1.minusWeeks(1).atTime(9, 0);
        TaskRow early = TestData.task("1", ANA.id(), created, 8);
        TaskRow unassigned = TestData.task("2", null, created, 8);
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(early, unassigned), List.of(), List.of());
        WeeklySeries s = WeeklySeries.build(Lifecycle.derive(data), List.of(BEN), List.of(W1));
        assertEquals(WeeklySeries.Cell.ZERO, s.cell(ANA.id(), W1));
        assertEquals(WeeklySeries.Cell.ZERO, s.cell(BEN.id(), W1));
        assertEquals(1, s.members().size());
    }
}
