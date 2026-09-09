package com.workloadhub.forecast.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class HourPlacementTest {

    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of());

    @Property
    boolean placedHoursSumToTheInputAndLandOnMondays(@ForAll @DoubleRange(min = 0, max = 200) double hours,
            @ForAll @IntRange(min = 0, max = 40) int span) {
        LocalDate start = LocalDate.of(2026, 3, 4);
        SortedMap<LocalDate, Double> out = HourPlacement.placeHours(hours, start, start.plusDays(span), CAL, Set.of());
        double sum = out.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean mondays = out.keySet().stream().allMatch(d -> d.getDayOfWeek().getValue() == 1);
        return Math.abs(sum - hours) < 1e-6 && mondays;
    }

    @Test
    void spreadsEvenlyOverWorkingDaysAcrossWeeks() {
        SortedMap<LocalDate, Double> out = HourPlacement.placeHours(10, LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 10), CAL, Set.of());
        // Thu 5, Fri 6 in week of 2 March; Mon 9, Tue 10 in week of 9 March: 4 days, 2.5 h each
        assertEquals(5.0, out.get(LocalDate.of(2026, 3, 2)), 1e-9);
        assertEquals(5.0, out.get(LocalDate.of(2026, 3, 9)), 1e-9);
    }

    @Test
    void endBeforeStartAndNoWorkingDayFallBackToTheStartWeek() {
        assertEquals(8.0, HourPlacement.placeHours(8, LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 2), CAL, Set.of()).get(LocalDate.of(2026, 3, 2)), 1e-9);
        SortedMap<LocalDate, Double> weekend = HourPlacement.placeHours(3, LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8), CAL, Set.of());
        assertEquals(3.0, weekend.get(LocalDate.of(2026, 3, 2)), 1e-9);
    }
}
